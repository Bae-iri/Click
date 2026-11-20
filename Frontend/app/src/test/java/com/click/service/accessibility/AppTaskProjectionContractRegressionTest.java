package com.click.service.accessibility;

import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.AppTaskObservation;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.overlay.AppTaskPolicy;
import org.json.JSONObject;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static com.click.testing.AppTaskFixtures.*;

/**
 * The executor must reobserve using the exact locally sealed projection that
 * produced a proposal, without weakening full fingerprint or target checks.
 */
public class AppTaskProjectionContractRegressionTest {
    private static final String PKG="org.example.app";

    @Test public void resultLabelReorderingReobservesWithTheSameSealedContract()throws Exception {
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
        JSONObject reobserved=AppTaskObservation.recaptureLike(same,enriched);
        assertEquals(enriched.getString("fingerprint"),reobserved.getString("fingerprint"));
        assertTrue("Executor reuses the controller's projection instead of comparing unlike views",AppTaskPolicy.allowed(enriched,reobserved,tap,null));
    }

    @Test public void resultOnlyStaticTextIsPreservedDuringNativeReobservation()throws Exception {
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
        JSONObject reobserved=AppTaskObservation.recaptureLike(same,enriched);
        assertNotNull(AppTaskObservation.find(reobserved,"node_0_2"));
        assertTrue(AppTaskPolicy.allowed(enriched,reobserved,tap,null));

        ScreenSnapshot changed=screen(PKG,node(PKG,"node_0_1","검색","button"));
        assertFalse("A relied-on proof disappearing is still a real fingerprint change",AppTaskPolicy.allowed(enriched,AppTaskObservation.recaptureLike(changed,enriched),tap,null));
    }

    @Test public void absentResultLabelsKeepPlainControlWorkingAndGenuineScreenChangesRemainRejected()throws Exception {
        ScreenSnapshot before=screen(PKG,node(PKG,"node_0_1","검색","button"));
        JSONObject plain=AppTaskObservation.capture(before);
        JSONObject withAbsentProof=AppTaskObservation.capture(before,Set.of("아직 없는 결과"));
        JSONObject tap=action(plain,"tap","screen_changed");
        assertEquals(plain.getString("fingerprint"),withAbsentProof.getString("fingerprint"));
        assertTrue(AppTaskPolicy.allowed(withAbsentProof,plain,tap,null));

        JSONObject actuallyChanged=AppTaskObservation.capture(screen(PKG,node(PKG,"node_0_1","다른 항목","button")));
        assertFalse("Real stale-screen rejection remains required",AppTaskPolicy.allowed(plain,actuallyChanged,tap,null));
    }

    @Test public void projectionOptionsAreImmutableAndNeverEnterTheWireSchema()throws Exception {
        Set<String> labels=new HashSet<>(Arrays.asList("  디스플레이  ",null,"someone@example.com"));
        ScreenSnapshot same=screen(PKG,node(PKG,"node_0_1","알림","button"),node(PKG,"node_0_2","디스플레이","button"));
        JSONObject observation=AppTaskObservation.capture(same,labels);
        labels.clear();labels.add("알림");
        JSONObject fresh=AppTaskObservation.recaptureLike(same,observation);
        assertEquals("디스플레이",fresh.getJSONArray("nodes").getJSONObject(0).getString("label"));
        assertEquals(observation.getString("fingerprint"),fresh.getString("fingerprint"));
        Set<String> expected=Set.of("status","package","observation_id","captured_at_ms","keyboard_visible","width","height","nodes","fingerprint");
        Set<String> actual=new HashSet<>();observation.keys().forEachRemaining(actual::add);
        assertEquals(expected,actual);
        assertFalse(observation.toString().contains("ProjectionSpec"));
    }

    @Test public void copyingAnEnrichedWireObservationDoesNotInventItsMissingLocalContract()throws Exception {
        ScreenSnapshot same=screen(PKG,node(PKG,"node_0_1","알림","button"),node(PKG,"node_0_2","디스플레이","button"));
        JSONObject original=AppTaskObservation.capture(same,Set.of("디스플레이"));
        JSONObject wireCopy=new JSONObject(original.toString());
        JSONObject tap=action(original,"tap","screen_changed");
        assertFalse("A serialized view cannot reconstruct a trusted projection from arbitrary labels",AppTaskPolicy.allowed(wireCopy,AppTaskObservation.recaptureLike(same,wireCopy),tap,null));
        JSONObject plain=AppTaskObservation.capture(same);
        assertTrue("Legacy plain JSON remains compatible",AppTaskPolicy.allowed(plain,AppTaskObservation.recaptureLike(same,new JSONObject(plain.toString())),tap,null));
    }

    @Test public void changedSelectionGeometryAndProtectedScreensStillReject()throws Exception {
        AccessibilityNodeSnapshot original=node(PKG,"node_0_1","정렬","toggle",false);
        ScreenSnapshot before=screen(PKG,node(PKG,"node_0_0","검색","button"),original);
        JSONObject observation=AppTaskObservation.capture(before,Set.of("정렬"));
        JSONObject tap=action(observation,"tap","selection_changed");
        ScreenSnapshot selected=screen(PKG,node(PKG,"node_0_0","검색","button"),node(PKG,"node_0_1","정렬","toggle",true));
        assertFalse(AppTaskPolicy.allowed(observation,AppTaskObservation.recaptureLike(selected,observation),tap,null));
        AccessibilityNodeSnapshot moved=new AccessibilityNodeSnapshot("node_0_1",null,true,PKG,"정렬",null,PKG+":id/node_0_1","android.widget.Button",null,
            new int[]{20,30,410,130},true,true,false,false,true,null,42,false,null,null,false,false,false,false);
        assertFalse(AppTaskPolicy.allowed(observation,AppTaskObservation.recaptureLike(screen(PKG,node(PKG,"node_0_0","검색","button"),moved),observation),tap,null));
        assertFalse(AppTaskPolicy.allowed(observation,AppTaskObservation.recaptureLike(screen(PKG,node(PKG,"node_0_1","PRIVATE","password")),observation),tap,null));
    }
}
