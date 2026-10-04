// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class TapResult(val success: Boolean, val error: String? = null) : ToolResultData
@Serializable data class ClickTextResult(val success: Boolean, val error: String? = null) : ToolResultData
@Serializable data class SwipeResult(val success: Boolean, val error: String? = null) : ToolResultData
@Serializable data class ScrollResult(val success: Boolean, val error: String? = null) : ToolResultData
@Serializable data class TypeTextResult(val success: Boolean, val error: String? = null) : ToolResultData
@Serializable data class ButtonResult(val success: Boolean, val error: String? = null) : ToolResultData
@Serializable data class OpenAppResult(val success: Boolean, val error: String? = null) : ToolResultData
@Serializable data class ScreenStateResult(val screenWidth: Int, val screenHeight: Int, val density: Float, val accessibilityRunning: Boolean, val currentPackage: String? = null, val currentClass: String? = null) : ToolResultData
@Serializable data class ElementInfo(val text: String? = null, val contentDescription: String? = null, val viewId: String? = null, val clickable: Boolean, val checkable: Boolean, val checked: Boolean, val enabled: Boolean, val boundsLeft: Int, val boundsTop: Int, val boundsRight: Int, val boundsBottom: Int, val centerX: Int, val centerY: Int) : ToolResultData
@Serializable data class FindElementsResult(val elements: List<ElementInfo>, val error: String? = null) : ToolResultData
@Serializable data class ScreenCaptureResult(val success: Boolean, val error: String? = null, val filePath: String? = null) : ToolResultData
@Serializable data class RequestPermissionResult(val success: Boolean, val error: String? = null) : ToolResultData
@Serializable data class AutoPairAdbResult(val success: Boolean, val error: String? = null, val pairingCode: String? = null, val pairingPort: Int? = null, val step: String? = null) : ToolResultData