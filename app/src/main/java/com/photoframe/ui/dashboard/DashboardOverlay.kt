package com.photoframe.ui.dashboard

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.net.URI

/** Passive, isolated web surface. Native gestures and controls are layered above it. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DashboardOverlay(url: String, dimPercent: Int, onReady: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    var attempt by remember(url) { mutableIntStateOf(0) }
    var ready by remember(url, attempt) { mutableStateOf(false) }
    var failed by remember(url, attempt) { mutableStateOf(false) }
    var browser by remember(url, attempt) { mutableStateOf<WebView?>(null) }
    val reportReady by rememberUpdatedState(onReady)
    LaunchedEffect(ready, failed) { reportReady(ready && !failed) }
    DisposableEffect(Unit) { onDispose { reportReady(false) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }

    DisposableEffect(lifecycle, browser) {
        val view = browser
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { resumed = true; view?.onResume() }
                Lifecycle.Event.ON_PAUSE -> { resumed = false; view?.onPause() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (!resumed) view?.onPause()
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Bounded initial load, then a quiet automatic retry. Never leave an error page over photos.
    LaunchedEffect(url, attempt, ready, failed, resumed) {
        if (!resumed) return@LaunchedEffect
        if (!ready && !failed) {
            delay(20_000)
            failed = true
            browser?.stopLoading()
        } else {
            delay(if (failed) 60_000 else 15 * 60_000L)
            attempt++ // also picks up server-side HTML/layout changes without an APK update
        }
    }
    val dim by animateFloatAsState(
        if (ready && !failed) dimPercent.coerceIn(0, 90) / 100f else 0f,
        label = "dashboard background",
    )
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        key(url, attempt) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    FrameLayout(context).apply {
                        var created: WebView? = null
                        try {
                            val web = WebView(context)
                            created = web
                            browser = web
                            web.setBackgroundColor(AndroidColor.TRANSPARENT)
                            web.isFocusable = false
                            web.isFocusableInTouchMode = false
                            web.settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = false
                                allowContentAccess = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                mediaPlaybackRequiresUserGesture = true
                                setSupportMultipleWindows(false)
                                javaScriptCanOpenWindowsAutomatically = false
                            }
                            web.webViewClient = object : WebViewClient() {
                                private var pageFailed = false
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                    !sameOrigin(url, request.url.toString())

                                override fun onPageFinished(view: WebView, pageUrl: String) {
                                    if (!pageFailed && !failed && sameOrigin(url, pageUrl)) ready = true
                                }
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame) { pageFailed = true; ready = false; failed = true }
                                }
                                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                    if (request.isForMainFrame) { pageFailed = true; ready = false; failed = true }
                                }
                                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                    pageFailed = true
                                    ready = false
                                    failed = true
                                    (view.parent as? ViewGroup)?.removeView(view)
                                    view.destroy()
                                    browser = null
                                    return true
                                }
                            }
                            addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                            web.loadUrl(url)
                        } catch (_: RuntimeException) {
                            // Some old Fire OS installations have no usable WebView provider.
                            removeAllViews()
                            created?.destroy()
                            browser = null
                            failed = true
                        }
                    }
                },
                update = { it.alpha = if (ready && !failed) 1f else 0f },
                onRelease = { holder ->
                    val web = holder.getChildAt(0) as? WebView
                    holder.removeAllViews()
                    web?.stopLoading()
                    web?.destroy()
                },
            )
        }
        if (!ready || failed) {
            Text(
                if (failed) "Dashboard unavailable · retrying automatically" else "Loading dashboard…",
                color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).padding(20.dp)
                    .background(Color.Black.copy(alpha = 0.65f)).padding(12.dp),
            )
        }
    }
}

internal fun sameOrigin(configured: String, destination: String): Boolean = runCatching {
    val a = URI(configured)
    val b = URI(destination)
    b.scheme.equals("https", true) && a.host.equals(b.host, true) &&
        (if (a.port == -1) 443 else a.port) == (if (b.port == -1) 443 else b.port) && b.rawUserInfo == null
}.getOrDefault(false)
