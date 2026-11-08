package org.tilecast.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
        Text("Actual running cache location is not reported by Core. A restart is required after changing storage; startup may fall back to internal storage if USB cannot be opened.", color = SignalWarning, style = MaterialTheme.typography.bodyMedium)
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
            "Reboot the TV or fully force-stop and reopen Tilecast to apply this choice. " +
                "Existing internal cached files are not moved or deleted and may continue using significant TV storage. Missing media is downloaded again when online. " +
                "Do not remove USB media during playback.",
            color = SignalMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
