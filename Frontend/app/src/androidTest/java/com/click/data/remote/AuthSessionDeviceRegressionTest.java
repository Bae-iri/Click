package com.click.data.remote;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.BuildConfig;
import com.click.data.local.AuthTokenStore;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Dedicated synthetic server only; does not authenticate or delete a real Kakao account. */
@RunWith(AndroidJUnit4.class)
public class AuthSessionDeviceRegressionTest {
    @Test public void kakaoLoginImmediateTaskRefreshAndReloginUsePersistedCredentials() throws Exception {
        assertTrue("Run only against the dedicated local regression server",
                BuildConfig.CLICK_API_BASE_URL.equals("http://127.0.0.1:18780"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AuthGateway gateway = new AuthGateway(context);
        AuthTokenStore tokens = new AuthTokenStore(context);
        AuthenticatedHttpClient http = new AuthenticatedHttpClient(context);
        try {
            login(gateway);
            String user = tokens.userId(), generation = tokens.sessionGeneration();
            assertTrue(tokens.hasRefreshToken());
            assertFalse(tokens.accessExpired());
            assertEquals("kakao", tokens.authenticationProvider());
            JSONObject interaction = new JSONObject().put("id", UUID.randomUUID().toString()).put("text", "와이파이 설정 열어줘");
            assertEquals(200, http.post("/api/v1/interactions/classify", interaction.toString()).status);
            JSONObject request = new JSONObject().put("id", UUID.randomUUID().toString()).put("raw_goal", "와이파이 설정 열어줘");
            assertEquals(200, http.post("/api/v1/voice-requests/enqueue", request.toString()).status);
            assertEquals(200, http.post("/api/v1/voice-requests/next/claim", "{}").status);
            assertTrue(AuthGateway.refresh(tokens));
            assertEquals(generation, tokens.sessionGeneration());
            assertEquals(200, http.get("/api/v1/voice-requests").status);
            assertEquals(204, http.post("/api/v1/auth/logout", "{}").status);
            tokens.clear();
            login(gateway);
            assertEquals(user, tokens.userId());
            assertNotEquals(generation, tokens.sessionGeneration());
            assertEquals(200, http.get("/api/v1/voice-requests").status);
        } finally {
            if (tokens.userId() != null) http.delete("/api/v1/users/" + tokens.userId());
            tokens.clear();
            gateway.close();
        }
    }
    @Test public void phoneSignupLoginInvalidPasswordAndImmediateTask() throws Exception {
        assertEquals("http://127.0.0.1:18780", BuildConfig.CLICK_API_BASE_URL);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AuthGateway gateway = new AuthGateway(context);
        AuthTokenStore tokens = new AuthTokenStore(context);
        AuthenticatedHttpClient http = new AuthenticatedHttpClient(context);
        try {
            awaitSuccess(callback -> gateway.register("Device Regression", "01000001234", "1234", callback));
            String user = tokens.userId();
            assertEquals("phone", tokens.authenticationProvider());
            assertTrue(tokens.hasRefreshToken());
            assertEquals(200, http.post("/api/v1/interactions/classify", new JSONObject()
                    .put("id", UUID.randomUUID().toString()).put("text", "와이파이 설정 열어줘").toString()).status);
            Throwable invalid = awaitResult(callback -> gateway.login("01000001234", "9999", callback));
            assertTrue(invalid instanceof ApiException);
            assertEquals(401, ((ApiException) invalid).status);
            assertTrue(tokens.hasRefreshToken());
            assertEquals(200, http.get("/api/v1/voice-requests").status);
            Throwable duplicate = awaitResult(callback -> gateway.register("Duplicate", "01000001234", "1234", callback));
            assertTrue(duplicate instanceof ApiException);
            assertEquals(409, ((ApiException) duplicate).status);
            assertEquals(user, tokens.userId());
            AccountActionsGateway actions = new AccountActionsGateway(context);
            try { awaitSuccess(actions::logout); } finally { actions.close(); }
            assertFalse(tokens.hasRefreshToken());
            awaitSuccess(callback -> gateway.login("01000001234", "1234", callback));
            assertEquals(user, tokens.userId());
            assertEquals(200, http.post("/api/v1/voice-requests/enqueue", new JSONObject()
                    .put("id", UUID.randomUUID().toString()).put("raw_goal", "와이파이 설정 열어줘").toString()).status);
            assertEquals(200, http.post("/api/v1/voice-requests/next/claim", "{}").status);
        } finally {
            if (tokens.userId() != null) http.delete("/api/v1/users/" + tokens.userId());
            tokens.clear(); gateway.close();
        }
    }
    private void awaitSuccess(java.util.function.Consumer<AuthGateway.Callback> operation) throws Exception {
        Throwable error = awaitResult(operation);
        if (error != null) throw new AssertionError("Authentication failed", error);
    }
    private Throwable awaitResult(java.util.function.Consumer<AuthGateway.Callback> operation) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        operation.accept(new AuthGateway.Callback() {
            public void onSuccess() { done.countDown(); }
            public void onFailure(Throwable error) { failure.set(error); done.countDown(); }
        });
        assertTrue("Authentication callback timed out", done.await(30, TimeUnit.SECONDS));
        return failure.get();
    }
    private void login(AuthGateway gateway) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        gateway.loginWithKakao("synthetic-device-regression-kakao-token", new AuthGateway.Callback() {
            public void onSuccess() { done.countDown(); }
            public void onFailure(Throwable error) { failure.set(error); done.countDown(); }
        });
        assertTrue("Login callback timed out", done.await(30, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError("Login failed", failure.get());
    }
}
