package org.tilecast.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.tilecast.player.core.CoreHostState
import org.tilecast.player.core.PlayerCoreHost
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.tilecast.player.storage.MediaCacheStorage
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
    val scope = rememberCoroutineScope()
    val internalCas = remember(context) { File(context.filesDir, "player-core/cas") }
    val internalPartial = remember(context) { File(context.filesDir, "player-core/partial") }
    val actualCas = (coreState as? CoreHostState.Ready)?.status?.takeIf { it.ok }?.casDir
    val internalRoot = File(context.filesDir, "player-core").canonicalFile
    val externalActive = actualCas != null && runCatching {
        val active = File(actualCas).canonicalFile
        active != internalCas.canonicalFile && !active.absolutePath.startsWith(internalRoot.absolutePath + File.separator)
    }.getOrDefault(false)
    var confirmCleanup by remember { mutableStateOf(false) }
    var cleanupRunning by remember { mutableStateOf(false) }
    var cleanupResult by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf(storage.selection()) }
    var volumes by remember { mutableStateOf(storage.removableVolumes()) }
    val usable = volumes.filter { it.freeBytes > 1024L * 1024 * 1024 }
    val selectedAvailable = usable.any { it.id == selection.volumeId }
    val configuredDescription = when (selection.mode) {
        "auto" -> if (usable.isNotEmpty()) "Automatic (USB ${usable.first().label} currently eligible)" else "Automatic (no eligible USB currently)"
        "usb" -> if (selectedAvailable) "USB (${usable.first { it.id == selection.volumeId }.label} currently eligible)" else "USB unavailable (internal fallback on next start)"
        else -> "Internal memory"
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Media cache storage", color = SignalText, style = MaterialTheme.typography.titleMedium)
        Text("Configured for next Core start: $configuredDescription", color = SignalMuted, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Actually active Core CAS: " + when {
                actualCas == null -> "not available (Core has not reported a ready cache)"
                externalActive -> "USB ($actualCas)"
                else -> "internal ($actualCas)"
            },
            color = if (actualCas == null) SignalWarning else SignalText,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("The configured selection only applies after a full process restart. If USB failed to open at startup, the actual Core path above remains internal. Removing USB during playback does not trigger a seamless fallback.", color = SignalWarning, style = MaterialTheme.typography.bodyMedium)
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
        if (externalActive && !cleanupRunning) {
            SignalOutlinedButton(onClick = { confirmCleanup = true; cleanupResult = null }) {
                Text("Clean up old internal media cache")
            }
        }
        if (confirmCleanup) {
            Text("Delete ONLY old internal CAS and incomplete downloads? This cannot be undone. Media may need to download again. The internal SQLite database, pairing, credentials and settings are preserved. Cleanup is available only while Native Core reports an active external CAS.", color = SignalWarning)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SignalOutlinedButton(onClick = { confirmCleanup = false }) { Text("Cancel") }
                SignalOutlinedButton(onClick = {
                    confirmCleanup = false
                    cleanupRunning = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            storage.cleanupOldInternalCache {
                                val ready = core.state.value as? CoreHostState.Ready
                                val path = ready?.status?.takeIf { it.ok }?.casDir ?: return@cleanupOldInternalCache false
                                runCatching {
                                    val active = File(path).canonicalFile.absolutePath
                                    !active.startsWith(internalRoot.absolutePath + File.separator) &&
                                        active == File(actualCas ?: "").canonicalFile.absolutePath
                                }.getOrDefault(false)
                            }
                        }
                        cleanupResult = result
                        cleanupRunning = false
                    }
                }) { Text("Confirm deletion") }
            }
        }
        if (cleanupRunning) Text("Cleaning old internal cache…", color = SignalMuted)
        cleanupResult?.let { Text(it, color = SignalMuted) }
        Text(
            "Reboot the TV or fully force-stop and reopen Tilecast to apply this choice. " +
                "Existing internal cached files are not moved or deleted and may continue using significant TV storage. Missing media is downloaded again when online. " +
                "Do not remove USB media during playback.",
            color = SignalMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
