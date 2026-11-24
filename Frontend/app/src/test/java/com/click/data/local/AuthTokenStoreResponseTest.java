package com.click.data.local;
import android.app.Application;
import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, application=Application.class)
public class AuthTokenStoreResponseTest {
 private JSONObject response(String token) throws Exception {
  return new JSONObject().put("user_id","test-user").put("access_token",token.repeat(43))
   .put("refresh_token",token.repeat(44)).put("expires_in",900).put("refresh_expires_in",604800);
 }
 @Test public void partialResponseCannotReplaceValidLoginAndRefreshKeepsGeneration() throws Exception {
  var context=RuntimeEnvironment.getApplication();
  var store=new AuthTokenStore(context.getSharedPreferences("response-test",Context.MODE_PRIVATE));
  store.saveAuthentication(response("a"),"kakao");String generation=store.sessionGeneration();
  assertThrows(IllegalArgumentException.class,()->store.save(new JSONObject()));
  assertEquals("a".repeat(43),store.accessToken());assertTrue(store.hasRefreshToken());assertFalse(store.accessExpired());
  store.save(response("b"));assertEquals(generation,store.sessionGeneration());assertEquals("kakao",store.authenticationProvider());
  store.saveAuthentication(response("c"),"phone");assertNotEquals(generation,store.sessionGeneration());assertEquals("phone",store.authenticationProvider());
 }
}
