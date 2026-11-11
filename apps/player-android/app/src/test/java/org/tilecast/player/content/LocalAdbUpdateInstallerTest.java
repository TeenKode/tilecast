package org.tilecast.player.content;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Local ADB must never become a replacement for the Android 12+ system
 * installer, and it must never target devices below the Player's minSdk.
 * The actual sync transfer and package replacement need hardware testing.
 */
public class LocalAdbUpdateInstallerTest {
    @Test public void onlyOldSupportedAndroidReachesLocalAdb() {
        assertFalse(LocalAdbUpdateInstaller.supported(22));
        assertTrue(LocalAdbUpdateInstaller.supported(23));
        assertTrue(LocalAdbUpdateInstaller.supported(29));
        assertTrue(LocalAdbUpdateInstaller.supported(30));
        assertFalse(LocalAdbUpdateInstaller.supported(31));
        assertFalse(LocalAdbUpdateInstaller.supported(35));
    }
}
