package com.click.ui.overlay;

import org.junit.Test;
import static org.junit.Assert.*;

public class FloatingPanelPositionTest {
    @Test public void keyboardMovesBottomPanelUpAndDismissalRestoresRestingPosition() {
        FloatingPanelPosition shown = FloatingPanelPosition.fit(500, 384, 24, 600);
        assertEquals(216, shown.y);
        assertEquals(600, shown.y + shown.height);
        FloatingPanelPosition hidden = FloatingPanelPosition.fit(500, 384, 24, 900);
        assertEquals(500, hidden.y);
        assertEquals(384, hidden.height);
    }

    @Test public void panelAlreadyAboveKeyboardDoesNotMove() {
        assertEquals(40, FloatingPanelPosition.fit(40, 384, 24, 600).y);
    }

    @Test public void shortViewportShrinksPanelAndBoundsStayValid() {
        FloatingPanelPosition shortScreen = FloatingPanelPosition.fit(500, 384, 24, 250);
        assertEquals(24, shortScreen.y);
        assertEquals(226, shortScreen.height);
        assertEquals(0, FloatingPanelPosition.fit(500, 384, 24, 0).height);
    }
}
