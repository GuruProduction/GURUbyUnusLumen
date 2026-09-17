package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object IntentToolDefinitions : ToolSetRegistration {
    const val SEND_INTENT = "sendIntent"
    const val SEND_BROADCAST = "sendBroadcast"
    const val QUERY_INTENT_ACTIVITIES = "queryIntentActivities"

    override val definitions = listOf(
        ToolDefinition(name = SEND_INTENT, description = "Send an Android intent. This is the real superpower — you can fire intents at any app to open URLs, share files, trigger actions, start activities. Use for controlling other apps and system functions.", category = "intent", parameters = listOf(ToolParameter("action", ToolParameterType.String, true, "Intent action, e.g. 'android.intent.action.VIEW', 'android.intent.action.SEND'"), ToolParameter("dataUri", ToolParameterType.String, false, "Data URI, e.g. 'https://example.com' or 'file:///path/to/file'"), ToolParameter("mimeType", ToolParameterType.String, false, "MIME type, e.g. 'text/plain', 'image/*'"), ToolParameter("packageName", ToolParameterType.String, false, "Package name to target a specific app, empty for any"), ToolParameter("extras", ToolParameterType.String, false, "JSON object of extras, e.g. '{\"android.intent.extra.TEXT\":\"Hello\"}'")), permissions = emptyList()),
        ToolDefinition(name = SEND_BROADCAST, description = "Send a broadcast intent. Use for system-wide events that other apps can receive.", category = "intent", parameters = listOf(ToolParameter("action", ToolParameterType.String, true, "Broadcast action, e.g. 'com.example.CUSTOM_EVENT'"), ToolParameter("extras", ToolParameterType.String, false, "JSON object of extras")), permissions = emptyList()),
        ToolDefinition(name = QUERY_INTENT_ACTIVITIES, description = "Find apps that can handle a specific intent. Use to discover what apps can open a URL, share a file, or handle an action.", category = "intent", parameters = listOf(ToolParameter("action", ToolParameterType.String, true, "Intent action, e.g. 'android.intent.action.VIEW'"), ToolParameter("dataUri", ToolParameterType.String, false, "Data URI to match")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = IntentToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}