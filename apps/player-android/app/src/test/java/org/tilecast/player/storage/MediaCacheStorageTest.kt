package org.tilecast.player.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaCacheStorageTest {
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
}
