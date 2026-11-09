package com.click.service.overlay;

import com.click.data.snapshot.NavigationState;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutomaticScrollSearchTest {
    private NavigationState frame(String key,int first,int last,int count) {
        return new NavigationState("list",NavigationState.Axis.VERTICAL,null,null,first,last,count,key);
    }
    @Test public void middleSettingsListFirstSearchesTowardTheBeginning() {
        AutomaticScrollSearch search=new AutomaticScrollSearch();
        assertFalse(search.plan("settings",frame("middle",10,15,40)).forward);
        assertFalse(search.plan("settings",frame("upper",4,9,40)).forward);
        assertTrue(search.plan("settings",frame("top",0,5,40)).forward);
        assertTrue(search.plan("settings",frame("middle",10,15,40)).forward);
        assertEquals(AutomaticScrollSearch.Status.FINISHED,search.plan("settings",frame("end",34,39,40)).status);
    }
    @Test public void genuinelyNewPagesAreNotAbandonedAfterFourOrEightScrolls() {
        AutomaticScrollSearch search=new AutomaticScrollSearch();
        for(int i=0;i<100;i++) {
            AutomaticScrollSearch.Plan plan=search.plan("long-list",frame("page"+i,i*5,i*5+4,1000));
            assertEquals(AutomaticScrollSearch.Status.MOVE,plan.status);assertTrue(plan.forward);
        }
    }
    @Test public void launcherUsesForwardPagingAndStopsOnARealWraparoundCycle() {
        AutomaticScrollSearch search=new AutomaticScrollSearch(AutomaticScrollSearch.Mode.LAUNCHER);
        for(String page:new String[]{"middle","right","last","first"}) {
            AutomaticScrollSearch.Plan p=search.plan("home",frame(page,-1,-1,-1));
            assertEquals(AutomaticScrollSearch.Status.MOVE,p.status);assertTrue(p.forward);
        }
        assertEquals("cycle_observed",search.plan("home",frame("middle",-1,-1,-1)).reason);
    }
    @Test public void unchangedFrameWaitsForObservationBeforeReversingAndDoesNotClaimCoverage() {
        AutomaticScrollSearch search=new AutomaticScrollSearch();
        assertFalse(search.plan("settings",frame("same",-1,-1,-1)).forward);
        assertEquals(AutomaticScrollSearch.Status.WAIT,search.plan("settings",frame("same",-1,-1,-1)).status);
        assertTrue(search.plan("settings",frame("same",-1,-1,-1)).forward);
        assertEquals(AutomaticScrollSearch.Status.WAIT,search.plan("settings",frame("same",-1,-1,-1)).status);
        assertEquals("no_progress",search.plan("settings",frame("same",-1,-1,-1)).reason);
    }
    @Test public void newScopeRestartsAndBacktrackingCanRevisitForwardPages() {
        AutomaticScrollSearch search=new AutomaticScrollSearch(AutomaticScrollSearch.Mode.LAUNCHER);
        search.plan("home",frame("middle",2,2,5));
        assertFalse(search.plan("home",frame("last",4,4,5)).forward);
        assertFalse(search.plan("home",frame("middle",-1,-1,-1)).forward);
        assertEquals(AutomaticScrollSearch.Status.MOVE,search.plan("other",frame("middle",-1,-1,-1)).status);
    }
    @Test public void launcherStartingAtLastPageStopsAtOppositeEndWithoutResweeping() {
        AutomaticScrollSearch search=new AutomaticScrollSearch(AutomaticScrollSearch.Mode.LAUNCHER);
        assertFalse(search.plan("home",frame("last",4,4,5)).forward);
        assertFalse(search.plan("home",frame("middle",2,2,5)).forward);
        assertEquals("both_ends_observed",search.plan("home",frame("first",0,0,5)).reason);
    }
}
