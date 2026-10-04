// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class TranscribeResult(val success: Boolean, val text: String?, val language: String?, val duration: Double?, val error: String?) : ToolResultData
@Serializable data class TtsResult(val success: Boolean, val error: String?) : ToolResultData
@Serializable data class TtsVoice(val name: String, val locale: String, val features: List<String>) : ToolResultData
@Serializable data class TtsVoicesResult(val success: Boolean, val voices: List<TtsVoice>, val error: String?) : ToolResultData
@Serializable data class AudioConvertResult(val success: Boolean, val outputPath: String?, val error: String?) : ToolResultData
@Serializable data class AudioFileInfoResult(val success: Boolean, val duration: Double?, val bitrate: Int?, val sampleRate: Int?, val channels: Int?, val format: String?, val error: String?) : ToolResultData
@Serializable data class HearAudioResult(val success: Boolean, val transcript: String?, val confidence: Float?, val audioFound: Boolean, val error: String?) : ToolResultData