package com.click.data.remote;

import android.app.Application;
import android.os.Looper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static com.click.testing.AppTaskFixtures.observation;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AppTaskGatewayDiagnosticsTest {
    @Test public void actualGatewayWritesOneReceiptForSuccessAndHttpRejection()throws Exception {
        try(var tokens=mockConstruction(com.click.data.local.AuthTokenStore.class)){
            Application app=RuntimeEnvironment.getApplication();AppGuideGateway gateway=new AppGuideGateway(app);
            var file=new java.io.File(app.getNoBackupFilesDir(),"app-task-diagnostics/current.jsonl").toPath();
            Files.deleteIfExists(file);
            var http=mock(AuthenticatedHttpClient.class);var executor=mock(ExecutorService.class);
            doAnswer(call->{((Runnable)call.getArgument(0)).run();return null;}).when(executor).execute(any());
            for(var entry:java.util.Map.of("http",http,"executor",executor).entrySet()){
                var field=AppGuideGateway.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(gateway,entry.getValue());
            }
            JSONObject request=new JSONObject().put("protocol_version","app-task.v2").put("request_id","private-request")
                .put("goal","PRIVATE goal").put("state",new JSONObject().put("task_id","private-task"))
                .put("screen",observation("org.example.app","검색")).put("apps",new JSONArray());
            JSONObject screen=request.getJSONObject("screen");
            JSONObject response=new JSONObject().put("protocol_version","app-task.v2").put("requires_confirmation",true)
                .put("request_id","private-request").put("task_id","private-task").put("observation_id",screen.getString("observation_id"))
                .put("fingerprint",screen.getString("fingerprint")).put("status","needs_input")
                .put("diagnostic",new JSONObject().put("called",false));
            when(http.post(eq(AppGuideGateway.TASK_PATH),anyString(),eq(12000)))
                .thenReturn(new HttpResult(200,response.toString(),null))
                .thenThrow(new ApiException("POST",AppGuideGateway.TASK_PATH,new HttpResult(422,"PRIVATE body",null)));
            var first=mock(AppGuideGateway.Callback.class);var second=mock(AppGuideGateway.Callback.class);
            try{
                gateway.send(request,first);gateway.send(new JSONObject(request.toString()).put("request_id","private-second"),second);
                shadowOf(Looper.getMainLooper()).idle();verify(first).success(any());verify(second).failure(any(ApiException.class));
                var lines=Files.readAllLines(file,StandardCharsets.UTF_8);assertEquals(2,lines.size());
                JSONObject received=new JSONObject(lines.get(0)),rejected=new JSONObject(lines.get(1));
                assertEquals(200,received.getInt("http_status"));assertEquals(0,received.getInt("observed_model_calls"));
                assertEquals(422,rejected.getInt("http_status"));assertEquals("http_rejected",rejected.getString("error_code"));
                assertEquals(AppTaskDiagnostics.ref("private-second"),rejected.getString("request_ref"));
                assertTrue(rejected.isNull("observed_model_calls"));
                String text=String.join("\n",lines);assertFalse(text.contains("PRIVATE"));assertFalse(text.contains("private-"));
            }finally{gateway.close();Files.deleteIfExists(file);}
        }
    }
}
