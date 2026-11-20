package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.local.AuthTokenStore;
import com.click.data.remote.AppGuideGateway;
import com.click.data.remote.AppTaskCompletionDiagnostics;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.AppTaskObservation;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.overlay.AppTaskController;
import com.click.service.overlay.AppTaskMemory;
import com.click.service.overlay.AppTaskMetaController;
import com.click.service.overlay.AppTaskPolicy;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Actual public-app matrix. Observation/fault clocks are copied unchanged from frozen Settings v2. */
@RunWith(AndroidJUnit4.class)
public final class MetaControllerPublicAppsMatrixTest {
    private static final String VERSION="click.native-public-apps.v1";
    private static final Set<String> PACKAGES=Set.of("com.android.settings","com.google.android.deskclock","com.google.android.documentsui","com.android.chrome");
    private volatile String currentPackage="com.android.settings";
    private Catalogue activeCatalogue;
    private static final String MEASUREMENT_REVISION="equal_independent_observation_window_public_apps_v1";
    private static final long BUDGET_MS=30000,DELAY_MS=5500,SETTLE_MS=750,STABLE_MS=700;
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context app=instrumentation.getTargetContext();
    private final Bundle args=InstrumentationRegistry.getArguments();

    private static final class AppConfig {
        final String pkg,label,homeAction;final Set<String> navigation=new HashSet<>();
        AppConfig(JSONObject json)throws Exception {
            pkg=json.getString("package");label=json.getString("label");homeAction=json.getString("home_action");
            assertTrue(PACKAGES.contains(pkg));assertTrue(Set.of("launcher",Settings.ACTION_SETTINGS).contains(homeAction));
            if(Settings.ACTION_SETTINGS.equals(homeAction))assertEquals("com.android.settings",pkg);
            Set<String> labels=new HashSet<>(strings(json.getJSONArray("public_labels"))),resources=new HashSet<>(strings(json.getJSONArray("public_resources")));
            JSONArray controls=json.getJSONArray("navigation_controls");assertTrue(controls.length()>0);
            for(int i=0;i<controls.length();i++){JSONObject control=controls.getJSONObject(i);String text=control.getString("label"),resource=control.getString("resource");
                assertTrue(labels.contains(text));assertTrue(resources.contains(resource));assertEquals(text,AppTaskObservation.label(text));
                assertFalse(resource.matches("(?i).*?(?:switch|toggle|seekbar|seek_bar|slider|checkbox|check_box|radio_button|edit_text).*"));
                navigation.add(text+"\n"+resource);}
        }
    }
    private static final class Task {
        final String id,pkg,goal,intent;final List<String> path,startPath,positiveIntentPath;final JSONArray oracleChecks;
        Task(JSONObject json)throws Exception {
            id=json.getString("task_id");pkg=json.getString("package");goal=json.getString("goal");intent=json.optString("positive_intent","");
            path=strings(json.getJSONArray("gui_path"));startPath=strings(json.getJSONArray("start_path"));
            positiveIntentPath=strings(json.optJSONArray("positive_intent_path"));oracleChecks=json.getJSONArray("oracle_checks");
            assertTrue(id.matches("[a-z][a-z0-9_]{0,79}"));assertTrue(PACKAGES.contains(pkg));assertFalse(goal.isBlank());
            assertFalse(path.isEmpty());assertTrue(path.size()<=12&&startPath.size()<=12);assertTrue(oracleChecks.length()>0&&oracleChecks.length()<=8);
            assertTrue(intent.isEmpty()||intent.matches("[A-Za-z][A-Za-z0-9_.]{0,199}"));
            for(int i=0;i<oracleChecks.length();i++){JSONObject check=oracleChecks.getJSONObject(i);String kind=check.getString("kind");
                assertTrue(Set.of("passive_title","visible","selected","checked").contains(kind));assertFalse(check.getString("label").isEmpty());
                if(Set.of("selected","checked").contains(kind))assertTrue(check.get("expected") instanceof Boolean);}
        }
    }
    private final class Catalogue {
        final JSONObject raw;final List<Task> tasks=new ArrayList<>();final Map<String,AppConfig> apps=new LinkedHashMap<>();final String contextKey;
        Catalogue(String encoded)throws Exception {
            assertNotNull("Pass frozen catalogue with -e catalogue64",encoded);
            raw=new JSONObject(new String(Base64.decode(encoded,Base64.DEFAULT),StandardCharsets.UTF_8));assertEquals(VERSION,raw.getString("schema_version"));
            JSONArray definitions=raw.getJSONArray("apps");assertEquals(3,definitions.length());
            for(int i=0;i<definitions.length();i++){AppConfig config=new AppConfig(definitions.getJSONObject(i));assertNull(apps.put(config.pkg,config));}
            JSONArray goals=raw.getJSONArray("tasks");assertEquals(6,goals.length());Set<String> unique=new HashSet<>();Map<String,Integer> counts=new LinkedHashMap<>();
            for(int i=0;i<goals.length();i++){Task task=new Task(goals.getJSONObject(i));assertTrue(unique.add(task.id));assertTrue(apps.containsKey(task.pkg));tasks.add(task);counts.put(task.pkg,counts.getOrDefault(task.pkg,0)+1);}
            for(String pkg:apps.keySet())assertEquals("Two actual goals per app",Integer.valueOf(2),counts.get(pkg));
            contextKey=AppTaskObservation.hash(VERSION+"\n"+MEASUREMENT_REVISION+"\n"+raw.toString()+"\n"+Build.VERSION.SDK_INT+"\n"+Build.FINGERPRINT+"\n"+locale()
                +"\n"+com.click.BuildConfig.VERSION_NAME+"\n"+com.click.BuildConfig.VERSION_CODE);
            assertEquals("Freeze observed locale before calls",raw.getString("locale"),locale());activeCatalogue=this;currentPackage=tasks.get(0).pkg;
        }
        boolean allowed(JSONObject node){AppConfig config=apps.get(currentPackage);return config!=null&&node!=null&&config.navigation.contains(node.optString("label")+"\n"+node.optString("resource"))
            &&Set.of("button","tab").contains(node.optString("role"))&&node.optBoolean("clickable")&&!node.optBoolean("editable")&&!node.optBoolean("scrollable")&&node.isNull("checked");}
        boolean allowedScroll(JSONObject node){AppConfig config=apps.get(currentPackage);return config!=null&&node!=null
            &&config.navigation.contains(node.optString("label")+"\n"+node.optString("resource"))&&"list".equals(node.optString("role"))
            &&node.optBoolean("scrollable")&&!node.optBoolean("editable")&&!node.optBoolean("has_value");}
    }

    @Test public void validateFrozenFixtures()throws Exception {
        Assume.assumeTrue("Opt in with publicAppsPreflight",flag("publicAppsPreflight"));isolated();Catalogue cat=new Catalogue(args.getString("catalogue64"));JSONArray rows=new JSONArray();
        try(Session session=new Session()){
            for(Task task:cat.tasks){JSONObject row=new JSONObject().put("task",task.id).put("package",task.pkg).put("ready",false);String guard=guardKey();
                try{
                    ScreenSnapshot initial=prepareHome(session,task);row.put("initial_state_key",semanticKey(initial));Oracle negative=oracle(session.automation,task);
                    row.put("negative_control",negative.known&&!negative.match);
                    if(!task.intent.isEmpty()){
                        open(task.intent);for(String label:task.positiveIntentPath){assertTrue(clickNavigation(cat,label));SystemClock.sleep(SETTLE_MS);}
                        row.put("intent_positive_control",awaitGoal(session.automation,task,7000));assertTrue(row.getBoolean("intent_positive_control"));prepareHome(session,task);
                    }else row.put("intent_positive_control",JSONObject.NULL);
                    for(String label:task.path){assertTrue("Frozen GUI route unavailable: "+label,clickNavigation(cat,label));SystemClock.sleep(SETTLE_MS);}
                    row.put("gui_positive_control",awaitGoal(session.automation,task,7000));
                    String after=guardKey();row.put("settings_guard_observable",!"unavailable".equals(guard)&&!"unavailable".equals(after));
                    row.put("settings_guard_unchanged",row.getBoolean("settings_guard_observable")&&guard.equals(after));
                    row.put("ready",row.getBoolean("negative_control")&&row.getBoolean("gui_positive_control")&&row.getBoolean("settings_guard_unchanged"));
                }catch(AssertionError|Exception failure){row.put("failure",failure instanceof SetupFailure?((SetupFailure)failure).code:failure instanceof AssertionError?"fixture_assertion":failure.getClass().getSimpleName());}
                rows.put(row);home();
            }
        }
        boolean passed=rows.length()==6;for(int i=0;i<rows.length();i++)passed&=rows.getJSONObject(i).optBoolean("ready");
        write("readiness.json",new JSONObject().put("schema_version",VERSION).put("measurement_revision",MEASUREMENT_REVISION).put("evidence_mode","live_native_public_apps")
            .put("passed",passed).put("model_calls",0).put("measurement_context_key",cat.contextKey).put("catalogue",cat.raw).put("checks",rows).toString(2),false);
        System.out.println("PUBLIC_APPS_READINESS "+rows);
    }

