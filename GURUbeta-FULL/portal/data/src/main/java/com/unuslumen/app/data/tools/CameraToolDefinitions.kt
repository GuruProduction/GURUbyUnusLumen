package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object CameraToolDefinitions : ToolSetRegistration {
    const val LIST_CAMERAS = "cameraList"
    const val SNAPSHOT = "cameraSnapshot"
    const val RECORD = "cameraRecord"
    const val MOTION_DETECT = "cameraMotionDetect"

    override val definitions = listOf(
        ToolDefinition(name = LIST_CAMERAS, description = "List discovered RTSP/ONVIF cameras on the network.", category = "camera", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = SNAPSHOT, description = "Capture a snapshot from a camera.", category = "camera", parameters = listOf(ToolParameter("camera", ToolParameterType.String, true, "Camera ID or RTSP URL"), ToolParameter("outputPath", ToolParameterType.String, false, "Output file path (optional)")), permissions = emptyList()),
        ToolDefinition(name = RECORD, description = "Record a video clip from a camera.", category = "camera", parameters = listOf(ToolParameter("camera", ToolParameterType.String, true, "Camera ID or RTSP URL"), ToolParameter("duration", ToolParameterType.Integer, true, "Recording duration in seconds"), ToolParameter("outputPath", ToolParameterType.String, false, "Output file path (optional)")), permissions = emptyList()),
        ToolDefinition(name = MOTION_DETECT, description = "Enable motion detection on a camera.", category = "camera", parameters = listOf(ToolParameter("camera", ToolParameterType.String, true, "Camera ID or RTSP URL"), ToolParameter("sensitivity", ToolParameterType.Integer, false, "Sensitivity level (1-100)"), ToolParameter("enable", ToolParameterType.Boolean, false, "Enable or disable motion detection")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = CameraToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}