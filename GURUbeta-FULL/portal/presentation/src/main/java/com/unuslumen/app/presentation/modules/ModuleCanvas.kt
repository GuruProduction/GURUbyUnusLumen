// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.modules

import android.annotation.SuppressLint
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.unuslumen.app.preferences.domain.model.GuruTheme
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridge for a module room's WebView. Identical contract to PortalCanvas:
 * JS calls ModuleBridge.sendEvent(name, payload) to talk to native;
 * native pushes commands via evaluateJavascript.
 *
 * The room shell ALSO exposes this bridge as window.GuruBridge with a
 * post(name, payload) method — a compatibility alias registered in the
 * shell's JS so a composition that learned the wrong native name still
 * routes home instead of dying silently. Compositions must prefer
 * data-module-event attributes; JS calls to the bridge are the fallback.
 */
class ModuleCanvasBridge {
    var onEvent: ((name: String, payload: String) -> Unit)? = null

    @JavascriptInterface
    fun sendEvent(name: String, payload: String) {
        onEvent?.invoke(name, payload)
    }
}

/**
 * Renders one module room — the numen's composed HTML/CSS/JS on a
 * full-screen WebView, themed to match the app via the same CSS-variable
 * injection PortalCanvas uses.
 *
 * Broken-room recovery is doctrine: when a composition fails to render
 * (bad HTML/JS the numen saved), the canvas surfaces an honest
 * needs-attention card — never a silent blank — because rooms self-heal
 * through conversation and the human must know to ask.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ModuleCanvas(
    html: String,
    css: String,
    js: String,
    moduleId: String,
    guruTheme: GuruTheme? = null,
    onEvent: ((name: String, payload: String) -> Unit)? = null,
    onWebViewCreated: ((WebView) -> Unit)? = null,
    onCanvasReady: (() -> Unit)? = null,
    onRenderError: ((Boolean) -> Unit)? = null,
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

    var webView by remember { mutableStateOf<WebView?>(null) }
    var isLoaded by remember { mutableStateOf(false) }
    var hasRenderError by remember { mutableStateOf(false) }
    val bridge = remember { ModuleCanvasBridge() }
    val isDestroyed = remember { AtomicBoolean(false) }
    // Composition state key: reload only when the room's content actually changes.
    var lastContent by remember(moduleId) { mutableStateOf("") }

    LaunchedEffect(onEvent) { bridge.onEvent = onEvent }
    LaunchedEffect(webView) { webView?.let { onWebViewCreated?.invoke(it) } }
    LaunchedEffect(isLoaded) { if (isLoaded) onCanvasReady?.invoke() }
    LaunchedEffect(hasRenderError) { onRenderError?.invoke(hasRenderError) }

    // Custom fonts from guru_fonts/ injected as base64 @font-face, matching
    // PortalCanvas so every grown room speaks the app's typography.
    val customFontCss = remember { buildModuleFontFaces(context) }

    val themeCss = remember(guruTheme, surfaceColor, surfaceVariantColor, onSurfaceColor,
        onSurfaceVariantColor, primaryColor, secondaryColor, tertiaryColor, backgroundColor, errorColor) {
        buildModuleThemeCss(
            surfaceColor, surfaceVariantColor, onSurfaceColor, onSurfaceVariantColor,
            primaryColor, secondaryColor, tertiaryColor, backgroundColor, errorColor
        )
    }

    val content = remember(html, css, js, themeCss, customFontCss, moduleId) { "$themeCss§$customFontCss§$css§$html§$js§$moduleId" }

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
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.textZoom = 100

                    addJavascriptInterface(bridge, "ModuleBridge")

                    setBackgroundColor(android.graphics.Color.TRANSPARENT)

                    // Render-error watchdog: JS exceptions anywhere in the
                    // room's code land here and flip the honest surface on.
                    setWebChromeClient(object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                            if (consoleMessage.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                                val msg = consoleMessage.message()
                                if (msg.contains("Uncaught", ignoreCase = true) ||
                                    msg.contains("SyntaxError", ignoreCase = true)) {
                                    Log.w("ModuleCanvas", "Room $moduleId JS error: $msg")
                                    hasRenderError = true
                                }
                            }
                            return super.onConsoleMessage(consoleMessage)
                        }
                    })

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isLoaded = true
                            Log.d("ModuleCanvas", "Room $moduleId canvas loaded")
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            // Main-frame failures mean the composition itself is broken.
                            if (request?.isForMainFrame == true) {
                                hasRenderError = true
                            }
                        }
                    }

                    isDestroyed.set(false)
                    webView = this
                }
            },
            update = { wv ->
                if (!isDestroyed.get() && content != lastContent) {
                    lastContent = content
                    isLoaded = false
                    hasRenderError = false
                    try {
                        val shell = buildModuleShellHtml(themeCss, customFontCss, css, js, html, moduleId)
                        wv.loadDataWithBaseURL(
                            "file:///android_asset/portal/",
                            shell,
                            "text/html",
                            "UTF-8",
                            null
                        )
                    } catch (_: Throwable) {
                        hasRenderError = true
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Doctrine: no silent blanks. A broken room states its state in the
        // same warm voice as everything else and points home to heal it.
        if (hasRenderError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "This room needs attention.\nAsk me to fix it and I'll re-compose it from the last good version.",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { wv ->
                if (!isDestroyed.get()) {
                    try {
                        wv.removeJavascriptInterface("ModuleBridge")
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
 * Build the module shell document. Same skeleton as the portal shell —
 * theme variables as CSS custom properties, custom fonts, the bridge, the
 * bundled libraries the doctrine promises the numen (marked, katex, mermaid,
 * chart.js, highlight) — plus the error sentinel hook the watchdog uses.
 */
