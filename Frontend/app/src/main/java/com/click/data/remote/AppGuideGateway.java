package com.click.data.remote;

import android.content.Context;
import android.os.*;
import org.json.*;
import java.util.concurrent.*;

public final class AppGuideGateway {
    public static final String PATH="/api/v1/agent/app-guide/step";
    public static final String TASK_PATH="/api/v1/agent/app-guide/task";
    public static final String MEMORY_PATH=TASK_PATH+"/memory",EVENTS_PATH=TASK_PATH+"/events";
    public static final String RESULT_PATH=TASK_PATH+"/result";
    public interface Callback {void success(JSONObject result);void failure(Throwable error);}
    private final AuthenticatedHttpClient http;
    private final AppTaskDiagnostics taskDiagnostics;
    private final AppTaskCompletionDiagnostics completionDiagnostics;
    private final RequestLifecycle lifecycle=new RequestLifecycle();
    private final RequestLifecycle memoryLifecycle=new RequestLifecycle();
    private volatile boolean closed;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final ExecutorService memoryExecutor=Executors.newSingleThreadExecutor();
    private final ExecutorService resultExecutor=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicInteger memoryGeneration=new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean flushing=new java.util.concurrent.atomic.AtomicBoolean();
    private final Handler main=new Handler(Looper.getMainLooper());
    public AppGuideGateway(Context context){http=new AuthenticatedHttpClient(context);taskDiagnostics=new AppTaskDiagnostics(context);completionDiagnostics=new AppTaskCompletionDiagnostics(context);}
    public void recordCompletion(JSONObject event){completionDiagnostics.record(event);}
    public void send(JSONObject request,Callback callback) {
        final long queuedAt=SystemClock.elapsedRealtime();
        final boolean task="app-task.v2".equals(request.optString("protocol_version"));
        executor.execute(()->{
            long startedAt=SystemClock.elapsedRealtime();
            JSONObject taskResponse=null;Integer taskHttpStatus=null;Exception taskFailure=null;
            try(RequestBudget budget=new RequestBudget(queuedAt+15000,lifecycle)) {
                RequestBudget.remaining(12000);
                HttpResult reply=http.post(task?TASK_PATH:PATH,request.toString(),12000);
                taskHttpStatus=reply.status;
                JSONObject result=new JSONObject(reply.body);taskResponse=result;
                if(task)validateTask(request,result);else validate(request,result);
                lifecycle.post(main,()->callback.success(result));
            }catch(Exception error){taskFailure=error;lifecycle.post(main,()->callback.failure(error));}
            finally {
                if(task&&com.click.data.local.CandidateDiagnosticLog.enabled()){long finishedAt=SystemClock.elapsedRealtime();taskDiagnostics.record(AppTaskDiagnostics.receipt(
                    request,taskResponse,taskHttpStatus,taskFailure,startedAt-queuedAt,finishedAt-startedAt,finishedAt-queuedAt));}
                if(com.click.data.local.CandidateDiagnosticLog.enabled())android.util.Log.i("ClickAppGuide",
                    "request_timing protocol="+(task?"app-task.v2":"app-guide.v1")+" queue_ms="+(startedAt-queuedAt)
                    +" work_ms="+(SystemClock.elapsedRealtime()-startedAt)+" total_ms="+(SystemClock.elapsedRealtime()-queuedAt));
            }
        });
    }
    public static void validate(JSONObject request,JSONObject result)throws Exception {
        JSONObject screen=request.getJSONObject("screen");
        if(!"app-guide.v1".equals(result.getString("protocol_version"))||!result.getBoolean("manual_only")
                ||!request.getString("request_id").equals(result.getString("request_id"))
                ||!screen.getString("observation_id").equals(result.getString("observation_id")))throw new java.io.IOException("INVALID_APP_GUIDE_RESPONSE");
        if(!result.isNull("target_node_id")) {
            JSONArray nodes=screen.getJSONArray("nodes");boolean found=false;
            for(int i=0;i<nodes.length();i++) {
                JSONObject node=nodes.getJSONObject(i);
                if(result.getString("target_node_id").equals(node.getString("node_id"))
                        &&node.getJSONArray("bounds").toString().equals(result.getJSONArray("target_bounds").toString()))found=true;
            }
            if(!found||!"guide".equals(result.getString("status")))throw new java.io.IOException("UNGROUNDED_APP_GUIDE_TARGET");
        }
    }
    public static void validateTask(JSONObject request,JSONObject result)throws Exception {
        JSONObject screen=request.getJSONObject("screen");
        if(!"app-task.v2".equals(result.getString("protocol_version"))||!result.getBoolean("requires_confirmation")
                ||!request.getString("request_id").equals(result.getString("request_id"))
                ||!request.getJSONObject("state").getString("task_id").equals(result.getString("task_id"))
                ||!screen.getString("observation_id").equals(result.getString("observation_id"))
                ||!screen.getString("fingerprint").equals(result.getString("fingerprint")))throw new java.io.IOException("INVALID_APP_TASK_RESPONSE");
        JSONObject completion=result.optJSONObject("completion"),bound=request.getJSONObject("state").optJSONObject("completion");
        if(completion!=null){
            if(!com.click.service.overlay.AppTaskCompletion.valid(completion))throw new java.io.IOException("INVALID_TASK_COMPLETION");
            java.util.Set<String> packages=new java.util.HashSet<>();packages.add(screen.optString("package"));
            JSONArray apps=request.optJSONArray("apps");for(int i=0;apps!=null&&i<apps.length();i++)packages.add(apps.getJSONObject(i).optString("package"));
            JSONArray checks=completion.getJSONArray("checks");for(int i=0;i<checks.length();i++)if(!packages.contains(checks.getJSONObject(i).getString("package"))&&bound==null)throw new java.io.IOException("UNBOUND_TASK_COMPLETION");
        }
        if(bound!=null&&(completion==null||!com.click.service.overlay.AppTaskCompletion.key(bound).equals(com.click.service.overlay.AppTaskCompletion.key(completion))))throw new java.io.IOException("CHANGED_TASK_COMPLETION");
        JSONObject action=result.optJSONObject("action");if(action==null)return;
        if(!"proposed".equals(result.getString("status"))||action.optString("id").isEmpty()
                ||!java.util.Set.of("tap","set_text","scroll_forward","scroll_backward","back","open_app").contains(action.optString("tool"))
                ||!com.click.service.overlay.AppTaskPolicy.validExpectation(action))throw new java.io.IOException("INVALID_APP_TASK_ACTION");
        String tool=action.getString("tool");
        if("open_app".equals(tool)){
            JSONArray apps=request.optJSONArray("apps");boolean selected=false;
            for(int i=0;apps!=null&&i<apps.length();i++)if(action.optString("package").equals(apps.getJSONObject(i).getString("package")))selected=true;
            if(!selected)throw new java.io.IOException("UNSELECTED_APP");return;
        }
        if("back".equals(tool))return;
        JSONObject node=com.click.data.snapshot.AppTaskObservation.find(screen,action.optString("target_id"));
        if(node==null||!node.getJSONArray("bounds").toString().equals(String.valueOf(action.optJSONArray("bounds"))))throw new java.io.IOException("UNGROUNDED_APP_TASK_TARGET");
        if("tap".equals(tool)&&!node.optBoolean("clickable")||"set_text".equals(tool)&&!node.optBoolean("editable")
                ||tool.startsWith("scroll_")&&!node.optBoolean("scrollable"))throw new java.io.IOException("UNSUPPORTED_APP_TASK_TOOL");
    }
    public void recall(JSONObject request,Callback callback){
        if(closed)return;
        int generation=memoryGeneration.incrementAndGet();memoryExecutor.execute(()->{
            if(generation!=memoryGeneration.get())return;
            try(RequestBudget budget=new RequestBudget(SystemClock.elapsedRealtime()+5000,memoryLifecycle)){
                JSONObject result=new JSONObject(http.post(MEMORY_PATH,request.toString(),3000).body);validateMemory(request,result);
                if(generation==memoryGeneration.get())lifecycle.post(main,()->callback.success(result));
            }catch(Exception error){if(generation==memoryGeneration.get())lifecycle.post(main,()->callback.failure(error));}
        });
    }
    public static void validateMemory(JSONObject request,JSONObject result)throws Exception {
        if(!"app-task-memory.v1".equals(result.optString("protocol_version")))throw new java.io.IOException("INVALID_TASK_MEMORY");
        JSONObject envelope=new JSONObject(result.toString()).put("protocol_version","app-task.v2").put("task_id",request.getJSONObject("state").getString("task_id"))
            .put("status",result.optJSONObject("action")==null?"needs_input":"proposed")
            .put("completion",request.getJSONObject("state").optJSONObject("completion"));
        validateTask(request,envelope);
        if(result.optBoolean("collection_enabled")&&(!result.optString("consent_id","").matches("[0-9a-f-]{36}")||result.optLong("consent_recorded_at_ms")<=0))throw new java.io.IOException("INVALID_MEMORY_CONSENT");
    }
    public void flush(com.click.data.local.AppTaskStore store){
        if(closed||store==null||!flushing.compareAndSet(false,true))return;
        memoryExecutor.execute(()->{try(RequestBudget budget=new RequestBudget(SystemClock.elapsedRealtime()+8000,memoryLifecycle)){
            for(int i=0;i<4;i++){JSONObject batch=store.experienceBatch();if(batch==null)break;
                JSONObject receipt=new JSONObject(http.post(EVENTS_PATH,batch.toString(),3000).body);
                if(!"app-task-experience-receipt.v1".equals(receipt.optString("protocol_version")))throw new java.io.IOException("INVALID_EXPERIENCE_RECEIPT");
                java.util.Set<String> sent=new java.util.HashSet<>();JSONArray events=batch.getJSONArray("events"),ack=receipt.getJSONArray("acknowledged");
                for(int j=0;j<events.length();j++)sent.add(events.getJSONObject(j).getString("event_id"));
                java.util.Set<String> received=new java.util.HashSet<>();for(int j=0;j<ack.length();j++)received.add(ack.getString(j));
                if(!sent.equals(received))throw new java.io.IOException("UNBOUND_EXPERIENCE_RECEIPT");store.acknowledgeExperiences(ack);
            }
        }catch(Exception error){android.util.Log.i("ClickAppTask","experience_upload_deferred type="+error.getClass().getSimpleName());}finally{flushing.set(false);}});
    }
    public void finishTask(JSONObject request,Callback callback){
        if(closed)return;resultExecutor.execute(()->{
            try(RequestBudget budget=new RequestBudget(SystemClock.elapsedRealtime()+6000,lifecycle)){
                JSONObject result=new JSONObject(http.post(RESULT_PATH,request.toString(),4000).body);
                if(!request.getString("request_id").equals(result.optString("request_id"))||!request.getString("session_id").equals(result.optString("session_id"))
                    ||!request.getString("task_id").equals(result.optString("task_id"))||!"confirmed".equals(result.optString("status"))||!"user_reported".equals(result.optString("technical_source")))throw new java.io.IOException("INVALID_TASK_RESULT");
                lifecycle.post(main,()->callback.success(result));
            }catch(Exception error){lifecycle.post(main,()->callback.failure(error));}
        });
    }
    public void close(){if(closed)return;closed=true;memoryGeneration.incrementAndGet();lifecycle.close();executor.shutdownNow();resultExecutor.shutdownNow();memoryExecutor.execute(memoryLifecycle::close);memoryExecutor.shutdown();}
}
