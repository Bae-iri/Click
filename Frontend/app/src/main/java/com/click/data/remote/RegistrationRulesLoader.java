package com.click.data.remote;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.function.LongSupplier;
import javax.net.ssl.SSLException;

/** Reuses recently verified rules and retries only the read-only discovery request. */
final class RegistrationRulesLoader {
    interface Fetcher { HttpResult fetch() throws IOException; }

    private static final long CACHE_MS = 15 * 60 * 1000;
    private final Fetcher fetcher;
    private final LongSupplier clock;
    private RegistrationContract cached;
    private long loadedAt;

    RegistrationRulesLoader(Fetcher fetcher, LongSupplier clock) {
        this.fetcher = fetcher;
        this.clock = clock;
    }

    synchronized RegistrationContract load() throws IOException, JSONException {
        long age = clock.getAsLong() - loadedAt;
        if (cached != null && age >= 0 && age < CACHE_MS) return cached;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpResult result = fetcher.fetch();
                if (result.status < 200 || result.status >= 300) {
                    throw new ApiException("GET", "/openapi.json", result);
                }
                RegistrationContract contract = RegistrationContract.fromOpenApi(new JSONObject(result.body));
                cached = contract;
                loadedAt = clock.getAsLong();
                return contract;
            } catch (IOException error) {
                if (attempt == 1 || Thread.currentThread().isInterrupted() || !retryable(error)) throw error;
            }
        }
        throw new IOException("Registration rules unavailable");
    }

    private static boolean retryable(IOException error) {
        if (error instanceof ApiException) {
            int status = ((ApiException) error).status;
            return status == 408 || status == 502 || status == 503 || status == 504;
        }
        if (error instanceof SSLException) return false;
        return !(error instanceof InterruptedIOException) || error instanceof SocketTimeoutException;
    }
}
