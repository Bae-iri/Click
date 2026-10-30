package com.click.data.remote;

import android.content.Context;
import android.os.Looper;
import com.click.data.local.AuthTokenStore;
import com.click.data.local.LocalAccountStore;
import com.click.data.local.TouchAutomationSettings;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AccountActionsGatewayTest {
    private Context context;
    private AuthTokenStore tokens;
    private AuthenticatedHttpClient http;
    private AccountActionsGateway gateway;
    private AuthGateway.Callback callback;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        tokens = mock(AuthTokenStore.class);
        when(tokens.userId()).thenReturn("test-user");
        http = mock(AuthenticatedHttpClient.class);
        callback = mock(AuthGateway.Callback.class);
        gateway = new AccountActionsGateway(context, tokens, http, Runnable::run);
        new LocalAccountStore(context).saveProfile("테스트", "test@example.com", "60", "", "");
        new TouchAutomationSettings(context).setEnabled(true);
    }

    @Test public void deletionRequiresServerSuccessBeforeClearingLocalState() throws Exception {
        when(http.delete("/api/v1/users/test-user")).thenAnswer(call -> {
            assertTrue(new LocalAccountStore(context).hasAccount());
            verify(tokens, never()).clear();
            return new HttpResult(204, "", null);
        });
        gateway.deleteAccount(callback);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        verify(http).delete("/api/v1/users/test-user");
        verify(tokens).clear();
        assertFalse(new LocalAccountStore(context).hasAccount());
        assertFalse(new TouchAutomationSettings(context).isEnabled());
        verify(callback).onSuccess();
    }

    @Test public void failedDeletionPreservesProfileAndDoesNotReportSuccess() throws Exception {
        java.io.IOException failure = new java.io.IOException("offline");
        when(http.delete(anyString())).thenThrow(failure);
        gateway.deleteAccount(callback);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(new LocalAccountStore(context).hasAccount());
        assertTrue(new TouchAutomationSettings(context).isEnabled());
        verify(tokens, never()).clear();
        verify(callback).onFailure(failure);
        verify(callback, never()).onSuccess();
    }

    @Test public void missingIdentityCannotSendDeletionOrClearLocalProfile() {
        when(tokens.userId()).thenReturn(null);
        gateway.deleteAccount(callback);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        verifyNoInteractions(http);
        verify(tokens, never()).clear();
        assertTrue(new LocalAccountStore(context).hasAccount());
        verify(callback).onFailure(any(java.io.IOException.class));
    }

    @Test public void logoutRevokesServerSessionWithoutDeletingServerAccount() throws Exception {
        gateway.logout(callback);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        verify(http).post("/api/v1/auth/logout", "{}");
        verify(http, never()).delete(anyString());
        verify(tokens).clear();
        assertFalse(new LocalAccountStore(context).hasAccount());
        verify(callback).onSuccess();
    }

    @Test public void failedLogoutKeepsLocalSessionForRetry() throws Exception {
        java.io.IOException failure = new java.io.IOException("offline");
        when(http.post(anyString(), anyString())).thenThrow(failure);
        gateway.logout(callback);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        verify(tokens, never()).clear();
        assertTrue(new LocalAccountStore(context).hasAccount());
        verify(callback).onFailure(failure);
        verify(callback, never()).onSuccess();
    }
}
