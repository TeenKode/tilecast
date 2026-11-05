package org.tilecast.player.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Renders untrusted Website/YouTube pages in separate host-owned WebViews.
 * These WebViews never receive the trusted-runtime JavaScript bridge or media grants.
 * The shared runtime owns the viewport and playback/failure policy.
 */
@Composable
fun RemoteWebLayer(
    surface: RemoteWebHostManager.Surface,
    onEvent: (surfaceId: String, generation: Long, kind: String, code: String?) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val browser = remember(context, surface.surfaceId, surface.generation) {
        RemoteWebBrowser(context, surface.surfaceId, surface.generation, onEvent)
    }
    DisposableEffect(browser) {
        onDispose { browser.dispose() }
    }
    val viewport = surface.viewportPx
    val width = with(density) { viewport.width.toDp() }
    val height = with(density) { viewport.height.toDp() }
    // A hidden surface is still preparing: destroying it here loops create/fail.
    val modifier = Modifier
        .offset { IntOffset(viewport.x, viewport.y) }
        .size(width, height)
        .clipToBounds()
        .alpha(if (surface.visible) 1f else 0f)
    AndroidView(
        factory = { browser.webView },
        update = { browser.update(surface) },
        modifier = modifier,
    )
}

/**
 * One browser per remote surface. Viewport and visibility updates never reload
 * the site; only a changed content descriptor or explicit reload count does.
 */
internal class RemoteWebBrowser(
    context: Context,
    private val surfaceId: String,
    private val generation: Long,
    private val onEvent: (String, Long, String, String?) -> Unit,
) {
    val webView: WebView = WebView(context)
    private var current: RemoteWebHostManager.Surface? = null
    private var disposed = false
    private var pageFailed = false
    private var pageReady = false

    init {
        webView.setBackgroundColor(Color.BLACK)
        with(webView.settings) {
            javaScriptEnabled = false
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (allowedNavigation(request.url.toString())) return false
                fail("navigation_blocked")
                return true
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (allowedNavigation(url)) return false
                fail("navigation_blocked")
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!disposed) {
                    pageFailed = false
                    pageReady = false
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (disposed || pageFailed || pageReady || !allowedNavigation(url.orEmpty())) return
                pageReady = true
                current?.page?.let { page ->
                    if (page.scrollX != 0 || page.scrollY != 0) view.scrollTo(page.scrollX, page.scrollY)
                }
                // HostRemoteWebSurface waits for both events before activation.
                event("loaded", null)
                event("stream-ready", null)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) fail("net_" + error.errorCode)
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode >= 400) fail("http_" + response.statusCode)
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                fail("ssl_error")
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                fail("process_terminated")
                // A remote process crash must not terminate the whole Tilecast app.
                return true
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun update(next: RemoteWebHostManager.Surface) {
        if (disposed) return
        val previous = current
        current = next
        val changed = previous == null || previous.page != next.page || previous.youTube != next.youTube
        if (changed) {
            with(webView.settings) {
                javaScriptEnabled = next.page?.javascriptEnabled ?: true
                domStorageEnabled = next.page?.domStorageEnabled ?: true
                next.page?.userAgent?.takeIf { it.isNotBlank() && it != "default" }?.let {
                    userAgentString = it
                }
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(
                webView, next.page?.cookiePolicy == "first_and_third_party",
            )
            webView.setBackgroundColor(
                runCatching { Color.parseColor(next.page?.backgroundColor ?: "#000000") }
                    .getOrDefault(Color.BLACK),
            )
            webView.setInitialScale(next.page?.zoomPercent ?: 100)
            val destination = pageUrl(next)
            if (destination == null || !allowedNavigation(destination)) {
                fail("bad_content")
                return
            }
            pageFailed = false
            pageReady = false
            webView.loadUrl(destination)
        } else if (previous != null && next.reloadCount != previous.reloadCount) {
            pageFailed = false
            pageReady = false
            webView.reload()
        }
    }

    private fun allowedNavigation(url: String): Boolean {
        val page = current?.page
        if (page != null) return RemoteWebHostManager.validPage(page.copy(url = url))
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host in setOf(
            "www.youtube-nocookie.com", "www.youtube.com", "youtube.com",
        ) && (uri.port == -1 || uri.port == 443)
    }

    private fun pageUrl(surface: RemoteWebHostManager.Surface): String? {
        surface.page?.let { return it.url }
        val youtube = surface.youTube ?: return null
        val prefix = if (youtube.videoId != null) {
            "https://www.youtube-nocookie.com/embed/" + Uri.encode(youtube.videoId)
        } else if (youtube.playlistId != null) {
            "https://www.youtube-nocookie.com/embed/videoseries?list=" + Uri.encode(youtube.playlistId)
        } else return null
        val separator = if (prefix.contains("?")) "&" else "?"
        val options = "autoplay=1&playsinline=1&controls=" +
            (if (youtube.controls) "1" else "0") +
            "&mute=" + (if (youtube.muted) "1" else "0") +
            "&start=" + youtube.startSeconds
        return prefix + separator + options
    }

    private fun fail(code: String) {
        if (disposed || pageFailed) return
        pageFailed = true
        event("failed", code.take(64))
    }

    private fun event(kind: String, code: String?) {
        if (!disposed) onEvent(surfaceId, generation, kind, code)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
    }
}
