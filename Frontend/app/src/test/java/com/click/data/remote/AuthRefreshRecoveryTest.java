package com.click.data.remote;

import android.app.Application;
import com.click.data.local.AuthTokenStore;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class AuthRefreshRecoveryTest {
    private AuthTokenStore tokens() {
        var tokens=mock(AuthTokenStore.class);
        when(tokens.hasRefreshToken()).thenReturn(true);
        when(tokens.refreshToken()).thenReturn("test-refresh-token");
        return tokens;
    }
    @Test public void temporaryHttpFailuresPreserveCredentialsAndTheirTaskJournal() throws Exception {
        for(int status:new int[]{429,500,502,503,504,403,422}) {
            var tokens=tokens();
            ApiException expected=assertThrows(ApiException.class,()->AuthGateway.refresh(tokens,
                body->new HttpResult(status,"{}",null)));
            assertEquals(status,expected.status);
            verify(tokens,never()).clear();
            verify(tokens,never()).save(any());
        }
    }
    @Test public void offlineFailurePreservesCredentials() throws Exception {
        var tokens=tokens();
        assertThrows(IOException.class,()->AuthGateway.refresh(tokens,body->{throw new IOException("offline");}));
        verify(tokens,never()).clear();
        verify(tokens,never()).save(any());
    }
    @Test public void explicitInvalidRefreshRevokesCredentials() throws Exception {
        var tokens=tokens();
        ApiException expected=assertThrows(ApiException.class,()->AuthGateway.refresh(tokens,
            body->new HttpResult(401,"{}",null)));
        assertEquals(401,expected.status);
        verify(tokens).clear();
        verify(tokens,never()).save(any());
    }
    @Test public void oldRefreshCannotClearOrOverwriteNewKakaoLogin() throws Exception {
        for (int status : new int[]{200, 401}) {
            var tokens = tokens();
            var refresh = new java.util.concurrent.atomic.AtomicReference<>("old-refresh");
            when(tokens.refreshToken()).thenAnswer(call -> refresh.get());
            assertTrue(AuthGateway.refresh(tokens, body -> {
                refresh.set("new-kakao-refresh");
                return new HttpResult(status, "{}", null);
            }));
            verify(tokens, never()).clear();
            verify(tokens, never()).save(any());
        }
    }
    @Test public void oldRefreshCannotResurrectLoggedOutSession() throws Exception {
        var tokens = tokens();
        var refresh = new java.util.concurrent.atomic.AtomicReference<>("old-refresh");
        when(tokens.refreshToken()).thenAnswer(call -> refresh.get());
        when(tokens.hasRefreshToken()).thenAnswer(call -> refresh.get() != null);
        assertFalse(AuthGateway.refresh(tokens, body -> {
            refresh.set(null);
            return new HttpResult(200, "{}", null);
        }));
        verify(tokens, never()).save(any());
        verify(tokens, never()).clear();
    }
    @Test public void oldRequestDoesNotRotateAlreadyReplacedAccessToken() throws Exception {
        var tokens = tokens();
        when(tokens.accessToken()).thenReturn("new-kakao-access");
        assertTrue(AuthGateway.refresh(tokens, "old-access"));
        verify(tokens, never()).save(any());
        verify(tokens, never()).clear();
    }
}
