package com.click.data.remote;
import android.app.Application;
import com.click.BuildConfig;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class ReleaseIdentityTest {
 @Test public void releaseIdentityChecksTheSameApiOriginThatTheAppActuallyCalls() throws Exception {
  try(var input=RuntimeEnvironment.getApplication().getAssets().open("click_release_identity.json")) {
   var json=new JSONObject(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
   assertEquals(BuildConfig.CLICK_API_BASE_URL,json.getString("api_base_url"));
  }
 }
}
