package com.click.domain.goal;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Deterministic, data-driven goal classifier shared with the approved AI goal catalog. */
public final class GoalCatalogClassifier {
    public static final class Match {
        public final String catalogVersion;
        public final String goalType;
        public final String displayName;
        public final String targetApp;
        public final String taskType;
        public final String executionStatus;
        public final String riskLevel;
        public final JSONObject slots;
        public final int score;

        Match(String catalogVersion, Rule rule, int score) {
            this.catalogVersion = catalogVersion;
            this.goalType = rule.goalType;
            this.displayName = rule.displayName;
            this.targetApp = rule.targetApp;
            this.taskType = rule.taskType;
            this.executionStatus = rule.executionStatus;
            this.riskLevel = rule.riskLevel;
            this.slots = rule.slots;
            this.score = score;
        }

        public boolean isExecutable() { return "approved".equals(executionStatus); }

        public JSONObject toTypedGoalJson() throws JSONException {
            return new JSONObject()
                    .put("catalog_version", catalogVersion)
                    .put("goal_type", goalType)
                    .put("target_app", targetApp)
                    .put("slots", new JSONObject(slots.toString()));
        }
    }

    public static final class Classification {
        public final Match match;
        public final boolean ambiguous;

        Classification(Match match, boolean ambiguous) {
            this.match = match;
            this.ambiguous = ambiguous;
        }

        public boolean matched() { return match != null; }
    }

    private static final class Rule {
        final String goalType;
        final String displayName;
        final String targetApp;
        final String taskType;
        final String executionStatus;
        final String riskLevel;
        final JSONObject slots;
        final List<List<String>> keywordGroups;
        final List<String> utterances;

        Rule(JSONObject object) throws JSONException {
            goalType = object.getString("goal_type");
            displayName = object.optString("display_name", goalType);
            targetApp = object.getString("target_app");
            taskType = object.optString("task_type", "unknown");
            executionStatus = object.optString("execution_status", "catalog_only");
            riskLevel = object.optString("risk_level", "normal");
            slots = object.optJSONObject("slots") == null
                    ? new JSONObject() : object.getJSONObject("slots");
            keywordGroups = parseGroups(object.getJSONArray("keyword_groups"));
            utterances = parseStrings(object.optJSONArray("utterances"));
        }
    }

    private final String version;
    private final List<Rule> rules;

    private GoalCatalogClassifier(String version, List<Rule> rules) {
        this.version = version;
        this.rules = Collections.unmodifiableList(rules);
    }

    public static GoalCatalogClassifier fromJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray entries = root.getJSONArray("goals");
        List<Rule> rules = new ArrayList<>();
        for (int index = 0; index < entries.length(); index++) {
            rules.add(new Rule(entries.getJSONObject(index)));
        }
        return new GoalCatalogClassifier(root.getString("version"), rules);
    }

    public String version() { return version; }

    public int size() { return rules.size(); }

    public Classification classify(String source, String foregroundPackage) {
        String text = normalize(source);
        if (text.isEmpty()) return new Classification(null, false);
        Rule best = null;
        int bestScore = -1;
        boolean tied = false;
        for (Rule rule : rules) {
            int score = score(rule, text, foregroundPackage);
            if (score < 0) continue;
            if (score > bestScore) {
                best = rule;
                bestScore = score;
                tied = false;
            } else if (score == bestScore && best != null && !best.goalType.equals(rule.goalType)) {
                tied = true;
            }
        }
        if (best == null) return new Classification(null, false);
        if (tied) return new Classification(null, true);
        return new Classification(new Match(version, best, bestScore), false);
    }

    private static int score(Rule rule, String text, String foregroundPackage) {
        int score = rule.keywordGroups.size() * 100;
        for (List<String> group : rule.keywordGroups) {
            int longest = 0;
            for (String keyword : group) {
                String normalized = normalize(keyword);
                if (!normalized.isEmpty() && text.contains(normalized)) {
                    longest = Math.max(longest, normalized.length());
                }
            }
            if (longest == 0) return -1;
            score += longest;
        }
        for (String utterance : rule.utterances) {
            if (text.equals(normalize(utterance))) score += 1_000;
        }
        if (foregroundMatches(rule.targetApp, foregroundPackage)) score += 25;
        return score;
    }

    private static boolean foregroundMatches(String targetApp, String packageName) {
        if (packageName == null) return false;
        String lower = packageName.toLowerCase(Locale.ROOT);
        if ("android_settings".equals(targetApp)) return lower.equals("com.android.settings");
        if ("coupang".equals(targetApp)) return lower.contains("coupang");
        return false;
    }

    private static List<List<String>> parseGroups(JSONArray groups) throws JSONException {
        List<List<String>> result = new ArrayList<>();
        for (int index = 0; index < groups.length(); index++) {
            result.add(parseStrings(groups.getJSONArray(index)));
        }
        return result;
    }

    private static List<String> parseStrings(JSONArray values) throws JSONException {
        if (values == null) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) result.add(values.getString(index));
        return result;
    }

    static String normalize(String source) {
        return source == null ? "" : source.toLowerCase(Locale.ROOT)
                .replaceAll("[^0-9a-z가-힣]", "");
    }
}
