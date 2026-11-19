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
    private final Set<String> appliedSettingFields;
    public final String contractVersion;
    public final boolean interactiveWorkflow;
    public final boolean goalPreparation;
    public boolean approvalProvenance;
    public boolean executionState;
    public boolean userDecline;
    public boolean coupangBrowse;
    public boolean gov24Login;
    public boolean gov24Civil;
    public boolean gov24Services;
    public boolean commonScreen;

    private AgentServerContract(Set<String> commands, Set<String> goals, Set<String> appliedSettingFields,
                                String version, boolean interactive, boolean preparation) {
        this.commands = commands;
        this.goals = goals;
        this.appliedSettingFields = appliedSettingFields;
        this.contractVersion = version;
        this.interactiveWorkflow = interactive;
        this.goalPreparation = preparation;
    }

    public boolean supports(String command) { return commands.contains(command); }
    public boolean supportsGoal(String goal) { return goals.contains(goal); }

    /** A staged backend rollout must not make older servers reject existing font requests. */
    public String encode(AgentTurnContract.Request request) throws JSONException {
        request.executionContextEnabled=executionState;
        request.contractVersion = contractVersion;
        request.interactiveWorkflow = interactiveWorkflow;
        JSONObject body = new JSONObject(request.toJson());
        JSONObject context = body.optJSONObject("device_context");
        if(context!=null && (coupangBrowse || gov24Login || gov24Civil)) {
            JSONObject capabilities=context.optJSONObject("capabilities");
            if(capabilities==null) {
                capabilities=new JSONObject();
                context.put("capabilities",capabilities);
            }
            if(coupangBrowse)capabilities.put("coupang_browse",true);
            if(gov24Login)capabilities.put("gov24_login",true);
            if(gov24Civil)capabilities.put("gov24_civil",true);
            if(gov24Services)capabilities.put("gov24_services",true);
        }
        JSONObject screen = body.optJSONObject("accessibility_tree");
        JSONObject settings = screen == null ? null : screen.optJSONObject("applied_settings");
        if (settings != null) {
            java.util.List<String> remove = new java.util.ArrayList<>();
            settings.keys().forEachRemaining(key -> { if (!appliedSettingFields.contains(key)) remove.add(key); });
            for (String key : remove) settings.remove(key);
            if (settings.length() == 0) screen.remove("applied_settings");
        }
        return body.toString();
    }

    public static AgentServerContract fromOpenApi(JSONObject document) throws JSONException {
        JSONObject request = document.getJSONObject("paths").getJSONObject(AgentTurnContract.PATH)
                .getJSONObject("post").getJSONObject("requestBody").getJSONObject("content")
                .getJSONObject("application/json").getJSONObject("schema");
        JSONObject properties = resolve(document, request).getJSONObject("properties");
        JSONObject version = properties.getJSONObject("contract_version");
        Set<String> versions = strings(version.optJSONArray("enum"));
        if (version.has("const")) versions.add(version.getString("const"));
        String selected = versions.contains(AgentTurnContract.CONTRACT_VERSION) ? AgentTurnContract.CONTRACT_VERSION
                : versions.contains("agent-fe-contract.v0.2") ? "agent-fe-contract.v0.2" : null;
        if (selected == null) throw new JSONException("Unsupported agent contract version");
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
        Set<String> settingFields = new HashSet<>();
        JSONObject settings = document.getJSONObject("components").getJSONObject("schemas").optJSONObject("AppliedSettings");
        if (settings != null && settings.optJSONObject("properties") != null)
            settings.getJSONObject("properties").keys().forEachRemaining(settingFields::add);
        AgentServerContract result=new AgentServerContract(commands, goals, settingFields, selected,
                AgentTurnContract.CONTRACT_VERSION.equals(selected) && properties.has("workflow_version"),
                AgentTurnContract.CONTRACT_VERSION.equals(selected) && commands.contains("resolve_goal")
                        && properties.has("preparation_version") && properties.has("clarification_answer")
                        && properties.has("goal_resolution_id"));
        JSONObject schemas=document.getJSONObject("components").getJSONObject("schemas");
        JSONObject observation=schemas.optJSONObject("AccessibilityTree");
        JSONObject observationProperties=observation == null ? null : observation.optJSONObject("properties");
        JSONObject profile=observationProperties == null ? null : observationProperties.optJSONObject("observation_profile");
        result.commonScreen=allowsString(profile, "common-screen.v1");
        JSONObject capabilities=schemas.optJSONObject("DeviceCapabilities");
        result.coupangBrowse=document.getJSONObject("paths").has(CoupangBrowseGateway.PATH)
            && capabilities!=null && capabilities.getJSONObject("properties").has("coupang_browse");
        result.gov24Login=document.getJSONObject("paths").has("/api/v1/agent/public-guide/commands")
            && capabilities!=null && capabilities.getJSONObject("properties").has("gov24_login");
        result.gov24Civil=result.gov24Login && capabilities.getJSONObject("properties").has("gov24_civil");
        result.gov24Services=result.gov24Civil && capabilities.getJSONObject("properties").has("gov24_services");
        JSONObject guide=schemas.optJSONObject("GuideCommandResponse"),cancel=schemas.optJSONObject("CancelVoiceRequest");
        result.userDecline=document.getJSONObject("paths").has("/api/v1/voice-requests/{request_id}/cancel")
            && guide!=null && cancel!=null
            && strings(guide.getJSONObject("properties").getJSONObject("command_type").optJSONArray("enum")).contains("cancelled")
            && strings(cancel.getJSONObject("properties").getJSONObject("reason").optJSONArray("enum"))
                .containsAll(java.util.Set.of("USER_DECLINED","USER_ABORTED"));
        result.approvalProvenance=properties.has("approval_provenance");
        JSONObject execution=document.getJSONObject("components").getJSONObject("schemas").optJSONObject("ExecutionContext");
        JSONObject protocol=execution==null?null:execution.optJSONObject("properties").optJSONObject("protocol_version");
        result.executionState=properties.has("execution_context") && protocol!=null
                && (ExecutionState.version().equals(protocol.optString("const")) || strings(protocol.optJSONArray("enum")).contains(ExecutionState.version()));
        return result;
    }

    private static JSONObject resolve(JSONObject document, JSONObject schema) throws JSONException {
        if (!schema.has("$ref")) return schema;
        String ref = schema.getString("$ref"), prefix = "#/components/schemas/";
        if (!ref.startsWith(prefix)) throw new JSONException("Unsupported agent schema reference");
        return document.getJSONObject("components").getJSONObject("schemas").getJSONObject(ref.substring(prefix.length()));
    }

    private static boolean allowsString(JSONObject schema, String value) throws JSONException {
        if (schema == null) return false;
        if (value.equals(schema.optString("const")) || strings(schema.optJSONArray("enum")).contains(value)) return true;
        for (String union : new String[]{"anyOf", "oneOf"}) {
            JSONArray alternatives = schema.optJSONArray(union);
            if (alternatives != null) for (int i=0; i<alternatives.length(); i++)
                if (allowsString(alternatives.optJSONObject(i), value)) return true;
        }
        return false;
    }

    private static Set<String> strings(JSONArray array) throws JSONException {
        Set<String> result = new HashSet<>();
        if (array != null) for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
        return result;
    }
}
