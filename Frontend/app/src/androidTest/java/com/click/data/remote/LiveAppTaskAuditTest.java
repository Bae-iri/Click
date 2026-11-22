package com.click.data.remote;

import android.app.UiAutomation;
import android.content.*;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.*;
import com.click.testutil.ServiceStateAccess;
import com.click.service.accessibility.*;
import com.click.service.overlay.*;
import org.json.*;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Real Qwen app guidance with scripted approvals and an independent native destination oracle. */
public class LiveAppTaskAuditTest {
    private final android.app.Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private final android.content.Context app=inst.getTargetContext();
    private UiAutomation ui;
    private final JSONArray rows=new JSONArray();
    private static final String CLOCK="com.sec.android.app.clockpackage",CHROME="com.android.chrome",YOUTUBE="com.google.android.youtube";
    private static final String[][] TASKS={
        {"clock_world",CLOCK,"시계에서 세계시각 탭으로 이동해줘","세계시각","selected"},
        {"clock_stopwatch",CLOCK,"시계에서 스톱워치 탭으로 이동해줘","스톱워치","selected"},
        {"clock_timer",CLOCK,"시계에서 타이머 탭으로 이동해줘","타이머","selected"},
        {"clock_alarm",CLOCK,"시계에서 알람 탭으로 이동해줘","알람","selected"},
        {"chrome_menu",CHROME,"Chrome 메뉴를 열어줘","새 시크릿 탭","visible"},
        {"chrome_settings",CHROME,"Chrome 설정 화면을 열어줘","검색엔진","visible"},
        {"chrome_new_tab",CHROME,"Chrome에서 새 탭을 열어줘","com.android.chrome:id/search_box_text","resource"},
        {"youtube_shorts",YOUTUBE,"YouTube에서 Shorts 탭으로 이동해줘","Shorts","selected"},
        {"youtube_subscriptions",YOUTUBE,"YouTube에서 구독 탭으로 이동해줘","구독","selected"},
        {"youtube_search",YOUTUBE,"YouTube 검색창을 열어줘","com.google.android.youtube:id/search_edit_text","resource"}
    };
    private void clickLabel(String label){
        for(var w:ui.getWindows()){var root=w.getRoot();if(root==null)continue;
            try{var found=root.findAccessibilityNodeInfosByText(label);
                for(var n:found){var t=n;while(t!=null&&!t.isClickable())t=t.getParent();
                    if(t!=null&&label.contentEquals(n.getText()==null?n.getContentDescription():n.getText())){t.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);return;}}
            }finally{root.recycle();}}
    }
    private String shell(String command)throws Exception{
        try(var fd=ui.executeShellCommand(command);var in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}
    }
    private void save() throws Exception {
        Files.write(new File(app.getCacheDir(),"device-audit-app-tasks.json").toPath(),new JSONObject()
            .put("schema_version","click.app-task-audit.v1").put("source","physical_device_production_app_task")
            .put("human_study",false).put("scripted_approvals",true).put("cases",rows).toString(2).getBytes(StandardCharsets.UTF_8));
    }
    private Object settingsOracle;private java.lang.reflect.Method settingsTitle;
    private final Map<String,JSONArray> titles=new HashMap<>();
    private boolean settings(String[] task){return "com.android.settings".equals(task[1]);}
    private boolean oracle(String[] task){
        if(settings(task))try{return (boolean)settingsTitle.invoke(settingsOracle,titles.get(task[0]));}catch(Exception e){throw new AssertionError(e);}
        for(var w:ui.getWindows()){var root=w.getRoot();if(root!=null)try{
            if(task[1].contentEquals(root.getPackageName())&&matches(root,task))return true;
        }finally{root.recycle();}}return false;
    }
    private boolean matches(android.view.accessibility.AccessibilityNodeInfo n,String[] task){
        if(n.isVisibleToUser()){
            String text=n.getText()==null?"":n.getText().toString(),desc=n.getContentDescription()==null?"":n.getContentDescription().toString();
            boolean label=text.equals(task[3])||desc.equals(task[3])||desc.startsWith(task[3]+",")||desc.startsWith(task[3]+" 탭");
            if("resource".equals(task[4])&&task[3].equals(n.getViewIdResourceName()))return true;
            if(label&&"visible".equals(task[4]))return true;
            if ("coupang_category".equals(task[0]) && n.isHeading() && !n.isClickable()
                    && ("카테고리".equals(text) || "전체 카테고리".equals(text))) return true;
            if(label&&"selected".equals(task[4])){
                boolean selected=n.isSelected();var p=n.getParent();try{if(p!=null)selected|=p.isSelected();}finally{if(p!=null)p.recycle();}
                if(selected)return true;
            }
        }
        for(int j=0;j<n.getChildCount();j++){var c=n.getChild(j);if(c!=null)try{if(matches(c,task))return true;}finally{c.recycle();}}return false;
    }
    private static ClickAccessibilityService service()throws Exception{
        var f=ClickAccessibilityService.class.getDeclaredField("connectedService");f.setAccessible(true);
        return (ClickAccessibilityService)((java.lang.ref.WeakReference<?>)f.get(null)).get();
    }
    private final class Driver implements AppTaskController.Listener {
        final String[] task;final JSONObject row;final Set<String> approvals=new HashSet<>();
        AppTaskController controller;AppTaskMemory memory;JSONObject latest;String fingerprint;long started;boolean closed;Throwable error;int screenChanges;
        Driver(String[] t,JSONObject r){task=t;row=r;}
        void start(AgentServerContract contract)throws Exception{
            started=SystemClock.elapsedRealtime();
            memory=new AppTaskMemory(task[2]);
            controller=new AppTaskController(new AppGuideGateway(app),memory,null,this,true,false,
                contract.appTaskCompletion,AppTaskMetaController.Arm.CURRENT,contract.appTaskGoalFirst);
            var enable=AppTaskController.class.getDeclaredMethod("enableOutcomes",boolean.class);enable.setAccessible(true);enable.invoke(controller,contract.appTaskOutcomes);
            // The product passes the app's visible name, never its package id.
            String label=Map.of(CLOCK,"시계",CHROME,"Chrome",YOUTUBE,"YouTube","com.android.settings","설정").getOrDefault(task[1],task[1]);
            controller.targetApp(task[1],label);controller.refresh();
        }
        void poll()throws Exception{
            var s=fresh();if(s!=null){String next=AppTaskObservation.capture(s).optString("fingerprint");
                if(!Objects.equals(next,fingerprint)){fingerprint=next;screenChanges++;controller.screenChanged(s);}}
            if(latest!=null&&"proposed".equals(latest.optString("status"))&&latest.optJSONObject("action")!=null){
                String id=latest.getJSONObject("action").optString("id");if(approvals.add(id))controller.execute(id,null);}
        }
        public ScreenSnapshot fresh(){return ClickAccessibilityService.freshSnapshot();}
        public boolean canObserve(){return !closed;}
        public void busy(boolean ignored){}
        public void failure(Throwable e){error=e;}
        public boolean inputMatches(String id,String hash){return ClickAccessibilityService.appTaskInputMatches(id,hash);}
        public void present(JSONObject response,ScreenSnapshot submitted){latest=response;
            try{JSONObject event=new JSONObject().put("at_ms",SystemClock.elapsedRealtime()-started).put("status",response.optString("status"))
                .put("verifying",response.optBoolean("verifying")).put("diagnostic",response.optJSONObject("diagnostic"));
                JSONObject action=response.optJSONObject("action");if(action!=null)event.put("tool",action.optString("tool"))
                    .put("expectation",action.optJSONObject("expectation"));
                event.put("result_assessment",response.optJSONObject("result_assessment"));
                // Public goal clauses and selectors, never authentication or input values.
                JSONObject stored=memory.stored();
                event.put("outcome_goal",stored.optJSONObject("outcome_goal"))
                    .put("outcome_bindings",stored.optJSONObject("outcome_bindings"))
                    .put("completion",stored.optJSONObject("completion"));
                // Screen facts only (no labels): why a state such as awaiting_screen was chosen.
                ScreenSnapshot now=fresh();
                if(now!=null){JSONObject seen=AppTaskObservation.capture(now);
                    event.put("screen",new JSONObject().put("package",String.valueOf(now.packageName)).put("window_kind",now.windowContext.windowKind())
                        .put("active_window",now.activeWindowId==null?JSONObject.NULL:now.activeWindowId).put("keyboard",now.windowContext.keyboardVisible())
                        .put("masking_ready",now.privacyMaskingReady).put("observation_status",seen.optString("status"))
                        .put("exported_nodes",seen.optJSONArray("nodes")==null?0:seen.optJSONArray("nodes").length()).put("screen_changes",screenChanges));
                    JSONArray selected=new JSONArray(),nodes=seen.optJSONArray("nodes");
                    for(int i=0;nodes!=null&&i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);
                        if(n.optBoolean("selected"))selected.put(new JSONObject().put("role",n.optString("role"))
                            .put("resource",n.optString("resource")).put("source",n.optString("source")));}
                    event.put("native_selected_controls",selected);}
                row.getJSONArray("events").put(event);
                if("proposed".equals(response.optString("status"))&&!row.has("first_action_ms"))row.put("first_action_ms",SystemClock.elapsedRealtime()-started);
            }catch(Exception failed){error=failed;}
        }
        public boolean execute(ScreenSnapshot baseline,JSONObject observation,JSONObject action,String input,String selectedPackage){
            String tool=action.optString("tool");JSONObject node=AppTaskObservation.find(observation,action.optString("target_id"));
            String label=node==null?"":node.optString("label");
            // Navigation only: the audit never submits text, chooses content, or starts an alarm/timer.
            Set<String> labels=Set.of("세계시각","스톱워치","타이머","알람","홈","Shorts","구독","검색","YouTube 검색",
                "Chrome 맞춤설정 및 제어","Chrome 설정","설정","새 탭","탭 전환");
            boolean allowed="back".equals(tool)||"open_app".equals(tool)&&task[1].equals(action.optString("package"))
                ||task[1].equals(observation.optString("package"))&&(
                    "tap".equals(tool)&&labels.contains(label)||("scroll_forward".equals(tool)||"scroll_backward".equals(tool))&&node!=null&&node.optBoolean("scrollable"));
            boolean settingsScreen=settings(task)&&Set.of("com.android.settings","com.android.settings.intelligence","com.samsung.accessibility").contains(observation.optString("package"));
            if(settingsScreen&&("scroll_forward".equals(tool)||"scroll_backward".equals(tool)))allowed=node!=null&&node.optBoolean("scrollable");
            // Settings search only: the controller's own query from the request, never free text.
            if(settingsScreen&&"set_text".equals(tool))allowed=node!=null&&node.optBoolean("editable")&&input!=null&&input.length()<=40;
            if(settingsScreen&&"tap".equals(tool)&&node!=null)
                allowed=node.optBoolean("clickable")&&node.isNull("checked")&&!label.isBlank()
                    &&!java.util.regex.Pattern.compile("초기화|재설정|삭제|공장|reset|끄기|해제|사용 안 함|^라이트$|^다크$|켜기",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(label).find();
            if(!allowed)return false;
            try{return AppTaskExecutor.execute(service(),baseline,fresh(),observation,action,"set_text".equals(tool)?input:null,selectedPackage);}
            catch(Exception failed){error=failed;return false;}
        }
        void close(){closed=true;if(controller!=null)controller.close();}
    }
    private boolean clickHighlightedCategory(int[] bounds) {
        for (var window : ui.getWindows()) {
            var root = window.getRoot();
            if (root == null) continue;
            try {
                if (!"com.coupang.mobile".contentEquals(root.getPackageName())) continue;
                for (var node : root.findAccessibilityNodeInfosByText("카테고리")) {
                    try {
                        var rect = new android.graphics.Rect();
                        node.getBoundsInScreen(rect);
                        if (!rect.contains((bounds[0]+bounds[2])/2, (bounds[1]+bounds[3])/2)) continue;
                        if (!("카테고리".contentEquals(String.valueOf(node.getText()))
                                || "카테고리".contentEquals(String.valueOf(node.getContentDescription())))) continue;
                        if (node.isVisibleToUser() && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return true;
                    } finally { node.recycle(); }
                }
            } finally { root.recycle(); }
        }
        return false;
    }

    private void confirmAndReadQueue(java.lang.reflect.Field running, JSONObject row) throws Exception {
        String[] request = {null};
        boolean[] clicked = {false};
        inst.runOnMainSync(() -> {
            try {
                var ref = (java.lang.ref.WeakReference<?>)running.get(null);
                if (ref == null || !(ref.get() instanceof ClickOverlayService flow)) return;
                request[0] = (String)ServiceStateAccess.get(flow,"activeVoiceRequestId");
                flow.onOpenRequested();
                var panel = (android.view.View)ServiceStateAccess.get(flow,"taskFlowView");
                if (panel == null) return;
                var candidates = new ArrayList<android.view.View>();
                panel.findViewsWithText(candidates,"네, 됐어요",android.view.View.FIND_VIEWS_WITH_TEXT);
                for (var candidate : candidates) {
                    if (!(candidate instanceof android.widget.TextView text) || !"네, 됐어요".contentEquals(text.getText())) continue;
                    while (!candidate.isClickable() && candidate.getParent() instanceof android.view.View parent) candidate = parent;
                    if (candidate.isShown() && candidate.isEnabled()) clicked[0] = candidate.performClick();
                    if (clicked[0]) break;
                }
            } catch (IllegalAccessException e) { throw new AssertionError(e); }
        });
        row.put("confirmation_clicked",clicked[0]);
        if (!clicked[0] || request[0] == null) return;
        var http = new AuthenticatedHttpClient(app);
        long until = SystemClock.elapsedRealtime()+10000;
        do {
            var result = http.get("/api/v1/voice-requests/"+request[0]);
            if (result.status != 200) { row.put("queue_read_http",result.status); return; }
            var record = new JSONObject(result.body).optJSONObject("request");
            if (record != null) {
                String state = record.optString("status");
                row.put("queue_status",state);
                if (Set.of("COMPLETED","FAILED","CANCELLED").contains(state)) return;
            }
            SystemClock.sleep(200);
        } while (SystemClock.elapsedRealtime()<until);
    }

    /** Follow the normal request queue and fallback dispatch before inspecting the app controller. */
    private void runQueuedNavigation(String[] task, JSONObject row) throws Exception {
        var touch = new com.click.data.local.TouchAutomationSettings(app);
        boolean previous = touch.isEnabled();
        var intent = new Intent(app, ClickOverlayService.class)
                .putExtra(ClickOverlayService.EXTRA_INITIAL_GOAL, task[2]);
        var running = ClickOverlayService.class.getDeclaredField("runningService");
        running.setAccessible(true);
        var seen = new HashSet<String>();
        long start = SystemClock.elapsedRealtime(), since = -1;
        row.put("entry", "request_queue").put("native_goal", false);
        try {
            touch.setEnabled(false);
            app.startService(intent);
            while (SystemClock.elapsedRealtime() - start < 75000) {
                long elapsed = SystemClock.elapsedRealtime() - start;
                boolean reached = oracle(task);
                row.put("native_goal", reached);
                if (reached && !row.has("goal_reached_ms")) row.put("goal_reached_ms", elapsed);
                since = reached ? (since < 0 ? elapsed : since) : -1;
                boolean[] verified = {false};
                Throwable[] failure = {null};
                inst.runOnMainSync(() -> {
                    try {
                        var ref = (java.lang.ref.WeakReference<?>)running.get(null);
                        var flow = ref == null ? null : (ClickOverlayService)ref.get();
                        if (flow == null) return;
                        if (ServiceStateAccess.get(flow, "acceptedGoal") == null
                                && !(boolean)ServiceStateAccess.get(flow, "turnInFlight")
                                && (ServiceStateAccess.get(flow, "goalPreparation") != null
                                    || ServiceStateAccess.get(flow,"requirementReview") != null)) {
                            var confirm = ClickOverlayService.class.getDeclaredMethod("confirmRequirementReview");
                            confirm.setAccessible(true); confirm.invoke(flow);
                        }
                        if ("coupang_category".equals(task[0])) {
                            var accepted = (JSONObject)ServiceStateAccess.get(flow, "acceptedGoal");
                            if (accepted != null) row.put("accepted_goal_type", accepted.optString("goal_type"));
                            var prompt = (JSONObject)ServiceStateAccess.get(flow, "resultPrompt");
                            if (prompt != null) {
                                row.put("technical_status",prompt.optString("technical_status"));
                                verified[0] = "OPENED".equals(prompt.optString("technical_status"));
                            }
                            var browse = (CoupangBrowseController)ServiceStateAccess.get(flow,"coupangBrowse");
                            if (browse != null) {
                                row.put("browse_status", browse.status());
                                row.put("browse_outcome", browse.outcome());
                                verified[0] |= "COMPLETED".equals(browse.status()) && "verified_navigation".equals(browse.outcome());
                            }
                            var bounds = (int[])ServiceStateAccess.get(flow,"lastTargetBounds");
                            var guide = (com.click.ui.overlay.GuideOverlayView)ServiceStateAccess.get(flow,"guideOverlayView");
                            if (bounds != null && guide != null && guide.targetMatches(bounds)
                                    && !seen.contains("category") && clickHighlightedCategory(bounds)) {
                                seen.add("category");
                                row.getJSONArray("events").put(new JSONObject().put("at_ms",elapsed)
                                        .put("tool","tap").put("approved",true).put("label","카테고리"));
                            }
                            return;
                        }
                        var owner = (AppTaskController)ServiceStateAccess.get(flow, "appGuide");
                        if (owner == null) return;
                        if (!row.has("app_controller_ms")) row.put("app_controller_ms", elapsed);
                        var assessment = (JSONObject)ServiceStateAccess.get(owner, "resultAssessment");
                        if (assessment != null) row.put("assessment", assessment.optString("status"));
                        verified[0] = (boolean)ServiceStateAccess.get(owner, "resultOffered")
                                && assessment != null && "verified".equals(assessment.optString("status"));
                        var action = (JSONObject)ServiceStateAccess.get(owner, "proposal");
                        if (action == null || !seen.add(action.optString("id"))) return;
                        var observation = (JSONObject)ServiceStateAccess.get(owner, "observation");
                        if (observation == null || !task[1].equals(observation.optString("package"))) return;
                        var node = AppTaskObservation.find(observation, action.optString("target_id"));
                        String label = node == null ? "" : node.optString("label");
                        boolean allow = "tap".equals(action.optString("tool")) && Set.of("스톱워치","타이머","Shorts",
                                "검색","YouTube 검색","Chrome 맞춤설정 및 제어","설정","Chrome 설정").contains(label);
                        var event = new JSONObject().put("at_ms",elapsed).put("tool",action.optString("tool"))
                                .put("approved",allow);
                        if (allow) event.put("label",label);
                        row.getJSONArray("events").put(event);
                        if (allow) owner.execute(action.optString("id"),null);
                    } catch (Throwable e) { failure[0] = e; }
                });
                if (failure[0] != null) throw new AssertionError(failure[0]);
                if (verified[0] && !reached) { row.put("false_completion", true); break; }
                if (verified[0] && elapsed - since >= 500) {
                    row.put("passed", true).put("verified_completion_ms", elapsed);
                    if ("true".equals(InstrumentationRegistry.getArguments().getString("auditConfirmResult")))
                        confirmAndReadQueue(running,row);
                    break;
                }
                SystemClock.sleep(150);
            }
            row.put("elapsed_ms", SystemClock.elapsedRealtime() - start);
        } finally {
            inst.runOnMainSync(() -> {
                try {
                    var ref = (java.lang.ref.WeakReference<?>)running.get(null);
                    if (ref != null && ref.get() instanceof ClickOverlayService flow) flow.onStopRequested();
                } catch (IllegalAccessException e) { throw new AssertionError(e); }
            });
            app.stopService(intent);
            touch.setEnabled(previous);
        }
    }

    @Test public void realProviderNavigationMatrix()throws Exception{
        var args=InstrumentationRegistry.getArguments();assumeTrue("true".equals(args.getString("deviceAuditApps")));
        assertTrue(app.getPackageName().endsWith(".deviceaudit"));new DeviceAuditSetupTest().prepareIsolatedAuditAccount();
        var contract=AgentServerContract.fromOpenApi(new JSONObject(AuthGateway.get("/openapi.json",null).body));
        ui=inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);var info=ui.getServiceInfo();
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(info);
        String original=Settings.Secure.getString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        int enabled=Settings.Secure.getInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,0);
        ui.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
        try{
            String own=new ComponentName(app,ClickAccessibilityService.class).flattenToString();var services=new ArrayList<String>();
            if(original!=null&&!original.isBlank())services.addAll(Arrays.asList(original.split(":")));services.remove(own);
            Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",services));SystemClock.sleep(250);
            services.add(own);Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",services));
            Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,1);
            int repeats=Integer.parseInt(args.getString("auditRepeats","3"));
            String set=args.getString("auditSet","apps");
            List<String[]> base=new ArrayList<>();if(!"settings".equals(set))base.addAll(Arrays.asList(TASKS));
            if ("coupang_category".equals(args.getString("auditTask")))
                base.add(new String[]{"coupang_category","com.coupang.mobile","쿠팡 카테고리 열어줘","카테고리","selected"});
            JSONArray manifest=new JSONArray();
            if(!"apps".equals(set)){
                try(var in=inst.getContext().getAssets().open("device-audit-navigation.json")){
                    manifest=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getJSONArray("tasks");}
                var independent=new LiveDeviceAuditTest();var f=LiveDeviceAuditTest.class.getDeclaredField("ui");f.setAccessible(true);f.set(independent,ui);
                settingsOracle=independent;settingsTitle=LiveDeviceAuditTest.class.getDeclaredMethod("oracle",JSONArray.class);settingsTitle.setAccessible(true);
            }
            float font=Settings.System.getFloat(app.getContentResolver(),Settings.System.FONT_SCALE);
            int brightness=Settings.System.getInt(app.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS),brightnessMode=Settings.System.getInt(app.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS_MODE);
            int volume=app.getSystemService(android.media.AudioManager.class).getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            String night=shell("cmd uimode night").trim();
            for(int repeat=0;repeat<repeats;repeat++){
              List<String[]> tasks=new ArrayList<>(base);
              for(int i=0;i<manifest.length();i++){JSONObject t=manifest.getJSONObject(i);titles.put("settings_"+t.getString("key"),t.getJSONArray("titles"));
                  tasks.add(new String[]{"settings_"+t.getString("key"),"com.android.settings",t.getJSONArray("queries").getString(repeat%3),"",""});}
              int index=-1;
              for(String[] task:tasks){
                index++;
                // Resume a paused run: trial numbers count every task in every repeat.
                if(repeat*tasks.size()+index<Integer.parseInt(args.getString("auditStartTrial","0")))continue;
                if(args.getString("auditTask")!=null&&!Arrays.asList(args.getString("auditTask").split(",")).contains(task[0]))continue;
                JSONObject row=new JSONObject().put("task",task[0]).put("repeat",repeat).put("trial",repeat*tasks.size()+index).put("goal",task[2]).put("passed",false).put("events",new JSONArray());
                rows.put(row);save();Driver driver=new Driver(task,row);
                try{
                    // Close every audited app first so no picture-in-picture or stale window covers the next one.
                    for(String pkg:List.of(CLOCK,CHROME,YOUTUBE,"com.android.settings"))shell("am force-stop "+pkg);
                    SystemClock.sleep(400);
                    Intent launch=settings(task)?new Intent(Settings.ACTION_SETTINGS):app.getPackageManager().getLaunchIntentForPackage(task[1]);assertNotNull("APP_NOT_INSTALLED",launch);
                    app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    // Check the start screen only once the requested app is really in front.
                    long until=SystemClock.elapsedRealtime()+6000;
                    while(SystemClock.elapsedRealtime()<until){var s=ClickAccessibilityService.freshSnapshot();
                        if(s!=null&&task[1].equals(s.packageName))break;SystemClock.sleep(150);}
                    SystemClock.sleep(900);
                    // Start every trial on a different tab/page so no-op arrivals cannot inflate success.
                    if(oracle(task)&&CLOCK.equals(task[1])){
                        // Clock reopens on its last tab; move to another tab instead of leaving the app.
                        clickLabel("알람".equals(task[3])?"세계시각":"알람");SystemClock.sleep(700);
                    }
                    if(oracle(task)){
                        ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);SystemClock.sleep(500);
                        if(oracle(task))throw new AssertionError("ALREADY_AT_GOAL_SETUP");
                    }
                    if ("true".equals(args.getString("auditViaQueue"))) {
                        runQueuedNavigation(task,row); continue;
                    }
                    final Throwable[] setup={null};inst.runOnMainSync(()->{try{driver.start(contract);}catch(Throwable e){setup[0]=e;}});
                    if(setup[0]!=null)throw new AssertionError(setup[0]);
                    long goalSince=-1,terminalSince=-1;String terminalKey="";boolean nativeMatch=false;
                    while(SystemClock.elapsedRealtime()-driver.started<75000){
                        final Throwable[] poll={null};inst.runOnMainSync(()->{try{driver.poll();}catch(Throwable e){poll[0]=e;}});
                        if(poll[0]!=null)throw new AssertionError(poll[0]);if(driver.error!=null)throw new AssertionError(driver.error);
                        nativeMatch=oracle(task);if(nativeMatch){if(goalSince<0){goalSince=SystemClock.elapsedRealtime();row.put("goal_reached_ms",goalSince-driver.started);}}else goalSince=-1;
                        JSONObject latest=driver.latest;String status=latest==null?"":latest.optString("status");
                        JSONObject assessment=latest==null?null:latest.optJSONObject("result_assessment");
                        boolean verified="completed".equals(status)||"confirm_result".equals(status)&&assessment!=null&&"verified".equals(assessment.optString("status"));
                        if(verified&&!nativeMatch){row.put("false_completion",true);throw new AssertionError("FALSE_COMPLETION");}
                        if(verified&&goalSince>0&&SystemClock.elapsedRealtime()-goalSince>=500){row.put("passed",true).put("verified_completion_ms",SystemClock.elapsedRealtime()-driver.started);break;}
                        // These product states require a person to continue. Count a stable
                        // request for help as failure instead of spending the rest of 75s idle.
                        String key=status+":"+driver.screenChanges;
                        if(Set.of("needs_input","protected","unavailable").contains(status)){
                            if(!key.equals(terminalKey)){terminalKey=key;terminalSince=SystemClock.elapsedRealtime();}
                            if(SystemClock.elapsedRealtime()-terminalSince>=3000){row.put("failure_code",
                                "unavailable".equals(status)?"PROVIDER_OR_PLAN_UNAVAILABLE":"EXPLICIT_HELP_REQUIRED");break;}
                        }else{terminalKey="";terminalSince=-1;}
                        SystemClock.sleep(160);
                    }
                    row.put("native_goal",nativeMatch);
                }catch(Throwable e){row.put("failure",e.getClass().getSimpleName());
                    if(e instanceof AssertionError&&e.getMessage()!=null&&e.getMessage().matches("[A-Z_]+"))row.put("failure_code",e.getMessage());
                }finally{inst.runOnMainSync(driver::close);row.put("finished",true);
                    if(driver.started>0)row.put("elapsed_ms",SystemClock.elapsedRealtime()-driver.started);save();
                    if(settings(task)){shell("settings put system font_scale "+font);shell("settings put system screen_brightness "+brightness);
                        shell("settings put system screen_brightness_mode "+brightnessMode);shell("cmd media_session volume --stream 3 --set "+volume);
                        if(night.endsWith("yes"))shell("cmd uimode night yes");else if(night.endsWith("no"))shell("cmd uimode night no");}}
                System.out.println("AUDIT_APP_CASE "+row);
              }
            }
        }finally{
            Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,original==null?"":original);
            Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,enabled);ui.dropShellPermissionIdentity();save();
        }
    }
}
