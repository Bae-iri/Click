package com.click.service.overlay;

import java.util.Set;
import com.click.data.remote.AgentTurnContract;
import com.click.data.remote.ExecutionState;
import java.util.Objects;

/** Owns task lifecycle and permissions. UI payloads and native-action evidence remain outside.
 * Transport completion never grants permission to resume a paused or terminal task.
 * All events are delivered on the service's main thread.
 */
final class TaskExecutionController {
    enum Phase {
        PREPARING, OBSERVING, CONFIRMATION, GUIDING, EXECUTING, VERIFYING,
        MANUAL, RESULT, FOLLOW_UP, RECOVERY, PAUSED, STOPPED, COMPLETED
    }
    enum RecoveryCause { OBSERVATION, ENVIRONMENT, NETWORK, RESULT_UNKNOWN }
    private RecoveryCause recoveryCause=RecoveryCause.OBSERVATION;
    record RequestToken(int generation, long sequence, String command) {}
    private static final Set<String> ACTIVE_STATUSES = Set.of("IN_PROGRESS", "WAITING_CONFIRMATION", "WAITING_GOAL");
    private String agentSessionId,agentEpisodeId;
    private int agentSessionVersion;
    private org.json.JSONObject acceptedGoal;
    private boolean firstTurnSent;
    org.json.JSONObject acceptedGoal(){return acceptedGoal;}
    void setAcceptedGoal(org.json.JSONObject goal){acceptedGoal=goal;}
    boolean started(){return firstTurnSent;}
    void setStarted(boolean value){firstTurnSent=value;}
    private ExecutionState serverState;
    private String lastRequestId;
    String sessionId(){return agentSessionId;}
    String episodeId(){return agentEpisodeId;}
    int sessionVersion(){return agentSessionVersion;}
    ExecutionState serverState(){return serverState;}
    void bindSession(String id){agentSessionId=id;acceptedGoal=null;resetCursor();}
    void clearSession(){bindSession(null);}
    void resetCursor(){firstTurnSent=false;agentEpisodeId=null;agentSessionVersion=0;serverState=null;lastRequestId=null;}
    boolean acceptResponse(AgentTurnContract.Response response,String command,boolean snapshot) {
        if(!Objects.equals(agentSessionId,response.sessionId) || response.sessionVersion<agentSessionVersion)return false;
        if(!snapshot && agentEpisodeId!=null && !Objects.equals(agentEpisodeId,response.episodeId)
                && !ExecutionState.episodeChanging(command))return false;
        if(!snapshot && response.sessionVersion==agentSessionVersion && lastRequestId!=null
                && !lastRequestId.equals(response.requestId) && !ExecutionState.preparation(command)
                && !"unchanged".equals(response.commandResultStatus))return false;
        if(acceptedGoal!=null && response.executionState!=null && response.executionState.goalType!=null
                && !acceptedGoal.optString("goal_type").equals(response.executionState.goalType)
                && !snapshot && !ExecutionState.episodeChanging(command))return false;
        agentSessionVersion=response.sessionVersion;agentEpisodeId=response.episodeId;serverState=response.executionState;
        lastRequestId=snapshot?null:response.requestId;
        return true;
    }
    private Phase phase = Phase.PREPARING;
    private Phase beforePause = Phase.OBSERVING, beforeRecovery = Phase.OBSERVING;
    // Orthogonal transport state; it does not replace the lifecycle phase.
    private boolean busy, observationRequested, resumeAuthorized, resumeAfterPause, resumeAfterEnvironment;
    private String pendingControl, responseCommand;
    private int generation;
    private long sequence, activeRequest = -1;

    Phase phase() { return phase; }
    int generation() { return generation; }
    boolean busy() { return busy; }
    boolean paused() { return phase == Phase.PAUSED || phase == Phase.STOPPED || phase == Phase.COMPLETED; }
    boolean manual() {
        return phase == Phase.MANUAL || phase == Phase.PAUSED && beforePause == Phase.MANUAL
                || phase == Phase.RECOVERY && beforeRecovery == Phase.MANUAL;
    }
    boolean retryRequired() { return phase == Phase.RECOVERY || phase == Phase.PAUSED && beforePause == Phase.RECOVERY; }
    boolean observationRequested() { return observationRequested; }
    String pendingControl() { return pendingControl; }
    boolean resumeAfterPause() { return resumeAfterPause; }
    void queueResumeAfterPause() { resumeAfterPause = true; }
    void clearQueuedResume() { resumeAfterPause = false; }

