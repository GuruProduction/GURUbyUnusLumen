package com.unuslumen.app.presentation.components

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.unuslumen.app.preferences.domain.model.GuruTheme
import kotlinx.coroutines.delay
import java.io.File
import android.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridge for bidirectional communication between the canvas WebView and Kotlin.
 * JS calls PortalBridge.sendEvent(name, payload) to send interactions to native.
 * Native calls evaluateJavascript("PortalCanvas.receiveCommand(...)") to push commands to the canvas.
 */
class PortalCanvasBridge {
    var onEvent: ((name: String, payload: String) -> Unit)? = null

    @JavascriptInterface
    fun sendEvent(name: String, payload: String) {
        onEvent?.invoke(name, payload)
    }
}

/**
 * Full-screen WebView canvas that fills the entire screen edge to edge.
 * This IS the portal. Everything GURU renders lives on this canvas.
 * Chat, diagrams, charts, dashboards, forms — all painted as HTML.
 *
 * The canvas receives commands from native via evaluateJavascript:
 * - appendMessage(messageId, html, type) — add a new message element
 * - updateInProgress(messageId, html) — update a streaming element's content
 * - finalizeMessage(messageId, html) — replace streaming content with final rendered version
 * - clear() — wipe the canvas
 * - setTheme(cssVariables) — update theme colors
 * - scrollToBottom() — scroll to latest content
 *
 * The canvas sends events to native via PortalBridge.sendEvent:
 * - toolcall_tap(toolCallUuid) — user tapped a tool call
 * - note_click(noteId) — user tapped a note card
 * - task_click(taskId) — user tapped a task card
 * - event_click(eventId) — user tapped a calendar event card
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PortalCanvas(
    onPortalEvent: ((name: String, payload: String) -> Unit)? = null,
    onWebViewCreated: ((WebView) -> Unit)? = null,
    onCanvasReady: (() -> Unit)? = null,
    guruTheme: GuruTheme? = null,
    chatTextConfig: ChatTextConfig = ChatTextConfig.DEFAULT,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
    val surfaceVariantColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    val onSurfaceVariantColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    val primaryColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val secondaryColor = androidx.compose.material3.MaterialTheme.colorScheme.secondary
    val tertiaryColor = androidx.compose.material3.MaterialTheme.colorScheme.tertiary
    val backgroundColor = androidx.compose.material3.MaterialTheme.colorScheme.background
    val errorColor = androidx.compose.material3.MaterialTheme.colorScheme.error

    var webView by remember { mutableStateOf<WebView?>(null) }
    var isLoaded by remember { mutableStateOf(false) }
    val bridge = remember { PortalCanvasBridge() }
    val isDestroyed = remember { AtomicBoolean(false) }

    // Wire up the event bridge
    LaunchedEffect(onPortalEvent) {
        bridge.onEvent = onPortalEvent
    }

    // Notify when WebView is created and when canvas is loaded
    LaunchedEffect(webView) {
        webView?.let { onWebViewCreated?.invoke(it) }
    }
    LaunchedEffect(isLoaded) {
        if (isLoaded) onCanvasReady?.invoke()
    }

    // Inject theme colors and chat text settings as CSS custom properties
    val themeCss = remember(guruTheme, chatTextConfig, surfaceColor, surfaceVariantColor, onSurfaceColor,
        onSurfaceVariantColor, primaryColor, secondaryColor, tertiaryColor, backgroundColor, errorColor) {
        buildCanvasThemeCss(
            surfaceColor = surfaceColor,
            surfaceVariantColor = surfaceVariantColor,
            onSurfaceColor = onSurfaceColor,
            onSurfaceVariantColor = onSurfaceVariantColor,
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            tertiaryColor = tertiaryColor,
            backgroundColor = backgroundColor,
            errorColor = errorColor,
            chatTextConfig = chatTextConfig
        )
    }

    // Track last applied theme to avoid redundant JS calls
    var lastAppliedTheme by remember { mutableStateOf("") }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = false
                    settings.loadsImagesAutomatically = true
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.safeBrowsingEnabled = false

                    // Viewport
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.textZoom = 100

                    // Scrolling
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false

                    // Bridge
                    addJavascriptInterface(bridge, "PortalBridge")

                    // Transparent background so the logo behind the canvas shows through
                    setBackgroundColor(AndroidColor.TRANSPARENT)

                    // Web client
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isLoaded = true
                            lastAppliedTheme = themeCss
                            Log.d("PortalCanvas", "Canvas shell loaded")
                        }
                    }

                    setWebChromeClient(WebChromeClient())

                    isDestroyed.set(false)

                    // Build custom font @font-face declarations from guru_fonts/ directory
                    // These are injected as base64 data URIs so the WebView can load them
                    // without needing file:// access to app internal storage
                    val customFontCss = buildCustomFontFaceDeclarations(ctx)

                    // Load the canvas shell HTML with theme and custom fonts baked in
                    val shellHtml = buildCanvasShellHtml(themeCss, customFontCss)
                    loadDataWithBaseURL(
                        "file:///android_asset/portal/",
                        shellHtml,
                        "text/html",
                        "UTF-8",
                        null
                    )

                    webView = this
                }
            },
            update = { wv ->
                if (!isDestroyed.get() && isLoaded && themeCss != lastAppliedTheme) {
                    try {
                        val escapedCss = themeCss.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
                        wv.evaluateJavascript(
                            "if (typeof PortalCanvas !== 'undefined') { PortalCanvas.setTheme('$escapedCss'); }",
                            null
                        )
                        lastAppliedTheme = themeCss
                    } catch (_: Throwable) {}
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    // Clean up WebView on disposal
    DisposableEffect(Unit) {
        onDispose {
            webView?.let { wv ->
                if (!isDestroyed.get()) {
                    try {
                        wv.removeJavascriptInterface("PortalBridge")
                        wv.destroy()
                    } catch (_: Throwable) {
                    } finally {
                        isDestroyed.set(true)
                    }
                }
            }
        }
    }
}

/**
 * Send a command to the canvas WebView.
 * Call this from the ViewModel or screen to push updates.
 */
fun PortalCanvas_sendCommand(
    webView: WebView?,
    command: String,
    vararg args: String
) {
    if (webView == null) return
    val jsArgs = args.joinToString(", ") { "'${it.replace("'", "\\'").replace("\n", "\\n").replace("\r", "")}'" }
    val js = "if (typeof PortalCanvas !== 'undefined') { PortalCanvas.$command($jsArgs); }"
    try {
        webView.evaluateJavascript(js, null)
    } catch (e: Exception) {
        Log.w("PortalCanvas", "Failed to send command $command: ${e.message}")
    }
}

