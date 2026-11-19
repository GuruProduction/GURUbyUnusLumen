// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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
import com.unuslumen.app.domain.portal.PortalHeight
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

/** The bridge event the injected script sends whenever the document's height changes. */
internal const val HEIGHT_EVENT = "height"

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

    // The height the document reports, in CSS pixels. Kept beside
    // measuredHeight so the dp conversion happens once, in one place, against
    // the WebView's own density rather than the screen's.
    //
    // WHY THE DENSITY MATTERS AND WAS WRONG (issue 477). The old measurement did
    // `px / Resources.getSystem().displayMetrics.density`, which converts a CSS
    // pixel to a dp using the SCREEN's density. A WebView lays out in CSS pixels
    // at its own device-pixel ratio, and those are already the units Compose's
    // `.dp` wants once divided by the view's density. Dividing by the screen's
    // instead made every auto-measured portal about 2.75 times too short on a
    // 440dpi phone, so the content was clipped even when it had fully loaded.
    val viewDensity = remember { context.resources.displayMetrics.density }

    // Wire up the event bridge. The height report is consumed here and never
    // reaches the caller's handler: it is a layout signal, not a user event.
    LaunchedEffect(onPortalEvent, viewDensity) {
        bridge.onEvent = { name, payload ->
            if (name == HEIGHT_EVENT) {
                payload.toIntOrNull()?.let { cssPx ->
                    // The shared rule decides grow-versus-shrink and does the unit
                    // conversion, so the composable holds no policy of its own.
                    val applied = PortalHeight.next(measuredHeight, cssPx, viewDensity)
                    // A no-op write still costs a recomposition, and the script
                    // reports on every animation frame of a chart.
                    if (applied != measuredHeight) measuredHeight = applied
                }
            } else {
                onPortalEvent?.invoke(name, payload)
            }
        }
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

    // An initial measure, as a FALLBACK only.
    //
    // The document reports its own height through the bridge now (see the
    // injected script), and that is the path that works: it fires on every
    // layout change rather than once. This pass still exists because a document
    // whose script never runs — a strict CSP, a script error before the reporter
    // is defined — would otherwise sit at the default height forever. It runs
    // later than it used to, uses the same growth rule, and no longer divides by
    // the wrong density.
    LaunchedEffect(isLoaded, viewDensity) {
        if (!isLoaded) return@LaunchedEffect
        webView?.let { wv ->
            if (!isDestroyed.get()) {
                try {
                    // Later than the old 100ms, because the reporter is the real
                    // path and this only needs to catch a document that stayed
                    // silent. Short enough that a fallback is not a visible pause.
                    kotlinx.coroutines.delay(400)
                    wv.evaluateJavascript(
                        "(function() {" +
                            "var d=document.documentElement,b=document.body;" +
                            "if(!b) return 0;" +
                            "return Math.max(d?d.scrollHeight:0,b.scrollHeight,Math.ceil(b.getBoundingClientRect().height));" +
                        "})()"
                    ) { result ->
                        result?.toIntOrNull()?.let { cssPx ->
                            val applied = PortalHeight.next(measuredHeight, cssPx, viewDensity)
                            if (applied != measuredHeight) measuredHeight = applied
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

        // ====================================================================
        // Content height reporting (issue 477)
        // ====================================================================
        //
        // WHY THIS EXISTS AT ALL. The host used to measure the document ONCE,
        // 100ms after onPageFinished, and never again. Anything that arrived
        // later — an image over Tor, an iframe finishing layout, a Chart.js
        // canvas, a Mermaid diagram — was cut off at whatever height the
        // document happened to have at 100ms. A box sized with
        // `aspect-ratio: 16/9` needs its parent width resolved before it has a
        // height, so at 100ms it reported zero and the whole embed was clipped.
        //
        // WHAT WAS HERE BEFORE. A `reportHeight` that computed a height and then
        // discarded it inside an empty `if`, plus a MutationObserver wired to
        // that function. The observer fired on every DOM change and did nothing,
        // which is worse than no observer: it made the surface look handled.
        //
        // THREE MEASURES ARE COMPARED, and the largest wins. `body.scrollHeight`
        // reports scrollable content when the body has `overflow-y: auto`, which
        // this template sets, so on its own it can be short. `scrollHeight` on
        // the document element and the body's own border-box height cover the
        // other two cases.
        var lastReportedHeight = 0;
        var reportScheduled = false;

        function measuredContentHeight() {
            var doc = document.documentElement;
            var body = document.body;
            if (!body) return 0;
            return Math.max(
                doc ? doc.scrollHeight : 0,
                body.scrollHeight,
                Math.ceil(body.getBoundingClientRect().height)
            );
        }

        function reportHeight() {
            var height = measuredContentHeight();
            if (height <= 0) return;
            // An unchanged measurement is noise: every mutation would otherwise
            // send an IPC round trip that changes nothing.
            if (height === lastReportedHeight) return;
            lastReportedHeight = height;
            if (typeof PortalBridge !== 'undefined' && PortalBridge.sendEvent) {
                PortalBridge.sendEvent('height', String(height));
            }
        }

        // Coalesce a burst of mutations into one report on the next frame. A
        // Mermaid render alone mutates dozens of nodes.
        function scheduleReport() {
            if (reportScheduled) return;
            reportScheduled = true;
            (window.requestAnimationFrame || function (fn) { setTimeout(fn, 16); })(function () {
                reportScheduled = false;
                reportHeight();
            });
        }

        // Run extra JS
        $extraJs

        window.addEventListener('load', function () { reportHeight(); scheduleReport(); });

        // A DOM mutation is not the only layout change that matters. An image
        // finishing load, a font swapping in, an iframe laying out or a chart
        // resizing all change the rendered height with NO mutation at all, which
        // is exactly the class of content this bug was clipping. ResizeObserver
        // covers those, and characterData covers a text-only update to a node
        // that already exists, which the old observer also missed.
        if (typeof ResizeObserver !== 'undefined') {
            new ResizeObserver(scheduleReport).observe(document.body);
        }
        new MutationObserver(scheduleReport).observe(document.body, {
            childList: true,
            subtree: true,
            attributes: true,
            characterData: true
        });

        // Late arrivals that ResizeObserver does not see: fonts and images that
        // load after the initial layout settle.
        document.addEventListener('load', scheduleReport, true);
        if (document.fonts && document.fonts.ready) {
            document.fonts.ready.then(scheduleReport);
        }
        // One late sweep for anything that lands without any signal at all.
        setTimeout(reportHeight, 600);
        setTimeout(reportHeight, 1600);
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
