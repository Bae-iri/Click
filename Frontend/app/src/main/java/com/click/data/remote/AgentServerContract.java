package com.click.data.remote;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Capabilities advertised by the server this build actually calls. */
public final class AgentServerContract {
    private final Set<String> commands;
    private final Set<String> goals;

    private AgentServerContract(Set<String> commands, Set<String> goals) {
        this.commands = commands;
        this.goals = goals;
    }

    public boolean supports(String command) { return commands.contains(command); }
    public boolean supportsGoal(String goal) { return goals.contains(goal); }

    public static AgentServerContract fromOpenApi(JSONObject document) throws JSONException {
        JSONObject request = document.getJSONObject("paths").getJSONObject(AgentTurnContract.PATH)
                .getJSONObject("post").getJSONObject("requestBody").getJSONObject("content")
                .getJSONObject("application/json").getJSONObject("schema");
        JSONObject properties = resolve(document, request).getJSONObject("properties");
        JSONObject version = properties.getJSONObject("contract_version");
        if (!AgentTurnContract.CONTRACT_VERSION.equals(version.optString("const"))
                && !strings(version.optJSONArray("enum")).contains(AgentTurnContract.CONTRACT_VERSION))
            throw new JSONException("Unsupported agent contract version");
        Set<String> commands = strings(properties.getJSONObject("command_type").getJSONArray("enum"));
        if (!commands.contains("start_task") || !commands.contains("cancel_task"))
            throw new JSONException("Agent start/cancel capabilities are missing");
        JSONObject goal = properties.getJSONObject("goal");
        if (goal.has("anyOf")) {
            JSONArray alternatives = goal.getJSONArray("anyOf");
            for (int i = 0; i < alternatives.length(); i++) {
                JSONObject option = alternatives.getJSONObject(i);
                if (option.has("$ref") || "object".equals(option.optString("type"))) { goal = option; break; }
            }
        }
        Set<String> goals = strings(resolve(document, goal).getJSONObject("properties")
                .getJSONObject("goal_type").getJSONArray("enum"));
        return new AgentServerContract(commands, goals);
    }

    private static JSONObject resolve(JSONObject document, JSONObject schema) throws JSONException {
        if (!schema.has("$ref")) return schema;
        String ref = schema.getString("$ref"), prefix = "#/components/schemas/";
        if (!ref.startsWith(prefix)) throw new JSONException("Unsupported agent schema reference");
        return document.getJSONObject("components").getJSONObject("schemas").getJSONObject(ref.substring(prefix.length()));
    }

    private static Set<String> strings(JSONArray array) throws JSONException {
        Set<String> result = new HashSet<>();
        if (array != null) for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
        return result;
    }
}
