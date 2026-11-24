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
        new LocalAccountStore(context).saveProfile("테스트", "test@example.com");
        new TouchAutomationSettings(context).setEnabled(true);
    }

    @Test public void deletionRequiresServerSuccessBeforeClearingLocalState() throws Exception {
        when(http.deleteForSession("/api/v1/users/test-user", "test-user", null)).thenAnswer(call -> {
            assertTrue(new LocalAccountStore(context).hasAccount());
            verify(tokens, never()).clear();
            return new HttpResult(204, "", null);
        });
        gateway.deleteAccount(callback);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        verify(http).deleteForSession("/api/v1/users/test-user", "test-user", null);
        verify(tokens).clear();
        assertFalse(new LocalAccountStore(context).hasAccount());
        assertFalse(new TouchAutomationSettings(context).isEnabled());
        verify(callback).onSuccess();
    }

    @Test public void failedDeletionPreservesProfileAndDoesNotReportSuccess() throws Exception {
        java.io.IOException failure = new java.io.IOException("offline");
        when(http.deleteForSession(anyString(), any(), any())).thenThrow(failure);
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
        verify(http).postForSession("/api/v1/auth/logout", "{}", "test-user", null);
        verify(http, never()).deleteForSession(anyString(), any(), any());
        verify(tokens).clear();
        assertFalse(new LocalAccountStore(context).hasAccount());
        verify(callback).onSuccess();
    }

    @Test public void failedLogoutKeepsLocalSessionForRetry() throws Exception {
        java.io.IOException failure = new java.io.IOException("offline");
        when(http.postForSession(anyString(), anyString(), any(), any())).thenThrow(failure);
        gateway.logout(callback);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        verify(tokens, never()).clear();
        assertTrue(new LocalAccountStore(context).hasAccount());
        verify(callback).onFailure(failure);
        verify(callback, never()).onSuccess();
    }
    @Test public void kakaoDeletionUnlinksOnlyAfterServerConfirmedAndLocalSessionCleared() throws Exception {
        when(tokens.authenticationProvider()).thenReturn("kakao");
        AccountActionsGateway.SocialConnection social = result -> {
            verify(tokens).clear();
            try { verify(http).deleteForSession("/api/v1/users/test-user", "test-user", null); } catch (Exception error) { throw new AssertionError(error); }
            result.accept(true);
        };
        gateway = new AccountActionsGateway(context, tokens, http, Runnable::run, social);
        gateway.deleteAccount(callback);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        verify(callback).onSuccess();
    }
    @Test public void failedKakaoUnlinkReportsDeletionCompletedWithExplicitNotice() {
        when(tokens.authenticationProvider()).thenReturn("kakao");
        gateway = new AccountActionsGateway(context, tokens, http, Runnable::run, result -> result.accept(false));
        gateway.deleteAccount(callback);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        verify(tokens).clear();
        verify(callback).onSuccessWithNotice(contains("카카오 연결 해제는 완료하지 못했어요"));
        verify(callback, never()).onFailure(any());
    }
    @Test public void failedClickDeletionNeverUnlinksKakao() throws Exception {
        when(tokens.authenticationProvider()).thenReturn("kakao");
        var social = mock(AccountActionsGateway.SocialConnection.class);
        gateway = new AccountActionsGateway(context, tokens, http, Runnable::run, social);
        when(http.deleteForSession(anyString(), any(), any())).thenThrow(new java.io.IOException("offline"));
        gateway.deleteAccount(callback);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        verifyNoInteractions(social);
        verify(tokens, never()).clear();
    }
    @Test public void lateLogoutCannotClearNewLogin() throws Exception {
        var generation = new java.util.concurrent.atomic.AtomicReference<>("old");
        when(tokens.sessionGeneration()).thenAnswer(call -> generation.get());
        when(http.postForSession(anyString(), anyString(), any(), any())).thenAnswer(call -> {
            generation.set("new");
            return new HttpResult(204, "", null);
        });
        gateway.logout(callback);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        verify(tokens, never()).clear();
        verify(callback).onFailure(any(java.io.IOException.class));
    }
}
