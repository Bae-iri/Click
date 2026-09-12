package com.click.data.remote;

final class HttpResult {
    final int status;
    final String body;
    final String conflictCode;

    HttpResult(int status, String body, String conflictCode) {
        this.status = status;
        this.body = body;
        this.conflictCode = conflictCode;
    }
}
