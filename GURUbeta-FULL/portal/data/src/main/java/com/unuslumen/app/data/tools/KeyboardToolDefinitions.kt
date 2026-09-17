package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object KeyboardToolDefinitions : ToolSetRegistration {
    const val TYPE_TEXT = "keyboardTypeText"
    const val PRESS_KEY = "keyboardPressKey"
    const val PRESS_KEY_COMBO = "keyboardPressKeyCombo"
    const val HIDE_KEYBOARD = "keyboardHideKeyboard"

    override val definitions = listOf(
        ToolDefinition(name = TYPE_TEXT, description = "Type text into the currently focused input field. Use for filling forms, entering text, or automating text input anywhere on the device.", category = "keyboard", parameters = listOf(ToolParameter("text", ToolParameterType.String, true, "Text to type")), permissions = emptyList()),
        ToolDefinition(name = PRESS_KEY, description = "Simulate a key press. Use for keyboard shortcuts, navigation keys, or special keys. Key codes: 3=HOME, 4=BACK, 24=VOLUME_UP, 25=VOLUME_DOWN, 26=POWER, 66=ENTER, 67=DELETE, 82=MENU, 84=SEARCH.", category = "keyboard", parameters = listOf(ToolParameter("keyCode", ToolParameterType.Integer, true, "Android key code to press")), permissions = emptyList()),
        ToolDefinition(name = PRESS_KEY_COMBO, description = "Simulate a key combination. Use for shortcuts like Ctrl+C, Ctrl+V, etc. Provide comma-separated key codes.", category = "keyboard", parameters = listOf(ToolParameter("keyCodes", ToolParameterType.String, true, "Comma-separated key codes, e.g. '57,31' for Ctrl+A")), permissions = emptyList()),
        ToolDefinition(name = HIDE_KEYBOARD, description = "Hide the on-screen keyboard.", category = "keyboard", parameters = emptyList(), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = KeyboardToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}