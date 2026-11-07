package org.tilecast.player.runtime

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.tilecast.player.R

/**
 * Host-owned native player for live RTSP/RTMP streams.
 *
 * Intended for a positioned host-layer surface, rather than a WebView video
 * element. Each instance owns exactly one ExoPlayer and releases it when the
 * surface is destroyed. Never log source URLs: they may contain credentials.
 */
data class NativeStreamConfig(
    val url: String,
    val rtspTransport: String = "tcp",
    val muted: Boolean = true,
    val volume: Int = 100,
    val fit: String = "contain",
    val reconnectSeconds: Int = 5,
    val maxReconnectAttempts: Int = 0, // 0 means keep retrying
    val autoReconnect: Boolean = true,
) {
    fun valid(): Boolean {
        if (url.length !in 1..2048) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in setOf("rtsp", "rtmp", "rtmps") || uri.host.isNullOrBlank()) return false
        if (uri.fragment != null) return false
        return rtspTransport in setOf("tcp", "udp") &&
            fit in setOf("contain", "cover", "fill") &&
            volume in 0..100 &&
            reconnectSeconds in 1..300 &&
            maxReconnectAttempts in 0..1000
    }
}

@OptIn(UnstableApi::class)
class NativeStreamPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val handler = Handler(Looper.getMainLooper())
    private val playerView = inflate(context, R.layout.tc_native_stream_view, this)
        .findViewById<PlayerView>(R.id.tc_stream_player)
    private val player = ExoPlayer.Builder(context).build()
    private var config: NativeStreamConfig? = null
    private var visible = true
    private var hostMuted = false
    private var active = false
    private var destroyed = false
    private var reconnectAttempts = 0
    private var retry: Runnable? = null
    private var callback: ((String, String?) -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (destroyed) return
            if (state == Player.STATE_READY) {
                reconnectAttempts = 0
                callback?.invoke("stream-ready", null)
                callback?.invoke("loaded", null)
            } else if (state == Player.STATE_ENDED) {
                scheduleRetry("ended")
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!destroyed) scheduleRetry("playback_error")
        }
    }

    init {
        playerView.useController = false
        playerView.player = player
        player.addListener(listener)
    }

    fun apply(
        next: NativeStreamConfig,
        isVisible: Boolean,
        mutedByHost: Boolean,
        onEvent: (String, String?) -> Unit,
    ) {
        if (destroyed) return
        callback = onEvent
        if (!next.valid()) {
            callback?.invoke("failed", "bad_content")
            return
        }
        val replaced = config != next
        config = next
        visible = isVisible
        hostMuted = mutedByHost
        playerView.resizeMode = when (next.fit) {
            "cover" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        player.volume = if (hostMuted || next.muted || !visible) 0f else next.volume / 100f
        player.playWhenReady = visible
        if (replaced || !active) {
            reconnectAttempts = 0
            start()
        }
    }

    fun reload() {
        if (destroyed || config == null) return
        reconnectAttempts = 0
        start()
    }

    private fun start() {
        val stream = config ?: return
        cancelRetry()
        if (destroyed) return
        active = true
        try {
            val mediaItem = MediaItem.fromUri(stream.url)
            if (stream.url.startsWith("rtsp:", ignoreCase = true)) {
                val rtsp = RtspMediaSource.Factory()
                if (stream.rtspTransport == "tcp") rtsp.setForceUseRtpTcp()
                player.setMediaSource(rtsp.createMediaSource(mediaItem))
            } else {
                // media3-datasource-rtmp is auto-discovered by DefaultDataSource.
                player.setMediaItem(mediaItem)
            }
            player.prepare()
            player.playWhenReady = visible
        } catch (_: Exception) {
            scheduleRetry("bad_source")
        }
    }

    private fun scheduleRetry(reason: String) {
        val stream = config ?: return
        if (destroyed) return
        active = false
        cancelRetry()
        if (!stream.autoReconnect ||
            (stream.maxReconnectAttempts > 0 && reconnectAttempts >= stream.maxReconnectAttempts)
        ) {
            callback?.invoke("failed", reason)
            return
        }
        reconnectAttempts++
        val work = Runnable { if (!destroyed) start() }
        retry = work
        handler.postDelayed(work, stream.reconnectSeconds * 1000L)
    }

    private fun cancelRetry() {
        retry?.let(handler::removeCallbacks)
        retry = null
    }

    fun release() {
        if (destroyed) return
        destroyed = true
        cancelRetry()
        player.removeListener(listener)
        playerView.player = null
        player.release()
        callback = null
        config = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }
}
