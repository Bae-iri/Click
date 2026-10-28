package com.click.ui.tutorial;

import android.app.Application;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import com.click.designpreview.TutorialNarrator;
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
    @Test public void synchronousInitFailureEndsPendingNarration() {
        try (MockedConstruction<TextToSpeech> engines = mockConstruction(TextToSpeech.class,
                (engine, construction) -> ((TextToSpeech.OnInitListener)
                        construction.arguments().get(1)).onInit(TextToSpeech.ERROR))) {
            TutorialNarrator narrator = new TutorialNarrator(RuntimeEnvironment.getApplication());
            TutorialNarrator.Listener listener = mock(TutorialNarrator.Listener.class);
            narrator.setListener(listener);
            narrator.speak("안내");
            shadowOf(Looper.getMainLooper()).idle();
            assertFalse(narrator.isSpeaking());
            verify(listener).changed(false, true);
            verify(engines.constructed().get(0), never()).speak(anyString(), anyInt(), any(), anyString());
            narrator.close();
        }
    }

    @Test public void synchronousInitSuccessPlaysQueuedKoreanNarration() {
        try (MockedConstruction<TextToSpeech> engines = mockConstruction(TextToSpeech.class,
                (engine, construction) -> {
                    when(engine.setLanguage(Locale.KOREA)).thenReturn(TextToSpeech.LANG_COUNTRY_AVAILABLE);
                    ((TextToSpeech.OnInitListener) construction.arguments().get(1)).onInit(TextToSpeech.SUCCESS);
                })) {
            TutorialNarrator narrator = new TutorialNarrator(RuntimeEnvironment.getApplication());
            narrator.speak("안내");
            shadowOf(Looper.getMainLooper()).idle();
            verify(engines.constructed().get(0)).speak(eq("안내"), eq(TextToSpeech.QUEUE_FLUSH), isNull(), anyString());
            narrator.close();
        }
    }

    @Test public void closedNarratorIgnoresLateInitialization() {
        try (MockedConstruction<TextToSpeech> engines = mockConstruction(TextToSpeech.class,
                (engine, construction) -> ((TextToSpeech.OnInitListener)
                        construction.arguments().get(1)).onInit(TextToSpeech.SUCCESS))) {
            TutorialNarrator narrator = new TutorialNarrator(RuntimeEnvironment.getApplication());
            narrator.speak("안내");
            narrator.close();
            shadowOf(Looper.getMainLooper()).idle();
            verify(engines.constructed().get(0), never()).setLanguage(any());
            verify(engines.constructed().get(0), never()).speak(anyString(), anyInt(), any(), anyString());
        }
    }
}
