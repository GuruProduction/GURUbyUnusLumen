package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.domain.model.CameraDevice
import com.unuslumen.app.domain.model.CameraInfo
import kotlinx.serialization.Serializable

@Serializable data class CameraListResult(val success: Boolean, val cameras: List<CameraDevice>, val error: String?) : ToolResultData
@Serializable data class CameraSnapshotResult(val success: Boolean, val imagePath: String?, val error: String?) : ToolResultData
@Serializable data class CameraRecordResult(val success: Boolean, val videoPath: String?, val duration: Int?, val error: String?) : ToolResultData
@Serializable data class CameraMotionResult(val success: Boolean, val enabled: Boolean, val error: String?) : ToolResultData