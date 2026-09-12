package com.click.service.overlay;

import java.util.ArrayDeque;
import java.util.Deque;

/** Front-end priority/FIFO queue; backend persistence can mirror these transitions later. */
final class VoiceRequestQueue {
    enum Priority { VIP, NORMAL }

    static final class Entry {
        final String text;
        final Priority priority;

        Entry(String text, Priority priority) {
            this.text = text;
            this.priority = priority;
        }
    }

    private final Deque<Entry> vip = new ArrayDeque<>();
    private final Deque<Entry> normal = new ArrayDeque<>();

    void offerNormal(String text) { normal.addLast(new Entry(text, Priority.NORMAL)); }
    void offerVip(String text) { vip.addLast(new Entry(text, Priority.VIP)); }
    Entry poll() { return vip.isEmpty() ? normal.pollFirst() : vip.pollFirst(); }
    void clearNormal() { normal.clear(); }
}
