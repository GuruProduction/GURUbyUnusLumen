package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object HttpToolDefinitions : ToolSetRegistration {
    const val HTTP_GET = "httpGet"; const val HTTP_POST = "httpPost"; const val HTTP_PUT = "httpPut"
    const val HTTP_DELETE = "httpDelete"; const val HTTP_PATCH = "httpPatch"; const val HTTP_HEAD = "httpHead"
    const val HTTP_DOWNLOAD = "httpDownload"; const val RESOLVE_DOWNLOAD_URL = "resolveDownloadUrl"

    override val definitions = listOf(
        ToolDefinition(name = HTTP_GET, description = "Make an HTTP GET request through Tor. Returns status code, headers, and body. All traffic routed through Tor SOCKS proxy.", category = "http", parameters = listOf(ToolParameter("url", ToolParameterType.String, true, "URL"), ToolParameter("headers", ToolParameterType.String, false, "JSON headers")), permissions = emptyList()),
        ToolDefinition(name = HTTP_POST, description = "Make an HTTP POST request through Tor.", category = "http", parameters = listOf(ToolParameter("url", ToolParameterType.String, true, "URL"), ToolParameter("body", ToolParameterType.String, false, "Request body"), ToolParameter("contentType", ToolParameterType.String, false, "Content type"), ToolParameter("headers", ToolParameterType.String, false, "JSON headers")), permissions = emptyList()),
        ToolDefinition(name = HTTP_PUT, description = "Make an HTTP PUT request through Tor.", category = "http", parameters = listOf(ToolParameter("url", ToolParameterType.String, true, "URL"), ToolParameter("body", ToolParameterType.String, false, "Body"), ToolParameter("contentType", ToolParameterType.String, false, "Content type"), ToolParameter("headers", ToolParameterType.String, false, "JSON headers")), permissions = emptyList()),
        ToolDefinition(name = HTTP_DELETE, description = "Make an HTTP DELETE request through Tor.", category = "http", parameters = listOf(ToolParameter("url", ToolParameterType.String, true, "URL"), ToolParameter("headers", ToolParameterType.String, false, "JSON headers")), permissions = emptyList()),
        ToolDefinition(name = HTTP_PATCH, description = "Make an HTTP PATCH request through Tor.", category = "http", parameters = listOf(ToolParameter("url", ToolParameterType.String, true, "URL"), ToolParameter("body", ToolParameterType.String, false, "Body"), ToolParameter("contentType", ToolParameterType.String, false, "Content type"), ToolParameter("headers", ToolParameterType.String, false, "JSON headers")), permissions = emptyList()),
        ToolDefinition(name = HTTP_HEAD, description = "Make an HTTP HEAD request through Tor.", category = "http", parameters = listOf(ToolParameter("url", ToolParameterType.String, true, "URL"), ToolParameter("headers", ToolParameterType.String, false, "JSON headers")), permissions = emptyList()),
        ToolDefinition(name = HTTP_DOWNLOAD, description = "Download a file from a URL through Tor.", category = "http", parameters = listOf(ToolParameter("url", ToolParameterType.String, true, "URL"), ToolParameter("destinationPath", ToolParameterType.String, true, "Local path"), ToolParameter("headers", ToolParameterType.String, false, "JSON headers")), permissions = emptyList()),
        ToolDefinition(name = RESOLVE_DOWNLOAD_URL, description = "Resolve a JavaScript-generated download URL from a page. Loads the page in WebView with JS enabled.", category = "http", parameters = listOf(ToolParameter("url", ToolParameterType.String, true, "Page URL"), ToolParameter("headers", ToolParameterType.String, false, "JSON headers")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = HttpToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}