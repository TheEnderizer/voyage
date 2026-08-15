package com.betteraudio.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

private const val READER_HOST = "epub.internal"
private const val READER_ORIGIN = "https://$READER_HOST/"

/**
 * A locked-down WebView that renders one EPUB spine item at a time, served entirely from the zip
 * archive (never the network) via [readEntry]. No JavaScript, no external network access — an
 * EPUB is untrusted content, so this is the whole security model: everything not inside the epub
 * itself gets a 404, and `blockNetworkLoads` refuses any request that somehow bypasses that.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class ReaderWebView(
    context: Context,
    private val readEntry: (String) -> ByteArray?,
    private val mimeTypeFor: (String) -> String,
    private val onFractionChanged: (Float) -> Unit,
    private val onTap: () -> Unit,
    private val onPageReady: () -> Unit
) : WebView(context) {

    var bgHex: String = "#000000"
    var fgHex: String = "#FFFFFF"
    var accentHex: String = "#FFA552"
    var fontSizePct: Int = 100
    /** Scroll fraction (0..1) to restore to once the next page finishes loading. Set by the
     *  caller (via [loadSpine]) before triggering the load. */
    var pendingRestoreFraction: Float = 0f

    /** Set while a size-change (e.g. rotation with the Activity NOT recreated) is settling — see
     *  [onSizeChanged]. Suppresses [onFractionChanged] so the transient scroll-range values
     *  Chromium reports mid-reflow never get written back as the "real" position. */
    private var suppressFractionCallback = false

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap()
            return false
        }
    })

    init {
        settings.javaScriptEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        settings.loadWithOverviewMode = false
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val url = request.url
                if (url.host != READER_HOST) return notFound()
                val path = url.path?.removePrefix("/").orEmpty()
                val bytes = readEntry(path) ?: return notFound()
                val mime = mimeTypeFor(path)
                val body = if (mime == "text/html") injectCss(bytes) else bytes
                return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(body))
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                restoreScroll()
                onPageReady()
            }
        }

        setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false // never consume — normal scrolling must keep working
        }
    }

    /** Loads [href] (OPF-directory-relative, matching `SpineItem.href`), restoring scroll to
     *  [restoreFraction] once loaded. */
    fun loadSpine(href: String, restoreFraction: Float) {
        pendingRestoreFraction = restoreFraction
        loadUrl(READER_ORIGIN + href.trimStart('/'))
    }

    /** Re-renders the current page (font-size change) while preserving [restoreFraction]. */
    fun reloadPreservingPosition(restoreFraction: Float) {
        pendingRestoreFraction = restoreFraction
        reload()
    }

    private fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun injectCss(bytes: ByteArray): ByteArray {
        val html = String(bytes, Charsets.UTF_8)
        val style = """
            <style>
              html, body { background:$bgHex !important; color:$fgHex !important; }
              body { font-size:$fontSizePct%; line-height:1.6; padding:16px 20px 40vh 20px; max-width:42em; margin:0 auto; }
              img, svg, video { max-width:100% !important; height:auto !important; }
              a { color:$accentHex; }
            </style>
        """.trimIndent()
        val headEnd = html.indexOf("</head>", ignoreCase = true)
        val injected = if (headEnd >= 0) html.substring(0, headEnd) + style + html.substring(headEnd)
                       else style + html
        return injected.toByteArray(Charsets.UTF_8)
    }

    /** Scroll restore runs post-layout, with one retry — images that finish decoding after
     *  `onPageFinished` can grow the content height and shift the true target offset. */
    private fun restoreScroll() {
        val target = pendingRestoreFraction
        post {
            applyFraction(target)
            postDelayed({ applyFraction(target) }, 150)
        }
    }

    private fun applyFraction(fraction: Float) {
        val maxScroll = (computeVerticalScrollRange() - computeVerticalScrollExtent()).coerceAtLeast(0)
        scrollTo(0, (fraction * maxScroll).toInt())
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (suppressFractionCallback) return
        val maxScroll = (computeVerticalScrollRange() - computeVerticalScrollExtent()).coerceAtLeast(1)
        onFractionChanged((t.toFloat() / maxScroll).coerceIn(0f, 1f))
    }

    /**
     * A resize in place — chiefly a rotation where the Activity is NOT recreated — reflows the
     * document at the new width asynchronously. `scrollY` itself doesn't move, but
     * `computeVerticalScrollRange()` keeps changing while Chromium relayouts land, so
     * [onScrollChanged] would otherwise report a series of wrong transient fractions and — since
     * the caller persists every fraction it's given — silently corrupt the saved reading
     * position. Capture the correct fraction right now (content geometry is still the pre-resize
     * one at this exact point, before the async reflow lands), suppress the callback, and reapply
     * it once layout has had a chance to settle — the same double-apply-with-delay [restoreScroll]
     * already uses for the equivalent post-load race.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw <= 0 || oldh <= 0 || (w == oldw && h == oldh)) return // first layout, not a resize
        val maxScroll = (computeVerticalScrollRange() - computeVerticalScrollExtent()).coerceAtLeast(1)
        val fraction = (scrollY.toFloat() / maxScroll).coerceIn(0f, 1f)
        pendingRestoreFraction = fraction
        suppressFractionCallback = true
        post {
            applyFraction(fraction)
            postDelayed({
                applyFraction(fraction)
                suppressFractionCallback = false
            }, 150)
        }
    }
}
