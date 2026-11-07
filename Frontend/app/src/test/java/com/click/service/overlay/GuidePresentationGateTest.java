package com.click.service.overlay;

import org.junit.Test;
import static org.junit.Assert.*;

public class GuidePresentationGateTest {
    @Test public void repeatedGuideAndResultDoNotReopenButExplicitRetryAndNewEpisodeDo() {
        GuidePresentationGate gate=new GuidePresentationGate();
        assertTrue(gate.accept("e1","guide","target"));
        assertFalse(gate.accept("e1","guide","target"));
        assertTrue(gate.accept("e1","result","prompt"));
        assertFalse(gate.accept("e1","result","prompt"));
        gate.clear();
        assertTrue(gate.accept("e1","result","prompt"));
        assertTrue(gate.accept("e2","result","prompt"));
    }
}