private fun buildModuleShellHtml(
    themeCss: String,
    customFontCss: String,
    extraCss: String,
    extraJs: String,
    bodyHtml: String,
    moduleId: String
): String {
    return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
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
        body { min-height: 100%; }
        :root { $themeCss }
        $extraCss
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
    <div id="module-root">$bodyHtml</div>
    <script>
        (function() {
            // Markdown inside .markdown-content, katex where it appears,
            // highlight on code blocks — the library suite the doctrine
            // promises every room, loaded from the same bundled assets the
            // portal itself uses.
            if (typeof marked !== 'undefined') {
                document.querySelectorAll('.markdown-content').forEach(function(el) {
                    el.innerHTML = marked.parse(el.textContent || '');
                });
            }
            if (typeof renderMathInElement !== 'undefined') {
                renderMathInElement(document.body, {
                    delimiters: [
                        {left: '$$', right: '$$', display: true},
                        {left: '\\(', right: '\\)', display: false}
                    ]
                });
            }
            if (typeof hljs !== 'undefined') {
                document.querySelectorAll('pre code').forEach(function(b) { hljs.highlightElement(b); });
            }
            // Interactivity wiring: data-module-event taps ride the bridge home.
            document.addEventListener('click', function(e) {
                var el = e.target.closest('[data-module-event]');
                if (!el) return;
                e.preventDefault();
                var name = el.getAttribute('data-module-event');
                var payload = el.getAttribute('data-module-payload') || '';
                if (typeof ModuleBridge !== 'undefined') ModuleBridge.sendEvent(name, payload);
            });

            // Compatibility alias: some compositions learned the wrong bridge
            // name (GuruBridge.post) — route them to the real bridge so no
            // button in any room dies silently.
            if (typeof window.GuruBridge === 'undefined' && typeof ModuleBridge !== 'undefined') {
                window.GuruBridge = {
                    post: function(name, payload) { ModuleBridge.sendEvent(name, payload); },
                    sendEvent: function(name, payload) { ModuleBridge.sendEvent(name, payload); }
                };
            }

            window.ModuleRoomData = {
                _data: {},
                receiveData: function(key, json) {
                    try {
                        var parsed = JSON.parse(json);
                        document.querySelectorAll('[data-room-bind="' + key + '"]').forEach(function(el) {
                            el.textContent = JSON.stringify(parsed);
                            el.dispatchEvent(new CustomEvent('roomdata', { detail: { key: key, data: parsed } }));
                        });
                        if (typeof window.onRoomData === 'function') window.onRoomData(key, parsed);
                        document.body.dispatchEvent(new CustomEvent('roomdata', { detail: { key: key, data: parsed } }));
                    } catch (e) {
                        console.error('ModuleRoomData.receiveData failed for key ' + key + ': ' + e.message);
                    }
                }
            };
        })();
        })();
    </script>
    <script>$extraJs</script>
