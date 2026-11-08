package org.tilecast.player.storage

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Selects a POSIX-accessible app-specific directory on a removable volume.
 * The native CAS requires regular file operations and atomic rename, so SAF
 * document URIs are deliberately not offered as CAS locations.
 *
 * Only directories returned by Android's getExternalFilesDirs() may be used.
 * No broad storage permission, manually entered path, or root access is needed.
 */
class MediaCacheStorage(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("tilecast-media-cache", Context.MODE_PRIVATE)

    data class Volume(val id: String, val label: String, val freeBytes: Long, val filesDir: File)

    data class Selection(val mode: String, val volumeId: String? = null)

    fun selection(): Selection = Selection(
        prefs.getString("mode", "internal").orEmpty(),
        prefs.getString("volume-id", null),
    )

    fun save(mode: String, volumeId: String? = null) {
        require(mode in setOf("internal", "auto", "usb"))
        prefs.edit().putString("mode", mode)
            .putString("volume-id", if (mode == "usb") volumeId else null)
            .apply()
    }

    /** Android may omit removable drives that it cannot expose to apps. */
    fun removableVolumes(): List<Volume> =
        app.getExternalFilesDirs(null)
            .filterNotNull()
            .filter { dir ->
                runCatching {
                    Environment.isExternalStorageRemovable(dir) &&
                        Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED &&
                        dir.isDirectory && dir.canWrite()
                }.getOrDefault(false)
            }
            .distinctBy { it.absolutePath }
            .map { dir ->
                val label = dir.absolutePath.substringBefore("/Android/data/")
                    .substringAfterLast('/').ifBlank { "Removable storage" }
                Volume(dir.absolutePath, label, dir.usableSpace, dir)
            }

    /**
     * Called only at cold Core startup. The database, pairing credentials,
     * configuration, and other private state remain in internal app storage.
     * Missing or unusable USB media falls back to the internal CAS.
     */
    fun resolvedMediaRoot(): File? {
        // Only eligible volumes participate in automatic selection, so a full
        // first USB drive cannot hide another drive with sufficient space.
        val volumes = removableVolumes().filter { it.freeBytes > 1024L * 1024 * 1024 }
        val wanted = chooseMediaVolumeId(selection(), volumes.map { it.id }) ?: return null
        val volume = volumes.firstOrNull { it.id == wanted } ?: return null
        val root = File(volume.filesDir, "tilecast-player-media")
        return root.takeIf {
            runCatching { (it.isDirectory || it.mkdirs()) && it.canWrite() }.getOrDefault(false)
        }
    }
}

/** Pure selection logic: a vanished explicit USB is never replaced by another one. */
internal fun chooseMediaVolumeId(
    selection: MediaCacheStorage.Selection,
    availableIds: List<String>,
): String? = when (selection.mode) {
    "auto" -> availableIds.firstOrNull()
    "usb" -> selection.volumeId?.takeIf { it in availableIds }
    else -> null
}
