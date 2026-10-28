package com.click.data.remote;

import java.io.IOException;

public final class ApiException extends IOException {
    public final String method;
    public final String path;
    public final int status;
    public final String conflictCode;
    public final String responseBody;
    public final String traceId;

    ApiException(String method, String path, HttpResult result) {
        super(method + " " + path + " -> HTTP " + result.status
                + (result.conflictCode == null ? "" : ", X-Conflict-Code=" + result.conflictCode)
                + ", response=" + (result.body == null || result.body.isEmpty() ? "<empty>" : result.body));
        this.method = method;
        this.path = path;
        this.status = result.status;
        this.conflictCode = result.conflictCode;
        this.responseBody = result.body;
        this.traceId = result.traceId;
    }
}
