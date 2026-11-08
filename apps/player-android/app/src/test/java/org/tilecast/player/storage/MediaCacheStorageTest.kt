package org.tilecast.player.storage

import java.io.File
import java.nio.file.Files
import android.system.OsConstants
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCacheStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun danglingSymlinkIsNeverClassifiedAsMissing() {
        val root = temporary.newFolder("cache")
        val dangling = File(root, "dangling")
        Files.createSymbolicLink(dangling.toPath(), File(root, "missing-target").toPath())
        assertFalse(dangling.exists()) // File.exists follows the broken link.
        assertEquals(OsConstants.S_IFLNK, cacheEntryMode(dangling))
        assertEquals(-1, cacheEntryMode(File(root, "genuinely-missing")))
    }

    private val usb = listOf("/storage/ABCD-1234/Android/data/org.tilecast.player/files", "/storage/5678-AAAA/Android/data/org.tilecast.player/files")

    @Test fun defaultsToInternal() {
        assertEquals(null, chooseMediaVolumeId(MediaCacheStorage.Selection("internal"), usb))
    }

    @Test fun autoUsesFirstAvailableUsb() {
        assertEquals(usb[0], chooseMediaVolumeId(MediaCacheStorage.Selection("auto"), usb))
        assertEquals(null, chooseMediaVolumeId(MediaCacheStorage.Selection("auto"), emptyList()))
    }

    @Test fun explicitUsbNeverSilentlySwitchesToAnotherVolume() {
        assertEquals(usb[1], chooseMediaVolumeId(MediaCacheStorage.Selection("usb", usb[1]), usb))
        assertEquals(null, chooseMediaVolumeId(MediaCacheStorage.Selection("usb", usb[1]), listOf(usb[0])))
    }

    @Test fun unknownModeFailsClosed() {
        assertEquals(null, chooseMediaVolumeId(MediaCacheStorage.Selection("unexpected", usb[0]), usb))
    }

    @Test fun inactiveInternalCleanupRequiresNativeCoreActuallyUsingEligibleUsb() {
        val internal = File("/data/user/0/org.tilecast.player/files/player-core/cas")
        val removable = File("/storage/ABCD-1234/Android/data/org.tilecast.player/files/tilecast-player-media/cas")
        val otherDrive = File("/storage/5678-AAAA/Android/data/org.tilecast.player/files/tilecast-player-media/cas")

        assertTrue(canClearInactiveInternalCache(removable.path, internal, listOf(removable)))
        // Saved USB preferences are not proof of a successful native CAS open.
        assertFalse(canClearInactiveInternalCache(internal.path, internal, listOf(removable)))
        assertFalse(canClearInactiveInternalCache(null, internal, listOf(removable)))
        assertFalse(canClearInactiveInternalCache("", internal, listOf(removable)))
        // Missing or unenumerated USB must not authorize deletion.
        assertFalse(canClearInactiveInternalCache(removable.path, internal, emptyList()))
        assertFalse(canClearInactiveInternalCache(otherDrive.path, internal, listOf(removable)))
    }

    @Test fun cleanupDoesNotAcceptAnInternalPathAsRegisteredUsb() {
        val internal = File("/data/user/0/org.tilecast.player/files/player-core/cas")
        assertFalse(canClearInactiveInternalCache(internal.path, internal, listOf(internal)))
    }
}
