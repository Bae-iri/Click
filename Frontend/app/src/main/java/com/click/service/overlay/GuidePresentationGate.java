package com.click.service.overlay;

import java.util.Objects;

/** Network refreshes do not reopen or reannounce the same visible state. */
final class GuidePresentationGate {
    private String last;
    boolean accept(String episode, String kind, String content) {
        String key = episode + "\u001f" + kind + "\u001f" + content;
        if (Objects.equals(last, key)) return false;
        last = key;
        return true;
    }
    void clear() { last = null; }
}
