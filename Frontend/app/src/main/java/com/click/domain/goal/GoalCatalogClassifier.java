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
        public final String goalTypeId;
        public final String displayName;
        public final String targetApp;
        public final String taskType;
        public final JSONObject slots;
        public final int score;
        public final double confidence;
        public final List<String> missingSlots;
        public final List<KeywordEvidence> matchedKeywordGroups;
        public final List<Alternative> alternatives;

        Match(String catalogVersion, Rule rule, int score, String source,
              List<Alternative> alternatives) {
            this.catalogVersion = catalogVersion;
            this.goalTypeId = rule.goalTypeId;
            this.displayName = rule.displayName;
            this.targetApp = rule.targetApp;
            this.taskType = rule.taskType;
            this.slots = resolveSlots(rule, source);
            this.score = score;
            this.confidence = Math.min(1.0, 0.7 + (score - rule.keywordGroups.size() * 100) / 100.0);
            this.matchedKeywordGroups = evidence(rule, normalize(source));
            this.alternatives = Collections.unmodifiableList(alternatives);
            List<String> missing = new ArrayList<>();
            for (String slot : rule.requiredSlots) {
                if (!this.slots.has(slot) || this.slots.optString(slot, "").isEmpty()) missing.add(slot);
            }
            this.missingSlots = Collections.unmodifiableList(missing);
        }

        public JSONObject toTypedGoalJson() throws JSONException {
            return new JSONObject()
                    .put("catalog_version", catalogVersion)
                    .put("goal_type_id", goalTypeId)
                    .put("slots", new JSONObject(slots.toString()));
        }

        public JSONObject toClassificationJson() throws JSONException {
            JSONArray evidence = new JSONArray();
            for (KeywordEvidence item : matchedKeywordGroups) {
                evidence.put(new JSONObject().put("group", item.group).put("keyword", item.keyword));
            }
            JSONArray candidateValues = new JSONArray();
            for (Alternative item : alternatives) {
                candidateValues.put(new JSONObject()
                        .put("goal_type_id", item.goalTypeId).put("score", item.score));
            }
            return new JSONObject()
                    .put("classification_status", "matched")
                    .put("method", "all_keyword_groups")
                    .put("confidence", confidence)
                    .put("matched_keyword_groups", evidence)
                    .put("alternatives", candidateValues)
                    .put("missing_slots", new JSONArray(missingSlots))
                    .put("multi_intent", false);
        }
    }

    public static final class KeywordEvidence {
        public final int group;
        public final String keyword;
        KeywordEvidence(int group, String keyword) { this.group = group; this.keyword = keyword; }
    }

    public static final class Alternative {
        public final String goalTypeId;
        public final int score;
        Alternative(String goalTypeId, int score) { this.goalTypeId = goalTypeId; this.score = score; }
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
        final String goalTypeId;
        final String displayName;
        final String targetApp;
        final String taskType;
        final JSONObject slots;
        final List<List<String>> keywordGroups;
        final List<String> utterances;
        final List<String> requiredSlots;

        Rule(JSONObject object) throws JSONException {
            goalTypeId = object.getString("goal_type_id");
            displayName = object.optString("display_name", goalTypeId);
            targetApp = object.getString("target_app");
            taskType = object.optString("task_type", "unknown");
            slots = object.optJSONObject("slots") == null
                    ? new JSONObject() : object.getJSONObject("slots");
            keywordGroups = parseGroups(object.getJSONArray("keyword_groups"));
            utterances = parseStrings(object.optJSONArray("utterances"));
            JSONObject schema = object.optJSONObject("slot_schema");
            requiredSlots = parseStrings(schema == null ? null : schema.optJSONArray("required"));
        }
    }

    private static final class Candidate {
        final Rule rule;
        final int score;
        Candidate(Rule rule, int score) { this.rule = rule; this.score = score; }
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
        List<Candidate> candidates = new ArrayList<>();
        for (Rule rule : rules) {
            int score = score(rule, text, foregroundPackage);
            if (score < 0) continue;
            candidates.add(new Candidate(rule, score));
        }
        if (candidates.isEmpty()) {
            Rule purchaseSearch = purchaseAsSearch(source, foregroundPackage);
            if (purchaseSearch == null) return new Classification(null, false);
            candidates.add(new Candidate(purchaseSearch, 200));
        }
        candidates.sort((left, right) -> Integer.compare(right.score, left.score));
        Candidate best = candidates.get(0);
        if (candidates.size() > 1 && candidates.get(1).score == best.score
                && !candidates.get(1).rule.goalTypeId.equals(best.rule.goalTypeId)) {
            return new Classification(null, true);
        }
        List<Alternative> alternatives = new ArrayList<>();
        for (int index = 1; index < Math.min(4, candidates.size()); index++) {
            Candidate candidate = candidates.get(index);
            alternatives.add(new Alternative(candidate.rule.goalTypeId, candidate.score));
        }
        return new Classification(new Match(version, best.rule, best.score, source, alternatives), false);
    }

    private Rule purchaseAsSearch(String source, String foregroundPackage) {
        String text = normalize(source);
        boolean purchasePhrase = text.endsWith("사줘") || text.endsWith("사주세요")
                || text.endsWith("구매해줘") || text.endsWith("주문해줘");
        if (!purchasePhrase || !foregroundMatches("coupang", foregroundPackage)) return null;
        for (Rule rule : rules) if ("coupang.product.search".equals(rule.goalTypeId)) return rule;
        return null;
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
        // Foreground is a tie-breaker, not authority to override more specific words.
        if (foregroundMatches(rule.targetApp, foregroundPackage)) score += 1;
        return score;
    }

    private static List<KeywordEvidence> evidence(Rule rule, String text) {
        List<KeywordEvidence> values = new ArrayList<>();
        for (int groupIndex = 0; groupIndex < rule.keywordGroups.size(); groupIndex++) {
            String best = null;
            for (String keyword : rule.keywordGroups.get(groupIndex)) {
                String normalized = normalize(keyword);
                if (!normalized.isEmpty() && text.contains(normalized)
                        && (best == null || normalized.length() > normalize(best).length())) best = keyword;
            }
            if (best != null) values.add(new KeywordEvidence(groupIndex, best));
        }
        return Collections.unmodifiableList(values);
    }

    private static JSONObject resolveSlots(Rule rule, String source) {
        try {
            JSONObject value = new JSONObject(rule.slots.toString());
            if ("coupang.product.search".equals(rule.goalTypeId)) {
                String query = source == null ? "" : source
                        .replaceAll("(?i)쿠팡에서|쿠팡|상품|제품|물건", " ")
                        .replaceAll("검색(해|해줘|해주세요)?|찾아(줘|주세요)?|사줘|사주세요|구매해줘|주문해줘", " ")
                        .replaceAll("[,.!?]", " ").trim().replaceAll("\\s+", " ");
                if (!query.isEmpty()) value.put("product_query", query);
            }
            return value;
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
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
