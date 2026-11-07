package com.click.data.remote;

import android.app.*;
import android.content.*;
import android.media.AudioManager;
import android.os.*;
import android.provider.Settings;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.BuildConfig;
import com.click.data.local.TouchAutomationSettings;
import com.click.data.snapshot.*;
import com.click.service.accessibility.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in, real native Settings + authenticated Jev server. Never fabricates user satisfaction. */
@RunWith(AndroidJUnit4.class)
public class LiveNativeSettingsStabilityTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context app=instrumentation.getTargetContext();
    private final JSONObject report=new JSONObject();
    private UiAutomation automation;
    private float originalFont;
    private int originalBrightness, originalMode, originalVolume;
    private void save() throws Exception {
        Files.write(new File(app.getCacheDir(),"native-settings-stability-result.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    private void shell(String cmd) throws Exception {
        try(ParcelFileDescriptor fd=automation.executeShellCommand(cmd); InputStream in=new FileInputStream(fd.getFileDescriptor())) { in.readAllBytes(); }
    }
    private void restoreValues() throws Exception {
        shell("settings put system font_scale "+originalFont);
        shell("settings put system screen_brightness "+originalBrightness);
        shell("settings put system screen_brightness_mode "+originalMode);
        shell("cmd media_session volume --stream 3 --set "+originalVolume);
        SystemClock.sleep(800);
    }
    private float value(String setting) throws Exception {
        if(setting.equals("font_size"))return Settings.System.getFloat(app.getContentResolver(),Settings.System.FONT_SCALE);
        if(setting.equals("brightness"))return Settings.System.getInt(app.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS);
        return app.getSystemService(AudioManager.class).getStreamVolume(AudioManager.STREAM_MUSIC);
    }
    private AccessibilityNodeSnapshot target(ScreenSnapshot s,JSONObject g) {
        for(AccessibilityNodeSnapshot n:s.nodes) if(n.nodeId.equals(g.optString("target_node_id")) && g.optJSONArray("target_bounds")!=null) {
            JSONArray b=g.optJSONArray("target_bounds");
            if(n.bounds[0]==b.optInt(0)&&n.bounds[1]==b.optInt(1)&&n.bounds[2]==b.optInt(2)&&n.bounds[3]==b.optInt(3))return n;
        }
        return null;
    }
    private boolean scroll(android.view.accessibility.AccessibilityNodeInfo root,boolean forward) {
        if(root.isScrollable() && root.performAction(forward?android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))return true;
        for(int i=0;i<root.getChildCount();i++) {
            android.view.accessibility.AccessibilityNodeInfo child=root.getChild(i);
            if(child!=null)try {if(scroll(child,forward))return true;}finally{child.recycle();}
        }
        return false;
    }
    private boolean scrollSettings(boolean forward) {
        for(android.view.accessibility.AccessibilityWindowInfo window:automation.getWindows()) {
            android.view.accessibility.AccessibilityNodeInfo root=window.getRoot();
            if(root!=null)try {if("com.android.settings".contentEquals(root.getPackageName()==null?"":root.getPackageName()) && scroll(root,forward))return true;}
            finally{root.recycle();}
        }
        return false;
    }
    @Test public void sixTasksFromSettingsHomeToVerifiedDeviceChange() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("liveSettingsStability")));
        automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        assertFalse("Unlock the device before validation",app.getSystemService(KeyguardManager.class).isDeviceLocked());
        String originalServices=Settings.Secure.getString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        int originalEnabled=Settings.Secure.getInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,0);
        TouchAutomationSettings touch=new TouchAutomationSettings(app);boolean originalTouch=touch.isEnabled();
        originalFont=Settings.System.getFloat(app.getContentResolver(),Settings.System.FONT_SCALE);
        originalBrightness=Settings.System.getInt(app.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS);
        originalMode=Settings.System.getInt(app.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS_MODE);
        AudioManager audio=app.getSystemService(AudioManager.class);
        originalVolume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        Map<Integer,Integer> otherStreams=new HashMap<>();
        for(int stream:new int[]{AudioManager.STREAM_RING,AudioManager.STREAM_NOTIFICATION,AudioManager.STREAM_ALARM,AudioManager.STREAM_SYSTEM})otherStreams.put(stream,audio.getStreamVolume(stream));
        report.put("source","physical_native_settings_real_jev").put("cases",new JSONArray()).put("maximum_screen_posts_per_task",16);
        AuthenticatedHttpClient http=new AuthenticatedHttpClient(app);
        try {
            JSONObject version=new JSONObject(AuthGateway.get("/version",null).body);
            assertEquals("The APK must call the approved verification server",InstrumentationRegistry.getArguments().getString("expectedBaseUrl"),BuildConfig.CLICK_API_BASE_URL.replaceAll("/+$",""));
            report.put("server_build",version);
            report.put("server",BuildConfig.CLICK_API_BASE_URL);
            assertEquals("Deploy the common target policy before the physical suite","settings-slider.v1",version.optString("target_validation"));
            String expected=InstrumentationRegistry.getArguments().getString("expectedSourceSha256");
            assertNotNull("Supply the tested release source SHA256",expected);
            assertEquals("The server must run the exact tested backend/AI sources",expected,version.getString("source_sha256"));
            automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
            ComponentName component=new ComponentName(app,ClickAccessibilityService.class);
            List<String> enabled=new ArrayList<>();
            if(originalServices!=null)for(String item:originalServices.split(":"))if(!item.isBlank()&&!component.equals(ComponentName.unflattenFromString(item)))enabled.add(item);
            Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",enabled));
            SystemClock.sleep(300);enabled.add(component.flattenToString());
            Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",enabled));
            Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,1);
            touch.setEnabled(true);
            SettingsScreenPrivacy privacy;
            try(InputStream v=app.getAssets().open("vision_vocabulary.json");InputStream p=app.getAssets().open("screen_privacy_policy.json")){privacy=SettingsScreenPrivacy.load(v,p);}
            AgentServerContract contract=AgentServerContract.fromOpenApi(new JSONObject(AuthGateway.get("/openapi.json",null).body));
            JSONObject catalog;
            try(InputStream in=app.getAssets().open("mvp_goals.json")){catalog=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
            for(String setting:List.of("font_size","brightness","volume"))for(String direction:List.of("increase","decrease")) {
                restoreValues();
                if(setting.equals("brightness"))shell("settings put system screen_brightness_mode 0");
                app.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                SystemClock.sleep(1200);
                // Start on Settings home with its first route row visible, not on a pre-opened control page.
                String firstRow=setting.equals("volume")?"소리 및 진동":"디스플레이";
                for(int attempt=0;attempt<8;attempt++) {
                    ScreenSnapshot initial=ClickAccessibilityService.freshSnapshot();
                    if(initial!=null && initial.nodes.stream().anyMatch(n->n.visibleToUser && firstRow.equals(n.text)))break;
                    scrollSettings(attempt>=4);SystemClock.sleep(350);
                }
                String session=UUID.randomUUID().toString();JSONObject last=null,event=null;
                JSONObject item=new JSONObject().put("goal",setting+"."+direction).put("start","android_settings_home").put("turns",new JSONArray());
                report.getJSONArray("cases").put(item);save();
                float before=value(setting);boolean changed=false,approved=false;int repeat=0,retries=0,actions=0,scrolls=0;
                try {
                    for(int turn=0;turn<16;turn++) {
                        SystemClock.sleep(800);
                        ScreenSnapshot screen=ClickAccessibilityService.freshSnapshot();assertNotNull("Native screen",screen);
                        JSONObject request=new JSONObject().put("contract_version",contract.contractVersion).put("request_id",UUID.randomUUID().toString())
                            .put("session_id",session).put("expected_version",last==null?0:last.getInt("session_version"))
                            .put("command_type",last==null?"start_task":"observe").put("accessibility_tree",new JSONObject(screen.toJson()));
                        if(last==null) {
                            request.put("goal",new JSONObject().put("catalog_version",catalog.getString("version")).put("goal_type","settings."+setting+"."+direction)
                                .put("target_app","android_settings").put("slots",new JSONObject().put("setting",setting).put("direction",direction)))
                                .put("device_context",new JSONObject().put("platform","android").put("locale","ko-KR"));
                            if(contract.interactiveWorkflow)request.put("workflow_version","interactive.v1");
                        } else {
                            request.put("episode_id",last.getString("episode_id"));
                            JSONObject guide=last.getJSONObject("guide_command");
                            if("confirmation".equals(guide.optString("command_type")) && repeat>=2) {
                                AccessibilityNodeSnapshot n=target(screen,guide);
                                assertTrue("Consent target must match the actual requested setting",SettingsSliderTarget.matches(screen,n,setting));
                                request.put("command_type","respond_confirmation").put("user_confirmation","accepted")
                                    .put("confirmation_id",last.getJSONObject("metadata").getJSONObject("confirmation").getString("id"));approved=true;
                            }
                        }
                        if(event!=null){request.put("user_action_event",event);event=null;}
                        last=new JSONObject(http.post(AgentTurnContract.PATH,privacy.filter(request).toString()).body);
                        JSONObject guide=last.getJSONObject("guide_command"),metadata=last.getJSONObject("metadata");
                        JSONObject outcome=metadata.optJSONObject("task_outcome");
                        JSONObject technical=outcome==null?metadata.optJSONObject("technical_outcome"):outcome.optJSONObject("technical");
                        JSONObject selection=metadata.optJSONObject("debug");selection=selection==null?null:selection.optJSONObject("ui_selection");
                        item.getJSONArray("turns").put(new JSONObject().put("state",last.optString("state_id")).put("status",last.optString("task_status"))
                            .put("action",guide.optString("action_type")).put("selection",selection).put("technical",technical));save();
                        if(technical!=null && "CHANGED".equals(technical.optString("status"))) {
                            float after=value(setting);assertTrue("Persisted system setting must move in the requested direction",direction.equals("increase")?after>before:after<before);
                            assertTrue("The test must have executed a real slider operation",changed);
                            assertNotNull("A verified change must reach the result question",metadata.optJSONObject("result_prompt"));
                            item.put("before",before).put("after",after).put("passed",true).put("physical_actions",actions);break;
                        }
                        assertFalse("No unverified completion", "COMPLETED".equals(last.optString("task_status")));
                        JSONObject recovery=guide.optJSONObject("render_policy");recovery=recovery==null?null:recovery.optJSONObject("recovery");
                        if(recovery!=null && "user".equals(recovery.optString("waiting_for"))) {
                            assertTrue("Only one explicit test retry per task",retries++<1);
                            event=AgentActionEvents.of(AgentActionEvents.MANUAL_OVERRIDE,null,null,null);
                        } else if(!approved && !changed && selection!=null && "no_candidates".equals(selection.optString("status")) && scrolls<4) {
                            boolean moved=scrollSettings(scrolls++>=2);item.put("native_search_scrolls",scrolls);
                            if(moved)event=AgentActionEvents.of(AgentActionEvents.SCROLL_COMPLETED,null,null,null);
                        } else if("tap".equals(guide.optString("action_type"))) {
                            ScreenSnapshot fresh=ClickAccessibilityService.freshSnapshot();AccessibilityNodeSnapshot n=target(fresh,guide);assertNotNull(n);
                            long at=System.currentTimeMillis();
                            assertTrue("Execute only the exact grounded native menu",ClickAccessibilityService.performGuidedTap(fresh,n.nodeId,n.bounds));actions++;
                            event=AgentActionEvents.of(AgentActionEvents.TAP_COMPLETED,guide.getString("guide_command_id"),n.nodeId,n.bounds,at);
                        } else if("confirmation".equals(guide.optString("command_type"))) {
                            repeat++;assertEquals(before,value(setting),.001f);
                        } else if("drag".equals(guide.optString("action_type"))) {
                            assertTrue("A live server confirmation must precede adjustment",approved);assertFalse("Never repeat the physical adjustment",changed);
                            ScreenSnapshot fresh=ClickAccessibilityService.freshSnapshot();AccessibilityNodeSnapshot n=target(fresh,guide);
                            assertTrue(SettingsSliderTarget.matches(fresh,n,setting));long at=System.currentTimeMillis();
                            assertTrue("Real native slider operation",ClickAccessibilityService.performGuidedSlider(fresh,n.nodeId,n.bounds,direction.equals("increase")?1:-1,setting));
                            actions++;changed=true;event=AgentActionEvents.of(AgentActionEvents.DRAG_COMPLETED,guide.getString("guide_command_id"),n.nodeId,n.bounds,at);
                        }
                        if(event!=null)event.put("episode_id",last.getString("episode_id")).put("session_id",session);
                    }
                    assertTrue("Task must reach verified device change within its bounded request budget",item.optBoolean("passed"));
                    for(Map.Entry<Integer,Integer> e:otherStreams.entrySet())assertEquals("Unrelated audio channel must stay unchanged",e.getValue().intValue(),audio.getStreamVolume(e.getKey()));
                } finally {
                    if(last!=null)try {
                        JSONObject state=new JSONObject(http.get("/api/v1/agent/sessions/"+session).body);
                        http.post(AgentTurnContract.PATH,new JSONObject().put("contract_version",contract.contractVersion).put("request_id",UUID.randomUUID().toString())
                            .put("session_id",session).put("expected_version",state.getInt("version")).put("episode_id",state.getString("episode_id")).put("command_type","cancel_task").toString());
                    }catch(Exception ignored){item.put("cancel_failed",true);}
                    restoreValues();save();
                }
            }
            report.put("passed",true);
        } catch(Throwable failure) { report.put("error_type",failure.getClass().getSimpleName());throw failure; }
        finally {
            restoreValues();touch.setEnabled(originalTouch);
            automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
            Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,originalServices==null?"":originalServices);
            Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,originalEnabled);automation.dropShellPermissionIdentity();
            report.put("restored_font",value("font_size")).put("restored_brightness",value("brightness")).put("restored_media_volume",value("volume"));save();
        }
    }
}
