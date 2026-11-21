package com.click.ui.tutorial;

import android.app.Application;
import com.click.data.local.LiveTutorialStore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class LiveTutorialStoreTest {
    private LiveTutorialStore store() {
        var prefs = RuntimeEnvironment.getApplication().getSharedPreferences("controlled-live-course", 0);
        prefs.edit().clear().commit(); return new LiveTutorialStore(prefs);
    }
    @Test public void onlyTheExactLaunchCanBindItsActualRequest() {
        var store = store(); String launch = store.begin("글씨 좀 크게 해줘");
        assertFalse(store.bindRequest("other-launch", "글씨 좀 크게 해줘", "wrong"));
        assertFalse(store.bindRequest(launch, "밝기를 높여줘", "wrong"));
        assertFalse(store.bindSession("other-request", "session"));
        assertTrue(store.bindRequest(launch, "글씨 좀 크게 해줘", "request"));
        assertFalse(store.bindRequest(launch, "글씨 좀 크게 해줘", "duplicate"));
        assertTrue(store.bindSession("request", "session"));
        assertTrue(store.presents("request", "session"));
        assertFalse(store.presents("other-request", "session"));
        assertFalse(store.presents("request", "old-session"));
    }
    @Test public void queuedOrUnacknowledgedWorkCannotCompleteTheCourse() {
        var store = store(); String launch = store.begin("글자 크기 키워줘");
        assertFalse(store.finish("request", "session", true));
        store.bindRequest(launch, "글자 크기 키워줘", "request");
        assertFalse(store.finish("request", "session", true));
        store.bindSession("request", "session");
        assertFalse(store.finish("request", "stale-session", true));
        assertFalse(store.awaitingHomeTour());
        assertTrue(store.finish("request", "session", true));
        assertTrue(store.awaitingHomeTour()); assertFalse(store.running());
        assertFalse(store.presents("request", "session"));
        store.finishHomeTour(); assertFalse(store.awaitingHomeTour());
    }
    @Test public void cancellationAndSupersededCoursesDoNotClaimSuccess() {
        var store = store(); String first = store.begin("글자 크기 키워줘");
        store.bindRequest(first, "글자 크기 키워줘", "first"); store.bindSession("first", "s1");
        String next = store.begin("글자 크기 키워줘");
        assertFalse(store.finish("first", "s1", true));
        store.bindRequest(next, "글자 크기 키워줘", "next"); store.bindSession("next", "s2");
        assertTrue(store.finish("next", "s2", false));
        assertFalse(store.running()); assertFalse(store.awaitingHomeTour());
    }
    @Test public void reconnectingTheSameRequestRejectsThePreviousSessionAndDoesNotStoreSpeech() {
        var prefs = RuntimeEnvironment.getApplication().getSharedPreferences("controlled-live-reconnect", 0);
        var store = new LiveTutorialStore(prefs);
        String text = "글자 크기를 좀 키워줘";
        String launch = store.begin(text); store.bindRequest(launch, text, "request"); store.bindSession("request", "old");
        var restored = new LiveTutorialStore(prefs);
        restored.bindSession("request", "new");
        assertFalse(restored.finish("request", "old", true));
        assertTrue(restored.presents("request", "new"));
        assertFalse(prefs.getAll().values().contains(text));
    }
}