    boolean canObserve() {
        return !paused() && !manual() && !retryRequired() && !busy && pendingControl == null
                && phase != Phase.RESULT && phase != Phase.FOLLOW_UP && phase != Phase.EXECUTING;
    }
    boolean matchesGuide(AgentTurnContract.GuideCommand guide,com.click.data.snapshot.ScreenSnapshot screen) {
        return serverState!=null && "turn".equals(serverState.source) && guide!=null && screen!=null
            && Objects.equals(serverState.sessionId,agentSessionId) && Objects.equals(serverState.episodeId,agentEpisodeId)
            && serverState.version==agentSessionVersion && "GUIDING".equals(serverState.phase) && Objects.equals(serverState.guideId,guide.guideCommandId)
            && Objects.equals(serverState.observationId,screen.observationId)
            && Objects.equals(serverState.attemptId,guide.renderPolicy==null?null:
                (guide.renderPolicy.isNull("attempt_id")?null:guide.renderPolicy.optString("attempt_id",null)));
    }
    boolean canAct() { return phase == Phase.GUIDING && !busy && pendingControl == null; }
    void setBusy(boolean value) { busy = value; if (!value) activeRequest = -1; }
    void requestObservation() {
        if (!manual() && phase != Phase.STOPPED && phase != Phase.COMPLETED) observationRequested = true;
    }
    void clearObservation() { observationRequested = false; }
    void invalidate() { generation++; activeRequest = -1; busy = false; responseCommand = null; }
    void newTask() {
        invalidate(); recoveryCause=RecoveryCause.OBSERVATION; phase = Phase.PREPARING; beforePause = beforeRecovery = Phase.OBSERVING;
        pendingControl = null; resumeAuthorized = resumeAfterPause = observationRequested = resumeAfterEnvironment = false;
    }
    void pause() {
        if (!paused()) { beforePause = phase; phase = Phase.PAUSED; }
        resumeAuthorized = false;resumeAfterEnvironment=false;
    }
    void resume() {
        if (phase == Phase.PAUSED) phase = beforePause==Phase.GUIDING || beforePause==Phase.CONFIRMATION
                || beforePause==Phase.EXECUTING || beforePause==Phase.VERIFYING ? Phase.OBSERVING : beforePause;
        resumeAuthorized = false;
    }
    void requestResume() {
        resumeAuthorized = true;
        // An explicit resume re-observes a manual/recovery page before recreating its instructions.
        if (phase == Phase.PAUSED && (beforePause == Phase.MANUAL || beforePause == Phase.RECOVERY)) {
            beforePause = beforeRecovery = Phase.OBSERVING;
        }
    }
    void deferResumeForEnvironment() { requestResume();resume();resumeAfterEnvironment=true; }
    boolean resumeAfterEnvironment(){return resumeAfterEnvironment;}
    void consumeEnvironmentResume(){resumeAfterEnvironment=false;}
    void queueControl(String command) { pendingControl = command; }
    void clearControl() { pendingControl = null; }
    boolean pageChanged() {
        if(retryRequired() && recoveryCause!=RecoveryCause.OBSERVATION && recoveryCause!=RecoveryCause.ENVIRONMENT)return false;
        if(paused() || manual() || pendingControl!=null || phase==Phase.RESULT || phase==Phase.FOLLOW_UP
                || phase==Phase.PREPARING || phase==Phase.EXECUTING)return false;
        phase=Phase.OBSERVING;beforeRecovery=Phase.OBSERVING;observationRequested=true;
        return true;
    }
    boolean environmentReady() {
        if(retryRequired() && recoveryCause!=RecoveryCause.OBSERVATION && recoveryCause!=RecoveryCause.ENVIRONMENT)return false;
        if(paused() || manual() || pendingControl!=null || phase==Phase.RESULT || phase==Phase.FOLLOW_UP || phase==Phase.EXECUTING)return false;
        phase=beforeRecovery=Phase.OBSERVING;observationRequested=true;return true;
    }
    boolean sensitiveEnvironmentBlocked() {
        // Privacy interruption must also stop an in-flight local gesture loop.
        // Explicit controls, manual handoff, and terminal phases retain precedence.
        if (phase == Phase.EXECUTING && pendingControl == null) phase = beforeRecovery = Phase.OBSERVING;
        return environmentBlocked();
    }
    boolean environmentBlocked() {
        if(!environmentReady())return false;
        beforeRecovery=Phase.OBSERVING;phase=Phase.RECOVERY;recoveryCause=RecoveryCause.ENVIRONMENT;observationRequested=false;return true;
    }
    void recovery(RecoveryCause cause) { recovery(); recoveryCause=cause; }
    void recovery() {
        if (phase == Phase.PAUSED) {
            if (beforePause != Phase.RECOVERY) { beforeRecovery = beforePause; beforePause = Phase.RECOVERY; }
        } else if (!paused() && phase != Phase.RECOVERY) { beforeRecovery = phase; phase = Phase.RECOVERY; }
    }
    private Phase afterRecovery() {
        return beforeRecovery==Phase.GUIDING || beforeRecovery==Phase.CONFIRMATION
                || beforeRecovery==Phase.EXECUTING || beforeRecovery==Phase.VERIFYING ? Phase.OBSERVING : beforeRecovery;
    }
    void retry() {
        if (phase == Phase.RECOVERY) phase = afterRecovery();
        else if (phase == Phase.PAUSED && beforePause == Phase.RECOVERY) beforePause = afterRecovery();
    }
    void exitManual() {
        if (phase == Phase.MANUAL) phase = Phase.OBSERVING;
        if (beforePause == Phase.MANUAL) beforePause = Phase.OBSERVING;
        if (beforeRecovery == Phase.MANUAL) beforeRecovery = Phase.OBSERVING;
    }
    void manualPresented() {
        if (!paused() && !retryRequired() && phase != Phase.RESULT && phase != Phase.FOLLOW_UP) {
            phase = Phase.MANUAL; observationRequested = false;
        }
    }
    void guidePresented(boolean confirmation) {
        if (!paused() && !retryRequired() && !manual() && phase != Phase.RESULT && phase != Phase.FOLLOW_UP)
            phase = serverState!=null && "VERIFYING".equals(serverState.phase)?Phase.VERIFYING:confirmation ? Phase.CONFIRMATION : Phase.GUIDING;
    }
    void actionStarted() { if (canAct()) phase = Phase.EXECUTING; }
    void actionDispatched(boolean success) {
        if (phase == Phase.EXECUTING) phase = success ? Phase.VERIFYING : Phase.GUIDING;
    }
    void stop() { phase = Phase.STOPPED; observationRequested = resumeAuthorized = resumeAfterPause = resumeAfterEnvironment = false; }

