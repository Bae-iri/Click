package com.click.data.remote;

import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.local.AuthTokenStore;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in isolated account. Never reads or replaces the user's installed app credentials. */
public class DeviceAuditSetupTest {
    @Test public void prepareIsolatedAuditAccount() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("deviceAuditSetup")));
        var app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Separate diagnostic app required",app.getPackageName().endsWith(".deviceaudit"));
        var tokens=new AuthTokenStore(app);
        if("true".equals(InstrumentationRegistry.getArguments().getString("deviceAuditResetAccount")))tokens.clear();
        if(tokens.hasRefreshToken())return;
        var random=new java.security.SecureRandom();
        String phone="0100000"+String.format(java.util.Locale.ROOT,"%04d",random.nextInt(10000));
        JSONObject body=new JSONObject().put("name","실기기 검증용 임시 계정").put("phone_number",phone)
            .put("password",String.format(java.util.Locale.ROOT,"%04d",random.nextInt(10000)));
        HttpResult result=AuthGateway.post("/api/v1/auth/register",body.toString(),null);
        assertEquals("Audit account registration",201,result.status);
        tokens.save(new JSONObject(result.body));
        // Instrumentation may terminate the process immediately after this method.
        var field=AuthTokenStore.class.getDeclaredField("preferences");field.setAccessible(true);
        assertTrue(((android.content.SharedPreferences)field.get(tokens)).edit().commit());
        assertTrue(tokens.hasRefreshToken());
    }
    @Test public void removeIsolatedAuditAccount() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("deviceAuditCleanup")));
        var app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(app.getPackageName().endsWith(".deviceaudit"));
        var tokens=new AuthTokenStore(app);
        if(tokens.userId()==null)return;
        new AuthenticatedHttpClient(app).delete("/api/v1/users/"+tokens.userId());
        tokens.clear();
    }
}
