package com.click.data.remote;

import java.io.IOException;
import java.util.function.LongSupplier;
import org.json.JSONException;
import org.json.JSONObject;

/** Public capabilities only. Shared by overlay instances, never stores user state. */
final class AgentContractLoader {
    interface Fetcher { HttpResult fetch() throws IOException; }
    static final long CACHE_MS = 60_000;
    private final Fetcher fetcher;
    private final LongSupplier clock;
    private AgentServerContract cached;
    private long loadedAt;

    AgentContractLoader(Fetcher fetcher, LongSupplier clock) {
        this.fetcher = fetcher;
        this.clock = clock;
    }

    synchronized AgentServerContract load() throws IOException, JSONException {
        long age = clock.getAsLong() - loadedAt;
        if (cached != null && age >= 0 && age < CACHE_MS) return cached;
        cached = null;
        HttpResult result = fetcher.fetch();
        if (result.status < 200 || result.status >= 300)
            throw new ApiException("GET", "/openapi.json", result);
        AgentServerContract next = AgentServerContract.fromOpenApi(new JSONObject(result.body));
        if (!next.executionState || !next.userDecline)
            throw new ExecutionState.ContractMismatch(ExecutionState.ContractMismatch.SERVER_UPGRADE_REQUIRED);
        cached = next;
        loadedAt = clock.getAsLong();
        return next;
    }

    synchronized void invalidate() { cached = null; }
}
