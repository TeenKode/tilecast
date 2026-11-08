package org.tilecast.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerActivityVisibilityTest {
    @Test fun stoppingOldActivityDoesNotHideItsReplacement() {
        val visibility = PlayerActivityVisibility()
        val old = Any()
        val replacement = Any()
        assertFalse(visibility.isForeground)
        assertTrue(visibility.update(old, true))
        assertTrue(visibility.update(replacement, true))
        assertTrue(visibility.update(old, false))
        assertFalse(visibility.update(replacement, false))
    }

    @Test fun resumeAndRepeatedStopsDoNotInflateTheVisibleCount() {
        val visibility = PlayerActivityVisibility()
        val activity = Any()
        assertTrue(visibility.update(activity, true))
        assertTrue(visibility.update(activity, true))
        assertFalse(visibility.update(activity, false))
        assertFalse(visibility.update(activity, false))
        assertTrue(visibility.update(activity, true))
        assertTrue(visibility.update(Any(), false))
    }
}
