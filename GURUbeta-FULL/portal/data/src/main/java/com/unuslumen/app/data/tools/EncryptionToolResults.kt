package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class CryptoResult(val success: Boolean, val result: String, val error: String?) : ToolResultData