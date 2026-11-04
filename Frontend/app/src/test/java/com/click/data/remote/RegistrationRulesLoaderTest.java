package com.click.data.remote;

import org.json.JSONException;
import org.junit.Test;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLHandshakeException;

import static org.junit.Assert.*;

public class RegistrationRulesLoaderTest {
    private HttpResult rules(int minimum) {
        return new HttpResult(200, """
                {"paths":{"/api/v1/auth/register":{"post":{"requestBody":{"content":{
                "application/json":{"schema":{"$ref":"#/components/schemas/RegisterRequest"}}
                }}}}},"components":{"schemas":{"RegisterRequest":{"properties":{
                "phone_number":{"type":"string"},"age_group":{"type":"string"},
                "password":{"type":"string","minLength":%d,"maxLength":4}
                }}}}}
                """.formatted(minimum), null);
    }

    @Test public void timeoutRecoversAndSubmissionReusesRulesFromScreen() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RegistrationRulesLoader loader = new RegistrationRulesLoader(() -> {
            if (calls.incrementAndGet() == 1) throw new SocketTimeoutException();
            return rules(4);
        }, () -> 0L);
        RegistrationContract displayed = loader.load();
        assertEquals(4, displayed.minimumPasswordLength);
        RegistrationContract submitted = loader.load();
        assertSame(displayed, submitted);
        assertEquals("0123", submitted.request("테스트", "01012345678", "0123")
                .getString("password"));
        assertEquals(2, calls.get());
    }

    @Test public void temporarilyUnavailableServerRecovers() throws Exception {
        for (int status : new int[] {408, 502, 503, 504}) {
            AtomicInteger calls = new AtomicInteger();
            RegistrationRulesLoader loader = new RegistrationRulesLoader(() ->
                    calls.incrementAndGet() == 1 ? new HttpResult(status, "unavailable", null) : rules(4), () -> 0L);
            assertEquals(4, loader.load().minimumPasswordLength);
            assertEquals(2, calls.get());
        }
    }

    @Test public void repeatedFailureStopsAfterTwoRequests() {
        AtomicInteger calls = new AtomicInteger();
        RegistrationRulesLoader loader = new RegistrationRulesLoader(() -> {
            calls.incrementAndGet();
            throw new SocketTimeoutException();
        }, () -> 0L);
        assertThrows(SocketTimeoutException.class, loader::load);
        assertEquals(2, calls.get());
    }

    @Test public void expiredRulesRefreshAndDoNotSilentlySurviveAnOutage() throws Exception {
        AtomicLong time = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        RegistrationRulesLoader loader = new RegistrationRulesLoader(() -> {
            int call = calls.incrementAndGet();
            if (call >= 3) throw new SocketTimeoutException();
            return rules(4);
        }, time::get);
        assertEquals(4, loader.load().minimumPasswordLength);
        time.set(15 * 60 * 1000L - 1);
        assertEquals(4, loader.load().minimumPasswordLength);
        assertEquals(1, calls.get());
        time.incrementAndGet();
        assertEquals(4, loader.load().minimumPasswordLength);
        time.addAndGet(15 * 60 * 1000L);
        assertThrows(SocketTimeoutException.class, loader::load);
        assertEquals(4, calls.get());
    }

    @Test public void invalidDocumentIsNotCachedOrRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RegistrationRulesLoader loader = new RegistrationRulesLoader(() ->
                calls.incrementAndGet() == 1 ? new HttpResult(200, "{}", null) : rules(4), () -> 0L);
        assertThrows(JSONException.class, loader::load);
        assertEquals(1, calls.get());
        assertEquals(4, loader.load().minimumPasswordLength);
        assertEquals(2, calls.get());
    }

    @Test public void permanentErrorsAndCancellationAreNotRetried() {
        for (int status : new int[] {401, 403, 404, 429}) {
            AtomicInteger calls = new AtomicInteger();
            RegistrationRulesLoader loader = new RegistrationRulesLoader(() -> {
                calls.incrementAndGet();
                return new HttpResult(status, "unavailable", null);
            }, () -> 0L);
            assertEquals(status, assertThrows(ApiException.class, loader::load).status);
            assertEquals(1, calls.get());
        }
        for (java.io.IOException failure : new java.io.IOException[] {
                new SSLHandshakeException("certificate"), new InterruptedIOException("cancelled")}) {
            AtomicInteger calls = new AtomicInteger();
            RegistrationRulesLoader loader = new RegistrationRulesLoader(() -> {
                calls.incrementAndGet();
                throw failure;
            }, () -> 0L);
            assertSame(failure, assertThrows(java.io.IOException.class, loader::load));
            assertEquals(1, calls.get());
        }
    }
}