/**
 * Build the canvas shell HTML — the base document that loads all JS libraries
 * and creates the canvas container div.
 */
private fun buildCanvasShellHtml(themeCss: String, customFontCss: String = ""): String {
    return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        /* Handwriting fonts for chat messages — built-in portal assets */
        @font-face {
            font-family: 'OriginalSalmon';
            src: url('file:///android_asset/portal/original_salmon.otf') format('opentype');
        }
        @font-face {
            font-family: 'MiracleDays';
            src: url('file:///android_asset/portal/miracle_days.otf') format('opentype');
        }
        @font-face {
            font-family: 'HugMeTight';
            src: url('file:///android_asset/portal/hug_me_tight.ttf') format('truetype');
        }
        @font-face {
            font-family: 'CaviarDreams';
            src: url('file:///android_asset/portal/caviar_dreams.ttf') format('truetype');
            font-weight: normal;
        }
        @font-face {
            font-family: 'CaviarDreams';
            src: url('file:///android_asset/portal/caviar_dreams_bold.ttf') format('truetype');
            font-weight: bold;
        }
        @font-face {
            font-family: 'MadeTommySoft';
            src: url('file:///android_asset/portal/made_tommy_soft.otf') format('opentype');
            font-weight: normal;
        }
        @font-face {
            font-family: 'MadeTommySoft';
            src: url('file:///android_asset/portal/made_tommy_soft_bold.otf') format('opentype');
            font-weight: bold;
        }
        @font-face {
            font-family: 'Abuget';
            src: url('file:///android_asset/portal/abuget.ttf') format('truetype');
        }
        @font-face {
            font-family: 'Inter';
            src: url('file:///android_asset/portal/inter_regular.ttf') format('truetype');
            font-weight: normal;
        }
        @font-face {
            font-family: 'Inter';
            src: url('file:///android_asset/portal/inter_bold.ttf') format('truetype');
            font-weight: bold;
        }
        /* Custom fonts from guru_fonts/ directory — injected as base64 data URIs */
        $customFontCss
        html {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            font-size: 15px;
            line-height: 1.5;
            color: var(--on-surface);
            background: transparent;
            overflow-y: auto;
            overflow-x: hidden;
            -webkit-overflow-scrolling: touch;
            width: 100%;
            height: 100%;
        }
        body {
            min-height: 100%;
            display: flex;
            flex-direction: column;
        }
        #portal-canvas {
            position: relative;
            z-index: 1;
            width: 100%;
            padding: 0 12px;
            flex: 1;
            display: flex;
            flex-direction: column;
            justify-content: flex-end;
        }
        :root { $themeCss }

        /* Message styles */
        .message {
            margin: 2px 0;
            position: relative;
        }
        .message.user {
            text-align: right;
            padding: 4px 8px;
            margin-left: 40px;
            margin-right: 4px;
            color: var(--on-surface);
        }
        .message.assistant {
            padding: 4px 2px;
            color: var(--on-surface);
        }
        /* Meta-row: label + timestamp, hairline underneath */
        .meta-row {
            margin-bottom: 3px;
        }
        .message.assistant .meta-label {
            font-family: -apple-system, sans-serif;
            font-weight: 700;
            font-size: 0.85em;
            color: #2B241C;
            font-variant: small-caps;
            letter-spacing: 2px;
        }
        .message.user .meta-label {
            font-family: -apple-system, sans-serif;
            font-weight: 600;
            font-size: 0.85em;
            color: #2B241C;
            font-variant: small-caps;
            letter-spacing: 2px;
        }
        .meta-time {
            font-family: -apple-system, sans-serif;
            font-size: 0.85em;
            color: #2B241C;
            opacity: 0.5;
            margin-left: 0.5em;
            font-variant: small-caps;
            letter-spacing: 2px;
        }
        .meta-hairline {
            width: 28px;
            height: 1px;
            background: #2B241C;
            opacity: 0.18;
            margin-bottom: 7px;
        }
        .message.user .user-text {
            font-weight: 400;
            color: var(--on-surface);
            opacity: 0.85;
        }
        .message.assistant .markdown-content {
            font-size: 1.02em;
            color: #000000;
            font-weight: 500;
        }
        /* Outcome-tinted ink — GURU's text shifts colour based on what happened */
        .message.assistant.outcome-failed .markdown-content,
        .message.assistant.outcome-failed .streaming-content {
            color: #8B0000;
        }
        .message.assistant.outcome-success .markdown-content,
        .message.assistant.outcome-success .streaming-content {
            color: #0D6B3B;
        }

        .message.streaming .streaming-cursor {
            display: inline-block;
            width: 2px;
            height: 1em;
            background: var(--primary);
            margin-left: 2px;
            animation: blink 0.8s step-end infinite;
            vertical-align: text-bottom;
        }
        .streaming-content {
            white-space: pre-wrap;
            word-wrap: break-word;
        }
        @keyframes blink {
            0%, 100% { opacity: 1; }
            50% { opacity: 0; }
        }
        .message.toolcall {
            padding: 6px 12px;
            background: var(--secondary-container, var(--surface-variant));
            border-radius: 8px;
            margin: 4px 0;
            cursor: pointer;
            display: flex;
            align-items: center;
            gap: 6px;
            font-size: 0.85em;
            font-family: 'Courier New', monospace;
            color: var(--on-surface);
            opacity: 0.9;
        }
        .message.toolcall .status-dot {
            width: 8px;
            height: 8px;
            border-radius: 50%;
            flex-shrink: 0;
        }
        .message.toolcall .status-dot.running {
            background: #FFA726;
            animation: pulse 0.5s ease-in-out infinite alternate;
        }
        .message.toolcall .status-dot.done { background: #4CAF50; }
        .message.toolcall .status-dot.failed { background: #EF5350; }
        @keyframes pulse {
            from { opacity: 0.3; }
            to { opacity: 1; }
        }

        /* Grouped tool call card — three-tier drill-down */
        .toolcall-group-card {
            margin: 8px 4px;
            padding: 12px 14px;
            background: rgba(180, 160, 120, 0.08);
            border: 1px solid rgba(218, 165, 32, 0.25);
            border-radius: 12px;
            -webkit-backdrop-filter: blur(6px);
            backdrop-filter: blur(6px);
        }
        .toolcall-group-card.failed {
            border-color: rgba(239, 83, 80, 0.3);
        }
        .toolcall-group-header {
            display: flex;
            align-items: center;
            gap: 10px;
            cursor: pointer;
            user-select: none;
            min-height: 32px;
        }
        .grouped-card-status {
            width: 16px;
            height: 16px;
            flex-shrink: 0;
            border-radius: 50%;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 12px;
            line-height: 1;
        }
        .grouped-card-status.running {
            border: 2px solid #DAA520;
            border-top-color: transparent;
            animation: tool-progress-spin 0.8s linear infinite;
        }
        .grouped-card-status.done::after {
            content: '✓';
            color: #DAA520;
            font-weight: 700;
        }
        .grouped-card-status.failed::after {
            content: '✕';
            color: var(--error);
            font-weight: 700;
        }
        @keyframes tool-progress-spin {
            to { transform: rotate(360deg); }
        }
        .grouped-summary {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            font-size: 1.05em;
            color: var(--on-surface);
            flex: 1;
            font-weight: 600;
        }
        .grouped-count-badge {
            background: rgba(218, 165, 32, 0.2);
            color: #DAA520;
            font-size: 0.7em;
            font-weight: 600;
            padding: 2px 7px;
            border-radius: 8px;
            flex-shrink: 0;
        }
        .grouped-expand-arrow {
            font-size: 0.7em;
            color: var(--on-surface-variant);
            transition: transform 0.25s ease;
            flex-shrink: 0;
        }
        .toolcall-group-card.expanded .grouped-expand-arrow {
            transform: rotate(180deg);
        }

        /* Tier 2 — list of rows, hidden by default, shown when card is expanded */
        .grouped-list-body {
            display: none;
            margin-top: 10px;
            padding-top: 4px;
        }
        .toolcall-group-card.expanded .grouped-list-body {
            display: block;
        }
        .grouped-tool-row {
            margin: 6px 0;
            padding: 0;
        }
        .row-header {
            display: flex;
            align-items: center;
            gap: 8px;
            padding: 8px 6px;
            border-radius: 8px;
            cursor: pointer;
            user-select: none;
            background: rgba(180, 160, 120, 0.05);
            min-height: 32px;
        }
        .row-header:hover { background: rgba(180, 160, 120, 0.1); }
        .grouped-row-icon {
            width: 18px;
            height: 18px;
            flex-shrink: 0;
            background-size: contain;
            background-repeat: no-repeat;
            background-position: center;
        }
        .grouped-row-icon[data-empty="true"] {
            background: none;
        }
        .grouped-row-status {
            width: 10px;
            height: 10px;
            border-radius: 50%;
            flex-shrink: 0;
        }
        .grouped-row-status.done { background: #DAA520; }
        .grouped-row-status.failed { background: #EF5350; }
        .grouped-row-status.running {
            border: 2px solid #DAA520;
            border-top-color: transparent;
            animation: tool-progress-spin 0.8s linear infinite;
        }
        .row-name {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            color: var(--on-surface);
            font-weight: 600;
            font-size: 0.95em;
            flex-shrink: 0;
        }
        .row-preview {
            color: var(--on-surface-variant);
            opacity: 0.75;
            font-size: 0.82em;
            flex: 1;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
            margin-left: 6px;
        }
        .row-arrow {
            font-size: 0.7em;
            color: var(--on-surface-variant);
            transition: transform 0.25s ease;
            flex-shrink: 0;
        }
        .grouped-tool-row.expanded .row-arrow {
            transform: rotate(180deg);
        }

        /* Tier 3 — full verbose output, hidden by default, shown when row is expanded */
        .row-deep-body {
            display: none;
            margin-left: 22px;
            padding-left: 12px;
            border-left: 1px solid rgba(180, 160, 120, 0.3);
            margin-top: 6px;
        }
        .grouped-tool-row.expanded .row-deep-body {
            display: block;
        }
        .row-deep-body-inner {
            padding: 8px 10px;
            background: rgba(180, 160, 120, 0.05);
            border-radius: 8px;
            max-height: 50vh;
            overflow-y: auto;
            -webkit-overflow-scrolling: touch;
        }
        .expanded-result-card {
            background: rgba(180, 160, 120, 0.05);
            border-radius: 8px;
            padding: 8px 10px;
            margin-bottom: 6px;
        }
        .expanded-result-title {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            font-weight: 600;
            font-size: 0.95em;
            color: var(--on-surface);
            margin-bottom: 2px;
        }
        .expanded-result-body {
            font-size: 0.88em;
            color: var(--on-surface-variant);
            margin-bottom: 2px;
            white-space: pre-wrap;
            word-wrap: break-word;
        }
        .expanded-result-subtask {
            font-size: 0.85em;
            color: var(--on-surface-variant);
            opacity: 0.85;
            margin-bottom: 2px;
        }
        .row-verbose-raw,
        .row-verbose-empty,
        .row-verbose-running {
            font-size: 0.88em;
            color: var(--on-surface-variant);
            padding: 8px 10px;
            white-space: pre-wrap;
            word-wrap: break-word;
            font-family: 'Courier New', monospace;
        }
        .row-verbose-running { color: #FFA726; }
        .row-verbose-empty { opacity: 0.4; }
        .message.portal-content {
            margin: 8px 0;
            padding: 0;
            border-radius: 12px;
            overflow: hidden;
            width: 100%;
        }
        .message.luxify-question, .message.luxify-skill {
            margin: 8px 0;
            padding: 12px;
            background: var(--surface-variant);
            border-radius: 12px;
        }

        /* Thinking block */
        .thinking-block {
            margin: 4px 0;
            padding: 8px 12px;
            background: var(--surface-variant);
            border-radius: 8px;
            font-size: 0.85em;
            color: var(--on-surface-variant);
            opacity: 0.8;
        }
        .thinking-block summary {
            cursor: pointer;
            font-weight: 600;
        }
        .thinking-block[open] summary {
            margin-bottom: 6px;
        }

        /* Markdown content styles */
        h1 { font-size: 1.4em; margin: 0.5em 0; color: var(--primary); }
        h2 { font-size: 1.25em; margin: 0.4em 0; }
        h3 { font-size: 1.1em; margin: 0.3em 0; }
        p { margin: 0.4em 0; }
        a { color: var(--primary); }
        code { background: var(--surface-variant); padding: 2px 6px; border-radius: 4px; font-family: 'Courier New', monospace; font-size: 0.9em; }
        pre { background: var(--surface-variant); padding: 12px; border-radius: 8px; overflow-x: auto; margin: 0.5em 0; }
        pre code { background: transparent; padding: 0; }
        table { border-collapse: collapse; width: 100%; margin: 0.5em 0; }
        th, td { border: 1px solid var(--surface-variant); padding: 8px; text-align: left; }
        th { background: var(--surface-variant); font-weight: 600; }
        blockquote { border-left: 3px solid var(--primary); padding-left: 12px; margin: 0.5em 0; color: var(--on-surface-variant); }
        img { max-width: 100%; height: auto; border-radius: 8px; }
        ul, ol { margin: 0.5em 0; padding-left: 1.5em; }
        li { margin: 0.2em 0; }

        /* Tool call result cards */
        .result-card {
            display: inline-block;
            margin: 4px;
            padding: 10px;
            background: var(--surface-variant);
            border-radius: 10px;
            cursor: pointer;
            max-width: 280px;
            vertical-align: top;
        }
        .result-card:hover { opacity: 0.85; }
        .result-card .card-title { font-weight: 600; font-size: 0.9em; }
        .result-card .card-subtitle { font-size: 0.8em; color: var(--on-surface-variant); margin-top: 2px; }

        /* Mermaid, KaTeX, Chart.js */
        .mermaid { margin: 0.5em 0; }
        .katex-display { margin: 0.5em 0; }
        canvas { max-width: 100% !important; height: auto !important; }

        /* Virtualisation placeholders */
        .vdom-placeholder {
            background: var(--surface-variant);
            border-radius: 8px;
            margin: 4px 0;
            opacity: 0.3;
        }

        /* Error message */
        .error-card {
            margin: 8px 12px;
            padding: 12px;
            border: 1px solid var(--error);
            border-radius: 12px;
            color: var(--error);
            text-align: center;
        }

        /* Image attachment thumbnails — rendered under user messages.
           Multiple visual thumbs collapse into one compact right-aligned grid:
           2 across, then 4-across squares that wrap rows for larger batches. */
        .attachments {
            display: flex;
            flex-wrap: wrap;
            justify-content: flex-end;
            gap: 4px;
        }
        .attachments-grid { margin-top: 6px; }
        .attachment-image-wrap {
            position: relative;
            margin-top: 6px;
            margin-left: auto;
            width: fit-content;
            border-radius: 14px;
            overflow: hidden;
            max-width: 240px;
            cursor: pointer;
            box-shadow: 0 1px 6px rgba(43, 36, 28, 0.18);
            background: var(--surface-variant);
            -webkit-backdrop-filter: blur(2px);
        }
        .attachment-image {
            display: block;
            max-width: 240px;
            max-height: 240px;
            width: auto;
            height: auto;
            object-fit: cover;
            border-radius: 14px;
            transition: opacity 0.15s ease;
        }
        /* Grid mode: fixed square cells, size steps down as the batch grows.
           Count class is set by the renderer from the number of visual thumbs.
           Default cell is 88px — four across a phone column — with larger
           explicit cells for the small batches. Batches above 10 keep the
           default cell, so rows always wrap neatly at four. */
        .attachments-grid .attachment-image-wrap {
            margin-left: 0;
            border-radius: 10px;
        }
        .attachments-grid .attachment-image {
            border-radius: 10px;
            width: 88px;
            height: 88px;
            max-width: none;
            max-height: none;
        }
        .attachments-grid-2 .attachment-image { width: 140px; height: 140px; }
        .attachments-grid-3 .attachment-image { width: 120px; height: 120px; }
        /* Filename fallback shown only when the thumbnail fails to load */
        .attachment-image-fallback {
            display: none;
            align-items: center;
            padding: 8px 12px;
            font-size: 0.85em;
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            color: var(--on-surface-variant);
        }
        .attachments-grid .attachment-image-fallback { padding: 6px 10px; font-size: 0.75em; }
        .attachment-image-wrap:active .attachment-image { opacity: 0.85; }
        .message.user .attachments:empty { display: none; }

        /* Video badge — small play triangle in the corner of video stills */
        .attachment-video-badge {
            position: absolute;
            right: 6px;
            bottom: 6px;
            width: 20px;
            height: 20px;
            border-radius: 50%;
            background: rgba(43, 36, 28, 0.55);
            color: #FFFFFF;
            font-size: 10px;
            line-height: 20px;
            text-align: center;
            pointer-events: none;
        }
        .attachment-video .attachment-image {
            width: 160px;
            height: 120px;
            object-fit: cover;
        }
        .attachments-grid .attachment-video .attachment-image {
            width: 88px;
            height: 88px;
        }
        .attachments-grid-2 .attachment-video .attachment-image { width: 140px; height: 140px; }
        .attachments-grid-3 .attachment-video .attachment-image { width: 120px; height: 120px; }

        /* Styled file chip — non-visual attachments (PDF, DOCX, MP3, ZIP...)
           get the same polish as image thumbs: warm card, gold type badge,
           filename, human-readable size. Sits in the .attachments flex row. */
        .attachment-file-chip {
            display: flex;
            align-items: center;
            gap: 8px;
            margin-top: 6px;
            max-width: 260px;
            padding: 8px 12px 8px 8px;
            border-radius: 12px;
            background: var(--surface-variant);
            box-shadow: 0 1px 6px rgba(43, 36, 28, 0.14);
            cursor: pointer;
        }
        .attachment-file-chip:active { opacity: 0.85; }
        .attachment-file-badge {
            flex-shrink: 0;
            min-width: 38px;
            padding: 5px 6px;
            border-radius: 8px;
            background: rgba(218, 165, 32, 0.18);
            color: #B8860B;
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            font-size: 0.68em;
            font-weight: 700;
            letter-spacing: 1px;
            text-align: center;
        }
        .attachment-file-info {
            display: flex;
            flex-direction: column;
            min-width: 0;
        }
        .attachment-file-name {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            font-size: 0.85em;
            font-weight: 600;
            color: var(--on-surface);
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
        .attachment-file-size {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            font-size: 0.72em;
            color: var(--on-surface-variant);
            opacity: 0.8;
            margin-top: 1px;
        }

        /* Skill loaded indicator */
        .skill-loaded {
            display: flex;
            align-items: center;
            gap: 10px;
            margin: 6px 12px;
            padding: 10px 14px;
            background: var(--surface-variant);
            border-left: 3px solid #DAA520;
            border-radius: 8px;
        }
        .skill-loaded-failed {
            border-left-color: var(--error);
        }
        .skill-loaded-icon {
            font-size: 18px;
            line-height: 1;
            flex-shrink: 0;
        }
        .skill-loaded-content {
            display: flex;
            flex-direction: column;
            gap: 1px;
        }
        .skill-loaded-name {
            font-family: 'Courier New', monospace;
            font-size: 0.9em;
            font-weight: 700;
            color: #DAA520;
        }
        .skill-loaded-status {
            font-size: 0.8em;
            color: var(--on-surface-variant);
            opacity: 0.85;
        }
        .skill-loaded-failed .skill-loaded-name {
            color: var(--error);
        }
    </style>
    <link rel="stylesheet" href="file:///android_asset/portal/katex.min.css">
    <link rel="stylesheet" href="file:///android_asset/portal/highlight-theme.min.css">
    <script src="file:///android_asset/portal/katex.min.js"></script>
    <script src="file:///android_asset/portal/auto-render.min.js"></script>
    <script src="file:///android_asset/portal/mermaid.min.js"></script>
    <script src="file:///android_asset/portal/chart.umd.min.js"></script>
    <script src="file:///android_asset/portal/highlight.min.js"></script>
    <script src="file:///android_asset/portal/marked.min.js"></script>
    <script src="file:///android_asset/portal/portal_utils.js"></script>
</head>
<body>
    <div id="portal-canvas"></div>
    <script>
        // Initialize Mermaid
        if (typeof mermaid !== 'undefined') {
            mermaid.initialize({
                startOnLoad: false,
                theme: 'dark',
                themeVariables: {
                    primaryColor: getComputedStyle(document.documentElement).getPropertyValue('--primary').trim(),
                    primaryTextColor: getComputedStyle(document.documentElement).getPropertyValue('--on-surface').trim(),
                    lineColor: getComputedStyle(document.documentElement).getPropertyValue('--on-surface-variant').trim(),
                    secondaryColor: getComputedStyle(document.documentElement).getPropertyValue('--secondary').trim(),
                    tertiaryColor: getComputedStyle(document.documentElement).getPropertyValue('--tertiary').trim()
                }
            });
        }
        console.log('PortalCanvas shell: theme on-surface=' + getComputedStyle(document.documentElement).getPropertyValue('--on-surface'));
        console.log('PortalCanvas shell: theme surface=' + getComputedStyle(document.documentElement).getPropertyValue('--surface'));

        // PortalCanvas command system
        var PortalCanvas = {
            _messageCache: {},
            _streamingId: null,
            _userScrolledUp: false,
            _virtualisedNodes: new Set(),
            _observer: null,
            toolIconOverrides: {},

            setToolIconOverrides: function(map) {
                PortalCanvas.toolIconOverrides = map || {};
                console.log('PortalCanvas: tool icon overrides set:', Object.keys(PortalCanvas.toolIconOverrides).length, 'categories');
            },

            renderToolIcon: function(category) {
                var url = PortalCanvas.toolIconOverrides[category];
                if (url) {
                    return '<img src="' + url + '" style="width:18px;height:18px;object-fit:contain;" />';
                }
                return '';
            },

            init: function() {
                // Track scroll position for auto-scroll behavior
                var canvas = document.getElementById('portal-canvas');
                if (!canvas) return;

                window.addEventListener('scroll', function() {
                    var nearBottom = (window.innerHeight + window.scrollY) >= (document.body.scrollHeight - 100);
                    PortalCanvas._userScrolledUp = !nearBottom;
                });

                // Auto-scroll to portal content when user interacts with it.
                // Covers all clicks inside .portal-content elements regardless of
                // whether they use data-portal-event or are pure CSS/JS interactions.
                document.addEventListener('click', function(e) {
                    var portal = e.target.closest ? e.target.closest('.portal-content') : null;
                    if (!portal) return;
                    setTimeout(function() {
                        portal.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
                    }, 120);
                }, true);

                // Set up IntersectionObserver for virtualisation
                if ('IntersectionObserver' in window) {
                    PortalCanvas._observer = new IntersectionObserver(function(entries) {
                        entries.forEach(function(entry) {
                            var el = entry.target;
                            var msgId = el.getAttribute('data-msg-id');
                            if (!msgId) return;

                            if (entry.isIntersecting) {
                                // Element entering viewport — restore content if virtualised
                                if (PortalCanvas._virtualisedNodes.has(msgId)) {
                                    PortalCanvas._virtualisedNodes.delete(msgId);
                                    var cached = PortalCanvas._messageCache[msgId];
                                    if (cached) {
                                        el.innerHTML = cached;
                                        el.classList.remove('vdom-placeholder');
                                        PortalCanvas._processContent(el);
                                    }
                                }
                            } else {
                                // Element leaving viewport — virtualise if not already
                                if (!PortalCanvas._virtualisedNodes.has(msgId) && el.offsetHeight > 0) {
                                    PortalCanvas._virtualisedNodes.add(msgId);
                                    PortalCanvas._messageCache[msgId] = el.innerHTML;
                                    var height = el.offsetHeight;
                                    el.innerHTML = '';
                                    el.classList.add('vdom-placeholder');
                                    el.style.height = height + 'px';
                                }
                            }
                        });
                    }, { rootMargin: '200px 0px' });
                }
            },

            appendMessage: function(messageId, html, type) {
                // Skip empty messages entirely so no invisible padding divs get created
                if (!html || html.trim() === '') {
                    return;
                }
                var canvas = document.getElementById('portal-canvas');
                if (!canvas) return;

                // Defensive: strip .streaming class from any existing elements so
                // orphaned streaming state (blinking cursor) never persists in the DOM
                var orphanedStreamers = canvas.querySelectorAll('.message.streaming');
                for (var i = 0; i < orphanedStreamers.length; i++) {
                    orphanedStreamers[i].classList.remove('streaming');
                }

                // Remove any existing element with this ID
                var existing = document.getElementById('msg-' + messageId);
                if (existing) existing.remove();

                var div = document.createElement('div');
                div.id = 'msg-' + messageId;
                div.className = 'message ' + type;
                div.setAttribute('data-msg-id', messageId);
                div.innerHTML = html;

                canvas.appendChild(div);

                // Cache for virtualisation
                PortalCanvas._messageCache[messageId] = html;

                // Observe for virtualisation (skip streaming messages)
                if (type !== 'assistant streaming' && PortalCanvas._observer) {
                    PortalCanvas._observer.observe(div);
                }

                // Process content (markdown, highlighting, etc.)
                if (type !== 'assistant streaming') {
                    PortalCanvas._processContent(div);
                }

                // Auto-scroll if user is at bottom
                if (!PortalCanvas._userScrolledUp) {
                    PortalCanvas._scrollToBottom();
                }
            },

            updateInProgress: function(messageId, partialHtml) {
                var el = document.getElementById('msg-' + messageId);
                if (!el) {
                    // Element doesn't exist yet — create it
                    PortalCanvas.appendMessage(messageId, partialHtml, 'assistant streaming');
                    return;
                }

                // Lightweight update — just set text content, no markdown processing
                // Use requestAnimationFrame to batch rapid updates
                if (PortalCanvas._rafId) cancelAnimationFrame(PortalCanvas._rafId);
                PortalCanvas._pendingUpdate = { id: messageId, html: partialHtml };
                PortalCanvas._rafId = requestAnimationFrame(function() {
                    var update = PortalCanvas._pendingUpdate;
                    if (!update) return;
                    var elem = document.getElementById('msg-' + update.id);
                    if (elem) {
                        // Read chat text settings from CSS custom properties
                        var root = document.documentElement;
                        var guruFont = root.style.getPropertyValue('--guru-font') || "Georgia, 'Times New Roman', serif";
                        var guruColour = root.style.getPropertyValue('--guru-colour') || '';
                        var guruScale = root.style.getPropertyValue('--guru-font-scale') || '';

                        // Preserve the meta-row and thinking block if present
                        var metaRow = elem.querySelector('.meta-row');
                        var hairline = elem.querySelector('.meta-hairline');
                        var thinkingBlock = elem.querySelector('.thinking-block');
                        elem.innerHTML = '';
                        if (metaRow) elem.appendChild(metaRow);
                        else {
                            var newMeta = document.createElement('div');
                            newMeta.className = 'meta-row';
                            newMeta.style.textAlign = 'left';
                            var newLabel = document.createElement('span');
                            newLabel.className = 'meta-label';
                            newLabel.textContent = 'GURU';
                            elem.appendChild(newMeta);
                            newMeta.appendChild(newLabel);
                        }
                        if (hairline) elem.appendChild(hairline);
                        else {
                            var newHairline = document.createElement('div');
                            newHairline.className = 'meta-hairline';
                            elem.appendChild(newHairline);
                        }
                        if (thinkingBlock) elem.appendChild(thinkingBlock);
                        var content = document.createElement('div');
                        content.className = 'streaming-content';
                        content.textContent = update.html; // textContent for speed, no HTML parsing
                        // Apply chat text styling from CSS variables
                        if (guruFont) content.style.fontFamily = guruFont;
                        if (guruColour) content.style.color = guruColour;
                        if (guruScale) content.style.fontSize = guruScale;
                        elem.appendChild(content);
                        // Add cursor without causing layout shifts
                        var newCursor = document.createElement('span');
                        newCursor.className = 'streaming-cursor';
                        content.appendChild(newCursor);
                    }
                    PortalCanvas._rafId = null;
                });

                // Auto-scroll if user is at bottom
                if (!PortalCanvas._userScrolledUp) {
                    PortalCanvas._scrollToBottom();
                }
            },

            updateThinking: function(messageId, partialThinking) {
                var el = document.getElementById('msg-' + messageId);
                if (!el) return;
                if (!partialThinking || partialThinking.trim() === '') return;

                // Find or create the thinking block
                var thinkingBlock = el.querySelector('.thinking-block');
                if (!thinkingBlock) {
                    thinkingBlock = document.createElement('details');
                    thinkingBlock.className = 'thinking-block';
                    var summary = document.createElement('summary');
                    summary.textContent = 'Thinking...';
                    thinkingBlock.appendChild(summary);
                    var content = document.createElement('div');
                    content.className = 'thinking-content';
                    thinkingBlock.appendChild(content);
                    // Insert after the meta-row and hairline, before the streaming content
                    var hairline = el.querySelector('.meta-hairline');
                    if (hairline && hairline.nextSibling) {
                        el.insertBefore(thinkingBlock, hairline.nextSibling);
                    } else {
                        el.insertBefore(thinkingBlock, el.firstChild);
                    }
                }

                var thinkingContent = thinkingBlock.querySelector('.thinking-content');
                if (thinkingContent) {
                    thinkingContent.textContent = partialThinking;
                }

                // Auto-scroll if user is at bottom
                if (!PortalCanvas._userScrolledUp) {
                    PortalCanvas._scrollToBottom();
                }
            },

            finalizeMessage: function(messageId, finalHtml, finalType) {
                // Skip empty final HTML so no invisible padding divs get created
                if (!finalHtml || finalHtml.trim() === '') {
                    // If the DOM element exists from streaming, remove it
                    var existing = document.getElementById('msg-' + messageId);
                    if (existing) existing.remove();
                    return;
                }
                var el = document.getElementById('msg-' + messageId);
                if (!el) {
                    // Element doesn't exist yet — create it with the correct type
                    PortalCanvas.appendMessage(messageId, finalHtml, finalType || 'assistant');
                    return;
                }

                // Strip all stale classes from streaming and old outcome classifications
                el.classList.remove('streaming');
                el.classList.remove('outcome-failed');
                el.classList.remove('outcome-success');

                // Apply the fresh type classes computed from the final text
                var typeClasses = (finalType || 'assistant').split(' ');
                for (var i = 0; i < typeClasses.length; i++) {
                    if (typeClasses[i]) el.classList.add(typeClasses[i]);
                }

                el.innerHTML = finalHtml;

                // Update cache
                PortalCanvas._messageCache[messageId] = finalHtml;

                // Process content with full markdown rendering
                PortalCanvas._processContent(el);

                // Start observing for virtualisation
                if (PortalCanvas._observer) {
                    PortalCanvas._observer.observe(el);
                }

                // Auto-scroll if user is at bottom
                if (!PortalCanvas._userScrolledUp) {
                    PortalCanvas._scrollToBottom();
                }
            },

            clear: function() {
                var canvas = document.getElementById('portal-canvas');
                if (canvas) canvas.innerHTML = '';
                PortalCanvas._messageCache = {};
                PortalCanvas._virtualisedNodes.clear();
                PortalCanvas._userScrolledUp = false;
            },

            setTheme: function(cssVariables) {
                var root = document.documentElement;
                var pairs = cssVariables.split(';').filter(function(s) { return s.trim(); });
                pairs.forEach(function(pair) {
                    var parts = pair.split(':');
                    if (parts.length === 2) {
                        root.style.setProperty(parts[0].trim(), parts[1].trim());
                    }
                });
            },

            setChatBarHeight: function(heightPx) {
                document.documentElement.style.setProperty('--chat-bar-height', heightPx + 'px');
                // Re-scroll to bottom so the last message snaps to the new padding boundary
                if (!PortalCanvas._userScrolledUp) {
                    PortalCanvas._scrollToBottom();
                }
            },

            scrollToBottom: function() {
                PortalCanvas._userScrolledUp = false;
                PortalCanvas._scrollToBottom();
            },

            _scrollToBottom: function() {
                requestAnimationFrame(function() {
                    window.scrollTo(0, document.body.scrollHeight);
                });
            },

            _processContent: function(element) {
                // Render markdown with marked.js
                if (typeof marked !== 'undefined') {
                    element.querySelectorAll('.markdown-content').forEach(function(el) {
                        var raw = el.textContent || el.innerHTML;
                        var parsed = marked.parse(raw);
                        el.innerHTML = parsed;
                    });
                }

                // Render KaTeX math
                if (typeof renderMathInElement !== 'undefined') {
                    renderMathInElement(element, {
                        delimiters: [
                            {left: '$$', right: '$$', display: true},
                            {left: '$', right: '$', display: false},
                            {left: '\\(', right: '\\)', display: false},
                            {left: '\\[', right: '\\]', display: true}
                        ]
                    });
                }

                // Highlight code blocks
                if (typeof hljs !== 'undefined') {
                    element.querySelectorAll('pre code').forEach(function(block) {
                        hljs.highlightElement(block);
                    });
                }

                // Render Mermaid diagrams
                if (typeof mermaid !== 'undefined' && mermaid.run) {
                    element.querySelectorAll('.mermaid').forEach(function(el) {
                        mermaid.run({ nodes: [el] });
                    });
                }

                // Wire up tool call click handlers
                element.querySelectorAll('.toolcall-trigger').forEach(function(el) {
                    el.style.cursor = 'pointer';
                    el.addEventListener('click', function(e) {
                        e.preventDefault();
                        var uuid = el.getAttribute('data-uuid');
                        if (uuid && typeof PortalBridge !== 'undefined') {
                            PortalBridge.sendEvent('toolcall_tap', uuid);
                        }
                    });
                });

                // Wire up grouped tool call cards — header tap toggles the card open
                element.querySelectorAll('.toolcall-group-card').forEach(function(card) {
                    var header = card.querySelector('.toolcall-group-header');
                    if (header) {
                        header.addEventListener('click', function(e) {
                            e.preventDefault();
                            card.classList.toggle('expanded');
                        });
                    }
                    // Render per-category icons inside this card
                    card.querySelectorAll('.grouped-row-icon').forEach(function(iconEl) {
                        var category = iconEl.getAttribute('data-category');
                        if (!category) return;
                        var url = PortalCanvas.toolIconOverrides[category];
                        if (url) {
                            iconEl.style.backgroundImage = "url('" + url + "')";
                        } else {
                            iconEl.setAttribute('data-empty', 'true');
                        }
                    });
                    // Tier 2 row taps expand the row deeper in place (tier 3) — no event to native
                    card.querySelectorAll('.grouped-tool-row').forEach(function(row) {
                        var rowHeader = row.querySelector('.row-header');
                        if (!rowHeader) return;
                        rowHeader.addEventListener('click', function(e) {
                            e.preventDefault();
                            e.stopPropagation();
                            row.classList.toggle('expanded');
                        });
                    });
                });

                // Wire up note click handlers
                element.querySelectorAll('[data-note-id]').forEach(function(el) {
                    el.style.cursor = 'pointer';
                    el.addEventListener('click', function(e) {
                        e.preventDefault();
                        var noteId = el.getAttribute('data-note-id');
                        if (noteId && typeof PortalBridge !== 'undefined') {
                            PortalBridge.sendEvent('note_click', noteId);
                        }
                    });
                });

                // Wire up task click handlers
                element.querySelectorAll('[data-task-id]').forEach(function(el) {
                    el.style.cursor = 'pointer';
                    el.addEventListener('click', function(e) {
                        e.preventDefault();
                        var taskId = el.getAttribute('data-task-id');
                        if (taskId && typeof PortalBridge !== 'undefined') {
                            PortalBridge.sendEvent('task_click', taskId);
                        }
                    });
                });

                // Wire up event click handlers
                element.querySelectorAll('[data-event-id]').forEach(function(el) {
                    el.style.cursor = 'pointer';
                    el.addEventListener('click', function(e) {
                        e.preventDefault();
                        var eventId = el.getAttribute('data-event-id');
                        if (eventId && typeof PortalBridge !== 'undefined') {
                            PortalBridge.sendEvent('event_click', eventId);
                        }
                    });
                });

                // Wire up portal event handlers
                element.querySelectorAll('[data-portal-event]').forEach(function(el) {
                    el.style.cursor = 'pointer';
                    el.addEventListener('click', function(e) {
                        e.preventDefault();
                        var eventName = el.getAttribute('data-portal-event');
                        var payload = el.getAttribute('data-portal-payload') || '';
                        if (eventName && typeof PortalBridge !== 'undefined') {
                            PortalBridge.sendEvent(eventName, payload);
                        }
                    });
                });

                // Wire up image attachment taps — fires attachment_image_tap with
                // the cached file path so native can open the fullscreen viewer.
                // Video thumbs carry data-attachment-type="video" and their payload
                // is the video file itself so native plays it rather than the still.
                element.querySelectorAll('.attachment-image-wrap').forEach(function(el) {
                    el.addEventListener('click', function(e) {
                        e.preventDefault();
                        e.stopPropagation();
                        var path = el.getAttribute('data-attachment-path') || '';
                        if (path && typeof PortalBridge !== 'undefined') {
                            PortalBridge.sendEvent('attachment_image_tap', path);
                        }
                    });
                    // Tag video thumbs so native knows what to open — read by PortalScreen
                    // on tap via payload splitting is unreliable for paths with colons,
                    // so video payloads are prefixed when building the DOM (see ChatToHtml)
                });

                // Wire up file chip taps — fires attachment_file_tap with the cached
                // path so native opens the file preview sheet. Guarded against double
                // wiring when a message is re-processed (finalize/virtualisation).
                element.querySelectorAll('.attachment-file-chip:not([data-tap-wired])').forEach(function(el) {
                    el.setAttribute('data-tap-wired', 'true');
                    el.addEventListener('click', function(e) {
                        e.preventDefault();
                        e.stopPropagation();
                        var path = el.getAttribute('data-attachment-path') || '';
                        if (path && typeof PortalBridge !== 'undefined') {
                            PortalBridge.sendEvent('attachment_file_tap', path);
                        }
                    });
                });

                // Wire up assistant message tap for context menu
                element.querySelectorAll('.message.assistant').forEach(function(msg) {
                    msg.addEventListener('click', function(e) {
                        if (e.target.closest('[data-portal-event]') || e.target.closest('[data-note-id]') || e.target.closest('[data-task-id]') || e.target.closest('[data-event-id]') || e.target.closest('.portal-content') || e.target.closest('.toolcall-trigger') || e.target.closest('.toolcall-group-card') || e.target.closest('a') || e.target.closest('button')) return;
                        var label = msg.querySelector('[data-message-uuid]');
                        var uuid = label ? label.getAttribute('data-message-uuid') : '';
                        if (uuid && typeof PortalBridge !== 'undefined') {
                            PortalBridge.sendEvent('message_tap', uuid);
                        }
                    });
                });

                // Execute script tags inside portal content.
                // Setting innerHTML does not execute <script> tags (DOM spec).
                // We manually create new script elements to force execution.
                element.querySelectorAll('.portal-content script').forEach(function(oldScript) {
                    var newScript = document.createElement('script');
                    if (oldScript.src) {
                        newScript.src = oldScript.src;
                    } else {
                        newScript.textContent = oldScript.textContent;
                    }
                    oldScript.parentNode.replaceChild(newScript, oldScript);
                });
            }
        };

        // Initialize canvas on load
        window.addEventListener('load', function() {
            PortalCanvas.init();
        });
    </script>
</body>
</html>
    """.trimIndent()
}

/**
 * Build CSS custom properties from the current Material Theme colors and chat text config.
 * Chat font, colour, and scale are emitted as CSS variables so the updateInProgress JS
 * function can read them when creating streaming content elements.
 */
private fun buildCanvasThemeCss(
    surfaceColor: Color,
    surfaceVariantColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariantColor: Color,
    primaryColor: Color,
    secondaryColor: Color,
    tertiaryColor: Color,
    backgroundColor: Color,
    errorColor: Color,
    chatTextConfig: ChatTextConfig = ChatTextConfig.DEFAULT
): String {
    fun colorToHex(color: Color): String {
        val r = (color.red * 255).toInt().coerceIn(0, 255)
        val g = (color.green * 255).toInt().coerceIn(0, 255)
        val b = (color.blue * 255).toInt().coerceIn(0, 255)
        val a = (color.alpha * 255).toInt().coerceIn(0, 255)
        return "#${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}${a.toString(16).padStart(2, '0')}".uppercase()
    }

    val baseCss = "--surface: ${colorToHex(surfaceColor)}; --surface-variant: ${colorToHex(surfaceVariantColor)}; --on-surface: ${colorToHex(onSurfaceColor)}; --on-surface-variant: ${colorToHex(onSurfaceVariantColor)}; --primary: ${colorToHex(primaryColor)}; --secondary: ${colorToHex(secondaryColor)}; --tertiary: ${colorToHex(tertiaryColor)}; --background: ${colorToHex(backgroundColor)}; --error: ${colorToHex(errorColor)};"

    // Chat text CSS variables for streaming content
    val guruFont = chatTextConfig.guruFont ?: "Georgia, 'Times New Roman', serif"
    val guruColour = chatTextConfig.guruColour ?: ""
    val guruScale = chatTextConfig.guruFontScale?.let { if (it != 1.0f) "${it}em" else "" } ?: ""
    val userFont = chatTextConfig.userFont ?: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
    val userColour = chatTextConfig.userColour ?: ""
    val userScale = chatTextConfig.userFontScale?.let { if (it != 1.0f) "${it}em" else "" } ?: ""

    val chatCss = "--guru-font: '$guruFont'; --guru-colour: $guruColour; --guru-font-scale: $guruScale; --user-font: '$userFont'; --user-colour: $userColour; --user-font-scale: $userScale;"

    return "$baseCss $chatCss"
}

/**
 * Read all font files from the guru_fonts/ directory in app internal storage
 * and convert them to @font-face declarations using base64 data URIs.
 *
 * This allows the WebView to load custom fonts without needing file:// access
 * to app internal storage, which would fail on most devices due to scoped storage
 * and WebView security restrictions. Base64 data URIs work everywhere.
 *
 * Each .ttf file becomes:
 *   @font-face { font-family: 'FontName'; src: url('data:font/truetype;charset=utf-8;base64,...') format('truetype'); }
 *
 * Each .otf file becomes:
 *   @font-face { font-family: 'FontName'; src: url('data:font/opentype;charset=utf-8;base64,...') format('opentype'); }
 *
 * If a font has both regular and bold variants (e.g. MyFont.ttf and MyFont_Bold.ttf),
 * both are emitted with appropriate font-weight declarations.
 */
private fun buildCustomFontFaceDeclarations(context: android.content.Context): String {
    val fontDir = File(context.filesDir, "guru_fonts")
    if (!fontDir.exists()) return ""

    val fontFiles = fontDir.listFiles()
        ?.filter { it.extension.lowercase() in listOf("ttf", "otf") }
        ?: return emptyList<String>().joinToString("\n")

    if (fontFiles.isEmpty()) return ""

    val sb = StringBuilder()
    // Group fonts by family name. Bold variants are detected by _bold suffix
    val regularFonts = fontFiles.filter { !it.nameWithoutExtension.lowercase().endsWith("_bold") }
    val boldFonts = fontFiles.filter { it.nameWithoutExtension.lowercase().endsWith("_bold") }

    for (fontFile in regularFonts) {
        val familyName = fontFile.nameWithoutExtension
        val (mime, format) = if (fontFile.extension.lowercase() == "ttf")
            "font/truetype" to "truetype"
        else
            "font/opentype" to "opentype"

        val base64 = try {
            Base64.encodeToString(fontFile.readBytes(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w("PortalCanvas", "Failed to read font ${fontFile.name}: ${e.message}")
            continue
        }

        sb.append("@font-face { font-family: '${familyName}'; src: url('data:${mime};charset=utf-8;base64,${base64}') format('${format}'); font-weight: normal; }\n")

        // Check for matching bold variant
        val boldMatch = boldFonts.find { it.nameWithoutExtension.lowercase() == "${familyName.lowercase()}_bold" }
        if (boldMatch != null) {
            val (boldMime, boldFormat) = if (boldMatch.extension.lowercase() == "ttf")
                "font/truetype" to "truetype"
            else
                "font/opentype" to "opentype"
            try {
                val boldBase64 = Base64.encodeToString(boldMatch.readBytes(), Base64.NO_WRAP)
                sb.append("@font-face { font-family: '${familyName}'; src: url('data:${boldMime};charset=utf-8;base64,${boldBase64}') format('${boldFormat}'); font-weight: bold; }\n")
            } catch (e: Exception) {
                Log.w("PortalCanvas", "Failed to read bold font ${boldMatch.name}: ${e.message}")
            }
        }
    }

    // Also emit any bold fonts that didn't have a matching regular variant
    for (fontFile in boldFonts) {
        val familyName = fontFile.nameWithoutExtension.removeSuffix("_bold").removeSuffix("_Bold")
        val alreadyEmitted = regularFonts.any { it.nameWithoutExtension == familyName }
        if (alreadyEmitted) continue

        val (mime, format) = if (fontFile.extension.lowercase() == "ttf")
            "font/truetype" to "truetype"
        else
            "font/opentype" to "opentype"

        val base64 = try {
            Base64.encodeToString(fontFile.readBytes(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w("PortalCanvas", "Failed to read font ${fontFile.name}: ${e.message}")
            continue
        }

        sb.append("@font-face { font-family: '${familyName}'; src: url('data:${mime};charset=utf-8;base64,${base64}') format('${format}'); font-weight: bold; }\n")
    }

    return sb.toString()
}