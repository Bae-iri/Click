package com.click.data.snapshot;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class TaskSuccessTest {
    @Test public void sharedGoalBoundEvidenceCases() throws Exception {
        try(java.io.InputStream in=getClass().getResourceAsStream("/task_success_cases.json")) {
            assertNotNull(in);
            JSONArray cases=new JSONArray(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            for(int i=0;i<cases.length();i++){
                JSONObject item=cases.getJSONObject(i);
                assertEquals(item.getString("name"),item.getBoolean("verified"),TaskSuccess.verified(item.getJSONObject("technical"),item.getString("goal_type")));
            }
        }
    }
}
