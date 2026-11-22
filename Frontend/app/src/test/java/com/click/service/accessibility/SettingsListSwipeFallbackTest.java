package com.click.service.accessibility;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import java.time.Instant;
import java.util.List;
import org.junit.Test;

public class SettingsListSwipeFallbackTest {
    private static final String PKG = "com.android.settings";

    private static AccessibilityNodeSnapshot row(String id, String text, int top) {
        return new AccessibilityNodeSnapshot(id, null, true, PKG, text, null, PKG + ":id/title",
                "android.widget.TextView", null, new int[]{40, top, 1040, top + 120},
                false, true, false, false, false, null, 7, false);
    }

    private static ScreenSnapshot screen(AccessibilityNodeSnapshot... nodes) {
        return new ScreenSnapshot(PKG, Instant.now().toString(), 1080, 2400, List.of(nodes), 7);
    }

    @Test public void unchangedRowsAreTreatedAsAnUnmovedList() {
        ScreenSnapshot before = screen(row("a", "연결", 900), row("b", "기기 간 연결", 1040));
        ScreenSnapshot after = screen(row("x", "연결", 900), row("y", "기기 간 연결", 1040));
        assertTrue(NativeScrollExecutor.sameVisibleRows(before, after));
    }

    @Test public void shiftedOrNewRowsMeanTheListMoved() {
        ScreenSnapshot before = screen(row("a", "연결", 900), row("b", "기기 간 연결", 1040));
        assertFalse(NativeScrollExecutor.sameVisibleRows(before, screen(row("a", "연결", 600), row("b", "기기 간 연결", 740))));
        assertFalse(NativeScrollExecutor.sameVisibleRows(before,
                screen(row("a", "연결", 900), row("b", "기기 간 연결", 1040), row("c", "디스플레이", 1180))));
    }

    @Test public void anEmptyScreenNeverTriggersTheFallback() {
        assertFalse(NativeScrollExecutor.sameVisibleRows(screen(), screen()));
    }
}
