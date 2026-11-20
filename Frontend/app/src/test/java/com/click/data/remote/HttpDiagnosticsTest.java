package com.click.data.remote;

import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34,application=Application.class)
public class HttpDiagnosticsTest {
    @Test public void appGuidanceAndQueueEndTimingsHaveUsefulRoutesWithoutUserIdentifiers() {
        ShadowLog.clear();
        new HttpDiagnostics("POST",AppGuideGateway.TASK_PATH).finish();
        new HttpDiagnostics("POST","/api/v1/voice-requests/PRIVATE_REQUEST/complete").finish();
        String logs=ShadowLog.getLogsForTag("ClickAgent").stream().map(item->item.msg).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(logs.contains("route="+AppGuideGateway.TASK_PATH));
        assertTrue(logs.contains("route=/api/v1/voice-requests/{request_id}/complete"));assertFalse(logs.contains("PRIVATE_REQUEST"));
    }
    @Test public void dynamicPathsNeverLeakIdentifiersOrQueryParametersIntoCapturedLogs() {
        ShadowLog.clear();
        var request=new HttpDiagnostics("GET","/api/v1/users/PRIVATE_USER?token=PRIVATE_TOKEN");
        request.status(503);request.finish();
        String logs=ShadowLog.getLogsForTag("ClickAgent").stream().map(item->item.msg).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(logs.contains("http_start"));assertTrue(logs.contains("http_end"));assertTrue(logs.contains("elapsed_ms="));
        assertTrue(logs.contains("status=503"));assertFalse(logs.contains("PRIVATE_USER"));assertFalse(logs.contains("PRIVATE_TOKEN"));
    }
}
