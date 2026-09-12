package com.click.service.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class VoiceRequestQueueTest {
    @Test public void normalRequestsAreFifo() {
        VoiceRequestQueue queue = new VoiceRequestQueue();
        queue.offerNormal("first");
        queue.offerNormal("second");
        assertEquals("first", queue.poll().text);
        assertEquals("second", queue.poll().text);
    }

    @Test public void vipRunsBeforeNormal() {
        VoiceRequestQueue queue = new VoiceRequestQueue();
        queue.offerNormal("normal");
        queue.offerVip("stop");
        assertEquals(VoiceRequestQueue.Priority.VIP, queue.poll().priority);
        assertEquals("normal", queue.poll().text);
    }

    @Test public void replacementClearsWaitingNormalRequests() {
        VoiceRequestQueue queue = new VoiceRequestQueue();
        queue.offerNormal("old one");
        queue.offerNormal("old two");
        queue.clearNormal();
        assertNull(queue.poll());
    }
}
