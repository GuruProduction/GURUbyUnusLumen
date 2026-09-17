package com.unuslumen.app.presentation.components

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.unuslumen.app.preferences.domain.model.GuruTheme
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridge class for bidirectional communication between the WebView portal and Kotlin.
 * JavaScript in the portal can call `PortalBridge.sendEvent(name, payload)` to send
 * user interactions back to the app. The app can call `evaluateJavascript("PortalBridge.receiveCommand(cmd, data)")`
 * to push updates to the portal.
 */
class PortalBridge {
    var onEvent: ((name: String, payload: String) -> Unit)? = null

    @JavascriptInterface
    fun sendEvent(name: String, payload: String) {
        onEvent?.invoke(name, payload)
    }
}

/**
 * Composable that renders HTML/CSS/JS content in an inline WebView.
 * This is the core of the Portal system — it takes content from Guru and renders
 * it inline in the chat, with theme color injection and bidirectional communication.
 *
 * @param html The HTML content to render
 * @param css Optional additional CSS to inject
 * @param js Optional additional JavaScript to inject
 * @param height Suggested height in dp (null for auto-measure)
 * @param interactive Whether the portal accepts user input
 * @param guruTheme Current theme for color injection (null for defaults)
 * @param onPortalEvent Callback for user interactions from the portal
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PortalWebView(
    html: String,
    css: String = "",
    js: String = "",
    height: Int? = null,
    interactive: Boolean = true,
    guruTheme: GuruTheme? = null,
    onPortalEvent: ((name: String, payload: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val surfaceColor = MaterialTheme.colorScheme.surface
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val backgroundColor = MaterialTheme.colorScheme.background
    val errorColor = MaterialTheme.colorScheme.error

    var measuredHeight by remember { mutableIntStateOf(height ?: 300) }
    var isLoaded by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val bridge = remember { PortalBridge() }
    // AtomicBoolean — immediately visible to all lambdas, no Compose snapshot propagation delay.
    // MutableState writes from onReset/onDispose may not be visible to the update lambda
    // in the same recomposition frame, causing calls on a destroyed WebView.
    val isDestroyed = remember { AtomicBoolean(false) }
    // Track the last loaded html to avoid redundant reloads
    var lastLoadedHtml by remember { mutableStateOf("") }

    // Wire up the event bridge
    LaunchedEffect(onPortalEvent) {
        bridge.onEvent = onPortalEvent
    }

    // Inject theme colors as CSS custom properties
    val themeCss = remember(guruTheme, surfaceColor, surfaceVariantColor, onSurfaceColor,
        onSurfaceVariantColor, primaryColor, secondaryColor, tertiaryColor, backgroundColor, errorColor) {
        buildThemeCss(
            surfaceColor = surfaceColor,
            surfaceVariantColor = surfaceVariantColor,
            onSurfaceColor = onSurfaceColor,
            onSurfaceVariantColor = onSurfaceVariantColor,
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            tertiaryColor = tertiaryColor,
            backgroundColor = backgroundColor,
            errorColor = errorColor
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.loadsImagesAutomatically = true
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.safeBrowsingEnabled = false

                    // Viewport and scaling — make content actually fit the WebView width
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.textZoom = 100
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false

                    // Enable nested scrolling so the WebView scrolls inside LazyColumn
                    isNestedScrollingEnabled = true

                    // Prevent the parent LazyColumn from stealing touch events
                    setOnTouchListener { _, event ->
                        when (event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                parent.requestDisallowInterceptTouchEvent(true)
                            }
                            android.view.MotionEvent.ACTION_UP,
                            android.view.MotionEvent.ACTION_CANCEL -> {
                                parent.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                        false // Let the WebView handle the event
                    }

                    // Security: restrict WebView capabilities
                    setWebChromeClient(WebChromeClient())

                    // Add the bridge for bidirectional communication
                    addJavascriptInterface(bridge, "PortalBridge")

                    // Set transparent background
                    setBackgroundColor(AndroidColor.TRANSPARENT)

                    // Wire up the WebViewClient to signal when page loads
                    webViewClient = PortalWebViewClient { isLoaded = true }

                    isDestroyed.set(false)
                    webView = this
                }
            },
            update = { wv ->
                if (!isDestroyed.get()) {
                    val fullHtml = buildPortalHtml(html, themeCss, css, js)
                    if (fullHtml != lastLoadedHtml) {
                        lastLoadedHtml = fullHtml
                        isLoaded = false
                        try {
                            wv.loadDataWithBaseURL(
                                "file:///android_asset/portal/",
                                fullHtml,
                                "text/html",
                                "UTF-8",
                                null
                            )
                        } catch (_: Throwable) {
                            // WebView was destroyed between the AtomicBoolean check and this call.
                            // Chromium throws a plain Throwable (not RuntimeException) from AwContents
                            // when you touch a destroyed WebView.
                            isDestroyed.set(true)
                            webView = null
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(measuredHeight.dp),
            // No onReset — DisposableEffect.onDispose handles cleanup when the
            // composition is actually disposed. onReset fires during view recycling
            // at unpredictable times relative to update, causing calls on destroyed WebViews.
        )
    }

    // Measure content height after load
    LaunchedEffect(isLoaded) {
        if (!isLoaded) return@LaunchedEffect
        webView?.let { wv ->
            if (!isDestroyed.get()) {
                try {
                    // Small delay to let rendering settle
                    kotlinx.coroutines.delay(100)
                    wv.evaluateJavascript(
                        "(function() { return document.body ? document.body.scrollHeight : 0; })()"
                    ) { result ->
                        result?.toIntOrNull()?.let { h ->
                            // Convert px to dp-equivalent and add some padding
                            val dpHeight = (h / android.content.res.Resources.getSystem().displayMetrics.density).toInt()
                            measuredHeight = (dpHeight + 16).coerceIn(50, 2000) // Clamp between 50dp and 2000dp
                        }
                    }
                } catch (_: Throwable) {
                    // WebView destroyed between check and call
                }
            }
        }
    }

    // Clean up WebView on disposal
    DisposableEffect(Unit) {
        onDispose {
            webView?.let { wv ->
                if (!isDestroyed.get()) {
                    try {
                        if (wv.parent != null) {
                            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                        }
                        wv.removeJavascriptInterface("PortalBridge")
                        wv.destroy()
                    } catch (_: Throwable) {
                        // WebView already destroyed by another path
                    } finally {
                        isDestroyed.set(true)
                    }
                }
            }
        }
    }
}

/**
 * Build the complete HTML document for the portal.
 * Wraps the user content in a base template with theme variables,
 * pre-loaded JS libraries, and the portal bridge.
 */
