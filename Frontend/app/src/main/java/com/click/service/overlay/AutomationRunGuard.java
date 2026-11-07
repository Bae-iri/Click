package com.click.service.overlay;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Local execution limits supplement the agent's goal/verification loop. No screen text is retained. */
public final class AutomationRunGuard {
    public enum Decision { ALLOW, DUPLICATE, WAITING_FOR_OBSERVATION, LIMIT_REACHED }
    private static final int MAX_ACTIONS = 24;
    private final Set<String> attempted = new HashSet<>();
    private final Set<String> operations = new HashSet<>();
    private String episodeKey;
    private String pendingGuide;
    private String beforeFingerprint;
    private int spent;

    public Decision begin(String session, String episode, String guide, String fingerprint) {
        return begin(session, episode, guide, fingerprint, guide);
    }

    public Decision begin(String session, String episode, String guide, String fingerprint, String operation) {
        String key = session + ":" + episode;
        if (!Objects.equals(key, episodeKey)) {
            clear();
            episodeKey = key;
        }
        observe(fingerprint);
        if (attempted.contains(guide)) return Decision.DUPLICATE;
        String operationKey = operation;
        if (operations.contains(operationKey)) return Decision.DUPLICATE;
        if (pendingGuide != null) return Decision.WAITING_FOR_OBSERVATION;
        if (spent >= MAX_ACTIONS) return Decision.LIMIT_REACHED;
        attempted.add(guide);
        spent++;
        operations.add(operationKey);
        pendingGuide = guide;
        beforeFingerprint = fingerprint;
        return Decision.ALLOW;
    }

    public void observe(String fingerprint) {
        if (fingerprint != null && beforeFingerprint != null && !fingerprint.equals(beforeFingerprint)) {
            pendingGuide = null;
            beforeFingerprint = null;
        }
    }

    public boolean awaiting(String guide) { return Objects.equals(guide, pendingGuide) && pendingGuide != null; }
    public void finish(String guide) {
        if (awaiting(guide)) { pendingGuide = null; beforeFingerprint = null; }
    }
    public void clear() {
        attempted.clear(); operations.clear(); episodeKey = null; pendingGuide = null; beforeFingerprint = null; spent=0;
    }
    public void interrupt() { pendingGuide=null; beforeFingerprint=null; }
    /** An explicit user retry grants another attempt, not another action budget. */
    public void retry() { interrupt(); attempted.clear(); operations.clear(); }
}
