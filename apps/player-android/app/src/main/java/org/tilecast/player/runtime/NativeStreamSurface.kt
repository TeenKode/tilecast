package org.tilecast.player.runtime

import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tilecast.player.runtime.RemoteWebHostManager.Surface

/**
 * An internal presentation URI is not a network request. The trusted runtime
 * projects the server-validated descriptor as a host-layer Page surface; this
 * Android-only adapter reads its bounded stream configuration and plays it
 * directly through Media3.
 */
internal object NativeStreamEnvelope {
    private const val PREFIX = "https://tilecast-native-stream.invalid/v1/"
    private val JSON = Json { ignoreUnknownKeys = false }

    fun isStreamAddress(address: String): Boolean = address.startsWith(PREFIX)

    fun parse(address: String): NativeStreamConfig? {
        if (!isStreamAddress(address) || address.length > 2048) return null
        val encoded = address.removePrefix(PREFIX)
        if (!encoded.matches(Regex("[A-Za-z0-9_-]{20,2048}"))) return null
        val data = runCatching {
            Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull() ?: return null
        if (data.size > 1500) return null
        val obj = runCatching { JSON.parseToJsonElement(data.toString(Charsets.UTF_8)).jsonObject }
            .getOrNull() ?: return null
        val config = NativeStreamConfig(
            url = obj.string("url") ?: return null,
            rtspTransport = obj.string("rtspTransport") ?: "tcp",
            muted = obj.flag("muted") ?: true,
            volume = obj.number("volume") ?: 100,
            fit = obj.string("fit") ?: "contain",
            reconnectSeconds = obj.number("reconnectSeconds") ?: 5,
            maxReconnectAttempts = obj.number("maxReconnectAttempts") ?: 0,
            autoReconnect = obj.flag("autoReconnect") ?: true,
        )
        return config.takeIf { it.valid() }
    }

    private fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.number(key: String) = get(key)?.jsonPrimitive?.intOrNull
    private fun JsonObject.flag(key: String) = get(key)?.jsonPrimitive?.booleanOrNull
}

@Composable
internal fun NativeStreamSurface(
    surface: Surface,
    modifier: Modifier,
    emit: (kind: String, code: String?) -> Unit,
) {
    val stream = remember(surface.serial) {
        surface.page?.url?.let(NativeStreamEnvelope::parse)
    }
    if (stream == null) {
        LaunchedEffect(surface.serial) { emit("failed", "invalid_stream_configuration") }
        return
    }
    val latestEmit = rememberUpdatedState(emit)
    var lastReload by remember(surface.serial) { mutableIntStateOf(surface.reloadCount) }
    AndroidView(
        modifier = modifier,
        factory = { context -> NativeStreamPlayerView(context) },
        update = { view ->
            view.apply(stream, surface.visible, surface.muted) { event, code ->
                latestEmit.value(event, code)
            }
            if (lastReload != surface.reloadCount) {
                lastReload = surface.reloadCount
                view.reload()
            }
        },
        onRelease = { it.release() },
    )
}
