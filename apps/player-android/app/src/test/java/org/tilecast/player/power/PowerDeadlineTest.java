package org.tilecast.player.power;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PowerDeadlineTest {
    @Test public void freshRequestsAreAllowed() {
        assertTrue(PowerDeadline.active(9000L, 1000L));
        assertTrue(PowerDeadline.active(10000L, 1000L));
    }
    @Test public void expiredRequestsAreRejected() {
        assertFalse(PowerDeadline.active(1000L, 1000L));
        assertFalse(PowerDeadline.active(1000L, 2000L));
    }
    @Test public void overflowAndFarFutureAreRejected() {
        assertFalse(PowerDeadline.active(Long.MAX_VALUE, 1L));
        assertFalse(PowerDeadline.active(Long.MIN_VALUE, 1L));
        assertFalse(PowerDeadline.active(100L, -1L));
        assertFalse(PowerDeadline.active(10001L, 1000L));
    }
}
