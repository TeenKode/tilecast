package org.tilecast.player.storage

import android.content.Context
import android.os.Environment
import android.system.Os
import android.system.OsConstants
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

    /** The live CAS path comes from nativeStatus, NOT from the saved preference. */
    fun activeUsbVolume(casDir: String?, volumes: List<Volume> = removableVolumes()): Volume? {
        if (casDir.isNullOrBlank()) return null
        val active = runCatching { File(casDir).canonicalFile }.getOrNull() ?: return null
        return volumes.firstOrNull { volume ->
            runCatching {
                File(File(volume.filesDir, "tilecast-player-media"), "cas").canonicalFile == active
            }.getOrDefault(false)
        }
    }

    fun internalCasDir(): File = File(File(app.filesDir, "player-core"), "cas")

    fun isInternalActive(casDir: String?): Boolean =
        !casDir.isNullOrBlank() &&
            runCatching { File(casDir).canonicalFile == internalCasDir().canonicalFile }.getOrDefault(false)

    enum class CleanupResult { CLEARED, EMPTY, NOT_USING_USB, UNSAFE_LAYOUT, FAILED }

    /**
     * The caller MUST hold PlayerCoreHost's mutex for the whole operation,
     * preventing stop/reopen while media is removed. A live native CAS status
     * and mounted USB enumeration are checked before each filesystem operation.
     * Never delete any private Core state other than cas/ and partial/.
     */
    fun clearInactiveInternalCache(liveCasDir: () -> String?): CleanupResult {
        fun safe(): Boolean {
            val actual = liveCasDir() ?: return false
            val volume = activeUsbVolume(actual) ?: return false
            val usbCas = File(File(volume.filesDir, "tilecast-player-media"), "cas")
            return canClearInactiveInternalCache(actual, internalCasDir(), listOf(usbCas))
        }
        if (!safe()) return CleanupResult.NOT_USING_USB
        val root = File(app.filesDir, "player-core")
        val targets = listOf(File(root, "cas"), File(root, "partial"))
        val validRoot = runCatching {
            isPlainDirectory(root) && root.canonicalFile == File(app.filesDir.canonicalFile, "player-core")
        }.getOrDefault(false)
        if (!validRoot) return CleanupResult.UNSAFE_LAYOUT
        var deleted = 0
        fun deleteTree(file: File): Boolean {
            if (!safe()) return false
            val mode = fileMode(file) ?: return false
            if (mode == MISSING) return true
            if (mode != OsConstants.S_IFREG && mode != OsConstants.S_IFDIR) return false
            if (!runCatching { file.canonicalPath.startsWith(root.canonicalPath + File.separator) }.getOrDefault(false)) return false
            if (mode == OsConstants.S_IFDIR) {
                val children = file.listFiles() ?: return false
                for (child in children) if (!deleteTree(child)) return false
            }
            if (!safe() || !file.delete()) return false
            deleted++
            return true
        }
        for (target in targets) {
            if (!safe()) return CleanupResult.NOT_USING_USB
            val mode = fileMode(target) ?: return CleanupResult.UNSAFE_LAYOUT
            if (mode == MISSING) continue
            if (mode != OsConstants.S_IFDIR) return CleanupResult.UNSAFE_LAYOUT
            if (!deleteTree(target)) return CleanupResult.FAILED
        }
        return if (deleted == 0) CleanupResult.EMPTY else CleanupResult.CLEARED
    }

    private fun isPlainDirectory(file: File): Boolean = fileMode(file) == OsConstants.S_IFDIR

    /** lstat detects dangling symlinks; File.exists() would silently skip them. */
    private fun fileMode(file: File): Int? = try {
        Os.lstat(file.absolutePath).st_mode and OsConstants.S_IFMT
    } catch (error: android.system.ErrnoException) {
        if (error.errno == OsConstants.ENOENT) MISSING else null
    }

    private companion object { const val MISSING = -1 }

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

/** Extra guard: only the exact active, Android-enumerated USB CAS is eligible. */
internal fun canClearInactiveInternalCache(
    actualCasDir: String?,
    internalCasDir: File,
    eligibleUsbCasDirs: List<File>,
): Boolean {
    if (actualCasDir.isNullOrBlank()) return false
    val actual = runCatching { File(actualCasDir).canonicalFile }.getOrNull() ?: return false
    if (runCatching { actual == internalCasDir.canonicalFile }.getOrDefault(false)) return false
    return eligibleUsbCasDirs.any { candidate ->
        runCatching { candidate.canonicalFile == actual }.getOrDefault(false)
    }
}
