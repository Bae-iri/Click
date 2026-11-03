package com.click.service.overlay;

import org.junit.Test;
import static org.junit.Assert.*;

public class AutomaticScrollSearchTest {
    @Test public void launcherSearchCanVisitEightPagesButNeverLoopsForever() {
        AutomaticScrollSearch search = new AutomaticScrollSearch(8);
        for (int i = 0; i < 8; i++) assertNotNull(search.next("launcher", "page" + i));
        assertNull(search.next("launcher", "page8"));
    }
    @Test public void unchangedScreenReversesOnceThenStops() {
        AutomaticScrollSearch search = new AutomaticScrollSearch();
        assertEquals(Boolean.TRUE, search.next("step", "same-screen"));
        assertEquals(Boolean.FALSE, search.next("step", "same-screen"));
        assertNull(search.next("step", "same-screen"));
        assertNull(search.next("step", "another-screen"));
    }

    @Test public void changedScreensStillHaveAFiniteBudgetAndNewStepsCanSearch() {
        AutomaticScrollSearch search = new AutomaticScrollSearch();
        for (int i = 0; i < 4; i++) assertEquals(Boolean.TRUE, search.next("step", "page" + i));
        assertNull(search.next("step", "page4"));
        assertEquals(Boolean.TRUE, search.next("next-step", "page4"));
        search.clear();
        assertEquals(Boolean.TRUE, search.next("step", "page0"));
    }

    @Test public void endOfListReversesAndAllowsPassingPreviouslySeenPages() {
        AutomaticScrollSearch search = new AutomaticScrollSearch();
        assertEquals(Boolean.TRUE, search.next("step", "middle"));
        assertEquals(Boolean.TRUE, search.next("step", "bottom"));
        search.reachedEnd(true);
        assertEquals(Boolean.FALSE, search.next("step", "bottom"));
        assertEquals(Boolean.FALSE, search.next("step", "middle"));
        search.reachedEnd(false);
        assertNull(search.next("step", "top"));
    }
}
