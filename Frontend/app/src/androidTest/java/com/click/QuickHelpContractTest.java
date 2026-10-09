package com.click;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.local.QuickHelpSelectionStore;
import com.click.data.remote.AgentTurnContract;
import com.click.domain.goal.GoalCatalogClassifier;
import com.click.domain.goal.QuickHelpTask;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class QuickHelpContractTest {
    @Test public void queuedCardKeepsSelectionAndBuildsStartTaskWithoutResolvingAgain() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String requestId = UUID.randomUUID().toString();
        QuickHelpSelectionStore selections = new QuickHelpSelectionStore(context);
        try {
            selections.save(requestId, QuickHelpTask.FONT_SIZE.goalType);
            QuickHelpSelectionStore restored = new QuickHelpSelectionStore(context);
            assertNull(restored.get(UUID.randomUUID().toString()));
            StringBuilder json = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    context.getAssets().open("mvp_goals.json"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) json.append(line);
            }
            GoalCatalogClassifier.Match selection = GoalCatalogClassifier.fromJson(json.toString())
                    .executableGoal(restored.get(requestId));
            assertNotNull(selection);
            AgentTurnContract.Request request = new AgentTurnContract.Request(UUID.randomUUID().toString(),
                    QuickHelpTask.FONT_SIZE.requestText, null, null, selection);
            request.acceptedGoal = selection.toTypedGoalJson();
            JSONObject command = new JSONObject(request.toJson());
            assertEquals("start_task", command.getString("command_type"));
            assertEquals("settings.font_size.increase", command.getJSONObject("goal").getString("goal_type"));
            assertEquals("increase", command.getJSONObject("goal").getJSONObject("slots").getString("direction"));
            assertFalse(command.has("goal_resolution_id"));
            restored.remove(requestId);
            assertNull(selections.get(requestId));
        } finally { selections.remove(requestId); }
    }
}
