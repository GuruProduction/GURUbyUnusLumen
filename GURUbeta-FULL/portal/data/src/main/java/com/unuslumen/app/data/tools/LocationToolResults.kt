// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class LocationResult(val success: Boolean, val latitude: Double = 0.0, val longitude: Double = 0.0, val accuracy: Double = 0.0, val altitude: Double = 0.0, val speed: Double = 0.0, val bearing: Double = 0.0, val provider: String = "", val time: Long = 0, val error: String? = null) : ToolResultData
@Serializable data class GeocodeEntry(val latitude: Double, val longitude: Double, val address: String, val locality: String, val country: String) : ToolResultData
@Serializable data class GeocodeResult(val success: Boolean, val results: List<GeocodeEntry>, val error: String? = null) : ToolResultData