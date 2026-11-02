package com.click.data.remote;

import com.click.service.overlay.SpeechErrorMessages;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpeechErrorMessagesTest {
    private String message(int status, String body) {
        return SpeechErrorMessages.message(new ApiException("POST", "/api/v1/speech/transcriptions",
                new HttpResult(status, body, null)));
    }

    @Test public void silencePromptsAnotherRecording() {
        String result = message(422, "{\"error\":{\"code\":\"NO_SPEECH\",\"message\":\"PRIVATE\"}}");
        assertTrue(result.contains("말씀하신 내용을 잘 듣지 못했어요"));
        assertFalse(result.contains("PRIVATE"));
    }

    @Test public void invalidAudioAndMalformedErrorsAreNotReportedAsSilence() {
        for (String body : new String[]{"{\"error\":{\"code\":\"INVALID_REQUEST\"}}", "NO_SPEECH", "[]", "{\"error\":null}"}) {
            assertFalse(message(422, body).contains("말씀하신 내용을 잘 듣지 못했어요"));
        }
    }

    @Test public void outageStillPromptsConnectionRetry() {
        assertTrue(message(503, "{\"error\":{\"code\":\"NO_SPEECH\"}}").contains("서버에 연결하지 못했어요"));
    }
}