    boolean receiveStatus(String status) {
        String command = responseCommand; responseCommand = null;
        if (status == null || ExecutionState.phase(status)==null) return false;
        if ("CANCELLED".equals(status) || "EXPIRED".equals(status) || "BLOCKED".equals(status)) { stop(); return true; }
        if (phase == Phase.STOPPED || phase == Phase.COMPLETED) return false;
        if ("PAUSED".equals(status)) { pause(); return true; }
        if (phase == Phase.PAUSED && !resumeAuthorized) return false;
        if (phase == Phase.PAUSED) phase = beforePause;
        resumeAuthorized = false;
        if (ACTIVE_STATUSES.contains(status)) {
            if (manual() || retryRequired()) return false;
            if ((phase == Phase.RESULT || phase == Phase.FOLLOW_UP) && !"adjust_task".equals(command)) return false;
        }
        switch (status) {
            case "COMPLETED": phase = Phase.COMPLETED; observationRequested = false; break;
            case "WAITING_RESULT": phase = Phase.RESULT; observationRequested = false; break;
            case "WAITING_ADJUSTMENT": phase = Phase.FOLLOW_UP; observationRequested = false; break;
            case "WAITING_GOAL": phase = Phase.PREPARING; break;
            case "WAITING_CONFIRMATION": phase = Phase.CONFIRMATION; break;
            case "IN_PROGRESS": phase = Phase.OBSERVING; break;
            default: return false;
        }
        return true;
    }
    RequestToken beginRequest() { return beginRequest(null); }
    RequestToken beginRequest(String command) {
        busy = true; activeRequest = ++sequence; responseCommand = null;
        return new RequestToken(generation, activeRequest, command);
    }
    boolean finishRequest(RequestToken token) {
        if (token.generation() != generation || token.sequence() != activeRequest || !busy) return false;
        busy = false; activeRequest = -1; responseCommand = token.command();
        return true;
    }
}
