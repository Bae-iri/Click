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
            try(var calls=mockStatic(AuthGateway.class)) {
                calls.when(()->AuthGateway.apiError(anyString(),any(HttpResult.class))).thenCallRealMethod();
                calls.when(()->AuthGateway.refresh(tokens)).thenCallRealMethod();
                calls.when(()->AuthGateway.post(eq("/api/v1/auth/refresh"),anyString(),isNull()))
                    .thenReturn(new HttpResult(status,"{}",null));
                try{AuthGateway.refresh(tokens);fail();}
                catch(ApiException expected){assertEquals(status,expected.status);}
                verify(tokens,never()).clear();
                verify(tokens,never()).save(any());
            }
        }
    }
    @Test public void offlineFailurePreservesCredentials() throws Exception {
        var tokens=tokens();
        try(var calls=mockStatic(AuthGateway.class)) {
                calls.when(()->AuthGateway.apiError(anyString(),any(HttpResult.class))).thenCallRealMethod();
            calls.when(()->AuthGateway.refresh(tokens)).thenCallRealMethod();
            calls.when(()->AuthGateway.post(eq("/api/v1/auth/refresh"),anyString(),isNull()))
                .thenThrow(new IOException("offline"));
            try{AuthGateway.refresh(tokens);fail();}catch(IOException expected){}
            verify(tokens,never()).clear();
        }
    }
    @Test public void explicitInvalidRefreshRevokesCredentials() throws Exception {
        var tokens=tokens();
        try(var calls=mockStatic(AuthGateway.class)) {
                calls.when(()->AuthGateway.apiError(anyString(),any(HttpResult.class))).thenCallRealMethod();
            calls.when(()->AuthGateway.refresh(tokens)).thenCallRealMethod();
            calls.when(()->AuthGateway.post(eq("/api/v1/auth/refresh"),anyString(),isNull()))
                .thenReturn(new HttpResult(401,"{}",null));
            try{AuthGateway.refresh(tokens);fail();}catch(ApiException expected){assertEquals(401,expected.status);}
            verify(tokens).clear();
        }
    }
}
