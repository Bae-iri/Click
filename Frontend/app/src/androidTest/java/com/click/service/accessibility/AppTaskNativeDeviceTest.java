package com.click.service.accessibility;

import android.app.*;
import android.accessibilityservice.AccessibilityService;
import android.content.*;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.*;
import com.click.service.overlay.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Opens apps and changes navigation only; never changes settings or submits input. */
@RunWith(AndroidJUnit4.class)
public class AppTaskNativeDeviceTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    @Test public void nativeToolsOpenReadTapScrollAndBackWithoutAnAppAllowlist()throws Exception {
        Context app=instrumentation.getTargetContext();UiAutomation automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        String key=Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,original=Settings.Secure.getString(app.getContentResolver(),key);
        int enabled=Settings.Secure.getInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,0);
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
        try{
            try(var wake=automation.executeShellCommand("input keyevent KEYCODE_WAKEUP")){}
            ComponentName component=new ComponentName(app,ClickAccessibilityService.class);List<String> services=new ArrayList<>();
            if(original!=null)for(String value:original.split(":"))if(!value.isEmpty()&&!component.equals(ComponentName.unflattenFromString(value)))services.add(value);
            Settings.Secure.putString(app.getContentResolver(),key,String.join(":",services));SystemClock.sleep(400);services.add(component.flattenToString());Settings.Secure.putString(app.getContentResolver(),key,String.join(":",services));Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,1);await(()->service()!=null);
            app.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));await(()->isPackage("com.android.settings"));
            for(String pkg:List.of("com.android.settings","com.android.chrome","com.google.android.youtube")){
                assertNotNull("Required test app missing: "+pkg,app.getPackageManager().getLaunchIntentForPackage(pkg));
                ScreenSnapshot before=ClickAccessibilityService.freshSnapshot();JSONObject observation=AppTaskObservation.capture(before);
                JSONObject open=new JSONObject().put("id",UUID.randomUUID().toString()).put("tool","open_app").put("package",pkg).put("expectation",new JSONObject().put("kind","package_opened").put("package",pkg));
                assertTrue("Native app-open tool: "+pkg,AppTaskExecutor.execute(service(),before,ClickAccessibilityService.freshSnapshot(),observation,open,null,pkg));
                ScreenSnapshot after=publicSnapshot(pkg);JSONObject current=AppTaskObservation.capture(after);
                assertEquals("Opening app must be observed: "+pkg+" actual="+current.optString("package"),AppTaskVerifier.Verdict.VERIFIED,AppTaskVerifier.check(observation,current,open,true,false));
                assertEquals("public",current.getString("status"));assertTrue("No common controls in "+pkg,current.getJSONArray("nodes").length()>0);
                // The same native back tool runs in each package, without a per-app branch.
                JSONObject back=new JSONObject().put("id",UUID.randomUUID().toString()).put("tool","back").put("package",pkg).put("expectation",new JSONObject().put("kind","screen_changed"));
                assertTrue("Native back tool: "+pkg,backFromPublicScreen(pkg,back));SystemClock.sleep(400);
            }
            // Prepare a public, scrollable page directly; its position in the Settings home
            // list depends on font size and the user's previous scroll position.
            app.startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));await(()->isPackage("com.android.settings"));SystemClock.sleep(700);
            ScreenSnapshot before=ClickAccessibilityService.freshSnapshot();JSONObject page=AppTaskObservation.capture(before);JSONObject list=null;
            for(int i=0;i<page.getJSONArray("nodes").length();i++){JSONObject n=page.getJSONArray("nodes").getJSONObject(i);if(n.optBoolean("scrollable")){list=n;break;}}
            assertNotNull("Display has a native scroll container",list);JSONObject scroll=targetAction(page,list,"scroll_forward");
            assertTrue("Native scroll accepted",AppTaskExecutor.execute(service(),before,ClickAccessibilityService.freshSnapshot(),page,scroll,null,null));
            await(()->{try{return AppTaskVerifier.check(page,AppTaskObservation.capture(ClickAccessibilityService.freshSnapshot()),scroll,true,false)==AppTaskVerifier.Verdict.VERIFIED;}catch(Exception e){return false;}});
            SystemClock.sleep(500);before=ClickAccessibilityService.freshSnapshot();JSONObject observation=AppTaskObservation.capture(before),target=null;
            JSONArray nodes=observation.getJSONArray("nodes");for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);String label=n.optString("label");if(n.optBoolean("clickable")&&(label.contains("뒤로")||label.contains("상위 메뉴")||label.equals("이전")||label.equals("Back")||label.equals("Navigate up"))){target=n;break;}}
            assertNotNull("A native toolbar back button is required",target);JSONObject tap=targetAction(observation,target,"tap");
            assertTrue("Native toolbar tap accepted",AppTaskExecutor.execute(service(),before,ClickAccessibilityService.freshSnapshot(),observation,tap,null,null));
            await(()->{try{return AppTaskVerifier.check(observation,AppTaskObservation.capture(ClickAccessibilityService.freshSnapshot()),tap,true,false)==AppTaskVerifier.Verdict.VERIFIED;}catch(Exception e){return false;}});
        }finally{
            if(service()!=null)service().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            Settings.Secure.putString(app.getContentResolver(),key,original==null?"":original);Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,enabled);automation.dropShellPermissionIdentity();
        }
    }
    private static JSONObject targetAction(JSONObject s,JSONObject n,String tool)throws Exception{return new JSONObject().put("id",UUID.randomUUID().toString()).put("tool",tool).put("target_id",n.getString("id")).put("bounds",n.getJSONArray("bounds")).put("package",s.getString("package")).put("expectation",new JSONObject().put("kind","screen_changed"));}
    private static boolean isPackage(String pkg){ScreenSnapshot s=ClickAccessibilityService.freshSnapshot();return s!=null&&pkg.equals(s.packageName)&&!s.nodes.isEmpty();}
    private static boolean backFromPublicScreen(String pkg,JSONObject action)throws Exception {
        long deadline=SystemClock.uptimeMillis()+10000;
        // A feed can refresh after the app-open assertion. Reobserve after a safe
        // stale-screen rejection, just as the controller does; never retry an accepted action.
        while(SystemClock.uptimeMillis()<deadline){ScreenSnapshot baseline=ClickAccessibilityService.freshSnapshot();if(baseline!=null&&pkg.equals(baseline.packageName)){
            JSONObject observation=AppTaskObservation.capture(baseline);
            if("public".equals(observation.optString("status"))&&AppTaskExecutor.execute(service(),baseline,ClickAccessibilityService.freshSnapshot(),observation,action,null,null))return true;
        }SystemClock.sleep(150);}return false;
    }
    private static ScreenSnapshot publicSnapshot(String pkg)throws Exception {
        long deadline=SystemClock.uptimeMillis()+10000;String previous=null,lastPackage="",lastStatus="";
        while(SystemClock.uptimeMillis()<deadline){ScreenSnapshot s=ClickAccessibilityService.freshSnapshot();if(s!=null){JSONObject screen=AppTaskObservation.capture(s);lastPackage=screen.optString("package");lastStatus=screen.optString("status");String key=screen.optString("fingerprint");
            if(pkg.equals(s.packageName)&&"public".equals(lastStatus)){if(key.equals(previous))return s;previous=key;}else previous=null;
        }SystemClock.sleep(150);}throw new AssertionError("Public app did not settle: target="+pkg+" actual="+lastPackage+" status="+lastStatus);
    }
    private static ClickAccessibilityService service(){try{var f=ClickAccessibilityService.class.getDeclaredField("connectedService");f.setAccessible(true);return (ClickAccessibilityService)((WeakReference<?>)f.get(null)).get();}catch(Exception e){throw new AssertionError(e);}}
    private static void await(BooleanSupplier condition){long deadline=SystemClock.uptimeMillis()+10000;while(SystemClock.uptimeMillis()<deadline){if(condition.getAsBoolean())return;SystemClock.sleep(100);}fail("Expected native effect was not observed");}
}
