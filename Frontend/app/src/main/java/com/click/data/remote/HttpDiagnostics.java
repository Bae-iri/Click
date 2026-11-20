package com.click.data.remote;

import android.os.SystemClock;
import android.util.Log;
import com.click.data.local.CandidateDiagnosticLog;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Timings only: never records URLs, identifiers, payloads, tokens or exception messages. */
final class HttpDiagnostics {
    private static final AtomicLong SEQUENCE=new AtomicLong();
    private static final Set<String> ROUTES=Set.of("/openapi.json","/version","/validation/environment",
        "/api/v1/auth/register","/api/v1/auth/login","/api/v1/auth/kakao","/api/v1/auth/refresh","/api/v1/auth/logout",
        "/api/v1/interactions/classify","/api/v1/voice-requests/enqueue","/api/v1/voice-requests/next/claim",
        "/api/v1/voice-requests/interrupt-and-clear","/api/v1/agent/commands","/api/v1/agent/goals/resolve",
        "/api/v1/agent/browse/commands","/api/v1/agent/public-guide/commands",
        "/api/v1/voice-requests",AppGuideGateway.PATH,AppGuideGateway.TASK_PATH,
        AppGuideGateway.MEMORY_PATH,AppGuideGateway.EVENTS_PATH,AppGuideGateway.RESULT_PATH);
    private final long id=SEQUENCE.incrementAndGet(),started=SystemClock.elapsedRealtime();
    private final String route,method;
    private int status;
    HttpDiagnostics(String method,String path) {
        this.method=method;this.route=route(path);
        if(CandidateDiagnosticLog.enabled())Log.i("ClickAgent","http_start id="+id+" method="+method+" route="+route);
    }
    private static String route(String path) {
        if(ROUTES.contains(path))return path;
        if(path.matches("/api/v1/voice-requests/[^/?]+/(?:complete|cancel|fail|revise)"))
            return "/api/v1/voice-requests/{request_id}/"+path.substring(path.lastIndexOf('/')+1);
        if(path.matches("/api/v1/agent/sessions/[^/?]+"))return "/api/v1/agent/sessions/{session_id}";
        return "other";
    }
    void status(int value){status=value;}
    void finish() {
        if(CandidateDiagnosticLog.enabled())Log.i("ClickAgent","http_end id="+id+" method="+method+" route="+route
            +" status="+status+" elapsed_ms="+(SystemClock.elapsedRealtime()-started));
    }
}
