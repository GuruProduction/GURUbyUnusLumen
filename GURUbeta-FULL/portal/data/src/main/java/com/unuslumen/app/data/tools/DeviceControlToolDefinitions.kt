package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object DeviceControlToolDefinitions : ToolSetRegistration {
    const val TAP_SCREEN = "tapScreen"
    const val CLICK_TEXT = "clickText"
    const val SWIPE = "swipe"
    const val SCROLL = "scroll"
    const val TYPE_TEXT = "typeText"
    const val PRESS_BUTTON = "pressButton"
    const val OPEN_APP = "openApp"
    const val GET_SCREEN_STATE = "getScreenState"
    const val FIND_ELEMENTS = "findElements"
    const val CAPTURE_SCREEN = "captureScreen"
    const val REQUEST_SCREEN_CAPTURE_PERMISSION = "requestScreenCapturePermission"
    const val AUTO_PAIR_ADB = "autoPairAdb"

    override val definitions = listOf(
        ToolDefinition(name = TAP_SCREEN, description = "Tap at specific screen coordinates. Use this to press buttons, click UI elements, or interact with any on-screen content. Requires Accessibility Service to be enabled.", category = "device_control", parameters = listOf(ToolParameter("x", ToolParameterType.Integer, true, "X coordinate in pixels. Screen left edge is 0."), ToolParameter("y", ToolParameterType.Integer, true, "Y coordinate in pixels. Screen top edge is 0.")), permissions = emptyList()),
        ToolDefinition(name = CLICK_TEXT, description = "Find and click UI element by its visible text. Searches for buttons, labels, or any text on screen and clicks it. Use this when you can see text on screen but don't know exact coordinates.", category = "device_control", parameters = listOf(ToolParameter("text", ToolParameterType.String, true, "The visible text to search for and click"), ToolParameter("exact", ToolParameterType.Boolean, false, "Whether to match exact text only (true) or partial match (false). Default true.")), permissions = emptyList()),
        ToolDefinition(name = SWIPE, description = "Perform a swipe gesture from one point to another. Use for scrolling, unlocking, or any drag operation.", category = "device_control", parameters = listOf(ToolParameter("startX", ToolParameterType.Integer, true, "Starting X coordinate"), ToolParameter("startY", ToolParameterType.Integer, true, "Starting Y coordinate"), ToolParameter("endX", ToolParameterType.Integer, true, "Ending X coordinate"), ToolParameter("endY", ToolParameterType.Integer, true, "Ending Y coordinate"), ToolParameter("durationMs", ToolParameterType.Integer, false, "Duration of swipe in milliseconds. Default 300. Longer = slower swipe.")), permissions = emptyList()),
        ToolDefinition(name = SCROLL, description = "Scroll the current screen. Use 'down' to see more content below, 'up' to go back up.", category = "device_control", parameters = listOf(ToolParameter("direction", ToolParameterType.String, true, "Direction to scroll: 'up' or 'down'")), permissions = emptyList()),
        ToolDefinition(name = TYPE_TEXT, description = "Type text into the currently focused input field. First tap a text field, then use this to enter text.", category = "device_control", parameters = listOf(ToolParameter("text", ToolParameterType.String, true, "Text to type into the input field")), permissions = emptyList()),
        ToolDefinition(name = PRESS_BUTTON, description = "Press a system button: back, home, recents (app switcher), power dialog, or quick settings.", category = "device_control", parameters = listOf(ToolParameter("button", ToolParameterType.String, true, "Button to press: 'back', 'home', 'recents', 'power', 'quick_settings'")), permissions = emptyList()),
        ToolDefinition(name = OPEN_APP, description = "Open an app by package name. Launches the app and waits for it to appear.", category = "device_control", parameters = listOf(ToolParameter("packageName", ToolParameterType.String, true, "Package name of the app to open, e.g. 'com.android.settings' for Settings")), permissions = emptyList()),
        ToolDefinition(name = GET_SCREEN_STATE, description = "Get current screen state: screen dimensions, accessibility service status, and basic info about what's visible.", category = "device_control", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = FIND_ELEMENTS, description = "Find all clickable elements on the current screen. Returns list of text, bounds, and view IDs. Use this to discover what can be tapped.", category = "device_control", parameters = listOf(ToolParameter("textFilter", ToolParameterType.String, false, "Filter to only include elements containing this text (optional)")), permissions = emptyList()),
        ToolDefinition(name = CAPTURE_SCREEN, description = "Capture a screenshot of the current screen. Returns the file path. Requires screen capture permission - will request it if not granted.", category = "device_control", parameters = listOf(ToolParameter("filename", ToolParameterType.String, false, "Filename for the screenshot (without extension). Default 'screenshot'")), permissions = emptyList()),
        ToolDefinition(name = REQUEST_SCREEN_CAPTURE_PERMISSION, description = "Request screen capture permission. This will open the app and show a system dialog asking the user to allow screen recording. Returns whether permission was requested.", category = "device_control", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = AUTO_PAIR_ADB, description = "Automatically pair with ADB Wireless Debugging. Navigates to Settings > Developer Options > Wireless Debugging, opens the pairing dialog, reads the pairing code and port, and pairs automatically. The user just needs Accessibility Service enabled. This handles the entire pairing flow without the user needing to manually enter codes.", category = "device_control", parameters = emptyList(), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = DeviceControlToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}