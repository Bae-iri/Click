package com.click.data.remote;

import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.BuildConfig;
import com.click.data.local.TouchAutomationSettings;
import com.click.service.accessibility.ClickAccessibilityService;
import com.click.service.overlay.ClickOverlayService;
import com.click.testutil.ServiceStateAccess;
import java.lang.reflect.Method;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Explicit live smoke: real Settings home, real providers, native change, restored in finally. */
@RunWith(AndroidJUnit4.class)
public class SettingsExperimentDeviceTest {
    private final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Object field(Object target, String name) { return ServiceStateAccess.get(target, name); }
    private void call(Object target, String name, Class<?>[] types, Object... args) {
        try { Method m=target.getClass().getDeclaredMethod(name,types); m.setAccessible(true); m.invoke(target,args); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void shell(UiAutomation ui, String command) throws Exception {
        try (var fd=ui.executeShellCommand(command); var in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) { in.readAllBytes(); }
    }
    private ClickOverlayService service() throws Exception {
        var f=ClickOverlayService.class.getDeclaredField("runningService");f.setAccessible(true);
        return (ClickOverlayService)((java.lang.ref.WeakReference<?>)f.get(null)).get();
    }
    @Test public void settingsHomeToVerifiedFontChange() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("settingsExperimentSmoke")));
        assertFalse("Use an experiment APK",BuildConfig.SETTINGS_EXPERIMENT_ARM.isEmpty());
        Context app=instrumentation.getTargetContext();
        var ui=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        assertFalse("Unlock the phone",app.getSystemService(android.app.KeyguardManager.class).isDeviceLocked());
        String component=app.getPackageName()+"/com.click.service.accessibility.ClickAccessibilityService";
        String enabled=Settings.Secure.getString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        assertTrue("Select this experiment arm before running",enabled!=null && enabled.contains(component));
        // Instrumentation restarts the process. Reconnect its already-authorized service.
        ui.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
        try {
            var services=new java.util.ArrayList<>(java.util.Arrays.asList(enabled.split(":")));
            services.remove(component);
            Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",services));
            SystemClock.sleep(400);
            Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,enabled);
            Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,1);
        } finally { ui.dropShellPermissionIdentity(); }
        for(int i=0;i<80 && ClickAccessibilityService.accessibilityWindowManager()==null;i++)SystemClock.sleep(100);
        assertNotNull("Accessibility connected",ClickAccessibilityService.accessibilityWindowManager());
        // A failed/aborted smoke can leave its queue item running across process restarts.
        // Retire only this diagnostic's exact request; never replace another user task.
        var http=new com.click.data.remote.AuthenticatedHttpClient(app);
        JSONObject queue=new JSONObject(http.get("/api/v1/voice-requests").body);
        var oldItems=new org.json.JSONArray();
        if(queue.optJSONObject("running")!=null)oldItems.put(queue.getJSONObject("running"));
        var queued=queue.optJSONArray("queued");
        if(queued!=null)for(int i=0;i<queued.length();i++)oldItems.put(queued.getJSONObject(i));
        for(int i=0;i<oldItems.length();i++) {
            var item=oldItems.getJSONObject(i);
            assertEquals("Finish other work before the experiment smoke", "휴대폰 전체 글자 크기 키워줘",item.optString("raw_goal"));
        }
        if(oldItems.length()>0)http.post("/api/v1/voice-requests/interrupt-and-clear",
            new JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("clear_queued",true).toString());
        ClickOverlayService previousService=service();
        if(previousService!=null){instrumentation.runOnMainSync(previousService::onStopRequested);SystemClock.sleep(1000);}
        float original=Settings.System.getFloat(app.getContentResolver(),Settings.System.FONT_SCALE);
        var automatic=new TouchAutomationSettings(app);boolean previousAutomatic=automatic.isEnabled();
        ClickOverlayService active=null;
        JSONObject evidence=new JSONObject().put("arm",BuildConfig.SETTINGS_EXPERIMENT_ARM)
            .put("start_screen","settings_home").put("original_font_scale",original);
        try {
            automatic.setEnabled(true);
            app.startActivity(new Intent(app,com.click.HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            shell(ui,"am start -W -a android.settings.SETTINGS -f 0x10008000");
            SystemClock.sleep(1200);
            app.startService(new Intent(app,ClickOverlayService.class)
                .putExtra(ClickOverlayService.EXTRA_INITIAL_GOAL,"휴대폰 전체 글자 크기 키워줘"));
            for(int i=0;i<80 && active==null;i++){SystemClock.sleep(100);active=service();}
            assertNotNull(active);
            final ClickOverlayService flow=active;
            boolean result=false;
            long started=SystemClock.elapsedRealtime();
            for(int i=0;i<360;i++) {
                SystemClock.sleep(500);
                final boolean[] done={false};
                instrumentation.runOnMainSync(()->{
                    if(field(flow,"acceptedGoal")==null && !(boolean)field(flow,"turnInFlight"))
                        call(flow,"confirmRequirementReview",new Class<?>[]{});
                    String confirmation=(String)field(flow,"agentConfirmationId");
                    if(confirmation!=null && !(boolean)field(flow,"turnInFlight"))
                        call(flow,"sendAgentTurn",new Class<?>[]{String.class,boolean.class,com.click.data.snapshot.ScreenSnapshot.class},
                            "accepted",true,field(flow,"lastSubmittedScreen"));
                    done[0]=field(flow,"resultPrompt")!=null;
                });
                if(done[0]){result=true;break;}
            }
            evidence.put("elapsed_ms",SystemClock.elapsedRealtime()-started).put("result_prompt",result)
                .put("after_font_scale",Settings.System.getFloat(app.getContentResolver(),Settings.System.FONT_SCALE));
            assertTrue("Settings navigation and adjustment must reach a result",result);
            final String[] technical={null};
            instrumentation.runOnMainSync(()->technical[0]=((JSONObject)field(flow,"resultPrompt")).optString("technical_status"));
            evidence.put("technical_status",technical[0]);
            assertEquals("Native change must be verified","CHANGED",technical[0]);
            assertTrue("The actual device value increased",Settings.System.getFloat(app.getContentResolver(),Settings.System.FONT_SCALE)>original);
        } finally {
            if(active!=null){final ClickOverlayService flow=active;instrumentation.runOnMainSync(flow::onStopRequested);SystemClock.sleep(1000);}
            automatic.setEnabled(previousAutomatic);
            shell(ui,"settings put system font_scale "+Float.toString(original));
            SystemClock.sleep(500);
            evidence.put("restored_font_scale",Settings.System.getFloat(app.getContentResolver(),Settings.System.FONT_SCALE));
            java.nio.file.Files.write(new java.io.File(app.getCacheDir(),"settings-experiment-smoke.json").toPath(),
                evidence.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            android.util.Log.i("ClickExperimentSmoke",evidence.toString());
            assertEquals("Restore original font size",original,Settings.System.getFloat(app.getContentResolver(),Settings.System.FONT_SCALE),.001f);
        }
    }
}
