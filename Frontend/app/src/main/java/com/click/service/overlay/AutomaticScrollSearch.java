package com.click.service.overlay;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Bounded navigation only; every move must be followed by a fresh agent observation. */
final class AutomaticScrollSearch {
    private static final int MAX_ATTEMPTS = 4;
    private String scope;
    private int attempts;
    private boolean forward = true;
    private boolean stopped;
    private final Set<String> seenForward = new HashSet<>();
    private final Set<String> seenBackward = new HashSet<>();

    Boolean next(String scope, String fingerprint) {
        if (!Objects.equals(this.scope, scope)) {
            clear();
            this.scope = scope;
        }
        if (stopped || attempts >= MAX_ATTEMPTS) return null;
        if (forward && !seenForward.add(fingerprint)) forward = false;
        if (!forward && !seenBackward.add(fingerprint)) {
            stopped = true;
            return null;
        }
        attempts++;
        return forward;
    }

    void reachedEnd(boolean forward) {
        if (forward) this.forward = false;
        else stopped = true;
    }

    void clear() {
        scope = null;
        attempts = 0;
        forward = true;
        stopped = false;
        seenForward.clear();
        seenBackward.clear();
    }
}
