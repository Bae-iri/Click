package com.click;

import android.content.Context;
import android.content.Intent;
import android.speech.tts.TextToSpeech;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.designpreview.TutorialNarrator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in device check: plays a fixed Korean sentence; never opens the microphone. */
@RunWith(AndroidJUnit4.class)
public class LiveNarrationTest {
    @Test public void koreanNarrationReachesPlaybackCompletion() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("liveSpeech")));
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        Context context = instrumentation.getTargetContext();
        assertFalse("Click must be able to discover installed TTS engines",
                context.getPackageManager().queryIntentServices(
                        new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isEmpty());
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean requested = new AtomicBoolean();
        AtomicBoolean failed = new AtomicBoolean();
        TutorialNarrator[] narrator = new TutorialNarrator[1];
        try {
            instrumentation.runOnMainSync(() -> {
                narrator[0] = new TutorialNarrator(context);
                narrator[0].setListener((speaking, unavailable) -> {
                    if (!requested.get()) return;
                    if (unavailable) failed.set(true);
                    if (unavailable || !speaking) finished.countDown();
                });
                requested.set(true);
                narrator[0].speak("클릭 음성 안내 테스트입니다.");
            });
            assertTrue("Korean narration did not complete", finished.await(20, TimeUnit.SECONDS));
            assertFalse("Korean TTS initialization or playback failed", failed.get());
        } finally {
            instrumentation.runOnMainSync(() -> { if (narrator[0] != null) narrator[0].close(); });
        }
    }

    @Test public void sharedEngineCancelsTheOldOwnerAndFinishesOnlyCurrentGuidance() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("liveSpeech")));
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean requested = new AtomicBoolean(), failed = new AtomicBoolean();
        TutorialNarrator[] clients = new TutorialNarrator[2];
        try {
            instrumentation.runOnMainSync(() -> {
                clients[0] = new TutorialNarrator(instrumentation.getTargetContext(), false);
                clients[0].speak("이전 화면의 취소될 안내입니다.");
                clients[1] = new TutorialNarrator(instrumentation.getTargetContext());
                clients[1].setListener((speaking, unavailable) -> {
                    if (!requested.get()) return;
                    if (unavailable) failed.set(true);
                    if (unavailable || !speaking) finished.countDown();
                });
                requested.set(true);
                clients[1].speak("현재 화면의 안내를 한 번만 읽습니다.");
                clients[0].speak("늦게 도착한 이전 안내입니다.");
                clients[0].close();
                assertFalse(clients[0].isSpeaking());
                assertTrue(clients[1].isSpeaking());
            });
            assertTrue("Current Korean narration did not finish", finished.await(20, TimeUnit.SECONDS));
            assertFalse("Shared narration failed", failed.get());
        } finally {
            instrumentation.runOnMainSync(() -> { for (TutorialNarrator client : clients) if (client != null) client.close(); });
        }
    }
}
