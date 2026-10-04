// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.domain.model.BatteryInfo
import com.unuslumen.app.domain.model.StorageInfo
import com.unuslumen.app.domain.model.MemoryInfo
import com.unuslumen.app.domain.model.SecurityInfo
import kotlinx.serialization.Serializable

@Serializable data class HealthResult(val success: Boolean, val battery: BatteryInfo? = null, val storage: StorageInfo? = null, val memory: MemoryInfo? = null, val security: SecurityInfo? = null, val error: String? = null) : ToolResultData
@Serializable data class SystemDeviceInfoResult(val success: Boolean, val manufacturer: String? = null, val model: String? = null, val device: String? = null, val androidVersion: String? = null, val sdkVersion: Int? = null, val brand: String? = null, val product: String? = null, val board: String? = null, val hardware: String? = null, val error: String? = null) : ToolResultData
@Serializable data class SessionLogEntry(val timestamp: String, val level: String, val message: String, val metadata: Map<String, String> = emptyMap()) : ToolResultData
@Serializable data class SessionLogsResult(val success: Boolean, val logs: List<SessionLogEntry>, val error: String? = null) : ToolResultData
@Serializable data class SessionExportResult(val success: Boolean, val filePath: String? = null, val error: String? = null) : ToolResultData