private fun buildPortalHtml(
    content: String,
    themeCss: String,
    extraCss: String,
    extraJs: String
): String {

    return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
    <style>
        /* Reset and base styles */
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            font-size: 14px;
            line-height: 1.5;
            color: var(--on-surface);
            background: transparent;
            overflow-x: hidden;
            overflow-y: auto;
            -webkit-overflow-scrolling: touch;
            touch-action: auto;
            padding: 8px;
        }
        /* Theme variables */
        :root { $themeCss }
        /* Portal content styling */
        h1 { font-size: 1.5em; margin: 0.5em 0; color: var(--primary); }
        h2 { font-size: 1.3em; margin: 0.4em 0; color: var(--on-surface); }
        h3 { font-size: 1.1em; margin: 0.3em 0; color: var(--on-surface); }
        p { margin: 0.5em 0; }
        a { color: var(--primary); text-decoration: none; }
        a:hover { text-decoration: underline; }
        code { background: var(--surface-variant); padding: 2px 6px; border-radius: 4px; font-family: 'Courier New', monospace; font-size: 0.9em; }
        pre { background: var(--surface-variant); padding: 12px; border-radius: 8px; overflow-x: auto; margin: 0.5em 0; }
        pre code { background: transparent; padding: 0; }
        table { border-collapse: collapse; width: 100%; margin: 0.5em 0; }
        th, td { border: 1px solid var(--surface-variant); padding: 8px; text-align: left; }
        th { background: var(--surface-variant); color: var(--on-surface); font-weight: 600; }
        blockquote { border-left: 3px solid var(--primary); padding-left: 12px; margin: 0.5em 0; color: var(--on-surface-variant); }
        img { max-width: 100%; height: auto; border-radius: 8px; }
        button { background: var(--primary); color: var(--on-surface); border: none; padding: 8px 16px; border-radius: 8px; cursor: pointer; }
        button:hover { opacity: 0.9; }
        input, select, textarea { background: var(--surface); color: var(--on-surface); border: 1px solid var(--surface-variant); padding: 8px; border-radius: 8px; width: 100%; }
        /* Mermaid diagram styling */
        .mermaid { margin: 0.5em 0; }
        /* KaTeX math styling */
        .katex-display { margin: 0.5em 0; }
        /* Chart.js canvas styling */
        canvas { max-width: 100% !important; height: auto !important; }
        /* Extra CSS */
        $extraCss
    </style>
    <!-- KaTeX CSS for math rendering -->
    <link rel="stylesheet" href="file:///android_asset/portal/katex.min.css">
    <!-- highlight.js theme for code blocks -->
    <link rel="stylesheet" href="file:///android_asset/portal/highlight-theme.min.css">
    <!-- Pre-loaded JS libraries (real minified versions) -->
    <script src="file:///android_asset/portal/katex.min.js"></script>
    <script src="file:///android_asset/portal/auto-render.min.js"></script>
    <script src="file:///android_asset/portal/mermaid.min.js"></script>
    <script src="file:///android_asset/portal/chart.umd.min.js"></script>
    <script src="file:///android_asset/portal/highlight.min.js"></script>
    <script src="file:///android_asset/portal/marked.min.js"></script>
    <script src="file:///android_asset/portal/portal_utils.js"></script>
