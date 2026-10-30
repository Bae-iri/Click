package com.click.ui.tutorial;

import android.app.Application;
import android.media.AudioManager;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import com.click.designpreview.TutorialNarrator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TutorialNarratorTest {
    private static void advance(long ms) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }
    private static final class Fixture implements AutoCloseable {
        final List<TextToSpeech.OnInitListener> initializers = new ArrayList<>();
        final List<UtteranceProgressListener> callbacks = new ArrayList<>();
        final List<String> spoken = new ArrayList<>(), ids = new ArrayList<>();
        final List<TutorialNarrator> clients = new ArrayList<>();
        final AudioManager audio = RuntimeEnvironment.getApplication().getSystemService(AudioManager.class);
        final MockedConstruction<TextToSpeech> engines = mockConstruction(TextToSpeech.class, (engine, construction) -> {
            initializers.add((TextToSpeech.OnInitListener) construction.arguments().get(1));
            when(engine.setLanguage(Locale.KOREA)).thenReturn(TextToSpeech.LANG_COUNTRY_AVAILABLE);
            doAnswer(call -> { callbacks.add(call.getArgument(0)); return TextToSpeech.SUCCESS; })
                    .when(engine).setOnUtteranceProgressListener(any());
            doAnswer(call -> { spoken.add(call.getArgument(0)); ids.add(call.getArgument(3)); return TextToSpeech.SUCCESS; })
                    .when(engine).speak(anyString(), anyInt(), any(), anyString());
        });
        Fixture() { audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0); }
        TutorialNarrator client(boolean screen) {
            TutorialNarrator client = new TutorialNarrator(RuntimeEnvironment.getApplication(), screen);
            clients.add(client); return client;
        }
        void init(int status) { initializers.get(initializers.size() - 1).onInit(status); advance(0); }
        void ready() { init(TextToSpeech.SUCCESS); advance(220); }
        TextToSpeech tts() { return engines.constructed().get(engines.constructed().size() - 1); }
        public void close() { for (TutorialNarrator client : clients) client.close(); engines.close(); }
    }

    @Test public void onlyLatestInstructionPlaysAfterInitializationAndRapidUiChanges() {
        try (Fixture f = new Fixture()) {
            TutorialNarrator narrator = f.client(true);
            narrator.speak("첫 번째 화면"); narrator.speak("두 번째 화면");
            f.init(TextToSpeech.SUCCESS);
            advance(100); narrator.speak("최종 화면"); advance(220);
            assertEquals(List.of("최종 화면"), f.spoken);
        }
    }
    @Test public void layoutUpdatesDoNotRestartSpeechAndReplayIsExplicit() {
        try (Fixture f = new Fixture()) {
            TutorialNarrator narrator = f.client(false); narrator.speak("디스플레이를 눌러주세요."); f.ready();
            narrator.speak("디스플레이를 눌러주세요."); advance(220);
            f.callbacks.get(0).onDone(f.ids.get(0)); advance(0);
            narrator.speak("디스플레이를 눌러주세요."); advance(220);
            assertEquals(1, f.spoken.size());
            narrator.replay("디스플레이를 눌러주세요."); advance(220);
            assertEquals(2, f.spoken.size()); assertNotEquals(f.ids.get(0), f.ids.get(1));
        }
    }
    @Test public void stopCancelsPendingPlaybackWithoutMutingTheNextInstruction() {
        try (Fixture f = new Fixture()) {
            TutorialNarrator narrator = f.client(false); narrator.speak("옛 안내"); narrator.stop(); f.ready();
            assertTrue(f.spoken.isEmpty());
            narrator.speak("옛 안내"); advance(220); assertTrue(f.spoken.isEmpty());
            narrator.speak("새 안내"); advance(220); assertEquals(List.of("새 안내"), f.spoken);
        }
    }
    @Test public void tutorialAndServiceShareOneEngineAndOldOwnerCannotStopNewSpeech() {
        try (Fixture f = new Fixture()) {
            TutorialNarrator service = f.client(false); service.speak("실제 안내"); f.ready();
            TutorialNarrator tutorial = f.client(true); tutorial.speak("연습 안내"); advance(220);
            service.speak("늦은 서버 응답"); service.close(); advance(220);
            assertEquals(1, f.engines.constructed().size());
            assertEquals(List.of("실제 안내", "연습 안내"), f.spoken);
            assertTrue(tutorial.isSpeaking()); verify(f.tts(), never()).shutdown();
            tutorial.close(); verify(f.tts()).shutdown();
        }
    }
    @Test public void inactiveScreenCannotStealSpeechBackFromService() {
        try (Fixture f = new Fixture()) {
            TutorialNarrator tutorial = f.client(true); tutorial.speak("연습 안내"); f.ready(); tutorial.pause();
            TutorialNarrator service = f.client(false); service.speak("실제 안내"); advance(220);
            tutorial.speak("늦은 화면 갱신"); advance(220);
            assertEquals(List.of("연습 안내", "실제 안내"), f.spoken);
            assertTrue(service.isSpeaking());
        }
    }
    @Test public void oldUtteranceCallbacksCannotChangeNewPlaybackState() {
        try (Fixture f = new Fixture()) {
            TutorialNarrator narrator = f.client(false); narrator.speak("첫 안내"); f.ready();
            String old = f.ids.get(0); narrator.speak("새 안내"); advance(220);
            f.callbacks.get(0).onDone(old); f.callbacks.get(0).onError(old); f.callbacks.get(0).onStop(old, true); advance(0);
            assertTrue(narrator.isSpeaking()); assertFalse(narrator.isUnavailable());
            f.callbacks.get(0).onDone(f.ids.get(1)); advance(0); assertFalse(narrator.isSpeaking());
        }
    }
    @Test public void audioFocusLossStopsOnceAndStaleFocusCannotStopTheNextSentence() {
        try (Fixture f = new Fixture()) {
            TutorialNarrator narrator = f.client(false); narrator.speak("첫 안내"); f.ready();
            AudioManager.OnAudioFocusChangeListener oldFocus = shadowOf(f.audio).getLastAudioFocusRequest().listener;
            oldFocus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
            assertFalse(narrator.isSpeaking());
            oldFocus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN); advance(220);
            assertEquals(1, f.spoken.size());
            narrator.speak("다음 안내"); advance(220);
            oldFocus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);
            assertTrue(narrator.isSpeaking()); assertEquals(2, f.spoken.size());
        }
    }
    @Test public void initializationFailureAndExplicitRetryUseFreshEngine() {
        try (Fixture f = new Fixture()) {
            TutorialNarrator narrator = f.client(false); narrator.speak("안내"); f.init(TextToSpeech.ERROR);
            assertFalse(narrator.isSpeaking()); assertTrue(narrator.isUnavailable());
            narrator.replay("안내"); f.ready();
            assertEquals(2, f.engines.constructed().size()); assertEquals(List.of("안내"), f.spoken);
            verify(f.engines.constructed().get(0)).shutdown();
        }
    }
    @Test public void failedPlaybackDoesNotRetryOnEveryScreenUpdate() {
        try (Fixture f = new Fixture()) {
            TutorialNarrator narrator = f.client(false); narrator.speak("안내"); f.ready();
            f.callbacks.get(0).onError(f.ids.get(0)); advance(0);
            narrator.speak("안내"); advance(220);
            assertEquals(1, f.spoken.size()); assertTrue(narrator.isUnavailable());
            narrator.replay("안내"); advance(220); assertEquals(2, f.spoken.size());
        }
    }
    @Test public void resumingTutorialStopsBackgroundGuidance() {
        try (Fixture f = new Fixture()) {
            TutorialNarrator tutorial = f.client(true); tutorial.pause();
            TutorialNarrator service = f.client(false); service.speak("실제 안내"); f.ready();
            tutorial.resume(); assertFalse(service.isSpeaking());
            tutorial.speak("돌아온 연습 안내"); advance(220);
            assertEquals(List.of("실제 안내", "돌아온 연습 안내"), f.spoken);
        }
    }
    @Test public void closeBeforeInitializationDoesNotPlayLater() {
        try (Fixture f = new Fixture()) {
            TutorialNarrator narrator = f.client(false); narrator.speak("안내"); narrator.close(); f.ready();
            assertTrue(f.spoken.isEmpty()); verify(f.tts()).shutdown();
        }
    }
    @Test public void missingEngineCallbacksBecomeReadableFailureInsteadOfStuckSpeaking() {
        try (Fixture f = new Fixture()) {
            TutorialNarrator narrator = f.client(false); narrator.speak("안내"); f.ready(); advance(10001);
            assertFalse(narrator.isSpeaking()); assertTrue(narrator.isUnavailable());
            narrator.replay("안내"); advance(220); assertEquals(2, f.spoken.size());
        }
    }
    @Test public void mutedAudioUsesCaptionsAndCanBeReplayedAfterUnmuting() {
        try (Fixture f = new Fixture()) {
            f.audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            TutorialNarrator narrator = f.client(false); narrator.speak("안내"); f.ready();
            assertTrue(f.spoken.isEmpty()); assertTrue(narrator.isUnavailable());
            f.audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0);
            narrator.replay("안내"); advance(220); assertEquals(List.of("안내"), f.spoken);
        }
    }
}
