package com.click.service.accessibility;

import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.AppTaskObservation;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.overlay.AppTaskPolicy;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;
import static com.click.testing.AppTaskFixtures.*;

/**
 * Characterizes a pre-existing projection mismatch; these tests do not fix it.
 * Controller.capture uses result labels, whereas AppTaskExecutor recaptures the
 * identical current snapshot without them before AppTaskPolicy.allowed.
 */
public class AppTaskProjectionContractRegressionTest {
    private static final String PKG="org.example.app";

    @Test public void resultLabelReorderingAloneRejectsTheIdenticalScreenAtExecutorPolicyGate()throws Exception {
        ScreenSnapshot same=screen(PKG,node(PKG,"node_0_1","알림","button"),
            node(PKG,"node_0_2","디스플레이","button"));
        JSONObject plain=AppTaskObservation.capture(same);
        JSONObject enriched=AppTaskObservation.capture(same,Set.of("디스플레이"));
        JSONObject tap=action(enriched,"tap","screen_changed");

        assertEquals("The exact same physical observation is projected twice",plain.getString("observation_id"),enriched.getString("observation_id"));
        assertEquals(plain.getLong("captured_at_ms"),enriched.getLong("captured_at_ms"));
        assertEquals(2,plain.getJSONArray("nodes").length());
        assertEquals(2,enriched.getJSONArray("nodes").length());
        assertEquals("No semantic controls changed; their JSON ordering changed",AppTaskObservation.learningKey(plain),AppTaskObservation.learningKey(enriched));
        assertEquals("알림",plain.getJSONArray("nodes").getJSONObject(0).getString("label"));
        assertEquals("디스플레이",enriched.getJSONArray("nodes").getJSONObject(0).getString("label"));
        assertNotEquals(plain.getString("fingerprint"),enriched.getString("fingerprint"));

        assertTrue("Controller's equally enriched before/current views authorize the action",AppTaskPolicy.allowed(enriched,enriched,tap,null));
        assertTrue("Plain/plain control authorizes the same target and bounds",AppTaskPolicy.allowed(plain,plain,tap,null));
        assertFalse("Executor's default recapture rejects it without any physical screen change",AppTaskPolicy.allowed(enriched,plain,tap,null));
    }

    @Test public void resultOnlyStaticTextAlsoChangesTheFingerprintBeyondNodeOrdering()throws Exception {
        AccessibilityNodeSnapshot proof=new AccessibilityNodeSnapshot("node_0_2",null,true,PKG,
            "안내 결과",null,PKG+":id/result_text","android.widget.TextView",null,
            new int[]{10,180,400,240},false,true,false,false,false,null,42);
        ScreenSnapshot same=screen(PKG,node(PKG,"node_0_1","검색","button"),proof);
        JSONObject plain=AppTaskObservation.capture(same);
        JSONObject enriched=AppTaskObservation.capture(same,Set.of("안내 결과"));
        JSONObject tap=action(plain,"tap","screen_changed");

        assertEquals(1,plain.getJSONArray("nodes").length());
        assertEquals(2,enriched.getJSONArray("nodes").length());
        assertNull(AppTaskObservation.find(plain,"node_0_2"));
        assertNotNull(AppTaskObservation.find(enriched,"node_0_2"));
        assertEquals("text",AppTaskObservation.find(enriched,"node_0_2").getString("role"));
        assertNotEquals("Sorting alone cannot reconcile an extra proof node",AppTaskObservation.learningKey(plain),AppTaskObservation.learningKey(enriched));
        assertEquals(AppTaskObservation.find(plain,"node_0_1").toString(),AppTaskObservation.find(enriched,"node_0_1").toString());
        assertTrue(AppTaskPolicy.allowed(plain,plain,tap,null));
        assertTrue(AppTaskPolicy.allowed(enriched,enriched,tap,null));
        assertFalse("The executor compares two different projections of one unchanged snapshot",AppTaskPolicy.allowed(enriched,plain,tap,null));
    }

    @Test public void absentResultLabelsKeepPlainControlWorkingAndGenuineScreenChangesRemainRejected()throws Exception {
        ScreenSnapshot before=screen(PKG,node(PKG,"node_0_1","검색","button"));
        JSONObject plain=AppTaskObservation.capture(before);
        JSONObject withAbsentProof=AppTaskObservation.capture(before,Set.of("아직 없는 결과"));
        JSONObject tap=action(plain,"tap","screen_changed");
        assertEquals(plain.getString("fingerprint"),withAbsentProof.getString("fingerprint"));
        assertTrue(AppTaskPolicy.allowed(withAbsentProof,plain,tap,null));

        JSONObject actuallyChanged=AppTaskObservation.capture(screen(PKG,node(PKG,"node_0_1","다른 항목","button")));
        assertFalse("This characterization does not suggest weakening real stale-screen rejection",AppTaskPolicy.allowed(plain,actuallyChanged,tap,null));
    }
}