    @Test public void validateNativeGuards()throws Exception {
        Assume.assumeTrue("Opt in with publicAppsGuardPreflight",flag("publicAppsGuardPreflight"));isolated();Catalogue cat=new Catalogue(args.getString("catalogue64"));
        JSONObject checks=new JSONObject().put("protected_fixture_available",false),report=new JSONObject().put("schema_version","click.native-public-apps-guard-readiness.v1")
            .put("measurement_revision",MEASUREMENT_REVISION).put("evidence_mode","live_native_public_apps").put("measurement_context_key",cat.contextKey)
            .put("model_calls",0).put("source","actual_native_public_apps_and_test_apk_private_screen").put("passed",false).put("checks",checks).put("diagnostics",new JSONObject());
        try(Session session=new Session()){
            String guard=guardKey();GuardProposal last=null;JSONArray appChecks=new JSONArray();
            try{
                for(AppConfig config:cat.apps.values()){
                    Task task=null;for(Task candidate:cat.tasks)if(config.pkg.equals(candidate.pkg)){task=candidate;break;}assertNotNull(task);prepareHome(session,task);
                    GuardProposal original=guardProposal(cat,task.path.get(0));ScreenSnapshot same=ClickAccessibilityService.freshSnapshot();
                    assertTrue(AppTaskPolicy.allowed(original.observation,AppTaskObservation.recaptureLike(same,original.observation),original.action,currentPackage));
                    assertTrue(eligible(service(),original.baseline,same,original.observation,original.action,currentPackage));
                    assertTrue(AppTaskExecutor.execute(service(),original.baseline,same,original.observation,original.action,null,currentPackage));SystemClock.sleep(SETTLE_MS);
                    for(int i=1;i<task.path.size();i++){assertTrue(clickNavigation(cat,task.path.get(i)));SystemClock.sleep(SETTLE_MS);}
                    assertTrue("Actual independent destination must be reached",awaitGoal(session.automation,task,7000));ScreenSnapshot changed=stablePublicApp();
                    boolean differs=!original.observation.optString("fingerprint").equals(AppTaskObservation.capture(changed).optString("fingerprint"));assertTrue(differs);
                    boolean rejected=!AppTaskExecutor.execute(service(),original.baseline,changed,original.observation,original.action,null,currentPackage);assertTrue(rejected);
                    appChecks.put(new JSONObject().put("package",config.pkg).put("same_screen_policy_allowed",true).put("same_screen_native_executed",true)
                        .put("independent_destination_reached",true).put("actual_screen_changed",differs).put("changed_screen_old_action_rejected",rejected));last=original;
                }
                checks.put("apps",appChecks);GuardProposal proposal=last;assertNotNull(proposal);
                Context test=instrumentation.getContext();String fixturePackage=test.getPackageName();
                assertNotEquals("Private fixture must be outside the service's own package",app.getPackageName(),fixturePackage);
                app.startActivity(new Intent().setComponent(new ComponentName(test,MetaControllerPrivateGuardFixtureActivity.class))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
                ScreenSnapshot protectedScreen=awaitPrivateFixture(fixturePackage);JSONObject protectedObservation=AppTaskObservation.capture(protectedScreen);
                boolean windowChanged=!java.util.Objects.equals(proposal.baseline.activeWindowId,protectedScreen.activeWindowId);checks.put("actual_window_changed",windowChanged);assertTrue(windowChanged);
                JSONObject beforePrivate=privateFixtureDiagnostics(session.automation,fixturePackage);report.getJSONObject("diagnostics").put("private_fixture_before",beforePrivate);
                boolean privateNative=beforePrivate.optBoolean("intact");
                checks.put("protected_fixture_available",true).put("protected_status_observed","protected".equals(protectedObservation.optString("status")))
                    .put("protected_empty_input_and_counter_verified_before",privateNative);assertTrue(privateNative);
                assertEquals("protected",protectedObservation.optString("status"));
                boolean protectedStaleRejected=!AppTaskExecutor.execute(service(),proposal.baseline,ClickAccessibilityService.freshSnapshot(),proposal.observation,proposal.action,null,currentPackage);
                checks.put("protected_screen_old_action_rejected",protectedStaleRejected);assertTrue(protectedStaleRejected);
                AccessibilityNodeSnapshot nativeButton=null;for(AccessibilityNodeSnapshot n:protectedScreen.nodes)
                    if(n.visibleToUser&&n.clickable&&"Guard probe action".equals(n.text)){assertNull("Unique private-fixture button required",nativeButton);nativeButton=n;}
                assertNotNull(nativeButton);
                JSONObject protectedAction=new JSONObject().put("id","native-guard-protected").put("tool","tap").put("package",fixturePackage)
                    .put("target_id",nativeButton.nodeId).put("bounds",new JSONArray(nativeButton.bounds)).put("expectation",new JSONObject().put("kind","screen_changed"));
                ScreenSnapshot protectedCurrent=ClickAccessibilityService.freshSnapshot();assertNotNull(protectedCurrent);
                assertEquals("Protected negative must use the same real current frame contract",protectedObservation.optString("fingerprint"),AppTaskObservation.recaptureLike(protectedCurrent,protectedObservation).optString("fingerprint"));
                assertEquals(protectedScreen.activeWindowId,protectedCurrent.activeWindowId);
                boolean protectedPolicyRejected=!AppTaskPolicy.allowed(protectedObservation,AppTaskObservation.recaptureLike(protectedCurrent,protectedObservation),protectedAction,fixturePackage);
                boolean protectedNativeRejected=!AppTaskExecutor.execute(service(),protectedScreen,protectedCurrent,protectedObservation,protectedAction,null,fixturePackage);
                checks.put("protected_same_frame_policy_rejected",protectedPolicyRejected).put("protected_same_frame_native_rejected",protectedNativeRejected);
                assertTrue(protectedPolicyRejected);assertTrue(protectedNativeRejected);SystemClock.sleep(SETTLE_MS);
                JSONObject afterPrivate=privateFixtureDiagnostics(session.automation,fixturePackage);report.getJSONObject("diagnostics").put("private_fixture_after",afterPrivate);
                boolean untouched=afterPrivate.optBoolean("intact");checks.put("protected_empty_input_and_counter_unchanged",untouched);assertTrue(untouched);

            }finally{home();}
            String after=guardKey();boolean unchanged=!"unavailable".equals(guard)&&!"unavailable".equals(after)&&guard.equals(after);
            checks.put("settings_guard_unchanged",unchanged);assertTrue(unchanged);report.put("passed",true);
        }catch(AssertionError|Exception failure){report.put("passed",false).put("failure",failure instanceof SetupFailure?((SetupFailure)failure).code:failure instanceof AssertionError?"native_guard_assertion":failure.getClass().getSimpleName());}
        write("guard-readiness.json",report.toString(2),false);System.out.println("PUBLIC_APPS_GUARD_READINESS "+report);
    }

    private static final class GuardProposal {
        final ScreenSnapshot baseline;final JSONObject observation,action;
        GuardProposal(ScreenSnapshot baseline,JSONObject observation,JSONObject action){this.baseline=baseline;this.observation=observation;this.action=action;}
    }
    private GuardProposal guardProposal(Catalogue catalogue,String label)throws Exception {
        for(int attempt=0;attempt<8;attempt++){
            ScreenSnapshot baseline=stablePublicApp();JSONObject observation=AppTaskObservation.capture(baseline),target=null;JSONArray nodes=observation.getJSONArray("nodes");
            for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);if(label.equals(n.optString("label"))&&catalogue.allowed(n)){assertNull("Unique public guard control required",target);target=n;}}
            if(target!=null)return new GuardProposal(baseline,observation,new JSONObject().put("id","native-guard-positive").put("tool","tap").put("package",currentPackage)
                .put("target_id",target.getString("id")).put("bounds",target.getJSONArray("bounds")).put("expectation",new JSONObject().put("kind","screen_changed")));
            JSONObject list=null;for(int i=0;i<nodes.length();i++)if(catalogue.allowedScroll(nodes.getJSONObject(i))){list=nodes.getJSONObject(i);break;}
            if(list==null)break;
            JSONObject scroll=new JSONObject().put("id","native-guard-scroll").put("tool","scroll_forward").put("package",currentPackage).put("target_id",list.getString("id"))
                .put("bounds",list.getJSONArray("bounds")).put("expectation",new JSONObject().put("kind","screen_changed"));
            if(!AppTaskExecutor.execute(service(),baseline,ClickAccessibilityService.freshSnapshot(),observation,scroll,null,currentPackage))break;SystemClock.sleep(SETTLE_MS);
        }throw new SetupFailure("public_guard_control_unavailable");
    }
    private ScreenSnapshot awaitPrivateFixture(String pkg)throws Exception {
        long end=SystemClock.elapsedRealtime()+10000,since=-1;Integer window=null;
        while(SystemClock.elapsedRealtime()<end){ScreenSnapshot s=ClickAccessibilityService.freshSnapshot();long now=SystemClock.elapsedRealtime();
            if(s!=null&&pkg.equals(s.packageName)&&"protected".equals(AppTaskObservation.capture(s).optString("status"))){
                if(java.util.Objects.equals(window,s.activeWindowId)){if(since>=0&&now-since>=STABLE_MS)return s;}else{window=s.activeWindowId;since=now;}
            }else{window=null;since=-1;}SystemClock.sleep(100);
        }throw new SetupFailure("native_private_fixture_unavailable");
    }
    private static JSONObject privateFixtureDiagnostics(UiAutomation automation,String pkg)throws Exception {
        AccessibilityNodeInfo root=foregroundRoot(automation);JSONObject result=new JSONObject().put("root_present",root!=null).put("package_matches",false).put("intact",false);
        if(root==null)return result;
        try{boolean matches=pkg.equals(String.valueOf(root.getPackageName()));result.put("package_matches",matches);if(!matches)return result;
            int[] proof=new int[7];privateFixtureProof(root,pkg,proof);
            return result.put("password_node_present",proof[0]>0).put("editable_password_node_present",proof[1]>0)
                .put("exactly_one_empty_editable_password",proof[2]==1).put("password_hint_showing",proof[3]>0).put("nonempty_password_present",proof[4]>0)
                .put("counter_zero_present",proof[5]>0).put("counter_zero_unique",proof[5]==1).put("counter_changed_present",proof[6]>0)
                .put("intact",proof[2]==1&&proof[4]==0&&proof[5]==1&&proof[6]==0);
        }finally{root.recycle();}
    }
    private static void privateFixtureProof(AccessibilityNodeInfo node,String pkg,int[] proof){
        if(node.isVisibleToUser()&&pkg.equals(String.valueOf(node.getPackageName()))){
            if(node.isPassword()){proof[0]++;if(node.isEditable()){proof[1]++;if(node.isShowingHintText())proof[3]++;
                if(node.isShowingHintText()||node.getText()==null||node.getText().length()==0)proof[2]++;else proof[4]++;}}
            if("Guard action count 0".equals(nodeLabel(node)))proof[5]++;
            if("Guard action count 1".equals(nodeLabel(node)))proof[6]++;
        }
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;try{privateFixtureProof(child,pkg,proof);}finally{child.recycle();}}
    }

    @Test public void runPublicAppsMatrix()throws Exception {
        Assume.assumeTrue("Opt in with publicAppsExperiment",flag("publicAppsExperiment"));isolated();Catalogue cat=new Catalogue(args.getString("catalogue64"));
        assertEquals("Live facade must be loopback only","http://127.0.0.1:8769",com.click.BuildConfig.CLICK_API_BASE_URL.replaceAll("/$",""));
        File readiness=new File(directory(),"readiness.json");assertTrue("Run independent readiness first",readiness.isFile());
        JSONObject prepared=readJson(readiness);assertEquals(VERSION,prepared.getString("schema_version"));assertEquals(cat.contextKey,prepared.getString("measurement_context_key"));
        assertEquals(cat.tasks.size(),prepared.getJSONArray("checks").length());
        for(int i=0;i<prepared.getJSONArray("checks").length();i++)assertTrue("Unready catalogue cannot enter measurement",prepared.getJSONArray("checks").getJSONObject(i).getBoolean("ready"));
        File guardReadiness=new File(directory(),"guard-readiness.json");assertTrue("Run actual native guard preflight before model calls",guardReadiness.isFile());
        JSONObject guarded=readJson(guardReadiness);assertEquals("click.native-public-apps-guard-readiness.v1",guarded.getString("schema_version"));
        assertEquals(cat.contextKey,guarded.getString("measurement_context_key"));assertTrue("Native guard preflight must pass",guarded.getBoolean("passed"));
        JSONArray conditions=new JSONArray(args.getString("conditionsJson","[\"normal\",\"accepted_no_effect\",\"delayed_transition\"]"));
        Set<String> permitted=Set.of("normal","accepted_no_effect","delayed_transition");
        for(int i=0;i<conditions.length();i++)assertTrue(permitted.contains(conditions.getString(i)));
        boolean append=flag("appendOutput");String runId=args.getString("runId","public-apps-native-v1");
        File episodeFile=new File(directory(),"episodes.jsonl");assertTrue("Refuse to replace measured output",append||!episodeFile.exists());
        JSONArray frozenConditions=new JSONArray().put("normal").put("accepted_no_effect").put("delayed_transition");
        JSONArray order=new JSONArray();int pair=0;
        for(int c=0;c<frozenConditions.length();c++)for(Task task:cat.tasks){
            String first=pair++%2==0?"A":"B";order.put(new JSONObject().put("pair_id",runId+":"+task.id+":"+frozenConditions.getString(c))
                .put("task",task.id).put("package",task.pkg).put("condition",frozenConditions.getString(c)).put("arms",new JSONArray().put(first).put(first.equals("A")?"B":"A")));
        }
        Set<String> measured=measuredEpisodeIds(episodeFile);
        for(int i=0;i<order.length();i++){JSONObject scheduled=order.getJSONObject(i);boolean selected=false;
            for(int c=0;c<conditions.length();c++)selected|=scheduled.getString("condition").equals(conditions.getString(c));
            if(selected)for(String arm:Arrays.asList("A","B"))assertFalse("A measured episode cannot be rerun",measured.contains(scheduled.getString("pair_id")+":"+arm));}
        JSONObject manifest=new JSONObject().put("schema_version",VERSION).put("evidence_mode","live_native_public_apps").put("run_id",runId).put("budget_ms",BUDGET_MS).put("delay_ms",DELAY_MS)
            .put("measurement_revision",MEASUREMENT_REVISION).put("observation_window_policy","until_30000ms_or_joint_success")
            .put("terminal_state_stops_measurement",false).put("false_offer_stops_measurement",false)
            .put("oracle_sampling_scope","independent_poll_samples_obtained_within_budget")
            .put("scripted_settle_ms",SETTLE_MS).put("goal_stable_ms",STABLE_MS).put("conditions",frozenConditions).put("scheduled_pairs",order)
            .put("measurement_context_key",cat.contextKey).put("catalogue",cat.raw).put("source","actual_android_public_apps_emulator")
            .put("oracle_source","independent_ui_automation_public_predicates").put("real_app",true).put("human_study",false);
        File frozen=new File(directory(),"manifest.json");
        if(frozen.isFile())assertEquals("Do not alter the frozen full schedule",readJson(frozen).toString(),manifest.toString());
        else write("manifest.json",manifest.toString(2),false);
        write("manifest-"+args.getString("phase","all")+".json",new JSONObject(manifest.toString()).put("execution_conditions",conditions).toString(2),false);
        AuthTokenStore tokens=new AuthTokenStore(app);String token=args.getString("testToken","local-meta-public-apps-experiment");
        String old=tokens.accessToken();assertTrue("Do not overwrite a real login",old==null||token.equals(old));
        tokens.save(new JSONObject().put("user_id","local-meta-public-apps-experiment").put("access_token",token).put("refresh_token","local-meta-public-apps-unused")
            .put("expires_in",3600).put("refresh_expires_in",3600));
        try(Session session=new Session()){
            for(int i=0;i<order.length();i++){
                JSONObject scheduled=order.getJSONObject(i);String condition=scheduled.getString("condition");
                boolean selected=false;for(int c=0;c<conditions.length();c++)selected|=condition.equals(conditions.getString(c));if(!selected)continue;
                Task task=cat.tasks.get(i%cat.tasks.size());
                for(int position=0;position<2;position++){
                    String arm=scheduled.getJSONArray("arms").getString(position);
                    JSONObject row=episode(session,cat,task,condition,arm,scheduled.getString("pair_id"),position);
                    write("episodes.jsonl",row+"\n",true);System.out.println("PUBLIC_APPS_EPISODE "+row);
                }
            }
        }
    }

    private JSONObject episode(Session session,Catalogue cat,Task task,String condition,String arm,String pair,int position)throws Exception {
        JSONObject row=new JSONObject().put("schema_version",VERSION).put("evidence_mode","live_native_public_apps").put("episode_id",pair+":"+arm).put("pair_id",pair).put("task",task.id).put("package",task.pkg)
            .put("condition",condition).put("arm",arm).put("arm_order",position).put("measurement_context_key",cat.contextKey).put("measurement_revision",MEASUREMENT_REVISION)
            .put("budget_ms",BUDGET_MS).put("setup_valid",false).put("independent_goal_reached",JSONObject.NULL).put("independent_goal_stable",JSONObject.NULL)
            .put("ever_independent_goal_stable",JSONObject.NULL).put("first_independent_stable_goal_ms",JSONObject.NULL).put("first_runtime_verified_ms",JSONObject.NULL)
            .put("oracle_samples",0).put("oracle_unknown_samples",0).put("last_oracle_sample_ms",JSONObject.NULL)
            .put("oracle_status","unknown").put("runtime_verified_offer",false).put("false_verified_offers",0).put("guard_violations",0)
            .put("unexpected_state_changes",0).put("elapsed_ms",0).put("ms_to_joint_success",JSONObject.NULL).put("pending_native_actions_at_end",0)
            .put("request_traces",new JSONArray()).put("planner_requests",0).put("actual_model_calls",0).put("unknown_model_call_requests",0)
            .put("provider_errors",0).put("plan_errors",0).put("gateway_errors",0).put("listener_errors",0).put("setup_reason",JSONObject.NULL);
        Driver driver=null;long setupStart=SystemClock.elapsedRealtime();
        try{
            drainProvider();ScreenSnapshot initial=prepareHome(session,task);String guard=guardKey();
            if("unavailable".equals(guard))throw new SetupFailure("settings_guard_unavailable");
            row.put("initial_state_key",semanticKey(initial)).put("setup_valid",true).put("setup_elapsed_ms",SystemClock.elapsedRealtime()-setupStart);
            driver=new Driver(cat,task,condition,arm,session.automation);Driver active=driver;session.driver=active;
            long started=SystemClock.elapsedRealtime(),goalSince=-1,firstStableMs=-1,lastSampleAt=-1;int oracleSamples=0,oracleUnknown=0;
            boolean joint=false;Oracle finalOracle=Oracle.unknown();
            active.startedAt=started;instrumentation.runOnMainSync(active::start);
            while(SystemClock.elapsedRealtime()-started<BUDGET_MS){
                instrumentation.runOnMainSync(active::approve);long revision=active.offerRevision;Oracle current=oracle(session.automation,task);long now=SystemClock.elapsedRealtime();
                if(now-started>BUDGET_MS)break;
                oracleSamples++;if(!current.known)oracleUnknown++;finalOracle=current;lastSampleAt=now;
                if(current.known&&current.match){if(goalSince<0)goalSince=now;}else goalSince=-1;
                boolean stable=goalSince>=0&&now-goalSince>=STABLE_MS;
                if(stable&&firstStableMs<0)firstStableMs=now-started;
                long jointAt=active.observeForJoint(current,revision,stable);
                if(jointAt>=0){joint=true;row.put("ms_to_joint_success",jointAt-started);break;}
                SystemClock.sleep(100);
            }
            instrumentation.runOnMainSync(()->active.observing=false);long ended=SystemClock.elapsedRealtime();
            row.put("elapsed_ms",ended-started).put("budget_exhausted",ended-started>=BUDGET_MS).put("independent_goal_reached",finalOracle.known?finalOracle.match:JSONObject.NULL)
                .put("independent_goal_stable",finalOracle.known?finalOracle.match&&goalSince>=0&&lastSampleAt-goalSince>=STABLE_MS:JSONObject.NULL)
                .put("ever_independent_goal_stable",firstStableMs>=0).put("first_independent_stable_goal_ms",firstStableMs<0?JSONObject.NULL:firstStableMs)
                .put("first_runtime_verified_ms",active.firstRuntimeVerifiedMs<0?JSONObject.NULL:active.firstRuntimeVerifiedMs)
                .put("first_terminal_ms",active.firstTerminalMs<0?JSONObject.NULL:active.firstTerminalMs).put("first_terminal_status",active.firstTerminalStatus)
                .put("oracle_samples",oracleSamples).put("oracle_unknown_samples",oracleUnknown).put("oracle_known_samples",oracleSamples-oracleUnknown)
                .put("last_oracle_sample_ms",lastSampleAt<0?JSONObject.NULL:lastSampleAt-started)
                .put("oracle_status",finalOracle.known?"known":"unknown").put("joint_success",joint)
                .put("runtime_verified_offer",active.runtimeOffer).put("ever_runtime_verified_offer",active.everRuntimeOffer)
                .put("false_verified_offers",active.falseOffers).put("unknown_runtime_verified_offers",active.unknownOffers);
            long drainAt=SystemClock.elapsedRealtime();
            boolean pendingAtCutoff=active.pending>0;
            while((active.pending>0||pendingAtCutoff&&SystemClock.elapsedRealtime()<active.settleUntil)&&SystemClock.elapsedRealtime()-drainAt<DELAY_MS+SETTLE_MS+1000)SystemClock.sleep(100);
            row.put("post_budget_drain_ms",SystemClock.elapsedRealtime()-drainAt).put("pending_native_actions_at_end",active.pending);
            long providerDrainAt=SystemClock.elapsedRealtime();drainProvider();
            long callbackDeadline=SystemClock.elapsedRealtime()+13000;while(active.busy&&SystemClock.elapsedRealtime()<callbackDeadline)SystemClock.sleep(100);
            row.put("post_budget_provider_drain_ms",SystemClock.elapsedRealtime()-providerDrainAt).put("pending_gateway_callback_at_end",active.busy);
            instrumentation.runOnMainSync(active::close);session.driver=null;
            Oracle cleanup=oracle(session.automation,task);row.put("goal_present_after_cleanup",cleanup.known?cleanup.match:JSONObject.NULL)
                .put("cleanup_oracle_status",cleanup.known?"known":"unknown");
            JSONArray traces=receipts(active.taskRef,controllerTurns(active.controller));row.put("task_ref",active.taskRef).put("request_traces",traces)
                .put("planner_requests",controllerTurns(active.controller)).put("request_measurement_scope","executed_gateway_receipts_and_controller_turn_count");
            JSONArray completionEvents=completionEvents(active.taskRef);row.put("completion_evidence_traces",completionEvents)
                .put("completion_evidence_trace_count",completionEvents.length()).put("completion_evidence_scope","bounded_production_journal_for_task");
            addTraceCounts(row,traces);
            row.put("attempted_actions",active.attempted).put("accepted_actions",active.accepted).put("native_dispatch_attempts",active.dispatchAttempts)
                .put("native_dispatch_acceptances",active.nativeAccepted).put("native_rejections",active.nativeRejected).put("blocked_action_proposals",active.blocked)
                .put("scripted_approvals",active.approvals).put("genuine_settings_events",active.events).put("observed_event_types",new JSONArray(active.eventTypes))
                .put("fault_injected",active.injected).put("fault_scheduled",active.injected).put("delayed_effect_applied",active.delayedAccepted).put("delayed_dispatch_accepted",active.delayedAccepted)
                .put("native_action_trace",active.actionTrace)
                .put("listener_errors",active.listenerErrors).put("final_status",active.latest==null?"none":active.latest.optString("status"))
                .put("privacy_guard_rejections",active.privacyGuardRejections).put("privacy_guard_blocked",active.privacyBlocked)
                .put("genuine_native_events",active.events)
                .put("recovery_count",active.controller.metaController().diagnostic().optInt("recovery_count"))
                .put("progress_rescue_count",active.controller.metaController().diagnostic().optInt("progress_rescue_count"))
                .put("subgoal_progress_rescue_count",active.controller.metaController().diagnostic().optInt("subgoal_progress_rescue_count"))
                .put("failure_replan_reservations",active.controller.metaController().diagnostic().optInt("failure_replan_reservations"));
            String finalGuard=guardKey();boolean guardKnown=!"unavailable".equals(finalGuard),guardChanged=guardKnown&&!guard.equals(finalGuard);
            row.put("settings_guard_observable",guardKnown).put("settings_guard_changed",guardKnown?guardChanged:JSONObject.NULL)
                .put("unexpected_state_changes",guardChanged?1:0).put("state_change_attribution",!guardKnown?"unknown":guardChanged?"requires_native_trace_review":"none_observed");
        }catch(SetupFailure failed){row.put("setup_reason",failed.code);}
        catch(Exception failed){row.put("harness_error",failed.getClass().getSimpleName());}
        finally{session.driver=null;if(driver!=null){Driver active=driver;instrumentation.runOnMainSync(active::close);}home();}
        return row;
    }

    private final class Driver implements AppTaskController.Listener {
        final Catalogue catalogue;final Task task;final String condition,arm;final UiAutomation independent;final Handler main=new Handler(Looper.getMainLooper());
        final AtomicBoolean closed=new AtomicBoolean();final Set<String> approved=new HashSet<>();final Set<Integer> eventTypes=new LinkedHashSet<>();
        final JSONArray actionTrace=new JSONArray();
        AppTaskController controller;volatile JSONObject latest;volatile boolean busy,observing=true,runtimeOffer,everRuntimeOffer,injected,delayedAccepted;
        volatile int pending,attempted,accepted,dispatchAttempts,nativeAccepted,nativeRejected,blocked,approvals,events,listenerErrors,falseOffers;
        long startedAt,settleUntil;volatile long offerRevision,firstRuntimeVerifiedMs=-1,firstTerminalMs=-1;volatile String firstTerminalStatus="none";
        volatile int unknownOffers,privacyGuardRejections;volatile boolean privacyBlocked;boolean falseRecordedForOffer,unknownRecordedForOffer;String taskRef;
        Driver(Catalogue catalogue,Task task,String condition,String arm,UiAutomation independent){this.catalogue=catalogue;this.task=task;this.condition=condition;this.arm=arm;this.independent=independent;}
        void start(){try{AppTaskMemory memory=new AppTaskMemory(task.goal);taskRef=ref(memory.id());
            controller=new AppTaskController(new AppGuideGateway(app),memory,null,this,true,false,true,
                arm.equals("A")?AppTaskMetaController.Arm.CURRENT:AppTaskMetaController.Arm.META);
            controller.targetApp(currentPackage,catalogue.apps.get(currentPackage).label);controller.refresh();
        }catch(Exception failed){listenerErrors++;}}
        void event(int type){main.post(()->{if(!canObserve()||controller==null)return;events++;eventTypes.add(type);ScreenSnapshot s=fresh();if(s!=null&&currentPackage.equals(s.packageName))controller.screenChanged(s);});}
        void approve(){if(!canObserve()||controller==null||pending>0||SystemClock.elapsedRealtime()<settleUntil)return;
            JSONObject response=latest;if(response==null||!"proposed".equals(response.optString("status")))return;
            JSONObject action=response.optJSONObject("action");if(action!=null&&approved.add(action.optString("id"))){approvals++;controller.execute(action.optString("id"),null);}}
        boolean terminal(){return latest!=null&&Set.of("needs_input","unavailable","protected").contains(latest.optString("status"));}
        void close(){if(!closed.compareAndSet(false,true))return;observing=false;main.removeCallbacksAndMessages(null);if(controller!=null)controller.close();}
        public ScreenSnapshot fresh(){ScreenSnapshot screen=ClickAccessibilityService.freshSnapshot();
            if(hasDocumentEntries(screen)){privacyGuardRejections++;privacyBlocked=true;return null;}return screen;}
        public boolean canObserve(){return observing&&!closed.get()&&!privacyBlocked&&(startedAt<=0||SystemClock.elapsedRealtime()-startedAt<BUDGET_MS);}
        public void busy(boolean value){busy=value;}public boolean inputMatches(String id,String value){return false;}
        public synchronized void present(JSONObject response,ScreenSnapshot baseline){if(!canObserve())return;long elapsed=SystemClock.elapsedRealtime()-startedAt;if(elapsed>BUDGET_MS)return;
            latest=response;JSONObject proof=response.optJSONObject("result_assessment");
            boolean verified=proof!=null&&"verified".equals(proof.optString("status"));
            if(!verified||!runtimeOffer){falseRecordedForOffer=false;unknownRecordedForOffer=false;}
            runtimeOffer=verified;everRuntimeOffer|=verified;offerRevision++;
            if(verified&&firstRuntimeVerifiedMs<0)firstRuntimeVerifiedMs=elapsed;
            if(firstTerminalMs<0&&Set.of("needs_input","unavailable","protected").contains(response.optString("status"))){firstTerminalMs=elapsed;firstTerminalStatus=response.optString("status");}
            if(verified)evaluateOffer(oracle(independent,task),offerRevision);}
        synchronized void evaluateOffer(Oracle observed,long revision){if(!canObserve()||!runtimeOffer||revision!=offerRevision)return;
            if(observed.known&&!observed.match&&!falseRecordedForOffer){falseOffers++;falseRecordedForOffer=true;}
            if(!observed.known&&!unknownRecordedForOffer){unknownOffers++;unknownRecordedForOffer=true;}}
        synchronized long observeForJoint(Oracle observed,long revision,boolean stable){
            if(!canObserve()||revision!=offerRevision)return -1;
            evaluateOffer(observed,revision);long decisionAt=SystemClock.elapsedRealtime();
            if(decisionAt-startedAt>BUDGET_MS||!stable||!runtimeOffer||falseOffers!=0||pending!=0||decisionAt<settleUntil
                    ||firstRuntimeVerifiedMs<0||firstRuntimeVerifiedMs>decisionAt-startedAt)return -1;
            observing=false;return decisionAt;
        }
        public void failure(Throwable error){listenerErrors++;}
        public boolean execute(ScreenSnapshot baseline,JSONObject observation,JSONObject action,String input,String selected){
            attempted++;String tool=action.optString("tool");JSONObject target=AppTaskObservation.find(observation,action.optString("target_id"));
            boolean same=currentPackage.equals(observation.optString("package"))&&"public".equals(observation.optString("status"));
            boolean tap=same&&"tap".equals(tool)&&catalogue.allowed(target);
            boolean nav=same&&("back".equals(tool)||Set.of("scroll_forward","scroll_backward").contains(tool)&&catalogue.allowedScroll(target));
            boolean open="open_app".equals(tool)&&currentPackage.equals(selected)&&currentPackage.equals(action.optString("package"));
            if(!tap&&!nav&&!open){blocked++;return false;}
            if(tap&&!eligible(service(),baseline,fresh(),observation,action,selected)){nativeRejected++;return false;}
            if(tap&&!injected&&!"normal".equals(condition)){
                injected=true;accepted++;
                if("accepted_no_effect".equals(condition)){trace(observation,action,"accepted_no_effect",false);return true;}
                trace(observation,action,"delayed_dispatch_scheduled",false);
                pending++;main.postDelayed(()->{try{if(!closed.get())delayedAccepted=dispatch(baseline,observation,action,selected);}finally{pending--;}}
                    ,DELAY_MS);return true;
            }
            boolean success=dispatch(baseline,observation,action,selected);if(success)accepted++;return success;
        }
        boolean dispatch(ScreenSnapshot baseline,JSONObject observation,JSONObject action,String selected){dispatchAttempts++;
            if("tap".equals(action.optString("tool"))&&!eligible(service(),baseline,fresh(),observation,action,selected)){nativeRejected++;trace(observation,action,"native_rejected",false);return false;}
            boolean ok=AppTaskExecutor.execute(service(),baseline,fresh(),observation,action,null,selected);
            trace(observation,action,ok?"native_accepted":"native_rejected",ok);
            if(ok){nativeAccepted++;settleUntil=SystemClock.elapsedRealtime()+SETTLE_MS;}else nativeRejected++;return ok;}
        void trace(JSONObject observation,JSONObject action,String outcome,boolean nativeAccepted){try{JSONObject n=AppTaskObservation.find(observation,action.optString("target_id"));
            actionTrace.put(new JSONObject().put("elapsed_ms",SystemClock.elapsedRealtime()-startedAt).put("tool",action.optString("tool"))
                .put("label",n==null?JSONObject.NULL:n.optString("label")).put("resource",n==null?JSONObject.NULL:n.optString("resource"))
                .put("outcome",outcome).put("native_accepted",nativeAccepted));}catch(Exception error){listenerErrors++;}}
    }

    private boolean eligible(AccessibilityService service,ScreenSnapshot baseline,ScreenSnapshot current,JSONObject observed,JSONObject action,String selected){
        try{
            if(service==null||baseline==null||current==null||!currentPackage.equals(current.packageName)||!java.util.Objects.equals(baseline.activeWindowId,current.activeWindowId)
                    ||!AppTaskPolicy.allowed(observed,AppTaskObservation.recaptureLike(current,observed),action,selected))return false;
            AccessibilityNodeSnapshot target=null;for(AccessibilityNodeSnapshot n:current.nodes)if(n.nodeId.equals(action.optString("target_id")))target=n;
            if(target==null||target.checkable||target.sensitiveInput||target.passwordInput()||!target.clickable||target.scrollable||!target.enabled||!target.visibleToUser)return false;
            AccessibilityNodeInfo node=ForegroundWindowReader.readRoot(service);if(node==null)return false;
            try{
                String[] path=target.nodeId.split("_");for(int i=2;i<path.length;i++){AccessibilityNodeInfo child=node.getChild(Integer.parseInt(path[i]));node.recycle();node=child;if(node==null)return false;}
                Rect b=new Rect();node.getBoundsInScreen(b);
                return node.isVisibleToUser()&&node.isEnabled()&&node.isClickable()&&!node.isCheckable()&&!node.isEditable()&&!node.isPassword()
                    &&currentPackage.contentEquals(String.valueOf(node.getPackageName()))&&current.activeWindowId!=null&&node.getWindowId()==current.activeWindowId
                    &&b.equals(new Rect(target.bounds[0],target.bounds[1],target.bounds[2],target.bounds[3]))
                    &&java.util.Objects.equals(target.resourceId,node.getViewIdResourceName())&&java.util.Objects.equals(target.className,String.valueOf(node.getClassName()));
            }finally{if(node!=null)node.recycle();}
        }catch(Exception invalid){return false;}
    }
    private boolean clickNavigation(Catalogue catalogue,String label)throws Exception {
        for(int attempt=0;attempt<8;attempt++){
            ScreenSnapshot before=stablePublicApp();JSONObject observed=AppTaskObservation.capture(before),target=null;
            JSONArray nodes=observed.getJSONArray("nodes");for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);if(label.equals(n.optString("label"))&&catalogue.allowed(n)){if(target!=null)throw new SetupFailure("ambiguous_gui_control");target=n;}}
            if(target!=null){JSONObject action=new JSONObject().put("id","fixture-"+attempt).put("tool","tap").put("package",currentPackage)
                .put("target_id",target.getString("id")).put("bounds",target.getJSONArray("bounds")).put("expectation",new JSONObject().put("kind","screen_changed"));
                if(eligible(service(),before,ClickAccessibilityService.freshSnapshot(),observed,action,currentPackage))return AppTaskExecutor.execute(service(),before,ClickAccessibilityService.freshSnapshot(),observed,action,null,currentPackage);
            }
            JSONObject list=null;for(int i=0;i<nodes.length();i++)if(catalogue.allowedScroll(nodes.getJSONObject(i))){list=nodes.getJSONObject(i);break;}
            if(list==null)return false;
            JSONObject scroll=new JSONObject().put("id","fixture-scroll").put("tool","scroll_forward").put("package",currentPackage).put("target_id",list.getString("id"))
                .put("bounds",list.getJSONArray("bounds")).put("expectation",new JSONObject().put("kind","screen_changed"));
            if(!AppTaskExecutor.execute(service(),before,ClickAccessibilityService.freshSnapshot(),observed,scroll,null,currentPackage))return false;SystemClock.sleep(SETTLE_MS);
        }
        return false;
    }

    private ScreenSnapshot prepareHome(Session session,Task task)throws Exception {
        currentPackage=task.pkg;home();for(String label:task.startPath){assertTrue("Frozen start route unavailable: "+label,alreadySelectedStartNavigation(label)||clickNavigation(activeCatalogue,label));SystemClock.sleep(SETTLE_MS);}
        ScreenSnapshot screen=stablePublicApp();Oracle result=oracle(session.automation,task);
        if(!result.known)throw new SetupFailure("initial_oracle_unknown");if(result.match)throw new SetupFailure("goal_present_at_start");return screen;
    }
    /** Fixture reset only: a selected native navigation tab is already at its frozen start step. */
    private boolean alreadySelectedStartNavigation(String label)throws Exception {
        ScreenSnapshot screen=ClickAccessibilityService.freshSnapshot();if(screen==null||!currentPackage.equals(screen.packageName)
                ||hasDocumentEntries(screen)||!"public".equals(AppTaskObservation.capture(screen).optString("status")))return false;
        AppConfig config=activeCatalogue.apps.get(currentPackage);int matches=0;
        for(AccessibilityNodeSnapshot n:screen.nodes){String text=publicLabel(n.contentDescription);if(text.isEmpty())text=publicLabel(n.text);
            String resource=n.resourceId==null?"":n.resourceId,cls=n.className==null?"":n.className.toLowerCase(java.util.Locale.ROOT);
            if(n.visibleToUser&&n.selected&&!n.sensitiveInput&&!n.checkable&&!n.scrollable&&label.equals(text)
                    &&config.navigation.contains(text+"\n"+resource)&&(cls.contains("tab")||resource.toLowerCase(java.util.Locale.ROOT).contains("tab_")))matches++;
        }return matches==1;
    }
    private void home()throws Exception {
        assertTrue(PACKAGES.contains(currentPackage));shell("am force-stop "+currentPackage);AppConfig config=activeCatalogue.apps.get(currentPackage);
        Intent intent=Settings.ACTION_SETTINGS.equals(config.homeAction)?new Intent(config.homeAction).setPackage(currentPackage):app.getPackageManager().getLaunchIntentForPackage(currentPackage);
        if(intent==null)throw new SetupFailure("launcher_unavailable");app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));stablePublicApp();
    }
    private void open(String action){app.startActivity(new Intent(action).setPackage(currentPackage).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));}
    private ScreenSnapshot stablePublicApp()throws Exception {
        long deadline=SystemClock.elapsedRealtime()+10000,since=-1;String last=null;
        while(SystemClock.elapsedRealtime()<deadline){ScreenSnapshot s=ClickAccessibilityService.freshSnapshot();
            if(hasDocumentEntries(s))throw new SetupFailure("document_items_present");
            if(s!=null&&currentPackage.equals(s.packageName)&&"public".equals(AppTaskObservation.capture(s).optString("status"))){String key=semanticKey(s);
                if(key.equals(last)){if(since>=0&&SystemClock.elapsedRealtime()-since>=600)return s;}else{last=key;since=SystemClock.elapsedRealtime();}}
            else{last=null;since=-1;}SystemClock.sleep(150);
        }throw new SetupFailure("public_app_window_not_stable");
    }
    private String semanticKey(ScreenSnapshot screen)throws Exception {
        JSONObject observation=AppTaskObservation.capture(screen);List<String> keys=new ArrayList<>();JSONArray nodes=observation.getJSONArray("nodes");
        for(int i=0;i<nodes.length();i++){JSONObject n=new JSONObject(nodes.getJSONObject(i).toString());String id=n.optString("id");n.remove("id");n.remove("viewport_hash");
            for(AccessibilityNodeSnapshot source:screen.nodes)if(id.equals(source.nodeId)){n.put("class",source.className).put("checkable",source.checkable);break;}keys.add(n.toString());}
        Collections.sort(keys);return AppTaskObservation.hash(screen.packageName+"\n"+String.join("\n",keys));
    }

    private static final class Oracle {final boolean known,match;Oracle(boolean known,boolean match){this.known=known;this.match=match;}static Oracle unknown(){return new Oracle(false,false);}}
    private Oracle oracle(UiAutomation automation,Task task){AccessibilityNodeInfo root=foregroundRoot(automation);if(root==null)return Oracle.unknown();
        try{
            if(!task.pkg.equals(String.valueOf(root.getPackageName())))return new Oracle(true,false);
            if(hasDocumentEntries(root,false))return Oracle.unknown();
            boolean matched=true;for(int i=0;i<task.oracleChecks.length();i++){
                JSONObject check=task.oracleChecks.optJSONObject(i);if(check==null)return Oracle.unknown();int found=count(root,check);
                if(found>1)return Oracle.unknown();matched&=found==1;
            }return new Oracle(true,matched);
        }finally{root.recycle();}
    }
    private int count(AccessibilityNodeInfo node,JSONObject check){
        String kind=check.optString("kind");List<String> resources=new ArrayList<>();JSONArray values=check.optJSONArray("resources");
        for(int i=0;values!=null&&i<values.length();i++)resources.add(values.optString(i));
        boolean nativeMatch=node.isVisibleToUser()&&currentPackage.equals(String.valueOf(node.getPackageName()))&&!node.isPassword()&&!node.isEditable()
            &&check.optString("label").equals(nodeLabel(node))&&(resources.isEmpty()||resources.contains(node.getViewIdResourceName()));
        if("passive_title".equals(kind))nativeMatch&=!node.isClickable()&&!node.isCheckable()&&(node.isHeading()||!resources.isEmpty()||!actionableParent(node));
        if("selected".equals(kind))nativeMatch&=node.isSelected()==check.optBoolean("expected");
        if("checked".equals(kind))nativeMatch&=node.isCheckable()&&node.isChecked()==check.optBoolean("expected");
        if(nativeMatch)return 1;
        int total=0;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;
            try{total+=count(child,check);if(total>1)return 2;}finally{child.recycle();}}
        return total;
    }
    private boolean hasDocumentEntries(AccessibilityNodeInfo node,boolean ancestorList){
        if(!currentPackage.equals("com.google.android.documentsui"))return false;
        String resource=node.getViewIdResourceName();resource=resource==null?"":resource;
        boolean list=ancestorList||resource.matches(".*:id/(?:dir_list|documents_list|document_list)(?:_.*)?");
        if(list&&node.isVisibleToUser()&&resource.matches(".*:id/(?:title|display_name|document_name|filename)")&&node.getText()!=null&&node.getText().length()>0)return true;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;
            try{if(hasDocumentEntries(child,list))return true;}finally{child.recycle();}}
        return false;
    }
    private boolean hasDocumentEntries(ScreenSnapshot screen){
        if(screen==null||!"com.google.android.documentsui".equals(screen.packageName))return false;
        Map<String,AccessibilityNodeSnapshot> nodes=new LinkedHashMap<>();for(AccessibilityNodeSnapshot n:screen.nodes)nodes.put(n.nodeId,n);
        for(AccessibilityNodeSnapshot n:screen.nodes){String resource=n.resourceId==null?"":n.resourceId;
            if(!n.visibleToUser||!resource.matches(".*:id/(?:title|display_name|document_name|filename)")
                    ||(n.text==null||n.text.isEmpty())&&(n.contentDescription==null||n.contentDescription.isEmpty()))continue;
            AccessibilityNodeSnapshot parent=n;int depth=0;while(parent!=null&&depth++<40){String id=parent.resourceId==null?"":parent.resourceId;
                if(id.matches(".*:id/(?:dir_list|documents_list|document_list)(?:_.*)?"))return true;parent=nodes.get(parent.parentId);}
        }return false;
    }
    private static boolean actionableParent(AccessibilityNodeInfo n){AccessibilityNodeInfo parent=n.getParent();int depth=0;
        while(parent!=null&&depth++<10){AccessibilityNodeInfo next=null;try{if(parent.isClickable()||parent.isCheckable()||parent.isEditable())return true;next=parent.getParent();}finally{parent.recycle();}parent=next;}if(parent!=null)parent.recycle();return false;}
    private static AccessibilityNodeInfo foregroundRoot(UiAutomation automation){List<AccessibilityWindowInfo> windows=automation.getWindows();if(windows==null)return null;
        AccessibilityNodeInfo chosen=null;int layer=Integer.MIN_VALUE;
        try{for(AccessibilityWindowInfo w:windows){if(w.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD||!w.isActive()&&!w.isFocused())continue;
                AccessibilityNodeInfo root=w.getRoot();String own=InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
                if(w.getType()==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY&&root!=null&&own.equals(String.valueOf(root.getPackageName()))){root.recycle();continue;}
                if(w.getLayer()>layer){if(chosen!=null)chosen.recycle();chosen=root;layer=w.getLayer();}else if(root!=null)root.recycle();}return chosen;
        }finally{for(AccessibilityWindowInfo w:windows)w.recycle();}}
    private boolean awaitGoal(UiAutomation automation,Task task,long limit){long end=SystemClock.elapsedRealtime()+limit,since=-1;
        while(SystemClock.elapsedRealtime()<end){Oracle o=oracle(automation,task);long now=SystemClock.elapsedRealtime();if(o.known&&o.match){if(since<0)since=now;if(now-since>=STABLE_MS)return true;}else since=-1;SystemClock.sleep(100);}return false;}

    private static String nodeLabel(AccessibilityNodeInfo n){String description=publicLabel(n.getContentDescription());return description.isEmpty()?publicLabel(n.getText()):description;}
    private static String publicLabel(CharSequence text){return AppTaskObservation.label(text==null?"":text.toString());}
    private static int controllerTurns(AppTaskController controller)throws Exception {var field=AppTaskController.class.getDeclaredField("turns");field.setAccessible(true);return field.getInt(controller);}
    private static Set<String> measuredEpisodeIds(File file)throws Exception {Set<String> ids=new HashSet<>();if(!file.isFile())return ids;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){String line;
            while((line=reader.readLine())!=null){if(line.isBlank())continue;JSONObject row=new JSONObject(line);
                assertEquals("Never mix cohorts in the retest output",VERSION,row.getString("schema_version"));
                assertTrue("Existing output already contains a duplicate episode",ids.add(row.getString("episode_id")));}}
        return ids;}
    private JSONArray completionEvents(String taskRef)throws Exception {JSONArray events=new JSONArray();
        for(String name:Arrays.asList("previous.jsonl","current.jsonl")){File file=new File(app.getNoBackupFilesDir(),"app-task-completion-diagnostics/"+name);if(!file.isFile())continue;
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){String line;
                while((line=reader.readLine())!=null){if(line.length()>8192)continue;try{JSONObject event=new JSONObject(line);
                    if(AppTaskCompletionDiagnostics.VERSION.equals(event.optString("schema_version"))&&taskRef.equals(event.optString("task_ref")))events.put(AppTaskCompletionDiagnostics.sanitize(event));
                }catch(Exception partial){}}}}
        return events;}
    private JSONArray receipts(String task,int expected)throws Exception {
        JSONArray found=new JSONArray();for(int attempt=0;attempt<15;attempt++){
            found=new JSONArray();for(String name:Arrays.asList("previous.jsonl","current.jsonl")){
                File f=new File(app.getNoBackupFilesDir(),"app-task-diagnostics/"+name);if(!f.isFile())continue;
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(f),StandardCharsets.UTF_8))){String line;
                    while((line=reader.readLine())!=null){if(line.length()>8192)continue;try{JSONObject r=new JSONObject(line);if(!task.equals(r.optString("task_ref")))continue;
                            JSONObject t=new JSONObject(r.toString());Object calls=r.opt("observed_model_calls");String state=calls instanceof Number?((Number)calls).intValue()==0?"not_started":"observed":"unknown";
                            t.put("model_call_state",state).put("model_calls",calls==null?JSONObject.NULL:calls);t.remove("observed_model_calls");found.put(t);
                        }catch(Exception partial){}}
                }
            }if(found.length()>=expected)return found;SystemClock.sleep(100);
        }return found;
    }
    private static void addTraceCounts(JSONObject row,JSONArray traces)throws Exception {int calls=0,unknown=0,provider=0,plan=0,gateway=0;double wait=0;
        for(int i=0;i<traces.length();i++){JSONObject t=traces.getJSONObject(i);calls+=t.optInt("model_calls");if("unknown".equals(t.optString("model_call_state")))unknown++;
            wait+=t.optDouble("model_wait_ms",0);String kind=t.optString("failure_kind");if("provider".equals(kind))provider++;if(Set.of("parse","plan","configuration").contains(kind))plan++;
            if("request_failed".equals(t.optString("outcome")))gateway++;}
        row.put("actual_model_calls",calls).put("unknown_model_call_requests",unknown).put("actual_model_wait_ms",wait)
            .put("provider_errors",provider).put("plan_errors",plan).put("gateway_errors",gateway);
    }
    private void drainProvider()throws Exception {String base=com.click.BuildConfig.CLICK_API_BASE_URL.replaceAll("/$","");
        if(!base.equals("http://127.0.0.1:8769"))return;HttpURLConnection c=(HttpURLConnection)new URL(base+"/drain").openConnection();
        c.setConnectTimeout(12000);c.setReadTimeout(13000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Authorization","Bearer "+args.getString("testToken","local-meta-public-apps-experiment"));
        try{if(c.getResponseCode()!=200)throw new SetupFailure("provider_drain_failed");try(InputStream in=c.getInputStream()){while(in.read()!=-1){}}}finally{c.disconnect();}}
    private String guardKey(){JSONObject values=new JSONObject();try{
        for(String key:Arrays.asList("font_scale","screen_brightness_mode","accelerometer_rotation","time_12_24"))values.put(key,Settings.System.getString(app.getContentResolver(),key));
        for(String key:Arrays.asList("airplane_mode_on","wifi_on","bluetooth_on","auto_time","auto_time_zone"))values.put(key,Settings.Global.getString(app.getContentResolver(),key));
        values.put("ringer",((android.media.AudioManager)app.getSystemService(Context.AUDIO_SERVICE)).getRingerMode()).put("locale",locale());
    }catch(Exception unavailable){return "unavailable";}return AppTaskObservation.hash(values.toString());}
    private final class Session implements AutoCloseable {
        final UiAutomation automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        final String original;final int enabled,flags,eventTypes;volatile Driver driver;
        Session()throws Exception {
            original=Settings.Secure.getString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            enabled=Settings.Secure.getInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,0);
            AccessibilityServiceInfo info=automation.getServiceInfo();flags=info.flags;eventTypes=info.eventTypes;
            automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
            try{
            info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            info.eventTypes=AccessibilityEvent.TYPES_ALL_MASK;automation.setServiceInfo(info);
            Set<String> services=new LinkedHashSet<>();if(original!=null)for(String s:original.split(":"))if(!s.isEmpty())services.add(s);
            ComponentName own=new ComponentName(app,ClickAccessibilityService.class);services.remove(own.flattenToString());Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",services));
            SystemClock.sleep(350);services.add(own.flattenToString());Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",services));
            Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,1);long end=SystemClock.elapsedRealtime()+10000;
            while(service()==null&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);assertNotNull("Accessibility service must connect",service());
            automation.setOnAccessibilityEventListener(event->{Driver d=driver;CharSequence pkg=event.getPackageName();int type=event.getEventType();
                if(d!=null&&pkg!=null&&currentPackage.contentEquals(pkg)&&Set.of(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,AccessibilityEvent.TYPE_VIEW_SCROLLED,AccessibilityEvent.TYPE_VIEW_SELECTED,AccessibilityEvent.TYPE_VIEW_CLICKED).contains(type))d.event(type);});
            }catch(Exception|AssertionError failed){close();throw failed;}
        }
        public void close(){driver=null;automation.setOnAccessibilityEventListener(null);
            try{Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,original==null?"":original);
                Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,enabled);AccessibilityServiceInfo info=automation.getServiceInfo();info.flags=flags;info.eventTypes=eventTypes;automation.setServiceInfo(info);
            }finally{automation.dropShellPermissionIdentity();}}
    }
    private void isolated(){assertEquals("Use only the isolated test application","com.click.metaexperiment.publicapps",app.getPackageName());assertEquals("Fresh emulator only","ranchu",Build.HARDWARE);}
    private static String locale(){return android.content.res.Resources.getSystem().getConfiguration().getLocales().toLanguageTags();}
    private static ClickAccessibilityService service(){try{var f=ClickAccessibilityService.class.getDeclaredField("connectedService");f.setAccessible(true);return (ClickAccessibilityService)((WeakReference<?>)f.get(null)).get();}catch(Exception error){throw new AssertionError(error);}}
    private void shell(String command)throws Exception {try(var fd=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command);InputStream in=new FileInputStream(fd.getFileDescriptor())){while(in.read()!=-1){}}}
    private boolean flag(String name){return Boolean.parseBoolean(args.getString(name,"false"));}
    private File directory(){File d=new File(app.getFilesDir(),"public-apps-meta-v1");d.mkdirs();return d;}
    private void write(String name,String value,boolean append)throws Exception {try(FileOutputStream out=new FileOutputStream(new File(directory(),name),append)){out.write(value.getBytes(StandardCharsets.UTF_8));}}
    private static JSONObject readJson(File file)throws Exception {try(InputStream in=new FileInputStream(file)){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);return new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));}}
    private static List<String> strings(JSONArray a)throws Exception {List<String> values=new ArrayList<>();for(int i=0;a!=null&&i<a.length();i++)values.add(a.getString(i));return values;}
    private static String ref(String value){return AppTaskObservation.hash(value).substring(0,24);}
    private static final class SetupFailure extends Exception {final String code;SetupFailure(String code){super(code);this.code=code;}}
}
