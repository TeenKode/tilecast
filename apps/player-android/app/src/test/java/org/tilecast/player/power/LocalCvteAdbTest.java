package org.tilecast.player.power;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Pure contract tests: real standby requires the physical television. */
public class LocalCvteAdbTest {
    @Test public void fixedCommandsCannotBlindlyTogglePower() {
        String sleep = LocalCvteAdb.fixedCommand(false);
        String wake = LocalCvteAdb.fixedCommand(true);
        for (String command : new String[]{sleep, wake}) {
            assertTrue(command.contains("fake_standby_switch"));
            assertTrue(command.contains("IS_FAKE_STANDBY_STATUS"));
            assertTrue(command.contains("input keyevent 26"));
            assertTrue(command.contains("POWER:UNAVAILABLE"));
            assertFalse(command.contains("keyevent 223"));
        }
        assertTrue(sleep.contains("= 1 ]"));
        assertTrue(wake.contains("= 0 ]"));
    }
    @Test public void onlyExactPowerConfirmationIsAccepted() {
        assertTrue(LocalCvteAdb.acknowledged("POWER:OK\n"));
        assertFalse(LocalCvteAdb.acknowledged("POWER:FAILED\n"));
        assertFalse(LocalCvteAdb.acknowledged("prefix POWER:OK"));
        assertFalse(LocalCvteAdb.acknowledged("POWER:UNAVAILABLE"));
    }
}
