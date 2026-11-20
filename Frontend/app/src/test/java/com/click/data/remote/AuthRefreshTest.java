package com.click.data.remote;

import android.app.Application;
import com.click.data.local.AuthTokenStore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class AuthRefreshTest {
    private AuthTokenStore store() {
        AuthTokenStore tokens=mock(AuthTokenStore.class);
        when(tokens.hasRefreshToken()).thenReturn(true);
        when(tokens.refreshToken()).thenReturn("existing-refresh-token");
        return tokens;
    }
    @Test public void temporaryServerAndRateLimitFailuresKeepSessionAndJournal() throws Exception {
        for(int status:new int[]{408,429,500,502,503,504}) {
            AuthTokenStore tokens=store();
            ApiException error=assertThrows(ApiException.class,()->AuthGateway.refresh(tokens,
                body->new HttpResult(status,"{}",null)));
            assertEquals(status,error.status);
            verify(tokens,never()).clear();
            verify(tokens,never()).save(any());
            // A later retry can succeed using the same retained credential.
            assertTrue(AuthGateway.refresh(tokens,body->new HttpResult(200,"{}",null)));
            verify(tokens).save(any());
        }
    }
    @Test public void rejectedRefreshCredentialClearsSession() {
        AuthTokenStore tokens=store();
        assertThrows(ApiException.class,()->AuthGateway.refresh(tokens,body->new HttpResult(401,"{}",null)));
        verify(tokens).clear();
    }
    @Test public void disconnectedAndMalformedResponsesKeepExistingCredentials() {
        AuthTokenStore tokens=store();
        assertThrows(java.io.IOException.class,()->AuthGateway.refresh(tokens,body->{throw new java.io.IOException("offline");}));
        assertThrows(org.json.JSONException.class,()->AuthGateway.refresh(tokens,body->new HttpResult(200,"broken",null)));
        verify(tokens,never()).clear();
        verify(tokens,never()).save(any());
    }
}
