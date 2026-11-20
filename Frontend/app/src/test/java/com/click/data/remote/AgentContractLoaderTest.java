package com.click.data.remote;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AgentContractLoaderTest {
    private JSONObject schema() throws Exception {
        try(var in=getClass().getResourceAsStream("/openapi.json")) {
            return new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    @Test public void repeatedOverlayDiscoveryReusesThePublicContractAndRefreshesNewFeaturesAfterExpiry() throws Exception {
        var fetch=mock(AgentContractLoader.Fetcher.class);var time=new AtomicLong();
        JSONObject old=schema();old.getJSONObject("paths").remove(AppGuideGateway.MEMORY_PATH);
        when(fetch.fetch()).thenReturn(new HttpResult(200,old.toString(),null),new HttpResult(200,schema().toString(),null));
        var loader=new AgentContractLoader(fetch,time::get);
        var first=loader.load();assertFalse(first.appTaskMemory);
        time.set(AgentContractLoader.CACHE_MS-1);assertSame(first,loader.load());verify(fetch).fetch();
        time.incrementAndGet();assertTrue(loader.load().appTaskMemory);verify(fetch,times(2)).fetch();
    }
    @Test public void expiryOrExplicitMismatchNeverFallsBackToStaleCapabilities() throws Exception {
        var fetch=mock(AgentContractLoader.Fetcher.class);var time=new AtomicLong();
        when(fetch.fetch()).thenReturn(new HttpResult(200,schema().toString(),null)).thenThrow(new java.io.IOException("offline"));
        var loader=new AgentContractLoader(fetch,time::get);loader.load();loader.invalidate();
        assertThrows(java.io.IOException.class,loader::load);assertThrows(java.io.IOException.class,loader::load);
        verify(fetch,times(3)).fetch();
    }
    @Test public void failedOrUnsupportedDiscoveryDoesNotPoisonTheNextRetry() throws Exception {
        var fetch=mock(AgentContractLoader.Fetcher.class);
        when(fetch.fetch()).thenReturn(new HttpResult(503,"{}",null),new HttpResult(200,schema().toString(),null));
        var loader=new AgentContractLoader(fetch,()->0L);
        assertThrows(ApiException.class,loader::load);assertTrue(loader.load().executionState);
        verify(fetch,times(2)).fetch();
    }
}
