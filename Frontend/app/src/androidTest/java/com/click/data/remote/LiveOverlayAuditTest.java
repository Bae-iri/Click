package com.click.data.remote;

import android.app.UiAutomation;
import android.content.*;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.core.app.ActivityScenario;
import com.click.data.local.*;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.accessibility.ClickAccessibilityService;
import com.click.service.overlay.ClickOverlayService;
import com.click.testutil.ServiceStateAccess;
import org.json.*;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Measures the real overlay, queue, providers, native executor and independent device outcome. */
public class LiveOverlayAuditTest {
    private final android.app.Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private final android.content.Context app=inst.getTargetContext();
    private final android.os.Bundle args=InstrumentationRegistry.getArguments();
    private UiAutomation ui;
    private final JSONArray rows=new JSONArray();
    private ActivityScenario<com.click.HomeActivity> homeScenario;
    private long submitHome(String goal){
        homeScenario=ActivityScenario.launch(new Intent(app,com.click.HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long[] started={0};
        homeScenario.onActivity(activity->{
            ((android.widget.EditText)activity.findViewById(com.click.R.id.home_request_input)).setText(goal);
            started[0]=SystemClock.elapsedRealtime();
            assertTrue("HOME_SUBMIT_UNAVAILABLE",activity.findViewById(com.click.R.id.home_request_send).performClick());
        });
        return started[0];
    }
    private Object get(Object target,String name){return ServiceStateAccess.get(target,name);}
    private void call(Object target,String name,Class<?>[] types,Object... values){try{
        var method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(target,values);
    }catch(Exception e){throw new AssertionError(e);}}
    private boolean waitingForHelp(ClickOverlayService target){try{
        var method=ClickOverlayService.class.getDeclaredMethod("waitingForTargetHelp");method.setAccessible(true);
        return (boolean)method.invoke(target);
    }catch(Exception e){throw new AssertionError(e);}}
    private ClickOverlayService service()throws Exception{
        var f=ClickOverlayService.class.getDeclaredField("runningService");f.setAccessible(true);
        return (ClickOverlayService)((java.lang.ref.WeakReference<?>)f.get(null)).get();
    }
    private void shell(String command)throws Exception{
        try(var fd=ui.executeShellCommand(command);var in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){in.readAllBytes();}
    }
    private void save()throws Exception{
        Files.write(new File(app.getCacheDir(),"device-audit-overlay.json").toPath(),new JSONObject()
            .put("schema_version","click.overlay-audit.v1").put("source","actual_visible_overlay_to_independent_os_outcome")
            .put("model",android.os.Build.MODEL).put("android",android.os.Build.VERSION.RELEASE)
            .put("scripted_user_confirmations",true).put("human_study",false).put("cases",rows).toString(2).getBytes(StandardCharsets.UTF_8));
    }
    @Test public void fullProductMatrix()throws Exception{
        assumeTrue("true".equals(args.getString("deviceAuditOverlay")));
        if (!"true".equals(args.getString("auditAutoFromStart"))) {
            assertTrue("Build the audit APK with -PCLICK_CANDIDATE_DIAGNOSTICS=true to record target frames",
                    com.click.BuildConfig.CANDIDATE_DIAGNOSTICS);
        }
        assertTrue(app.getPackageName().endsWith(".deviceaudit"));new DeviceAuditSetupTest().prepareIsolatedAuditAccount();
        ui=inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);var info=ui.getServiceInfo();
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(info);
        assertFalse(app.getSystemService(android.app.KeyguardManager.class).isDeviceLocked());
        String originalServices=Settings.Secure.getString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        int enabled=Settings.Secure.getInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,0);
        var touch=new TouchAutomationSettings(app);boolean previousTouch=touch.isEnabled();
        float font=Settings.System.getFloat(app.getContentResolver(),Settings.System.FONT_SCALE);
        int brightness=Settings.System.getInt(app.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS),brightnessMode=Settings.System.getInt(app.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS_MODE);
        var audio=app.getSystemService(android.media.AudioManager.class);int volume=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        ui.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
        JSONObject manifest;try(var in=inst.getContext().getAssets().open("device-audit-navigation.json")){
            manifest=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        JSONArray tasks=manifest.getJSONArray("tasks");
        for(String setting:List.of("font_size","brightness","volume"))for(String direction:List.of("increase","decrease")){
            String name=setting.equals("font_size")?"휴대폰 전체 글자 크기":setting.equals("brightness")?"화면 밝기":"미디어 음량";
            String verb=direction.equals("increase")?" 키워줘":" 줄여줘";
            tasks.put(new JSONObject().put("key",setting+"."+direction).put("setting",setting).put("direction",direction)
                .put("first_labels",new JSONArray().put(setting.equals("volume")?"소리 및 진동":"디스플레이"))
                .put("queries",new JSONArray().put(name+verb).put(name+verb).put(name+verb)));
        }
        var independent=new LiveDeviceAuditTest();var oracleUi=LiveDeviceAuditTest.class.getDeclaredField("ui");oracleUi.setAccessible(true);oracleUi.set(independent,ui);
        var oracle=LiveDeviceAuditTest.class.getDeclaredMethod("oracle",JSONArray.class);oracle.setAccessible(true);
        try{
            String own=new ComponentName(app,ClickAccessibilityService.class).flattenToString();var services=new ArrayList<String>();
            if(originalServices!=null&&!originalServices.isBlank())services.addAll(Arrays.asList(originalServices.split(":")));services.remove(own);
            Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",services));SystemClock.sleep(250);
            services.add(own);Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",services));
            Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,1);
            new GuidancePreferences(app).markHomeTutorialSeen();touch.setEnabled(true);
            boolean autoFromStart = "true".equals(args.getString("auditAutoFromStart"));
            int repeats=Integer.parseInt(args.getString("auditRepeats","4"));
            for(int repeat=0;repeat<repeats;repeat++)for(int index=0;index<tasks.length();index++){
                int trial=repeat*tasks.length()+index;
                if(trial<Integer.parseInt(args.getString("auditStartTrial","0")))continue;
                JSONObject task=tasks.getJSONObject(index);String key=task.getString("key");
                if(args.getString("auditTask")!=null&&!key.equals(args.getString("auditTask")))continue;
                if("changes".equals(args.getString("auditCohort"))&&!task.has("setting"))continue;
                if("navigation".equals(args.getString("auditCohort"))&&task.has("setting"))continue;
                int queryIndex=Integer.parseInt(args.getString("auditQueryIndex",String.valueOf(repeat%3)));
                String goal=task.getJSONArray("queries").getString(queryIndex);
                boolean judgmentOnly="true".equals(args.getString("auditJudgmentReady"));
                JSONObject row=new JSONObject().put("task",key).put("repeat",repeat).put("input",goal).put("passed",false)
                    .put("trial",trial)
                    .put("first_guidance_mode",autoFromStart ? "auto_from_start" : "manual_until_first_draw_then_scripted_auto")
                    .put("cohort",task.has("setting")?"setting_change":"settings_navigation");rows.put(row);save();
                final int trialVolume=key.equals("volume.decrease")&&volume==0?1:volume;
                row.put("original_media_volume",volume).put("trial_media_volume",trialVolume);
                ClickOverlayService flow=null;
                try{
                    shell("settings put system font_scale "+font);shell("settings put system screen_brightness "+brightness);
                    shell("settings put system screen_brightness_mode "+(key.startsWith("brightness")?0:brightnessMode));
                    shell("cmd media_session volume --stream 3 --set "+trialVolume);
                    if(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)!=trialVolume)throw new AssertionError("INITIAL_VOLUME_NOT_APPLIED");
                    // Only the isolated audit account's own unfinished diagnostic requests are retired.
                    var http=new AuthenticatedHttpClient(app);
                    http.post("/api/v1/voice-requests/interrupt-and-clear",new JSONObject().put("id",UUID.randomUUID().toString()).put("raw_goal","중단").put("clear_queued",true).toString());
                    shell("am start -W -n "+app.getPackageName()+"/com.click.HomeActivity -f 0x10008000");SystemClock.sleep(400);
                    if(judgmentOnly)shell("am force-stop com.android.settings");
                    shell("am start -W -a android.settings.SETTINGS -f 0x10008000");SystemClock.sleep(700);
                    if(judgmentOnly){
                        if(JudgmentEvidence.settingsHeading(ui,new JSONArray().put("설정").put("Settings"),app.getResources().getDisplayMetrics().heightPixels)==null)
                            throw new AssertionError("INITIAL_SETTINGS_HOME_UNVERIFIED");
                        row.put("precondition","settings_process_restarted_home_heading_verified");SystemClock.sleep(400);
                    }
                    if(Boolean.parseBoolean(args.getString("auditInitialScroll",String.valueOf(repeat%3==1))))for(int j=0;j<4;j++){
                        var s=ClickAccessibilityService.freshSnapshot();if(s!=null)ClickAccessibilityService.performSettingsScroll(true,s.packageName,s.activeWindowId);SystemClock.sleep(220);}
                    touch.setEnabled(autoFromStart);
                    long started;
                    if(judgmentOnly){started=submitHome(goal);row.put("entry","CLICK_home_text_submission");}
                    else{started=SystemClock.elapsedRealtime();app.startService(new Intent(app,ClickOverlayService.class).putExtra(ClickOverlayService.EXTRA_INITIAL_GOAL,goal));}
                    long deadline=started+Long.parseLong(args.getString("auditBudgetMs","60000"));boolean observedGoal=false;long helpSince=-1;
                    long readySince=-1;String readyKey=null;
                    if(judgmentOnly)row.put("measurement_endpoint","visible_judgment_ready").put("screen_review","pending")
                        .put("judgment_ready_success",JSONObject.NULL).put("completion_signal_used_for_success",false);
                    while(SystemClock.elapsedRealtime()<deadline){
                        if(flow==null)flow=service();if(flow==null){SystemClock.sleep(50);continue;}
                        if(judgmentOnly){
                            if(task.has("setting")){
                                String setting=task.getString("setting");float baseline=setting.equals("font_size")?font:setting.equals("brightness")?brightness:trialVolume;
                                float value=setting.equals("font_size")?Settings.System.getFloat(app.getContentResolver(),Settings.System.FONT_SCALE)
                                    :setting.equals("brightness")?Settings.System.getInt(app.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS):audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                                observedGoal=task.getString("direction").equals("increase")?value>baseline:value<baseline;
                                row.put("baseline",baseline).put("observed_value",value);
                            }else observedGoal=JudgmentEvidence.settingsHeading(ui,task.getJSONArray("titles"),app.getResources().getDisplayMetrics().heightPixels)!=null;
                            if(observedGoal&&!row.has("goal_reached_ms"))row.put("goal_reached_ms",SystemClock.elapsedRealtime()-started);
                            var proof=observedGoal?(task.has("setting")?JudgmentEvidence.settingControl(ClickAccessibilityService.freshSnapshot(),task.getString("setting"))
                                :JudgmentEvidence.settingsHeading(ui,task.getJSONArray("titles"),app.getResources().getDisplayMetrics().heightPixels)):null;
                            boolean[] covered={false};ClickOverlayService currentFlow=flow;
                            inst.runOnMainSync(()->{
                                var panel=(android.view.View)get(currentFlow,"taskFlowView");
                                boolean panelCovers=JudgmentEvidence.covered(proof,panel);
                                var bubble=(android.view.View)get(currentFlow,"overlayView");
                                covered[0]=panelCovers||JudgmentEvidence.covered(proof,bubble);
                                if(proof!=null&&panelCovers&&panel!=null){var collapse=panel.findViewById(com.click.R.id.task_flow_collapse);
                                    if(collapse!=null&&collapse.isShown()){collapse.performClick();try{row.put("panel_collapse_actions",row.optInt("panel_collapse_actions")+1);}catch(Exception e){throw new AssertionError(e);}}}
                            });
                            if(proof!=null&&!covered[0]){
                                touch.setEnabled(false);
                                if(!proof.key().equals(readyKey)){
                                    readyKey=proof.key();readySince=SystemClock.elapsedRealtime();
                                    row.put("visible_evidence_first_ms",readySince-started).put("evidence_first",JudgmentEvidence.capture(ui,app.getCacheDir(),proof))
                                        .put("evidence_bounds",new JSONArray(new int[]{proof.bounds().left,proof.bounds().top,proof.bounds().right,proof.bounds().bottom}));
                                }
                                if(SystemClock.elapsedRealtime()-readySince>=1000){
                                    row.put("evidence_last",JudgmentEvidence.capture(ui,app.getCacheDir(),proof))
                                        .put("visible_stable_ms",SystemClock.elapsedRealtime()-readySince)
                                        .put("time_to_judgment_ready_ms",SystemClock.elapsedRealtime()-started)
                                        .put("judgment_ready_candidate",true).put("goal_evidence_occluded",false);
                                    break;
                                }
                                SystemClock.sleep(60);continue;
                            }
                            readyKey=null;readySince=-1;
                        }
                        final ClickOverlayService current=flow;final JSONObject[] prompt={null};final Throwable[] failure={null};final boolean[] help={false};
                        inst.runOnMainSync(()->{try{
                            boolean busy=(boolean)get(current,"turnInFlight");
                            if(get(current,"acceptedGoal")==null&&!busy)call(current,"confirmRequirementReview",new Class<?>[]{});
                            JSONObject accepted=(JSONObject)get(current,"acceptedGoal");
                            if(accepted!=null){
                                String expected="settings."+(task.has("setting")?key:"navigation."+key);
                                row.put("accepted_goal_type",accepted.optString("goal_type"));
                                assertEquals("WRONG_ACCEPTED_GOAL",expected,accepted.optString("goal_type"));
                            }
                            var guide=(com.click.ui.overlay.GuideOverlayView)get(current,"guideOverlayView");
                            int[] target=(int[])get(current,"lastTargetBounds");
                            if(!row.has("first_visible_guidance_ms")&&get(current,"lastTargetNodeId")!=null
                                    &&guide!=null&&guide.targetMatches(target)&&guide.targetFrameElapsedMs()>=started){
                                row.put("first_visible_guidance_ms",guide.targetFrameElapsedMs()-started);
                                ScreenSnapshot submitted=(ScreenSnapshot)get(current,"lastSubmittedScreen");
                                String id=(String)get(current,"lastTargetNodeId");boolean correct=false;
                                JSONArray labels=task.getJSONArray("first_labels");
                                if(submitted!=null)for(var node:submitted.nodes)if(node.visibleToUser&&(node.nodeId.equals(id)||node.nodeId.startsWith(id+"_")))
                                    for(int j=0;j<labels.length();j++)for(String label:new String[]{node.text,node.contentDescription})
                                        if(label!=null&&label.trim().equalsIgnoreCase(labels.getString(j)))correct=true;
                                row.put("first_action_correct",correct);
                            }
                            if(!row.has("first_actionable_ready_ms")&&get(current,"lastTargetNodeId")!=null&&get(current,"lastTargetBounds")!=null)
                                row.put("first_actionable_ready_ms",SystemClock.elapsedRealtime()-started);
                            if(row.has("first_visible_guidance_ms")&&!touch.isEnabled()&&!busy){
                                touch.setEnabled(true);call(current,"sendAgentTurn",new Class<?>[]{String.class,boolean.class},null,true);
                            }
                            if(get(current,"agentConfirmationId")!=null&&!busy)
                                call(current,"sendAgentTurn",new Class<?>[]{String.class,boolean.class,ScreenSnapshot.class},"accepted",true,get(current,"lastSubmittedScreen"));
                            prompt[0]=(JSONObject)get(current,"resultPrompt");
                            help[0]=!((boolean)get(current,"turnInFlight"))&&(waitingForHelp(current)||(boolean)get(current,"waitingForExplicitRetry"));
                            var panel=(com.click.ui.overlay.TaskFlowOverlayView)get(current,"taskFlowView");
                            if(accepted!=null&&get(current,"agentConfirmationId")==null&&panel!=null&&!panel.hasProgress()){
                                var actions=(android.view.ViewGroup)get(panel,"recoveryActions");
                                help[0]|=!((boolean)get(current,"turnInFlight"))&&actions.isShown()&&actions.getChildCount()>0;
                            }
                        }catch(Throwable e){failure[0]=e;}});
                        if(failure[0]!=null)throw new AssertionError(failure[0]);
                        if(task.has("setting")){
                            String setting=task.getString("setting");float baseline=setting.equals("font_size")?font:setting.equals("brightness")?brightness:trialVolume;
                            float value=setting.equals("font_size")?Settings.System.getFloat(app.getContentResolver(),Settings.System.FONT_SCALE)
                                :setting.equals("brightness")?Settings.System.getInt(app.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS):audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                            observedGoal=task.getString("direction").equals("increase")?value>baseline:value<baseline;
                            row.put("baseline",baseline).put("observed_value",value);
                        }else observedGoal=judgmentOnly?JudgmentEvidence.settingsHeading(ui,task.getJSONArray("titles"),app.getResources().getDisplayMetrics().heightPixels)!=null
                            :(boolean)oracle.invoke(independent,task.getJSONArray("titles"));
                        if(observedGoal&&!row.has("goal_reached_ms"))row.put("goal_reached_ms",SystemClock.elapsedRealtime()-started);
                        if(!judgmentOnly&&prompt[0]!=null){
                            String status=prompt[0].optString("technical_status");row.put("technical_status",status).put("native_goal",observedGoal);
                            boolean verified=task.has("setting")?"CHANGED".equals(status):"OPENED".equals(status);
                            row.put("false_completion",verified&&!observedGoal).put("passed",verified&&observedGoal)
                                .put("result_prompt_ms",SystemClock.elapsedRealtime()-started);break;
                        }
                        if(help[0]){
                            if(helpSince<0)helpSince=SystemClock.elapsedRealtime();
                            if(SystemClock.elapsedRealtime()-helpSince>=2000){row.put("failure_code","EXPLICIT_HELP_REQUIRED");break;}
                        }else helpSince=-1;
                        SystemClock.sleep(60);
                    }
                    row.put("native_goal",observedGoal).put("elapsed_ms",SystemClock.elapsedRealtime()-started);
                    if(judgmentOnly){
                        JSONArray known=new JSONArray();
                        for(int t=0;t<tasks.length();t++){var titles=tasks.getJSONObject(t).optJSONArray("titles");
                            if(titles!=null)for(int j=0;j<titles.length();j++)known.put(titles.getString(j));}
                        row.put("final_public_frame",JudgmentEvidence.publicFrame(ClickAccessibilityService.freshSnapshot(),known));
                    }
                    if(!row.optBoolean("passed")&&!row.optBoolean("judgment_ready_candidate")&&!row.has("failure_code"))row.put("failure_code",row.has("result_prompt_ms")?"UNVERIFIED_RESULT":"TIMEOUT");
                }catch(Throwable error){row.put("failure",error.getClass().getSimpleName());
                    if(error.getMessage()!=null&&error.getMessage().matches("[A-Z_]+"))row.put("failure_code",error.getMessage());}
                finally{
                    if(flow!=null){ClickOverlayService current=flow;inst.runOnMainSync(current::onStopRequested);SystemClock.sleep(700);}
                    app.stopService(new Intent(app,ClickOverlayService.class));
                    if(homeScenario!=null){homeScenario.close();homeScenario=null;}
                    shell("settings put system font_scale "+font);shell("settings put system screen_brightness "+brightness);
                    shell("settings put system screen_brightness_mode "+brightnessMode);shell("cmd media_session volume --stream 3 --set "+volume);
                    save();
                }
                System.out.println("AUDIT_OVERLAY_CASE "+row);
            }
        }finally{
            touch.setEnabled(previousTouch);Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,originalServices==null?"":originalServices);
            Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,enabled);ui.dropShellPermissionIdentity();save();
        }
    }
}
