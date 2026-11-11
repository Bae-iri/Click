package com.click.data.remote;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.BuildConfig;
import com.click.data.local.AuthTokenStore;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in read-only installed-client probe; never creates a task or changes a setting. */
@RunWith(AndroidJUnit4.class)
public class InstalledServerCompatibilityTest {
 @Test public void installedClientNegotiatesItsServerAndRetainsLogin() throws Exception {
  var arguments=InstrumentationRegistry.getArguments();
  assumeTrue("true".equals(arguments.getString("verifyInstalledCompatibility")));
  var app=InstrumentationRegistry.getInstrumentation().getTargetContext();
  HttpResult schema=AuthGateway.get("/openapi.json",null);
  assertEquals(200,schema.status);
  JSONObject document=new JSONObject(schema.body);
  AgentServerContract contract=AgentServerContract.fromOpenApi(document);
  assertTrue(contract.supports("start_task"));assertTrue(contract.supports("cancel_task"));
  assertTrue(contract.supportsGoal("settings.font_size.increase"));
  boolean protocol=document.getJSONObject("components").getJSONObject("schemas").has("ExecutionContext");
  String expected=arguments.getString("expectedExecutionState");
  if(expected!=null)assertEquals("v1".equals(expected),protocol);
  assertTrue("The existing login must be retained",new AuthTokenStore(app).hasRefreshToken());
  HttpResult identity=new AuthenticatedHttpClient(app).get("/api/v1/users/me");
  assertEquals("Read-only authenticated check",200,identity.status);
  JSONObject report=new JSONObject().put("passed",true).put("api_base_url",BuildConfig.CLICK_API_BASE_URL)
    .put("execution_protocol_supported",protocol).put("login_retained",true).put("authenticated_read_status",identity.status)
    .put("task_created",false).put("device_action_performed",false);
  Files.write(new File(app.getCacheDir(),"installed-server-compatibility.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
 }
}
