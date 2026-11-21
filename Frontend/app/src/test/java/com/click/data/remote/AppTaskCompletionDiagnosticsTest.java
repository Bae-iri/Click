package com.click.data.remote;

import android.app.Application;
import com.click.data.snapshot.AppTaskObservation;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;
import static com.click.testing.AppTaskFixtures.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class AppTaskCompletionDiagnosticsTest {
    @Test public void changedSelectionContextIsDistinctFromFrameDriftWithoutRetainingItsContents()throws Exception {
        JSONObject event=AppTaskCompletionDiagnostics.sanitize(new JSONObject().put("stage","discarded").put("reason","changed_context")
            .put("selected_app","PRIVATE package").put("goal","PRIVATE goal"));
        assertEquals("discarded",event.getString("stage"));assertEquals("changed_context",event.getString("reason"));
        assertFalse(event.toString().contains("PRIVATE"));assertFalse(event.has("selected_app"));assertFalse(event.has("goal"));
    }
    @Test public void eventsKeepOnlyHashedReferencesAndClosedBoundedFields()throws Exception {
        var nativeScreen=screen("org.example.app",node("org.example.app","private-node","PRIVATE label","button"));
        JSONObject observed=AppTaskObservation.capture(nativeScreen);
        JSONObject event=AppTaskCompletionDiagnostics.event("private-task",AppTaskDiagnostics.ref("private-request"),"a".repeat(64),
            observed,nativeScreen,2,1,"goal_check","checked","not_met","fresh",-150L,3,0);
        assertEquals(AppTaskCompletionDiagnostics.VERSION,event.getString("schema_version"));
        assertEquals(AppTaskDiagnostics.ref("private-task"),event.getString("task_ref"));
        assertEquals(AppTaskDiagnostics.ref("private-request"),event.getString("request_ref"));
        assertEquals("a".repeat(24),event.getString("candidate_ref"));
        assertEquals("not_met",event.getString("verdict"));assertEquals(-150,event.getInt("observation_age_ms"));
        assertTrue(event.getString("window_ref").matches("[0-9a-f]{24}"));
        String text=event.toString();assertFalse(text.contains("PRIVATE"));assertFalse(text.contains("private-"));assertFalse(text.contains("org.example"));
        event.put("goal","PRIVATE goal").put("error","PRIVATE secret").put("reason","PRIVATE arbitrary")
            .put("task_ref","private-task").put("node_count",9999).put("elapsed_ms",-5).put("verdict","PRIVATE verdict");
        JSONObject clean=AppTaskCompletionDiagnostics.sanitize(event);
        assertEquals("unknown",clean.getString("reason"));assertEquals("not_checked",clean.getString("verdict"));
        assertTrue(clean.isNull("task_ref"));assertTrue(clean.isNull("node_count"));assertTrue(clean.isNull("elapsed_ms"));
        assertFalse(clean.toString().contains("PRIVATE"));assertFalse(clean.has("goal"));assertFalse(clean.has("error"));
    }

    @Test public void journalRotatesSeparatelyAndCannotPersistArbitraryInput()throws Exception {
        var app=RuntimeEnvironment.getApplication();File root=app.getNoBackupFilesDir();
        File receipt=new File(root,"app-task-diagnostics/current.jsonl");Files.createDirectories(receipt.getParentFile().toPath());Files.write(receipt.toPath(),"receipt-sentinel\n".getBytes(StandardCharsets.UTF_8));
        File directory=new File(root,"app-task-completion-diagnostics");Files.createDirectories(directory.toPath());
        File current=new File(directory,"current.jsonl");Files.write(current.toPath(),new byte[256*1024]);
        new AppTaskCompletionDiagnostics(app).record(new JSONObject().put("stage","verified").put("reason","fresh_native_observations")
            .put("labels","PRIVATE controls").put("token","PRIVATE token").put("task_ref",AppTaskDiagnostics.ref("private-task")));
        assertEquals(256*1024,new File(directory,"previous.jsonl").length());assertTrue(current.length()>0&&current.length()<2048);
        JSONObject stored=new JSONObject(new String(Files.readAllBytes(current.toPath()),StandardCharsets.UTF_8).trim());
        assertEquals("verified",stored.getString("stage"));assertTrue(stored.getLong("recorded_at_ms")>0);
        assertFalse(stored.toString().contains("PRIVATE"));assertFalse(stored.has("labels"));assertFalse(stored.has("token"));
        assertEquals("receipt-sentinel\n",new String(Files.readAllBytes(receipt.toPath()),StandardCharsets.UTF_8));
    }

    @Test public void unavailableDiagnosticStorageNeverThrowsIntoTaskExecution()throws Exception {
        var app=RuntimeEnvironment.getApplication();File directory=new File(app.getNoBackupFilesDir(),"app-task-completion-diagnostics");
        Files.write(directory.toPath(),"occupied".getBytes(StandardCharsets.UTF_8));
        new AppTaskCompletionDiagnostics(app).record(new JSONObject().put("stage","goal_check"));
        assertEquals("occupied",new String(Files.readAllBytes(directory.toPath()),StandardCharsets.UTF_8));
    }
}
