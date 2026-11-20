package com.click.service.accessibility;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.AppTaskObservation;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.overlay.AppTaskCompletion;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

/** Regressions on actual Android AX nodes and the actual native executor; no model calls. */
@RunWith(AndroidJUnit4.class)
public final class AppTaskSharedFixNativeTest {
    @Test public void enrichedObservationExecutesAndMenuCannotCompleteNavigation()throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context app=instrumentation.getTargetContext(),test=instrumentation.getContext();
        UiAutomation automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        String key=Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
        String original=Settings.Secure.getString(app.getContentResolver(),key);
        int enabled=Settings.Secure.getInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,0);
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
        try{
            ComponentName component=new ComponentName(app,ClickAccessibilityService.class);
            Set<String> services=new java.util.LinkedHashSet<>();
            if(original!=null)for(String s:original.split(":"))if(!s.isEmpty())services.add(s);
            services.remove(component.flattenToString());
            Settings.Secure.putString(app.getContentResolver(),key,String.join(":",services));
            SystemClock.sleep(350);services.add(component.flattenToString());
            Settings.Secure.putString(app.getContentResolver(),key,String.join(":",services));
            Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,1);
            long deadline=SystemClock.uptimeMillis()+10000;
            while(service()==null&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(100);
            assertNotNull("Test accessibility service must connect",service());
            app.startActivity(new Intent().setComponent(new ComponentName(test,AppTaskSharedFixFixtureActivity.class))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            awaitNode(test.getPackageName(),"디스플레이",true);
            // The first AX event can precede the end of Android's activity animation.
            SystemClock.sleep(700);
            ScreenSnapshot initial=ClickAccessibilityService.freshSnapshot();
            Set<String> requested=new HashSet<>(Set.of("디스플레이","공개 안내"));
            JSONObject observation=AppTaskObservation.capture(initial,requested);
            JSONObject plain=AppTaskObservation.capture(initial);
            assertNotEquals("Fixture must expose the original projection mismatch",plain.getString("fingerprint"),observation.getString("fingerprint"));
            JSONObject menuCompletion=new JSONObject().put("checks",new JSONArray().put(new JSONObject()
                .put("package",test.getPackageName()).put("label","디스플레이")));
            assertEquals("A menu is present, but the requested destination has not opened",AppTaskCompletion.Verdict.UNKNOWN,
                AppTaskCompletion.checkForGoal("디스플레이 화면을 열어줘",menuCompletion,observation,initial));
            JSONObject target=null;JSONArray nodes=observation.getJSONArray("nodes");
            for(int i=0;i<nodes.length();i++)if("디스플레이".equals(nodes.getJSONObject(i).optString("label"))
                    &&nodes.getJSONObject(i).optBoolean("clickable"))target=nodes.getJSONObject(i);
            assertNotNull(target);
            JSONObject action=new JSONObject().put("id","native-shared-fix").put("tool","tap")
                .put("package",test.getPackageName()).put("target_id",target.getString("id"))
                .put("bounds",target.getJSONArray("bounds"))
                .put("expectation",new JSONObject().put("kind","screen_changed"));
            ScreenSnapshot current=ClickAccessibilityService.freshSnapshot();
            assertEquals("Fixture must settle before native execution",observation.toString(),
                AppTaskObservation.recaptureLike(current,observation).put("observation_id",observation.get("observation_id"))
                    .put("captured_at_ms",observation.get("captured_at_ms")).toString());
            assertTrue("Same physical screen must execute under the proposal's sealed projection",
                AppTaskExecutor.execute(service(),initial,current,observation,action,null,test.getPackageName()));
            ScreenSnapshot destination=awaitNode(test.getPackageName(),"디스플레이",false);
            JSONObject after=AppTaskObservation.capture(destination,Set.of("디스플레이"));
            assertEquals("A destination heading is valid evidence, including an already-open destination",AppTaskCompletion.Verdict.MATCHED,
                AppTaskCompletion.checkForGoal("디스플레이 화면을 열어줘",menuCompletion,after,destination));
        }finally{
            if(service()!=null)service().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
            Settings.Secure.putString(app.getContentResolver(),key,original==null?"":original);
            Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,enabled);
            automation.dropShellPermissionIdentity();
        }
    }
    private static ScreenSnapshot awaitNode(String pkg,String label,boolean clickable)throws Exception {
        long deadline=SystemClock.uptimeMillis()+10000;
        while(SystemClock.uptimeMillis()<deadline){
            ScreenSnapshot snapshot=ClickAccessibilityService.freshSnapshot();
            if(snapshot!=null&&pkg.equals(snapshot.packageName))for(var node:snapshot.nodes)
                if(node.visibleToUser&&label.equals(node.text)&&node.clickable==clickable)return snapshot;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Expected fixture control was not observed");
    }
    private static ClickAccessibilityService service()throws Exception {
        var field=ClickAccessibilityService.class.getDeclaredField("connectedService");field.setAccessible(true);
        return (ClickAccessibilityService)((WeakReference<?>)field.get(null)).get();
    }
}
