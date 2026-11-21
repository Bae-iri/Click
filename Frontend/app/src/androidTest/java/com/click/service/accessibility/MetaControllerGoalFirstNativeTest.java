package com.click.service.accessibility;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.local.AuthTokenStore;
import com.click.data.remote.AppGuideGateway;
import com.click.data.snapshot.AppTaskObservation;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.overlay.AppTaskCompletion;
import com.click.service.overlay.AppTaskController;
import com.click.service.overlay.AppTaskMemory;
import com.click.service.overlay.AppTaskMetaController;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

/** New component/navigation cohorts. Existing frozen harnesses and their raw results stay unchanged. */
@RunWith(AndroidJUnit4.class)
public final class MetaControllerGoalFirstNativeTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context app=instrumentation.getTargetContext();
    private final Bundle args=InstrumentationRegistry.getArguments();
    private final MetaControllerPublicAppsMatrixTest fixtures=new MetaControllerPublicAppsMatrixTest();

    /** Real GUI + scripted local HTTP, zero model calls. Remove adb reverse tcp:8769 before this method. */
    @Test public void probeGoalFirstNative()throws Exception {
        Assume.assumeTrue("Opt in with goalFirstNativeProbe",flag("goalFirstNativeProbe"));isolated();
        Object catalogue=inner("Catalogue",args.getString("catalogue64"));
        JSONArray cases=new JSONArray();String guard=(String)call(fixtures,"guardKey");
        assertNotEquals("unavailable",guard);authenticateFixture();
        try(AutoCloseable session=(AutoCloseable)inner("Session");ScriptedPeer peer=new ScriptedPeer()){
            try{
            UiAutomation automation=(UiAutomation)get(session,"automation");
            for(Object task:tasks(catalogue)){
                call(fixtures,"prepareHome",session,task);
                for(String label:path(task)){
                    assertTrue("Frozen public navigation unavailable: "+label,(Boolean)call(fixtures,"clickNavigation",catalogue,label));
                    SystemClock.sleep(750);
                }
                assertTrue("Independent native goal must be stable before controlled probe",(Boolean)call(fixtures,"awaitGoal",automation,task,7000L));
                ScreenSnapshot current=(ScreenSnapshot)call(fixtures,"stablePublicApp");
                JSONObject completion=prototype(task,current);
                assertEquals(AppTaskCompletion.Verdict.MATCHED,AppTaskCompletion.checkForGoal(text(task,"goal"),completion,AppTaskObservation.capture(current),current));
                cases.put(runComponent(peer,task,"actual_destination_pending_plan",completion,true,false,false));
            }
            Object files=task(catalogue,"files_documents");
            call(fixtures,"prepareHome",session,files);
            assertTrue((Boolean)call(fixtures,"clickNavigation",catalogue,"Show roots"));SystemClock.sleep(750);
            ScreenSnapshot drawer=(ScreenSnapshot)call(fixtures,"stablePublicApp");JSONObject menu=uniqueNode(drawer,"Documents",null);
            assertTrue("Negative fixture is an actionable menu item",menu.optBoolean("clickable"));
            JSONObject unsafe=new JSONObject().put("checks",new JSONArray().put(selector(text(files,"pkg"),menu)));
            assertTrue(AppTaskCompletion.unsafeForGoal(text(files,"goal"),unsafe,AppTaskObservation.capture(drawer),drawer));
            cases.put(runComponent(peer,files,"clickable_drawer_is_not_destination",unsafe,false,false,false));

            Object display=task(catalogue,"settings_display");call(fixtures,"prepareHome",session,display);
            for(String label:path(display)){assertTrue((Boolean)call(fixtures,"clickNavigation",catalogue,label));SystemClock.sleep(750);}
            assertTrue((Boolean)call(fixtures,"awaitGoal",automation,display,7000L));
            JSONObject displayCompletion=prototype(display,(ScreenSnapshot)call(fixtures,"stablePublicApp"));
            cases.put(runComponent(peer,display,"stale_actual_destination_copy",displayCompletion,false,true,false));
            app.startActivity(new Intent().setComponent(new ComponentName(instrumentation.getContext(),MetaControllerPrivateGuardFixtureActivity.class))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            String privatePackage=instrumentation.getContext().getPackageName();
            ScreenSnapshot privateScreen=(ScreenSnapshot)call(fixtures,"awaitPrivateFixture",privatePackage);
            assertEquals("protected",AppTaskObservation.capture(privateScreen).optString("status"));
            cases.put(runComponent(peer,display,"actual_protected_fixture",displayCompletion,false,false,true));
            peer.checkFailure();
            }finally{call(fixtures,"home");}
        }
        boolean passed=cases.length()==9;for(int i=0;i<cases.length();i++)passed&=cases.getJSONObject(i).optBoolean("passed");
        String finalGuard=(String)call(fixtures,"guardKey");passed&=guard.equals(finalGuard);
        JSONObject report=new JSONObject().put("schema_version","click.goal-first-native-component.v1")
            .put("source","actual_native_public_apps_scripted_loopback_response").put("model_calls",0)
            .put("scripted_goal_candidates",true).put("navigation_performance_study",false)
            .put("coordinator_policy","current").put("goal_already_present_before_controller",true)
            .put("cases",cases).put("settings_guard_unchanged",guard.equals(finalGuard)).put("passed",passed);
        write("component","report.json",report.toString(2),false);assertTrue("Native component regression failed; inspect report",passed);
        System.out.println("GOAL_FIRST_NATIVE_COMPONENT "+report);
    }

    /** Actual-provider A-only navigation, six preregistered normal goals. Requires separately frozen protocol/facade. */
    @Test public void runGoalFirstNavigation6()throws Exception {
        Assume.assumeTrue("Opt in with goalFirstNavigationExperiment",flag("goalFirstNavigationExperiment"));isolated();
        Object catalogue=inner("Catalogue",args.getString("catalogue64"));String runId=args.getString("runId","");
        assertTrue("Explicit new run ID is required",runId.matches("[A-Za-z0-9][A-Za-z0-9:_-]{0,99}"));
        File target=directory("navigation");File episodes=new File(target,"episodes.jsonl");
        assertFalse("Do not overwrite measured episodes",episodes.exists());
        JSONArray schedule=new JSONArray();for(Object task:tasks(catalogue))schedule.put(new JSONObject().put("pair_id",runId+":"+text(task,"id")+":normal")
            .put("task",text(task,"id")).put("package",text(task,"pkg")).put("condition","normal").put("arms",new JSONArray().put("A")));
        JSONObject manifest=new JSONObject().put("schema_version","click.goal-first-native-navigation.v1").put("run_id",runId)
            .put("evidence_mode","live_native_public_apps").put("coordinator_policies",new JSONObject().put("A","current"))
            .put("planning_mode","goal_first")
            .put("conditions",new JSONArray().put("normal")).put("budget_ms",30000).put("scheduled_episodes",6)
            .put("scheduled_pairs",schedule).put("measurement_context_key",get(catalogue,"contextKey"))
            .put("catalogue",get(catalogue,"raw")).put("source","new_wrapper_of_unchanged_public_apps_episode")
            .put("a_only",true).put("human_study",false);
        File frozen=new File(target,"manifest.json");
        if(frozen.exists())assertEquals("Frozen six-goal assignment changed",read(frozen).toString(),manifest.toString());
        else write("navigation","manifest.json",manifest.toString(2),false);
        if(flag("goalFirstNavigationFreeze")){System.out.println("GOAL_FIRST_NAVIGATION_FREEZE "+manifest);return;}
        authenticateFixture();
        try(AutoCloseable session=(AutoCloseable)inner("Session")){
            for(Object task:tasks(catalogue)){
                String pair=runId+":"+text(task,"id")+":normal";
                JSONObject row=(JSONObject)call(fixtures,"episode",session,catalogue,task,"normal","A",pair,0);
                write("navigation","episodes.jsonl",row.toString()+"\n",true);System.out.println("GOAL_FIRST_NAVIGATION_EPISODE "+row);
                assertTrue("Native preparation failed",row.optBoolean("setup_valid"));
                assertEquals("current",row.optString("coordinator_policy"));
                assertEquals(0,row.optInt("false_verified_offers"));assertEquals(0,row.optInt("guard_violations"));assertEquals(0,row.optInt("unexpected_state_changes"));
            }
        }
    }

    private JSONObject runComponent(ScriptedPeer peer,Object task,String scenario,JSONObject candidate,boolean expected,boolean stale,boolean protectedFixture)throws Exception {
        String pkg=protectedFixture?instrumentation.getContext().getPackageName():text(task,"pkg");
        ComponentDriver driver=new ComponentDriver(text(task,"goal"),pkg,candidate,stale,protectedFixture);
        peer.candidate=new JSONObject(candidate.toString());int before=peer.requests;long started=SystemClock.elapsedRealtime();
        try{
            instrumentation.runOnMainSync(driver::start);long deadline=started+(expected?7000:1600);
            while(SystemClock.elapsedRealtime()<deadline){if(expected&&driver.verified)break;SystemClock.sleep(50);}
            instrumentation.runOnMainSync(driver::stop);peer.checkFailure();
            int requests=peer.requests-before;boolean frames=driver.firstFrame!=null&&driver.secondFrame!=null
                &&!driver.firstFrame.equals(driver.secondFrame)&&driver.secondCaptured-driver.firstCaptured>=350;
            boolean pending="g0".equals(driver.memory.active())&&"pending".equals(driver.memory.state().getJSONArray("subgoals").getJSONObject(0).getString("status"));
            boolean passed=driver.executions==0&&pending&&(expected?driver.verified&&frames&&requests==1&&driver.failures==0
                &&"goal_first".equals(peer.lastMode):!driver.everVerified&&requests<=(stale||protectedFixture?0:1));
            return new JSONObject().put("task",text(task,"id")).put("package",pkg).put("scenario",scenario)
                .put("expected_verified",expected).put("verified",driver.verified).put("ever_verified",driver.everVerified)
                .put("gateway_requests",requests).put("last_request_mode",requests==0?JSONObject.NULL:peer.lastMode)
                .put("model_calls",0).put("native_action_attempts",driver.executions).put("listener_failures",driver.failures)
                .put("pending_plan_preserved",pending).put("distinct_fresh_frames",frames)
                .put("native_frame_interval_ms",frames?driver.secondCaptured-driver.firstCaptured:JSONObject.NULL)
                .put("elapsed_ms",SystemClock.elapsedRealtime()-started).put("final_status",driver.status).put("passed",passed);
        }finally{instrumentation.runOnMainSync(driver::stop);}
    }

    private final class ComponentDriver implements AppTaskController.Listener {
        final String goal,pkg;final JSONObject candidate;final boolean stale,protectedFixture;final AppTaskMemory memory;
        final Map<String,Long> captures=new LinkedHashMap<>();AppTaskController controller;
        volatile boolean observing=true,verified,everVerified;volatile int failures,executions;String status="none",firstFrame,secondFrame;long firstCaptured,secondCaptured;
        ComponentDriver(String goal,String pkg,JSONObject candidate,boolean stale,boolean protectedFixture)throws Exception {
            this.goal=goal;this.pkg=pkg;this.candidate=candidate;this.stale=stale;this.protectedFixture=protectedFixture;
            memory=new AppTaskMemory(goal);memory.plan(new JSONArray().put(new JSONObject().put("title","Requested destination")));
            if(stale||protectedFixture)memory.completion(candidate);
        }
        void start(){controller=new AppTaskController(new AppGuideGateway(app),memory,null,this,!stale&&!protectedFixture,false,true,AppTaskMetaController.Arm.CURRENT);
            controller.targetApp(pkg,"Public app");controller.refresh();}
        void stop(){observing=false;if(controller!=null)controller.close();}
        public ScreenSnapshot fresh(){ScreenSnapshot s=ClickAccessibilityService.freshSnapshot();if(s==null)return null;
            if(stale)s=new ScreenSnapshot(s.packageName,Instant.ofEpochMilli(System.currentTimeMillis()-60000).toString(),s.screenWidth,s.screenHeight,s.nodes,s.activeWindowId,s.privacyMaskingReady).withWindowContext(s.windowContext);
            captures.put(s.observationId,Instant.parse(s.capturedAt).toEpochMilli());return s;}
        public boolean canObserve(){return observing;}public void busy(boolean value){}public boolean inputMatches(String target,String hash){return false;}
        public boolean execute(ScreenSnapshot baseline,JSONObject observed,JSONObject action,String input,String selected){executions++;return false;}
        public void failure(Throwable error){failures++;}
        public void present(JSONObject response,ScreenSnapshot baseline){status=response.optString("status");
            try{if("checking_result".equals(status)&&firstFrame==null){firstFrame=(String)get(controller,"resultObservation");firstCaptured=((Number)get(controller,"resultCapturedAt")).longValue();}
                JSONObject assessment=response.optJSONObject("result_assessment");verified=assessment!=null&&"verified".equals(assessment.optString("status"));everVerified|=verified;
                if(verified){secondFrame=assessment.optString("observation_id");Long at=captures.get(secondFrame);secondCaptured=at==null?0:at;}
            }catch(Exception failed){failures++;}}
    }

    /** Scripted peer returns no action and preserves pending plan bits. Nothing reaches a provider. */
    private final class ScriptedPeer implements AutoCloseable {
        final ServerSocket server;final Thread thread;volatile boolean closed;volatile int requests;volatile String lastMode="none";volatile JSONObject candidate;volatile Throwable failure;
        ScriptedPeer()throws Exception {
            server=new ServerSocket(8769,8,InetAddress.getByName("127.0.0.1"));server.setSoTimeout(250);
            thread=new Thread(()->{while(!closed)try(Socket socket=server.accept()){
                socket.setSoTimeout(5000);InputStream in=socket.getInputStream();ByteArrayOutputStream header=new ByteArrayOutputStream();int sequence=0;
                while(header.size()<8192&&sequence<4){int value=in.read();if(value<0)throw new java.io.IOException("Incomplete header");header.write(value);
                    sequence=value==(sequence==0||sequence==2?'\r':'\n')?sequence+1:value=='\r'?1:0;}
                String headers=header.toString(StandardCharsets.US_ASCII.name());if(!headers.startsWith("POST "+AppGuideGateway.TASK_PATH+" "))throw new java.io.IOException("Unexpected local peer route");
                int length=-1;for(String line:headers.split("\r\n"))if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
                if(length<1||length>65536)throw new java.io.IOException("Invalid local body bound");byte[] data=in.readNBytes(length);if(data.length!=length)throw new java.io.IOException("Incomplete body");
                JSONObject request=new JSONObject(new String(data,StandardCharsets.UTF_8)),screen=request.getJSONObject("screen"),state=request.getJSONObject("state");
                JSONArray goals=state.getJSONArray("subgoals");String active=null;for(int i=0;i<goals.length();i++)if("pending".equals(goals.getJSONObject(i).optString("status"))){active=goals.getJSONObject(i).getString("id");break;}
                lastMode=request.optString("planning_mode","next_action");requests++;
                JSONObject response=new JSONObject().put("protocol_version","app-task.v2").put("requires_confirmation",true)
                    .put("request_id",request.getString("request_id")).put("task_id",state.getString("task_id"))
                    .put("observation_id",screen.getString("observation_id")).put("fingerprint",screen.getString("fingerprint"))
                    .put("status","confirm_result").put("message","Controlled native candidate").put("action",JSONObject.NULL)
                    .put("subgoals",new JSONArray(goals.toString())).put("active_subgoal",active==null?JSONObject.NULL:active)
                    .put("completion",new JSONObject(candidate.toString())).put("diagnostic",new JSONObject().put("called",false)
                        .put("failure_stage","none").put("planning_mode","goal_first").put("goal_assessment","satisfied"));
                byte[] body=response.toString().getBytes(StandardCharsets.UTF_8);String prefix="HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(prefix.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);socket.getOutputStream().flush();
            }catch(SocketTimeoutException idle){}catch(Throwable error){if(!closed)failure=error;}} ,"goal-first-scripted-peer");thread.start();
        }
        void checkFailure(){if(failure!=null)throw new AssertionError("Scripted peer failed",failure);}
        public void close()throws Exception {closed=true;server.close();thread.join(1500);checkFailure();}
    }

    private JSONObject prototype(Object task,ScreenSnapshot screen)throws Exception {
        // The first public predicate identifies the destination; the independent oracle still checks the full fixture conjunction.
        JSONObject check=((JSONArray)get(task,"oracleChecks")).getJSONObject(0);
        String kind=check.getString("kind"),pkg=text(task,"pkg");JSONObject node;
        if("passive_title".equals(kind)){
            // The independent native oracle can identify a containing app bar. The
            // planner projection instead exposes its actual passive title child.
            // Bind that current child, retaining all normal native goal safety checks.
            JSONObject observed=AppTaskObservation.capture(screen);JSONArray nodes=observed.getJSONArray("nodes"),matching=new JSONArray();
            JSONObject selected=null;int safe=0;
            for(int i=0;i<nodes.length();i++){
                JSONObject current=nodes.getJSONObject(i);if(!check.getString("label").equals(current.optString("label")))continue;
                JSONObject detail=new JSONObject().put("label",current.optString("label")).put("resource",current.optString("resource"))
                    .put("role",current.optString("role")).put("clickable",current.optBoolean("clickable")).put("scrollable",current.optBoolean("scrollable"));
                boolean passive="text".equals(current.optString("role"))&&!current.optBoolean("clickable")&&!current.optBoolean("scrollable")&&!current.optBoolean("editable");
                JSONObject trial=new JSONObject().put("checks",new JSONArray().put(selector(pkg,current)));
                boolean eligible=passive&&AppTaskCompletion.checkForGoal(text(task,"goal"),trial,observed,screen)==AppTaskCompletion.Verdict.MATCHED;
                matching.put(detail.put("safe_native_goal_target",eligible));if(eligible){safe++;selected=current;}
            }
            write("component","fixture-projection-"+text(task,"id")+".json",new JSONObject().put("task",text(task,"id"))
                .put("oracle_kind",kind).put("oracle_resources",check.optJSONArray("resources")).put("matching_public_title_nodes",matching)
                .put("safe_title_candidates",safe).put("independent_oracle_unchanged",true).toString(2),false);
            assertEquals("Unique safe projected title required for "+text(task,"id"),1,safe);node=selected;
        }else node=uniqueNode(screen,check.getString("label"),check.optJSONArray("resources"));
        JSONObject selector=selector(pkg,node);
        if("selected".equals(kind))selector.put("selected",check.getBoolean("expected"));
        if("checked".equals(kind))selector.put("checked",check.getBoolean("expected"));
        return new JSONObject().put("checks",new JSONArray().put(selector));
    }
    private JSONObject uniqueNode(ScreenSnapshot screen,String label,JSONArray resources)throws Exception {
        JSONObject match=null;JSONArray nodes=AppTaskObservation.capture(screen).getJSONArray("nodes");
        for(int i=0;i<nodes.length();i++){JSONObject node=nodes.getJSONObject(i);boolean resource=resources==null||resources.length()==0;
            for(int j=0;resources!=null&&j<resources.length();j++)resource|=resources.getString(j).equals(node.optString("resource"));
            if(label.equals(node.optString("label"))&&resource){assertNull("Exact public predicate must be unique",match);match=node;}}
        assertNotNull("Current native projection must expose the public predicate",match);return match;
    }
    private static JSONObject selector(String pkg,JSONObject node)throws Exception {return new JSONObject().put("package",pkg).put("label",node.getString("label")).put("resource",node.getString("resource")).put("role",node.getString("role"));}
    private Object inner(String name,Object...arguments)throws Exception {for(Class<?> c:MetaControllerPublicAppsMatrixTest.class.getDeclaredClasses())if(c.getSimpleName().equals(name)){
        Constructor<?> constructor=c.getDeclaredConstructors()[0];constructor.setAccessible(true);Object[] values=new Object[arguments.length+1];values[0]=fixtures;System.arraycopy(arguments,0,values,1,arguments.length);
        try{return constructor.newInstance(values);}catch(InvocationTargetException error){throw cause(error);}}
        throw new NoSuchMethodException(name);}
    private static Object call(Object owner,String name,Object...arguments)throws Exception {for(Method method:owner.getClass().getDeclaredMethods())if(method.getName().equals(name)&&method.getParameterCount()==arguments.length){method.setAccessible(true);
        try{return method.invoke(owner,arguments);}catch(InvocationTargetException error){throw cause(error);}}throw new NoSuchMethodException(name);}
    private static Exception cause(InvocationTargetException error){Throwable cause=error.getCause();if(cause instanceof AssertionError)throw (AssertionError)cause;return cause instanceof Exception?(Exception)cause:new RuntimeException(cause);}
    private static Object get(Object owner,String name)throws Exception {Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    @SuppressWarnings("unchecked") private List<Object> tasks(Object catalogue)throws Exception {return (List<Object>)get(catalogue,"tasks");}
    @SuppressWarnings("unchecked") private List<String> path(Object task)throws Exception {return (List<String>)get(task,"path");}
    private Object task(Object catalogue,String id)throws Exception {for(Object task:tasks(catalogue))if(id.equals(text(task,"id")))return task;throw new IllegalArgumentException(id);}
    private static String text(Object owner,String name)throws Exception{return (String)get(owner,name);}
    private boolean flag(String name){return Boolean.parseBoolean(args.getString(name,"false"));}
    private void authenticateFixture()throws Exception {AuthTokenStore tokens=new AuthTokenStore(app);String token="local-meta-public-apps-experiment",old=tokens.accessToken();assertTrue("Do not overwrite a real login",old==null||token.equals(old));
        tokens.save(new JSONObject().put("user_id","local-meta-public-apps-experiment").put("access_token",token).put("refresh_token","local-meta-public-apps-unused").put("expires_in",3600).put("refresh_expires_in",3600));}
    private void isolated(){assertEquals("com.click.metaexperiment.publicapps",app.getPackageName());assertEquals("ranchu",Build.HARDWARE);
        assertEquals("http://127.0.0.1:8769",com.click.BuildConfig.CLICK_API_BASE_URL.replaceAll("/$",""));}
    private File directory(String mode){String suffix=args.getString("outputSuffix","");assertTrue("New outputSuffix required",suffix.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,47}"));
        File directory=new File(app.getFilesDir(),"goal-first-native-"+mode+"-"+suffix);assertTrue(directory.isDirectory()||directory.mkdirs());return directory;}
    private void write(String mode,String name,String value,boolean append)throws Exception {try(FileOutputStream stream=new FileOutputStream(new File(directory(mode),name),append)){stream.write(value.getBytes(StandardCharsets.UTF_8));}}
    private static JSONObject read(File file)throws Exception {return new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}
}