</head>
<body>
    <div id="portal-content">$content</div>
    <script>
        // Initialize Mermaid if present
        if (typeof mermaid !== 'undefined' && document.querySelector('.mermaid')) {
            mermaid.initialize({
                startOnLoad: true,
                theme: 'dark',
                themeVariables: {
                    primaryColor: getComputedStyle(document.documentElement).getPropertyValue('--primary').trim(),
                    primaryTextColor: getComputedStyle(document.documentElement).getPropertyValue('--on-surface').trim(),
                    primaryBorderColor: getComputedStyle(document.documentElement).getPropertyValue('--surface-variant').trim(),
                    lineColor: getComputedStyle(document.documentElement).getPropertyValue('--on-surface-variant').trim(),
                    secondaryColor: getComputedStyle(document.documentElement).getPropertyValue('--secondary').trim(),
                    tertiaryColor: getComputedStyle(document.documentElement).getPropertyValue('--tertiary').trim()
                }
            });
        }

        // Render KaTeX if present
        if (typeof renderMathInElement !== 'undefined') {
            renderMathInElement(document.getElementById('portal-content'), {
                delimiters: [
                    {left: '$$', right: '$$', display: true},
                    {left: '$', right: '$', display: false},
                    {left: '\\\\(', right: '\\\\)', display: false},
                    {left: '\\\\[', right: '\\\\]', display: true}
                ]
            });
        }

        // Highlight code blocks if present
        if (typeof hljs !== 'undefined') {
            document.querySelectorAll('pre code').forEach(function(block) {
                hljs.highlightElement(block);
            });
        }

        // Report content height to native
        function reportHeight() {
            var body = document.body;
            if (!body) return;
            var height = body.scrollHeight;
            if (typeof PortalBridge !== 'undefined') {
                // Height is measured via evaluateJavascript, but we also try the bridge
            }
        }

        // Run extra JS
        $extraJs

        // Report initial height
        window.addEventListener('load', reportHeight);
        // Report height after any dynamic content renders
        var observer = new MutationObserver(reportHeight);
        observer.observe(document.body, { childList: true, subtree: true });
    </script>
</body>
</html>
    """.trimIndent()
}

/**
 * Build CSS custom properties from the current Material Theme colors.
 * These are injected into the portal's :root so all CSS can reference them.
 */
private fun buildThemeCss(
    surfaceColor: androidx.compose.ui.graphics.Color,
    surfaceVariantColor: androidx.compose.ui.graphics.Color,
    onSurfaceColor: androidx.compose.ui.graphics.Color,
    onSurfaceVariantColor: androidx.compose.ui.graphics.Color,
    primaryColor: androidx.compose.ui.graphics.Color,
    secondaryColor: androidx.compose.ui.graphics.Color,
    tertiaryColor: androidx.compose.ui.graphics.Color,
    backgroundColor: androidx.compose.ui.graphics.Color,
    errorColor: androidx.compose.ui.graphics.Color
): String {
    fun colorToHex(color: androidx.compose.ui.graphics.Color): String {
        val r = (color.red * 255).toInt().coerceIn(0, 255)
        val g = (color.green * 255).toInt().coerceIn(0, 255)
        val b = (color.blue * 255).toInt().coerceIn(0, 255)
        val a = (color.alpha * 255).toInt().coerceIn(0, 255)
        return "#${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}${a.toString(16).padStart(2, '0')}".uppercase()
    }

    return """
        --surface: ${colorToHex(surfaceColor)};
        --surface-variant: ${colorToHex(surfaceVariantColor)};
        --on-surface: ${colorToHex(onSurfaceColor)};
        --on-surface-variant: ${colorToHex(onSurfaceVariantColor)};
        --primary: ${colorToHex(primaryColor)};
        --secondary: ${colorToHex(secondaryColor)};
        --tertiary: ${colorToHex(tertiaryColor)};
        --background: ${colorToHex(backgroundColor)};
        --error: ${colorToHex(errorColor)};
    """.trimIndent()
}

/**
 * Custom WebViewClient that handles page load events and height reporting.
 */
private class PortalWebViewClient(
    private val onPageLoaded: () -> Unit = {}
) : WebViewClient() {
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageLoaded()
        // Trigger height measurement after page loads
        view?.evaluateJavascript(
            "(function() { return document.body ? document.body.scrollHeight : 0; })()"
        ) { _ ->
            // Height is handled by the composable's LaunchedEffect
        }
    }
}
