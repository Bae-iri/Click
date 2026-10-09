package com.click.data.remote;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.local.AuthTokenStore;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.domain.goal.GoalCatalogClassifier;
import com.click.domain.goal.RequirementReview;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in real server check. Uses a synthetic settings screen and never changes device settings. */
@RunWith(AndroidJUnit4.class)
public class LiveAgentConnectionTest {
    @Test public void typedGoalReachesRealRuntimeAndCanBeCancelled() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("liveAgent")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Log into the app before running the live agent check",
                new AuthTokenStore(context).hasRefreshToken());
        AgentServerContract contract = AgentServerContract.fromOpenApi(new JSONObject(AuthGateway.get("/openapi.json", null).body));
        GoalCatalogClassifier catalog;
        try (java.io.InputStream input = context.getAssets().open("mvp_goals.json")) {
            catalog = GoalCatalogClassifier.fromJson(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        RequirementReview review = new RequirementReview("글자 크기 키워줘");
        assertNull(review.confirm(catalog, contract));
        review.choose("phone");
        assertFalse(review.confirmed());
        JSONObject confirmed = review.confirm(catalog, contract);
        assertNotNull(confirmed);
        String session = UUID.randomUUID().toString();
        ScreenSnapshot screen = new ScreenSnapshot("com.android.settings", java.time.Instant.now().toString(),
                1080, 2400, Arrays.asList(node("title", "설정", false, 40), node("display", "디스플레이", true, 240)), 7);
        AgentTurnContract.Request start = new AgentTurnContract.Request(session, review.summary(), screen, null);
        start.acceptedGoal = confirmed;
        AgentTurnGateway gateway = new AgentTurnGateway(context);
        AuthenticatedHttpClient http = new AuthenticatedHttpClient(context);
        try {
            AgentTurnContract.Response response = send(gateway, start);
            assertNotNull(response.guideCommand);
            assertNotNull(response.metadata);
            assertEquals("ai_agent.runtime", response.metadata.getString("runtime"));
            assertTrue(response.guideCommand.message.length() > 0);
            android.util.Log.i("ClickLiveAgent", "runtime=" + response.metadata.getString("runtime")
                    + " status=" + response.taskStatus + " guide=" + response.guideCommand.message);
            AgentTurnContract.Response stopped = send(gateway, AgentTurnContract.Request.control(session,
                    response.sessionVersion, response.episodeId, "cancel_task", null));
            assertEquals("CANCELLED", stopped.taskStatus);
            android.util.Log.i("ClickLiveAgent", "cancel=CANCELLED");
        } finally {
            // Also clean up after assertion failures or a lost start response.
            try {
                JSONObject state = new JSONObject(http.get("/api/v1/agent/sessions/" + session).body);
                if (!"CANCELLED".equals(state.optString("status")) && !"COMPLETED".equals(state.optString("status")))
                    send(gateway, AgentTurnContract.Request.control(session, state.getInt("version"),
                            state.optString("episode_id", null), "cancel_task", null));
            } catch (ApiException error) { if (error.status != 404) throw error; }
            finally { gateway.close(); }
        }
    }
    private AccessibilityNodeSnapshot node(String id, String text, boolean clickable, int top) {
        return new AccessibilityNodeSnapshot(id, null, true, "com.android.settings", text, null,
                "android:id/title", "android.widget.TextView", null, new int[]{40, top, 900, top + 100},
                clickable, true, false, false, false, null, 7);
    }
    private AgentTurnContract.Response send(AgentTurnGateway gateway, AgentTurnContract.Request request) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AgentTurnContract.Response[] result = {null};
        Throwable[] error = {null};
        gateway.send(request, new AgentTurnGateway.Callback() {
            public void onSuccess(AgentTurnContract.Response response) { result[0] = response; completed.countDown(); }
            public void onFailure(Throwable failure) { error[0] = failure; completed.countDown(); }
        });
        assertTrue("Agent response timed out", completed.await(50, TimeUnit.SECONDS));
        if (error[0] != null) throw new AssertionError("Agent request failed: " + request.commandType(), error[0]);
        return result[0];
    }
}
