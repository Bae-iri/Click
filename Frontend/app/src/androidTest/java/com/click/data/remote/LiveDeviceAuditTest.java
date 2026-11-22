package com.click.data.remote;

import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.*;
import com.click.service.accessibility.ClickAccessibilityService;
import com.click.service.overlay.NativeSettingsSearchPlanner;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in production-server audit. Real Settings navigation; no value-control taps. */
public class LiveDeviceAuditTest {
    private final android.app.Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private final android.content.Context app=inst.getTargetContext();
    private final android.os.Bundle args=InstrumentationRegistry.getArguments();
    private UiAutomation ui;
    private AuthenticatedHttpClient http;
    private AgentServerContract contract;
    private SettingsScreenPrivacy privacy;
    private final JSONArray rows=new JSONArray();
    private JSONObject manifest;
    private final Map<String,String> queueIds=new HashMap<>();
    private String createSession(String text) throws Exception {
        String id=UUID.randomUUID().toString();
        http.post("/api/v1/voice-requests/enqueue",new JSONObject().put("id",id).put("raw_goal",text).put("priority","NORMAL").toString());
        JSONObject response=new JSONObject(http.post("/api/v1/voice-requests/next/claim","{}").body);
        JSONObject running=response.optJSONObject("request");if(running==null)running=response.getJSONObject("running");
        assertEquals("ISOLATED_QUEUE_NOT_EMPTY",id,running.getString("id"));
        String session=running.getString("agent_session_id");queueIds.put(session,id);return session;
    }
    private JSONObject load(String asset) throws Exception {
        try(var in=inst.getContext().getAssets().open(asset)){return new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
    }
    private void initialize() throws Exception {
        assertTrue(app.getPackageName().endsWith(".deviceaudit"));
        // Reuse exactly the isolated account; provision only when explicitly authorized by this audit flag.
        new DeviceAuditSetupTest().prepareIsolatedAuditAccount();
        http=new AuthenticatedHttpClient(app);
        contract=AgentServerContract.fromOpenApi(new JSONObject(AuthGateway.get("/openapi.json",null).body));
        try(var v=app.getAssets().open("vision_vocabulary.json");var p=app.getAssets().open("screen_privacy_policy.json")){privacy=SettingsScreenPrivacy.load(v,p);}
        manifest=load("device-audit-navigation.json");
    }
    private void save(String name) throws Exception {
        JSONObject report=new JSONObject().put("schema_version","click.device-audit.v1").put("model",android.os.Build.MODEL)
            .put("android",android.os.Build.VERSION.RELEASE).put("source","physical_device_production_server")
            .put("human_study",false).put("scripted_user_actions",true).put("cases",rows);
        Files.write(new File(app.getCacheDir(),name+".json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    private JSONObject send(AgentTurnContract.Request request,String setting) throws Exception {
        JSONObject wire=new JSONObject(contract.encode(request));
        if(request.accessibilityTree!=null)wire=privacy.filter(wire,setting);
        return new JSONObject(http.post(AgentTurnContract.PATH,wire.toString()).body);
    }
    private void cancel(String session) {
        if(session==null)return;
        try{JSONObject state=new JSONObject(http.get("/api/v1/agent/sessions/"+session).body);
            send(AgentTurnContract.Request.control(session,state.getInt("version"),state.optString("episode_id",null),"cancel_task",null),null);
        }catch(Exception ignored){}
        String queue=queueIds.remove(session);if(queue!=null)try{
            http.post("/api/v1/voice-requests/"+queue+"/cancel","{\"reason\":\"USER_ABORTED\"}");
        }catch(Exception ignored){}
    }
    private void shell(String command) throws Exception {
        try(var fd=ui.executeShellCommand(command);var in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){in.readAllBytes();}
    }
    private ScreenSnapshot screen() throws Exception {
        long end=SystemClock.elapsedRealtime()+8000;
        while(SystemClock.elapsedRealtime()<end){var s=ClickAccessibilityService.freshSnapshot();
            if(s!=null&&"com.android.settings".equals(s.packageName)&&!s.nodes.isEmpty())return s;SystemClock.sleep(120);}
        throw new AssertionError("NATIVE_SETTINGS_UNAVAILABLE");
    }
    private boolean oracle(JSONArray titles) {
        // Independent OS accessibility traversal: only a non-actionable toolbar/header counts.
        for(var window:ui.getWindows()){
            var root=window.getRoot();if(root==null)continue;
            try{if(("com.android.settings".contentEquals(root.getPackageName())||"com.samsung.accessibility".contentEquals(root.getPackageName()))&&heading(root,titles))return true;}
            finally{root.recycle();}
        }return false;
    }
    private boolean heading(android.view.accessibility.AccessibilityNodeInfo node,JSONArray titles) {
        if(node.isVisibleToUser()&&node.getText()!=null){
            String value=node.getText().toString();boolean wanted=false;
            for(int j=0;j<titles.length();j++)wanted|=value.equals(titles.optString(j));
            if(wanted){android.graphics.Rect r=new android.graphics.Rect();node.getBoundsInScreen(r);
                var parent=node.getParent();boolean actionable=node.isClickable()||node.isCheckable();
                while(parent!=null){
                    if(parent.isScrollable()){parent.recycle();break;}
                    actionable|=parent.isClickable()||parent.isCheckable();
                    var next=parent.getParent();parent.recycle();parent=next;
                }
                if(!actionable&&r.top<app.getResources().getDisplayMetrics().heightPixels*.42)return true;
            }
        }
        for(int i=0;i<node.getChildCount();i++){var child=node.getChild(i);if(child!=null)try{if(heading(child,titles))return true;}finally{child.recycle();}}
        return false;
    }
    @Test public void navigationMatrix() throws Exception {
        assumeTrue("true".equals(args.getString("deviceAuditNavigation")));initialize();
        ui=inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        var info=ui.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            |android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(info);
        assertFalse("Unlock device",app.getSystemService(android.app.KeyguardManager.class).isDeviceLocked());
        String original=Settings.Secure.getString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        int originalEnabled=Settings.Secure.getInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,0);
        boolean touch=new com.click.data.local.TouchAutomationSettings(app).isEnabled();
        ui.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
        try{
            String own=new ComponentName(app,ClickAccessibilityService.class).flattenToString();
            var services=new ArrayList<String>();if(original!=null&&!original.isBlank())services.addAll(Arrays.asList(original.split(":")));
            services.remove(own);Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",services));
            SystemClock.sleep(300);services.add(own);Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",services));
            Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,1);
            new com.click.data.local.TouchAutomationSettings(app).setEnabled(true);
            JSONArray tasks=manifest.getJSONArray("tasks");int repeats=Integer.parseInt(args.getString("auditRepeats","3"));
            for(int rep=0;rep<repeats;rep++)for(int i=0;i<tasks.length();i++){
                JSONObject task=tasks.getJSONObject(i);String key=task.getString("key"),goalType="settings.navigation."+key;
                if(args.getString("auditTask")!=null&&!key.equals(args.getString("auditTask")))continue;
                JSONObject row=new JSONObject().put("task",key).put("repeat",rep).put("goal_type",goalType)
                    .put("passed",false).put("turns",new JSONArray()).put("input",task.getJSONArray("queries").getString(rep%3));
                rows.put(row);save("device-audit-navigation");String session=null;JSONObject last=null;
                try{
                    shell("am start -W -a android.settings.SETTINGS -f 0x10008000");SystemClock.sleep(650);screen();
                    if(rep%3==1)for(int j=0;j<4;j++){var s=screen();ClickAccessibilityService.performSettingsScroll(true,s.packageName,s.activeWindowId);SystemClock.sleep(200);}
                    long started=SystemClock.elapsedRealtime();
                    session=createSession(row.getString("input"));
                    var resolve=AgentTurnContract.Request.resolve(session,row.getString("input"),null);resolve.preparationVersion="goal-inputs.v1";
                    JSONObject resolution=send(resolve,null);row.put("resolution_ms",SystemClock.elapsedRealtime()-started);
                    JSONObject actual=resolution.optJSONObject("goal");row.put("resolved_goal",actual==null?JSONObject.NULL:actual.optString("goal_type"));
                    assertNotNull("GOAL_NOT_RESOLVED",actual);assertEquals("WRONG_GOAL",goalType,actual.getString("goal_type"));
                    JSONObject preparation=resolution.optJSONObject("preparation");JSONObject event=null;
                    var search=new NativeSettingsSearchPlanner();long deadline=started+75000;
                    for(int turn=0;turn<20&&SystemClock.elapsedRealtime()<deadline;turn++){
                        ScreenSnapshot s=screen();AgentTurnContract.Request request;
                        if(last==null){request=new AgentTurnContract.Request(session,row.getString("input"),s,null);request.acceptedGoal=actual;
                            if(preparation!=null){request.preparationVersion="goal-inputs.v1";request.goalResolutionId=preparation.getString("id");}}
                        else request=AgentTurnContract.Request.control(session,last.getInt("session_version"),last.getString("episode_id"),"observe",s);
                        request.userActionEvent=event;event=null;long sent=SystemClock.elapsedRealtime();last=send(request,"navigation_"+key);
                        JSONObject guide=last.getJSONObject("guide_command");String action=guide.optString("action_type");
                        JSONObject md=last.getJSONObject("metadata"),technical=md.optJSONObject("technical_outcome");
                        if(md.optJSONObject("task_outcome")!=null)technical=md.getJSONObject("task_outcome").optJSONObject("technical");
                        boolean nativeGoal=oracle(task.getJSONArray("titles"));
                        row.getJSONArray("turns").put(new JSONObject().put("latency_ms",SystemClock.elapsedRealtime()-sent)
                            .put("action",action).put("state",last.optString("state_id")).put("status",last.optString("task_status"))
                            .put("native_goal",nativeGoal).put("technical",technical));save("device-audit-navigation");
                        boolean declared="COMPLETED".equals(last.optString("task_status"))||technical!=null&&"OPENED".equals(technical.optString("status"));
                        if(declared&&!nativeGoal){row.put("false_completion",true);throw new AssertionError("FALSE_COMPLETION");}
                        if(nativeGoal&&!row.has("goal_reached_ms"))row.put("goal_reached_ms",SystemClock.elapsedRealtime()-started);
                        if(declared&&nativeGoal){row.put("passed",true).put("verified_completion_ms",SystemClock.elapsedRealtime()-started);break;}
                        if("tap".equals(action)){
                            if(!row.has("first_action_ms"))row.put("first_action_ms",SystemClock.elapsedRealtime()-started);
                            String id=guide.getString("target_node_id");JSONArray b=guide.getJSONArray("target_bounds");int[] bounds={b.getInt(0),b.getInt(1),b.getInt(2),b.getInt(3)};
                            var safe=com.click.service.accessibility.TouchAutomationPolicy.clickableTarget(s,id,bounds,goalType,last.optString("state_id"));
                            assertNotNull("UNSAFE_OR_STALE_TARGET",safe);
                            assertTrue("NATIVE_TAP_REJECTED",ClickAccessibilityService.performGuidedNavigationTap(s,id,bounds,goalType,last.optString("state_id")));
                            event=AgentActionEvents.of(AgentActionEvents.TAP_COMPLETED,guide.getString("guide_command_id"),id,bounds,System.currentTimeMillis());
                        }else if(!nativeGoal){
                            var plan=search.next(session+":"+last.optString("state_id")+":"+s.packageName+":"+s.activeWindowId,s);
                            if(plan.move)ClickAccessibilityService.performSettingsScroll(plan.forward,s.packageName,s.activeWindowId);
                            if(plan.finished)break;
                            event=AgentActionEvents.of(AgentActionEvents.MANUAL_OVERRIDE,null,null,null);
                        }
                        if(event!=null)event.put("episode_id",last.getString("episode_id")).put("session_id",session);
                        SystemClock.sleep(350);
                    }
                }catch(Throwable error){row.put("failure",error.getClass().getSimpleName());
                    if(error instanceof ApiException)row.put("http_status",((ApiException)error).status).put("conflict_code",((ApiException)error).conflictCode);
                    if(error instanceof AssertionError)row.put("failure_code",error.getMessage());
                }finally{cancel(session);save("device-audit-navigation");}
                System.out.println("AUDIT_CASE "+row);
            }
        }finally{
            new com.click.data.local.TouchAutomationSettings(app).setEnabled(touch);
            Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,original==null?"":original);
            Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,originalEnabled);ui.dropShellPermissionIdentity();
            save("device-audit-navigation");
        }
    }
    @Test public void resolutionMatrix() throws Exception {
        assumeTrue("true".equals(args.getString("deviceAuditResolution")));initialize();
        JSONObject catalog;
        try(var in=app.getAssets().open("mvp_goals.json")){catalog=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        JSONArray goals=catalog.getJSONArray("goals");
        for(int repeat=0;repeat<3;repeat++)for(int i=0;i<goals.length();i++){
            JSONObject goal=goals.getJSONObject(i);if("catalog_only".equals(goal.optString("execution_status")))continue;
            String expected=goal.getString("goal_type"),name=goal.getString("display_name");
            JSONArray examples=goal.optJSONArray("utterances");String text=repeat==0&&examples!=null&&examples.length()>0?examples.getString(0)
                :repeat==1?name+" 도와줘":name+" 하고 싶어";
            String session=null;JSONObject row=new JSONObject().put("task",expected).put("input",text)
                .put("repeat",repeat).put("passed",false).put("evidence","catalogue_derived_phrasing_not_heldout");rows.put(row);
            long started=SystemClock.elapsedRealtime();
            try{
                session=createSession(text);
                var request=AgentTurnContract.Request.resolve(session,text,null);request.preparationVersion="goal-inputs.v1";
                JSONObject response=send(request,null);row.put("latency_ms",SystemClock.elapsedRealtime()-started)
                    .put("resolution_status",response.optString("resolution_status"));
                JSONObject actual=response.optJSONObject("goal"),prep=response.optJSONObject("preparation");
                String selected=actual==null?null:actual.optString("goal_type");
                if(selected==null&&prep!=null&&prep.optJSONObject("public_guide_goal")!=null)
                    selected=prep.getJSONObject("public_guide_goal").optString("goal_type");
                row.put("selected_goal",selected==null?JSONObject.NULL:selected).put("exact_goal",expected.equals(selected));
                String family=prep==null?null:prep.optString("family");row.put("family",family);
                row.put("passed",expected.equals(selected));
                if(prep!=null&&prep.optJSONObject("question")!=null)
                    row.put("clarification_field",prep.getJSONObject("question").optString("field"));
            }catch(Exception error){row.put("latency_ms",SystemClock.elapsedRealtime()-started).put("failure",error.getClass().getSimpleName());
                if(error instanceof ApiException)row.put("http_status",((ApiException)error).status);}
            finally{cancel(session);save("device-audit-resolution");}
            System.out.println("AUDIT_RESOLUTION "+row);
        }
    }
}
