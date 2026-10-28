package com.click.data.remote;

final class HttpResult {
    final int status;
    final String body;
    final String conflictCode;
    final String traceId;

    HttpResult(int status, String body, String conflictCode) {
        this(status, body, conflictCode, null);
    }

    HttpResult(int status, String body, String conflictCode, String traceId) {
        this.status = status;
        this.body = body;
        this.conflictCode = conflictCode;
        this.traceId = traceId;
    }
}
