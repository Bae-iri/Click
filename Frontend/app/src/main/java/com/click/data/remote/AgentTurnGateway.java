package com.click.data.remote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AgentTurnGateway {
    public interface Callback {
        void onSuccess(AgentTurnContract.Response response);
        void onFailure(Throwable error);
    }
    public interface ContractCallback {
        void onSuccess(AgentServerContract contract);
        void onFailure(Throwable error);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AuthenticatedHttpClient http;
    private volatile AgentServerContract contract;
    private final com.click.data.local.DeviceTaskJournal journal;
    private final com.click.data.snapshot.SettingsScreenPrivacy screenPrivacy;

    public AgentTurnGateway(Context context) {
        http = new AuthenticatedHttpClient(context);
        journal=new com.click.data.local.AuthTokenStore(context).taskJournal();
        com.click.data.snapshot.SettingsScreenPrivacy loaded = null;
        try (java.io.InputStream vocabulary = context.getAssets().open("vision_vocabulary.json");
             java.io.InputStream policy = context.getAssets().open("screen_privacy_policy.json")) {
            loaded = com.click.data.snapshot.SettingsScreenPrivacy.load(vocabulary, policy);
        } catch (Exception ignored) { /* Missing policy fails closed before screen HTTP. */ }
        screenPrivacy = loaded;
    }

    /** Run before action events or one-use approvals are consumed by a request. */
    public boolean needsFreshObservation(com.click.data.snapshot.ScreenSnapshot screen) {
        if (screenPrivacy == null || screen == null) return false;
        try {
            screenPrivacy.filter(new org.json.JSONObject().put("accessibility_tree", new org.json.JSONObject(screen.toJson())));
        } catch (com.click.data.snapshot.SettingsScreenPrivacy.Incomplete incomplete) {
            return true;
        } catch (com.click.data.snapshot.SettingsScreenPrivacy.Blocked blocked) {
            // Keep an unsupported/transitional frame on device and use the
            // bounded observation retry. Credentials still fail closed below.
            return blocked.reason == com.click.data.snapshot.SettingsScreenPrivacy.Blocked.Reason.UNSUPPORTED_APP;
        } catch (Exception blockedOrInvalid) {
            // The actual send path must enforce its original privacy/contract error.
        }
        return false;
    }

    private static Throwable classifyFailure(Throwable error){
        if(error instanceof ApiException && "CONTRACT_MISMATCH".equals(((ApiException)error).conflictCode))return new ExecutionState.ContractMismatch();
        return error;
    }

    public void send(AgentTurnContract.Request request, Callback callback) {
        final long deadline=android.os.SystemClock.elapsedRealtime()+RequestBudget.TOTAL_MS;
        executor.execute(() -> {
            try (RequestBudget budget=new RequestBudget(deadline)) {
                AgentTurnContract.Response response = execute(request);
                mainHandler.post(() -> callback.onSuccess(response));
            } catch (Throwable error) {
                mainHandler.post(() -> callback.onFailure(classifyFailure(error)));
            }
        });
    }

    private AgentTurnContract.Response execute(AgentTurnContract.Request request) throws Exception {
        AgentServerContract server = loadContract();
        if(!server.executionState)throw new ExecutionState.ContractMismatch(ExecutionState.ContractMismatch.SERVER_UPGRADE_REQUIRED);
        if (!server.supports(request.commandType()))
            throw new java.io.IOException("현재 서버에서 이 작업 단계를 지원하지 않아요.");
        org.json.JSONObject body = new org.json.JSONObject(server.encode(request));
        if ("cancel_task".equals(request.commandType()) || "pause_task".equals(request.commandType())) {
            body.remove("accessibility_tree");
            body.remove("vision_snapshot");
        }
        if (body.has("accessibility_tree") || body.has("vision_snapshot")) {
            if (screenPrivacy == null)
                throw new com.click.data.snapshot.SettingsScreenPrivacy.Blocked();
            body = screenPrivacy.filter(body, request.observationSetting,
                    request.accessibilityTree == null || request.accessibilityTree.privacyMaskingReady);
        }
        journal.stage(body);
        HttpResult result = http.post(AgentTurnContract.PATH, body.toString(),25000);
        AgentTurnContract.Response response = new AgentTurnContract.Response(result.body);
        if(!request.requestId.equals(response.requestId) || !request.sessionId.equals(response.sessionId))throw new java.io.IOException("INVALID_TURN_RESPONSE");
        ExecutionState.validate(response,request,false);
        journal.acknowledge(request.sessionId,request.requestId);
        if (response.metadata != null && result.traceId != null)
            response.metadata.put("backend_trace_id", result.traceId);
        return response;
    }

    public com.click.data.local.DeviceTaskJournal journal(){return journal;}
    public void reconcileOffline(String sessionId,Callback callback) {
        final long deadline=android.os.SystemClock.elapsedRealtime()+RequestBudget.TOTAL_MS;
        executor.execute(()->{
            try(RequestBudget budget=new RequestBudget(deadline)) {
                loadContract();
                org.json.JSONObject wire=journal.pending(sessionId);
                if(wire!=null) {
                    try {
                        HttpResult result=http.post(AgentTurnContract.PATH,wire.toString(),25000);
                        AgentTurnContract.Response receipt=new AgentTurnContract.Response(result.body);
                        if(!wire.getString("request_id").equals(receipt.requestId) || !sessionId.equals(receipt.sessionId))
                            throw new java.io.IOException("INVALID_TURN_RESPONSE");
                        ExecutionState.validateStored(receipt,wire,false);
                        recordReceiptVerification(journal,wire,receipt);
                        journal.acknowledge(sessionId,wire.getString("request_id"));
                    }catch(ApiException conflict) {
                        if(conflict.status!=410 && (conflict.status!=409 || !java.util.Set.of("STALE_VERSION","STALE_REPLAY","EPISODE_MISMATCH","SESSION_CLOSED","SESSION_TERMINATED","SESSION_CANCELLED","COMMAND_NOT_ALLOWED").contains(conflict.conflictCode==null?"":conflict.conflictCode)))throw conflict;
                        // Another committed turn may fence a late receipt. Never rewrite its
                        // version, event timestamp, request ID, approval or screen to force it in.
                        org.json.JSONObject state=new org.json.JSONObject(http.get("/api/v1/agent/sessions/"+sessionId).body);
                        if(state.optInt("version")<=wire.optInt("expected_version") && conflict.status!=410)throw conflict;
                        journal.acknowledge(sessionId,wire.getString("request_id"));
                    }
                }
                AgentTurnContract.Response state=readSession(sessionId);
                if(wire!=null && !java.util.Objects.equals(wire.optString("episode_id"),state.episodeId))
                    state.metadata.put("offline_receipt_superseded",true);
                mainHandler.post(()->callback.onSuccess(state));
            }catch(Throwable error){mainHandler.post(()->callback.onFailure(classifyFailure(error)));}
        });
    }

    public AgentServerContract currentContract() { return contract; }

    /** Keep correlated evidence before discarding every replayable guide in the reply. */
    static void recordReceiptVerification(com.click.data.local.DeviceTaskJournal journal,
            org.json.JSONObject wire,AgentTurnContract.Response receipt) throws java.io.IOException {
        org.json.JSONObject verification=receipt.metadata==null?null:receipt.metadata.optJSONObject("verification");
        org.json.JSONObject result=verification==null?null:verification.optJSONObject("result");
        if(result==null || !"SUCCESS".equals(result.optString("status")))return;
        String attempt=result.optString("attempt_id",null);
        journal.verifiedAttempt(wire.optString("session_id"),wire.optString("episode_id"),attempt);
        org.json.JSONObject event=wire.optJSONObject("user_action_event");
        if((attempt==null || attempt.isEmpty()) && event!=null)
            journal.verified(wire.optString("session_id"),event.optString("guide_command_id"));
    }

    public void loadContract(ContractCallback callback) {
        final long deadline=android.os.SystemClock.elapsedRealtime()+RequestBudget.TOTAL_MS;
        executor.execute(() -> {
            try (RequestBudget budget=new RequestBudget(deadline)) {
                AgentServerContract result = loadContract();
                mainHandler.post(() -> callback.onSuccess(result));
            } catch (Exception error) { mainHandler.post(() -> callback.onFailure(classifyFailure(error))); }
        });
    }

    private AgentServerContract loadContract() throws Exception {
        if (contract == null) {
            HttpResult result = AuthGateway.get("/openapi.json", null);
            if (result.status < 200 || result.status >= 300) throw new ApiException("GET", "/openapi.json", result);
            contract = AgentServerContract.fromOpenApi(new org.json.JSONObject(result.body));
        }
        if(!contract.executionState || !contract.userDecline) {
            contract=null; // An explicit retry must be able to discover a newly deployed server.
            throw new ExecutionState.ContractMismatch(ExecutionState.ContractMismatch.SERVER_UPGRADE_REQUIRED);
        }
        return contract;
    }

    public void close() { executor.shutdownNow(); }

    /** Recover the authoritative version after a lost response; contains no replayable guide. */
    public void synchronize(String sessionId, Callback callback) {
        final long deadline=android.os.SystemClock.elapsedRealtime()+RequestBudget.TOTAL_MS;
        executor.execute(() -> {
            try (RequestBudget budget=new RequestBudget(deadline)) {
                AgentTurnContract.Response response=readSession(sessionId);
                mainHandler.post(()->callback.onSuccess(response));
            }catch(Throwable error){mainHandler.post(()->callback.onFailure(classifyFailure(error)));}
        });
    }

    private AgentTurnContract.Response readSession(String sessionId) throws Exception {
        loadContract(); // Capability mismatch is different from corrupted task state.
        org.json.JSONObject state = new org.json.JSONObject(http.get("/api/v1/agent/sessions/" + sessionId).body);
        org.json.JSONObject outcome = state.optJSONObject("task_outcome");
        org.json.JSONObject metadata = new org.json.JSONObject().put("task_outcome", outcome)
                .put("task_id", state.opt("task_id")).put("requires_goal",state.optBoolean("requires_goal",false))
                .put("browsing",state.optBoolean("browsing",false)).put("public_guide",state.optBoolean("public_guide",false));
        if(outcome!=null && outcome.optJSONObject("termination")!=null)metadata.put("termination",outcome.getJSONObject("termination"));
        if(outcome!=null && "WAITING_RESULT".equals(state.optString("status")))metadata.put("result_prompt",outcome.optJSONObject("prompt"));
        if(state.optJSONObject("result_prompt")!=null)metadata.put("result_prompt",state.getJSONObject("result_prompt"));
        if(state.optJSONObject("follow_up")!=null)metadata.put("follow_up",state.getJSONObject("follow_up"));
        AgentTurnContract.Response response=new AgentTurnContract.Response(new org.json.JSONObject().put("execution_state",state.opt("execution_state")).put("request_id",java.util.UUID.randomUUID().toString())
                .put("session_id",sessionId).put("session_version",state.getInt("version"))
                .put("episode_id",state.opt("episode_id")).put("task_status",state.getString("status"))
                .put("response_type","control").put("metadata",metadata).toString());
        ExecutionState.validate(response,null,true);
        return response;
    }
}