</body>
</html>
    """.trimIndent()
}

/**
 * Custom font @font-face declarations from filesDir/guru_fonts, matching
 * PortalCanvas's base64-uri technique. A room inherits the app's typography.
 */
private fun buildModuleFontFaces(context: android.content.Context): String {
    val fontDir = File(context.filesDir, "guru_fonts")
    if (!fontDir.exists()) return ""
    val fontFiles = fontDir.listFiles()?.filter { it.extension.lowercase() in listOf("ttf", "otf") } ?: return ""
    if (fontFiles.isEmpty()) return ""

    val sb = StringBuilder()
    val bolds = fontFiles.filter { it.nameWithoutExtension.lowercase().endsWith("_bold") }
    for (font in fontFiles.filter { !it.nameWithoutExtension.lowercase().endsWith("_bold") }) {
        val (mime, format) = if (font.extension.lowercase() == "ttf") "font/truetype" to "truetype" else "font/opentype" to "opentype"
        val base64 = try { Base64.encodeToString(font.readBytes(), Base64.NO_WRAP) } catch (_: Exception) { continue }
        sb.append("@font-face { font-family: '${font.nameWithoutExtension}'; src: url('data:$mime;charset=utf-8;base64,$base64') format('$format'); font-weight: normal; }\n")
    }
    for (font in bolds) {
        val (mime, format) = if (font.extension.lowercase() == "ttf") "font/truetype" to "truetype" else "font/opentype" to "opentype"
        val base64 = try { Base64.encodeToString(font.readBytes(), Base64.NO_WRAP) } catch (_: Exception) { continue }
        sb.append("@font-face { font-family: '${font.nameWithoutExtension.removeSuffix("_bold").removeSuffix("_Bold")}'; src: url('data:$mime;charset=utf-8;base64,$base64') format('$format'); font-weight: bold; }\n")
    }
    return sb.toString()
}

/** CSS custom properties from the Material theme — PortalCanvas's exact injection contract. */
private fun buildModuleThemeCss(
    surfaceColor: Color,
    surfaceVariantColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariantColor: Color,
    primaryColor: Color,
    secondaryColor: Color,
    tertiaryColor: Color,
    backgroundColor: Color,
    errorColor: Color
): String {
    fun colorToHex(color: Color): String {
        val r = (color.red * 255).toInt().coerceIn(0, 255)
        val g = (color.green * 255).toInt().coerceIn(0, 255)
        val b = (color.blue * 255).toInt().coerceIn(0, 255)
        return "#${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}".uppercase()
    }
    return "--surface: ${colorToHex(surfaceColor)}; --surface-variant: ${colorToHex(surfaceVariantColor)}; " +
        "--on-surface: ${colorToHex(onSurfaceColor)}; --on-surface-variant: ${colorToHex(onSurfaceVariantColor)}; " +
        "--primary: ${colorToHex(primaryColor)}; --secondary: ${colorToHex(secondaryColor)}; " +
        "--tertiary: ${colorToHex(tertiaryColor)}; --background: ${colorToHex(backgroundColor)}; " +
        "--error: ${colorToHex(errorColor)};"
}

/**
 * Send a raw command through the shell's ModuleRoomData namespace.
 * Escaping and guards follow PortalCanvas_sendCommand's discipline.
 */
fun ModuleCanvas_sendCommand(
    webView: WebView?,
    command: String,
    vararg args: String
) {
    if (webView == null) return
    val jsArgs = args.joinToString(", ") { "'${it.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")}'" }
    val js = "if (typeof ModuleRoomData !== 'undefined') { ModuleRoomData.$command($jsArgs); }"
    try {
        webView.evaluateJavascript(js, null)
    } catch (e: Exception) {
        Log.w("ModuleCanvas", "Failed to send command $command: ${e.message}")
    }
}

/**
 * Push a live data snapshot into a rendered room — the doctrine's hydration
 * contract: view rooms compose with [data-room-bind] placeholders and a body
 * JS `window.onRoomData(key, data)` listener; native pushes real data by key
 * whenever the underlying Flow changes, so the room is a living mirror, not
 * a photo.
 *
 * @param webView the room's canvas from ModuleScreen's onWebViewCreated
 * @param key the [data-room-bind] key the room declared (e.g. "automations")
 * @param json the serialized snapshot
 */
fun ModuleCanvas_pushData(
    webView: WebView?,
    key: String,
    json: String
) {
    if (webView == null) return
    val jsKey = key.replace("\\", "\\\\").replace("'", "\\'")
    val safeJson = json
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "")
    val js = "if (typeof ModuleRoomData !== 'undefined') { ModuleRoomData.receiveData('$jsKey', '$safeJson'); }"
    try {
        webView.evaluateJavascript(js, null)
    } catch (e: Exception) {
        Log.w("ModuleCanvas", "Failed to push data key=$key: ${e.message}")
    }
}