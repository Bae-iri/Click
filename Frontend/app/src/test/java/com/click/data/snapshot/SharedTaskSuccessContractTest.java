package com.click.data.snapshot;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** Runs the identical public evidence fixtures on Android and Python. */
@RunWith(Parameterized.class)
public class SharedTaskSuccessContractTest {
    @Parameterized.Parameters(name="{0}") public static Collection<Object[]> cases() throws Exception {
        try(var input=SharedTaskSuccessContractTest.class.getResourceAsStream("/task_success_cases.json")) {
            assertNotNull(input);
            JSONArray cases=new JSONArray(new String(input.readAllBytes(),StandardCharsets.UTF_8));
            var data=new ArrayList<Object[]>();
            for(int i=0;i<cases.length();i++) {JSONObject c=cases.getJSONObject(i);data.add(new Object[]{c.getString("name"),c});}
            return data;
        }
    }
    private final JSONObject fixture;
    public SharedTaskSuccessContractTest(String name,JSONObject fixture) {this.fixture=fixture;}
    @Test public void evidenceMatchesTheSharedGoalContract() throws Exception {
        assertEquals(fixture.getString("name"),fixture.getBoolean("verified"),
            TaskSuccess.verified(fixture.getJSONObject("technical"),fixture.getString("goal_type")));
    }
}
