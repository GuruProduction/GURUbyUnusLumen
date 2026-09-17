package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object WebViewBrowserToolDefinitions : ToolSetRegistration {
    const val WEB_BROWSER_LOAD = "webBrowserLoad"; const val WEB_BROWSER_CONTENT = "webBrowserContent"
    const val WEB_BROWSER_SCREENSHOT = "webBrowserScreenshot"; const val WEB_BROWSER_CLICK = "webBrowserClick"
    const val WEB_BROWSER_INPUT = "webBrowserInput"; const val WEB_BROWSER_SCROLL = "webBrowserScroll"
    const val WEB_BROWSER_CONSOLE = "webBrowserConsole"; const val WEB_BROWSER_NETWORK = "webBrowserNetwork"

    override val definitions = listOf(
        ToolDefinition(name = WEB_BROWSER_LOAD, description = "Load a web page in a full WebView with JavaScript enabled, routed through Tor. Renders the page completely (handles React, Vue, Angular, Svelte SPAs).", category = "webview", parameters = listOf(ToolParameter("url", ToolParameterType.String, true, "URL to load"), ToolParameter("renderWaitSeconds", ToolParameterType.Integer, false, "Seconds to wait for JS rendering")), permissions = emptyList()),
        ToolDefinition(name = WEB_BROWSER_CONTENT, description = "Extract rendered text content from the currently loaded page. Optionally pass a CSS selector.", category = "webview", parameters = listOf(ToolParameter("selector", ToolParameterType.String, false, "CSS selector")), permissions = emptyList()),
        ToolDefinition(name = WEB_BROWSER_SCREENSHOT, description = "Capture a screenshot of the currently loaded web page.", category = "webview", parameters = listOf(ToolParameter("savePath", ToolParameterType.String, false, "Optional file path")), permissions = emptyList()),
        ToolDefinition(name = WEB_BROWSER_CLICK, description = "Click an element on the loaded page by CSS selector.", category = "webview", parameters = listOf(ToolParameter("selector", ToolParameterType.String, true, "CSS selector")), permissions = emptyList()),
        ToolDefinition(name = WEB_BROWSER_INPUT, description = "Type text into a form field on the loaded page by CSS selector.", category = "webview", parameters = listOf(ToolParameter("selector", ToolParameterType.String, true, "CSS selector"), ToolParameter("text", ToolParameterType.String, true, "Text to type")), permissions = emptyList()),
        ToolDefinition(name = WEB_BROWSER_SCROLL, description = "Scroll the loaded page.", category = "webview", parameters = listOf(ToolParameter("direction", ToolParameterType.String, false, "up, down, top, bottom")), permissions = emptyList()),
        ToolDefinition(name = WEB_BROWSER_CONSOLE, description = "Get JavaScript console output from the loaded page.", category = "webview", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = WEB_BROWSER_NETWORK, description = "Get network requests made by the loaded page.", category = "webview", parameters = emptyList(), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = WebViewBrowserToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}