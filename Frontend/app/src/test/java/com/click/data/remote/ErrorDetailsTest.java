package com.click.data.remote;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import org.junit.Test;
import static org.junit.Assert.*;

public class ErrorDetailsTest {
    private ApiException api(String path, int status) {
        return new ApiException("POST", path, new HttpResult(status,
                "{\"detail\":\"Resource not found.\",\"trace_id\":\"private-trace\"}", null));
    }

    @Test public void missingRequestEndpointsExplainServerUpdateForBothInputModes() {
        for (String path : new String[] {"/api/v1/voice-requests/enqueue",
                "/api/v1/voice-requests/next/claim", "/api/v1/voice-requests/interrupt-and-clear",
                "/api/v1/agent/commands"}) {
            for (int status : new int[] {404, 405}) {
                String message = ErrorDetails.requestMessage(api(path, status));
                assertTrue(message.contains("업데이트"));
                assertFalse(message.contains("음성"));
                assertFalse(message.contains(path));
                assertFalse(message.contains("private-trace"));
            }
        }
    }

    @Test public void missingIndividualRequestDoesNotClaimServerIsOutdated() {
        assertFalse(ErrorDetails.requestMessage(api("/api/v1/voice-requests/old-id/complete", 404))
                .contains("업데이트"));
        assertFalse(ErrorDetails.requestMessage(api("/api/v1/agent/sessions/old-id", 404))
                .contains("업데이트"));
    }

    @Test public void loginFailuresExplainHowToRecover() {
        assertTrue(ErrorDetails.requestMessage(api("/api/v1/auth/refresh", 401)).contains("로그인"));
        assertTrue(ErrorDetails.requestMessage(new IOException("AUTH_REQUIRED")).contains("로그인"));
    }

    @Test public void temporaryFailuresDoNotAskForServerUpdate() {
        assertTrue(ErrorDetails.requestMessage(api("/api/v1/voice-requests/enqueue", 429)).contains("잠시"));
        assertTrue(ErrorDetails.requestMessage(api("/api/v1/voice-requests/enqueue", 503)).contains("잠시"));
        assertTrue(ErrorDetails.requestMessage(new UnknownHostException()).contains("인터넷"));
        assertTrue(ErrorDetails.requestMessage(new ConnectException()).contains("인터넷"));
        // A timed-out enqueue may already have succeeded; do not suggest immediately duplicating it.
        assertTrue(ErrorDetails.requestMessage(new SocketTimeoutException()).contains("요청 상태"));
    }

    @Test public void unexpectedErrorsStayNeutralAndHideDiagnosticDetails() {
        String message = ErrorDetails.requestMessage(new IOException("internal-details"));
        assertTrue(message.contains("요청"));
        assertFalse(message.contains("음성"));
        assertFalse(message.contains("internal-details"));
        assertNotNull(ErrorDetails.requestMessage(null));
    }
}
