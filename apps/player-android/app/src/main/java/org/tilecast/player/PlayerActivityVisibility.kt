package org.tilecast.player

/** Process visibility: an old Activity can stop after its replacement starts. */
internal class PlayerActivityVisibility {
    private val started = mutableSetOf<Any>()

    val isForeground: Boolean get() = started.isNotEmpty()

    fun update(activity: Any, visible: Boolean): Boolean {
        if (visible) started.add(activity) else started.remove(activity)
        return isForeground
    }
}
