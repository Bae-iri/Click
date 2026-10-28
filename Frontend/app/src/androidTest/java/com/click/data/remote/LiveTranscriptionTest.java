package com.click.data.remote;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.local.AuthTokenStore;
import com.click.service.overlay.SpeechErrorMessages;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Sends only a fixed synthetic Korean fixture, never microphone audio or agent commands. */
@RunWith(AndroidJUnit4.class)
public class LiveTranscriptionTest {
    @Test public void koreanFixtureReachesPrivateWhisperThroughAppGateway() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("liveStt")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Log into Click before the live STT check", new AuthTokenStore(context).hasRefreshToken());
        ByteArrayOutputStream wav = new ByteArrayOutputStream();
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("speech/korean-font-size.wav")) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) wav.write(buffer, 0, count);
        }
        CountDownLatch finished = new CountDownLatch(1);
        String[] transcript = {null};
        Throwable[] failure = {null};
        TranscriptionGateway gateway = new TranscriptionGateway(context);
        try {
            gateway.transcribe(wav.toByteArray(), new TranscriptionGateway.Callback() {
                public void onSuccess(String text) { transcript[0] = text; finished.countDown(); }
                public void onFailure(Throwable error) { failure[0] = error; finished.countDown(); }
            });
            assertTrue("STT request timed out", finished.await(65, TimeUnit.SECONDS));
            if (failure[0] != null) {
                SpeechErrorMessages.log(failure[0]);
                fail(SpeechErrorMessages.message(failure[0]));
            }
            assertNotNull(transcript[0]);
            String text = transcript[0].replaceAll("\\s+", "");
            assertTrue("STT did not recognize the synthetic font-size request",
                    text.contains("글자") && text.contains("크기") && text.contains("키워"));
        } finally {
            gateway.close();
        }
    }
}
