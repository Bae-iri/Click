package com.click.service.overlay;

import android.app.UiAutomation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.ScreenIdentity;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.accessibility.ClickAccessibilityService;
import com.click.ui.overlay.TaskFlowOverlayView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Runs production recovery UI and its real native Back action without a server or setting writes. */
@RunWith(AndroidJUnit4.class)
public class RecoveryActionDeviceTest {
    private final android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private UiAutomation automation;

    private static Field field(Class<?> type,String name) throws Exception {
        Field field=type.getDeclaredField(name);field.setAccessible(true);return field;
    }
    private static void set(Object target,String name,Object value) throws Exception {field(target.getClass(),name).set(target,value);}
    private boolean connected() {
        try{return ((java.lang.ref.WeakReference<?>)field(ClickAccessibilityService.class,"connectedService").get(null)).get()!=null;}
        catch(Exception failure){throw new AssertionError(failure);}
    }
    private void connect(Context app) {
        automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        for(int i=0;i<20 && !connected();i++)SystemClock.sleep(200);
        if(connected())return;
        String original=Settings.Secure.getString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        int enabled=Settings.Secure.getInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,0);
        String component=new android.content.ComponentName(app,ClickAccessibilityService.class).flattenToString();
        assertNotNull("Accessibility must already be enabled",original);assertEquals(1,enabled);
        java.util.List<String> others=new java.util.ArrayList<>();boolean found=false;
        for(String entry:original.split(":")) {
            if(android.content.ComponentName.unflattenFromString(component).equals(android.content.ComponentName.unflattenFromString(entry)))found=true;
            else others.add(entry);
        }
        assertTrue("Do not grant a new accessibility service",found);
        automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        try {
            Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",others));
            SystemClock.sleep(500);
        } finally {
            Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,original);
            Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,enabled);
            automation.dropShellPermissionIdentity();
        }
        for(int i=0;i<40 && !connected();i++)SystemClock.sleep(200);
        assertTrue("Existing accessibility service must reconnect",connected());
    }
    private ScreenSnapshot waitForPage(String title) {
        for(int i=0;i<50;i++) {
            ScreenSnapshot screen=ClickAccessibilityService.freshSnapshot();
            if(screen!=null && title.equals(ScreenIdentity.pageTitle(screen)))return screen;
            SystemClock.sleep(150);
        }
        throw new AssertionError("Native Settings did not reach the expected public page: "+title);
    }
    private void tap(Rect bounds) {
        long now=SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,bounds.centerX(),bounds.centerY(),0);
        MotionEvent up=MotionEvent.obtain(now,now+80,MotionEvent.ACTION_UP,bounds.centerX(),bounds.centerY(),0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try {assertTrue(automation.injectInputEvent(down,true));assertTrue(automation.injectInputEvent(up,true));}
        finally {down.recycle();up.recycle();}
    }
    private void scenario(boolean automatic) throws Exception {
        Context app=instrumentation.getTargetContext();connect(app);
        app.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ScreenSnapshot before=waitForPage("설정");
        // Establish a real off-route child without depending on Samsung's list animations/scroll position.
        app.startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForPage("연결");
        SharedPreferences preference=app.getSharedPreferences("recovery_action_probe_automation",Context.MODE_PRIVATE);
        assertTrue(preference.edit().clear().putBoolean("enabled",automatic).commit());
        Context context=new ContextWrapper(app) {
            @Override public SharedPreferences getSharedPreferences(String name,int mode) {
                return "touch_automation".equals(name)?preference:super.getSharedPreferences(name,mode);
            }
        };
        WindowManager manager=ClickAccessibilityService.accessibilityWindowManager();
        assertNotNull("Use the production accessibility overlay window manager",manager);
        ClickOverlayService[] service={null};TaskFlowOverlayView[] panel={null};
        TaskExecutionController[] execution={null};Rect buttonBounds=new Rect();
        Handler handler=new Handler(Looper.getMainLooper());
        ScreenSnapshot baseline=before;
        try {
            instrumentation.runOnMainSync(()->{
                try {
                    service[0]=new ClickOverlayService();
                    Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(service[0],context);
                    execution[0]=(TaskExecutionController)field(ClickOverlayService.class,"execution").get(service[0]);
                    execution[0].bindSession("native-recovery-probe");execution[0].setStarted(true);
                    set(execution[0],"agentEpisodeId","probe-episode");
                    execution[0].setAcceptedGoal(new JSONObject().put("goal_type","settings.font_size.increase")
                            .put("target_app","android_settings").put("slots",new JSONObject().put("setting","font_size")));
                    execution[0].environmentReady();
                    set(service[0],"restoredStartedSession",true);set(service[0],"initialGoal","글자 크기 키워줘");
                    set(service[0],"lastSubmittedScreen",baseline);set(service[0],"observedPageScreen",baseline);
                    set(service[0],"windowManager",manager);set(service[0],"speechHandler",handler);
                    panel[0]=new TaskFlowOverlayView(app,new TaskFlowOverlayView.Listener() {
                        public void chooseGoal(JSONObject value){} public void clarify(String value){} public void rephrase(String value){}
                        public void result(String value){} public void adjust(String value){} public void control(String value){}
                    });
                    var params=new WindowManager.LayoutParams(panel[0].getWindowWidth(app.getResources().getDisplayMetrics().widthPixels),-2,
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
                    params.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;
                    set(service[0],"taskFlowView",panel[0]);set(service[0],"taskFlowParams",params);
                    manager.addView(panel[0],params);
                    Method recover=ClickOverlayService.class.getDeclaredMethod("showTypedRecovery",String.class,Runnable.class);recover.setAccessible(true);
                    recover.invoke(service[0],"SETTINGS_OFF_PATH",(Runnable)()->fail("A recovery must navigate, not repeat a model call"));
                } catch(Exception failure){throw new AssertionError(failure);}
            });
            if(!automatic) {
                SystemClock.sleep(300);
                instrumentation.runOnMainSync(()->{
                    Button button=panel[0].findViewWithTag("task_recovery_action");
                    assertNotNull("Real Back button must be present",button);assertEquals("이전 화면으로",button.getText().toString());
                    assertTrue("Real Back button must be visible",button.isShown());assertTrue(button.getGlobalVisibleRect(buttonBounds));
                    assertTrue(buttonBounds.width()>0 && buttonBounds.height()>0);
                    int[] location=new int[2];button.getLocationOnScreen(location);
                    buttonBounds.set(location[0],location[1],location[0]+button.getWidth(),location[1]+button.getHeight());
                    Bitmap image=Bitmap.createBitmap(panel[0].getWidth(),panel[0].getHeight(),Bitmap.Config.ARGB_8888);
                    panel[0].draw(new Canvas(image));
                    try(var out=new FileOutputStream(new File(app.getCacheDir(),"native-recovery-button.png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}
                    catch(Exception failure){throw new AssertionError(failure);}finally {image.recycle();}
                });
                tap(buttonBounds);
            }
            try {waitForPage("설정");}
            catch(AssertionError failure) {
                String[] status={""};
                instrumentation.runOnMainSync(()->{
                    try {status[0]="automatic="+automatic+" dispatched="+field(ClickOverlayService.class,"settingsBackRecoveries").get(service[0])
                            +" phase="+execution[0].phase()+" guidance="+panel[0].guidanceMessage();}
                    catch(Exception error){throw new AssertionError(error);}
                });
                throw new AssertionError(status[0],failure);
            }
            SystemClock.sleep(1100);
            instrumentation.runOnMainSync(()->{
                assertEquals("native-recovery-probe",execution[0].sessionId());assertEquals("probe-episode",execution[0].episodeId());
                assertEquals("settings.font_size.increase",execution[0].acceptedGoal().optString("goal_type"));
                assertTrue("Return must request a fresh observation",execution[0].observationRequested());
            });
        } finally {
            instrumentation.runOnMainSync(()->{
                handler.removeCallbacksAndMessages(null);
                if(panel[0]!=null && panel[0].isAttachedToWindow())manager.removeViewImmediate(panel[0]);
            });
            assertTrue(preference.edit().clear().commit());app.deleteSharedPreferences("recovery_action_probe_automation");
        }
    }
    @Test public void actualRecoveryButtonReturnsToThePreviousSettingsPage() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("nativeRecoveryProbe")));
        scenario(false);
    }
    @Test public void automaticRecoveryReturnsToThePreviousSettingsPage() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("nativeRecoveryProbe")));
        scenario(true);
    }
}
