package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class VolumeResult(val success: Boolean, val currentVolume: Int, val maxVolume: Int, val percentage: Int, val stream: String, val error: String? = null) : ToolResultData
@Serializable data class RingerModeResult(val success: Boolean, val mode: String, val error: String? = null) : ToolResultData
@Serializable data class SpeakResult(val success: Boolean, val error: String? = null) : ToolResultData
@Serializable data class VibrateResult(val success: Boolean, val error: String? = null) : ToolResultData
@Serializable data class SoundItem(val title: String, val uri: String, val type: String) : ToolResultData
@Serializable data class PlaySoundResult(val success: Boolean, val soundType: String, val sounds: List<SoundItem> = emptyList(), val error: String? = null) : ToolResultData
@Serializable data class VolumeLevel(val stream: String, val currentVolume: Int, val maxVolume: Int, val percentage: Int, val isMuted: Boolean) : ToolResultData
@Serializable data class AudioInfoResult(val success: Boolean, val volumes: List<VolumeLevel>, val ringerMode: String, val isMusicActive: Boolean, val isSpeakerphoneOn: Boolean, val isBluetoothScoOn: Boolean, val hasVibrator: Boolean, val error: String? = null) : ToolResultData