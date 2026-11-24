package com.click.data.remote;
import android.app.Application;
import com.click.data.local.AuthTokenStore;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class AuthenticatedHttpClientSessionTest {
 @Test public void delayed401ReusesAlreadyRefreshedTokenWithoutInvalidatingLogin() throws Exception {
  var tokens=mock(AuthTokenStore.class);var access=new AtomicReference<>("old-access");
  when(tokens.userId()).thenReturn("same-user");when(tokens.sessionGeneration()).thenReturn("same-login");
  when(tokens.hasRefreshToken()).thenReturn(true);when(tokens.accessToken()).thenAnswer(call->access.get());
  try(var server=mockStatic(AuthGateway.class)) {
   server.when(()->AuthGateway.get("/test","old-access")).thenAnswer(call->{access.set("new-access");return new HttpResult(401,"{}",null);});
   server.when(()->AuthGateway.refresh(tokens,"old-access")).thenCallRealMethod();
   server.when(()->AuthGateway.get("/test","new-access")).thenReturn(new HttpResult(200,"ok",null));
   assertEquals("ok",new AuthenticatedHttpClient(tokens).get("/test").body);
   verify(tokens,never()).clear();
  }
 }
 @Test public void aRequestFromPreviousLoginCannotBeRetriedAsNewAccount() throws Exception {
  var tokens=mock(AuthTokenStore.class);var user=new AtomicReference<>("old-user");var generation=new AtomicReference<>("old-login");
  when(tokens.userId()).thenAnswer(call->user.get());when(tokens.sessionGeneration()).thenAnswer(call->generation.get());
  when(tokens.accessToken()).thenReturn("access");
  try(var server=mockStatic(AuthGateway.class)) {
   server.when(()->AuthGateway.get("/test","access")).thenAnswer(call->{user.set("new-user");generation.set("new-login");return new HttpResult(401,"{}",null);});
   server.when(()->AuthGateway.refresh(tokens,"access")).thenReturn(true);
   IOException failure=assertThrows(IOException.class,()->new AuthenticatedHttpClient(tokens).get("/test"));
   assertEquals("AUTH_REQUIRED",failure.getMessage());
   server.verify(()->AuthGateway.get("/test","access"),times(1));
   verify(tokens,never()).clear();
  }
 }
 @Test public void accountActionCannotSendNewLoginCredentialForOldAccount() throws Exception {
  var tokens=mock(AuthTokenStore.class);
  when(tokens.userId()).thenReturn("new-user");when(tokens.sessionGeneration()).thenReturn("new-login");
  when(tokens.accessToken()).thenReturn("new-access");
  try(var server=mockStatic(AuthGateway.class)) {
   var http=new AuthenticatedHttpClient(tokens);
   assertThrows(IOException.class,()->http.deleteForSession("/api/v1/users/old-user","old-user","old-login"));
   assertThrows(IOException.class,()->http.postForSession("/api/v1/auth/logout","{}","old-user","old-login"));
   server.verifyNoInteractions();verify(tokens,never()).clear();
  }
 }
}
