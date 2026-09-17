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