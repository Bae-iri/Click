package com.click.service.accessibility;

import com.click.data.snapshot.ScreenSnapshot;

import java.util.concurrent.atomic.AtomicReference;

public final class LatestScreenSnapshot {
    private static final AtomicReference<ScreenSnapshot> LATEST = new AtomicReference<>();

    private LatestScreenSnapshot() {}

    static void set(ScreenSnapshot snapshot) {
        LATEST.set(snapshot);
    }

    public static ScreenSnapshot get() {
        return LATEST.get();
    }
}
