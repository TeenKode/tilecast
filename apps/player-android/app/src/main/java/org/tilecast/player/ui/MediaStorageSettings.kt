package org.tilecast.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.tilecast.player.core.CoreHostState
import org.tilecast.player.core.PlayerCoreHost
import org.tilecast.player.storage.MediaCacheStorage
import org.tilecast.player.ui.theme.SignalButton
import org.tilecast.player.ui.theme.SignalMuted
import org.tilecast.player.ui.theme.SignalOutlinedButton
import org.tilecast.player.ui.theme.SignalText
import org.tilecast.player.ui.theme.SignalWarning

/** Local administrator-only storage settings; not a server-side screen policy. */
@Composable
fun MediaStorageSettings() {
    val context = LocalContext.current
    val storage = remember(context) { MediaCacheStorage(context) }
    val core = remember(context) { PlayerCoreHost.get(context) }
    val coreState by core.state.collectAsState()
    val activeCasDir = ((coreState as? CoreHostState.Ready)?.status)
        ?.takeIf { it.ok }?.casDir
    var selection by remember { mutableStateOf(storage.selection()) }
    var volumes by remember { mutableStateOf(storage.removableVolumes()) }
    var confirmCleanup by remember { mutableStateOf(false) }
    var cleanupRunning by remember { mutableStateOf(false) }
    var cleanupFeedback by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val usable = volumes.filter { it.freeBytes > 1024L * 1024 * 1024 }
    val selectedAvailable = usable.any { it.id == selection.volumeId }
    val configuredDescription = when (selection.mode) {
        "auto" -> if (usable.isNotEmpty()) "Automatic (USB ${usable.first().label} currently eligible)" else "Automatic (no eligible USB currently)"
        "usb" -> if (selectedAvailable) "USB (${usable.first { it.id == selection.volumeId }.label} currently eligible)" else "USB unavailable (internal fallback on next start)"
        else -> "Internal memory"
    }
    val activeUsb = storage.activeUsbVolume(activeCasDir, volumes)
    val runningDescription = when {
        activeCasDir == null -> "Core has not reported an active cache"
        storage.isInternalActive(activeCasDir) -> "Internal memory"
        activeUsb != null -> "USB ${activeUsb.label}"
        else -> "External path not recognized as currently mounted USB"
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Media cache storage", color = SignalText, style = MaterialTheme.typography.titleMedium)
        Text("Configured for next Core start: $configuredDescription", color = SignalMuted, style = MaterialTheme.typography.bodyMedium)
        Text("Actually running (native Core): $runningDescription", color = SignalText, style = MaterialTheme.typography.bodyMedium)
        Text("Active CAS directory: ${activeCasDir ?: "unavailable"}", color = SignalMuted, style = MaterialTheme.typography.bodyMedium)
        if (activeCasDir != null && storage.isInternalActive(activeCasDir) && selection.mode != "internal") {
            Text("USB is configured, but Core is actually using internal memory. The selected USB may have been missing or failed to open; check USB and fully restart Tilecast.", color = SignalWarning, style = MaterialTheme.typography.bodyMedium)
        }
        Text("Changes require force-stopping and relaunching the entire Player process or rebooting the TV. The maintenance 'Restart Tilecast' button only restarts the Activity, not the native Core.", color = SignalWarning, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SignalOutlinedButton(onClick = {
                storage.save("internal")
                selection = storage.selection()
            }) { Text("Internal memory") }
            SignalOutlinedButton(onClick = {
                storage.save("auto")
                selection = storage.selection()
            }) { Text("Automatic (USB first)") }
            SignalOutlinedButton(onClick = { volumes = storage.removableVolumes() }) { Text("Rescan USB") }
        }
        usable.forEach { volume ->
            SignalOutlinedButton(onClick = {
                storage.save("usb", volume.id)
                selection = storage.selection()
            }) {
                Text("Use USB ${volume.label} (${volume.freeBytes / (1024L * 1024 * 1024)} GiB free)")
            }
        }
        if (volumes.isEmpty()) {
            Text("No writable USB volume is exposed by Android to this app.", color = SignalMuted, style = MaterialTheme.typography.bodyMedium)
        } else if (usable.isEmpty()) {
            Text("USB needs more than 1 GiB free and must be writable.", color = SignalWarning, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "Existing internal cached files are not moved or removed when USB is selected. " +
                "The new USB cache downloads missing media again while online. " +
                "Do not remove USB media during playback.",
            color = SignalMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (activeUsb != null) {
            SignalOutlinedButton(onClick = { confirmCleanup = true }, enabled = !cleanupRunning) {
                Text(if (cleanupRunning) "Clearing internal cache…" else "Clear inactive internal media cache")
            }
        } else {
            Text("Internal cache cleanup is only available when native Core confirms that it is running on a currently mounted USB volume.", color = SignalMuted, style = MaterialTheme.typography.bodyMedium)
        }
        cleanupFeedback?.let { message ->
            Text(message, color = SignalWarning, style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (confirmCleanup) {
        AlertDialog(
            onDismissRequest = { confirmCleanup = false },
            title = { Text("Delete the old internal media cache?") },
            text = {
                Text(
                    "Only player-core/cas and player-core/partial in internal storage will be deleted. " +
                        "The active USB cache, SQLite database, pairing, credentials and settings are preserved. " +
                        "If USB is later removed or unavailable, offline playback may stop until the content is downloaded again. " +
                        "This cannot be undone."
                )
            },
            confirmButton = {
                SignalButton(onClick = {
                    confirmCleanup = false
                    cleanupRunning = true
                    cleanupFeedback = null
                    scope.launch {
                        // Core holds its lifecycle lock and re-reads JNI status
                        // before each deletion; no stale StateFlow snapshot.
                        val result = core.clearInactiveInternalMediaCache(storage)
                        cleanupRunning = false
                        cleanupFeedback = when (result) {
                            MediaCacheStorage.CleanupResult.CLEARED -> "Old internal CAS and partial downloads deleted; pairing and settings were kept."
                            MediaCacheStorage.CleanupResult.EMPTY -> "There was no old internal media cache to delete."
                            MediaCacheStorage.CleanupResult.NOT_USING_USB -> "Cleanup refused: native Core is not confirmed active on a currently mounted USB volume."
                            MediaCacheStorage.CleanupResult.UNSAFE_LAYOUT -> "Cleanup refused: unexpected cache filesystem layout or symbolic link."
                            MediaCacheStorage.CleanupResult.FAILED -> "Cleanup failed. Some old cached files may remain; the active USB cache and private state were not targeted."
                        }
                    }
                }) { Text("Delete old cache") }
            },
            dismissButton = {
                SignalOutlinedButton(onClick = { confirmCleanup = false }) { Text("Cancel") }
            },
        )
    }
}
