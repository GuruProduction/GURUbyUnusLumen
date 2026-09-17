package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object SoundToolDefinitions : ToolSetRegistration {
    const val GET_VOLUME = "getVolume"; const val SET_VOLUME = "setVolume"; const val ADJUST_VOLUME = "adjustVolume"
    const val GET_RINGER_MODE = "getRingerMode"; const val SET_RINGER_MODE = "setRingerMode"
    const val SPEAK_TEXT = "speakText"; const val STOP_SPEAKING = "stopSpeaking"; const val VIBRATE = "vibrate"
    const val PLAY_RINGTONE = "playRingtone"; const val PLAY_SOUND_FILE = "playSoundFile"; const val STOP_SOUND = "stopSound"
    const val GET_AUDIO_INFO = "getAudioInfo"

    override val definitions = listOf(
        ToolDefinition(name = GET_VOLUME, description = "Get the current volume level for an audio stream.", category = "sound", parameters = listOf(ToolParameter("stream", ToolParameterType.String, true, "Stream: alarm, music, notification, ring, system, voice_call")), permissions = emptyList()),
        ToolDefinition(name = SET_VOLUME, description = "Set the volume level for an audio stream.", category = "sound", parameters = listOf(ToolParameter("stream", ToolParameterType.String, true, "Stream type"), ToolParameter("level", ToolParameterType.Integer, true, "Volume level 0 to max")), permissions = emptyList()),
        ToolDefinition(name = ADJUST_VOLUME, description = "Adjust volume up or down by steps.", category = "sound", parameters = listOf(ToolParameter("stream", ToolParameterType.String, true, "Stream type"), ToolParameter("direction", ToolParameterType.String, true, "up or down"), ToolParameter("steps", ToolParameterType.Integer, false, "Number of steps")), permissions = emptyList()),
        ToolDefinition(name = GET_RINGER_MODE, description = "Get the current ringer mode.", category = "sound", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = SET_RINGER_MODE, description = "Set the ringer mode.", category = "sound", parameters = listOf(ToolParameter("mode", ToolParameterType.String, true, "silent, vibrate, or normal")), permissions = emptyList()),
        ToolDefinition(name = SPEAK_TEXT, description = "Speak text aloud using text-to-speech.", category = "sound", parameters = listOf(ToolParameter("text", ToolParameterType.String, true, "Text to speak"), ToolParameter("rate", ToolParameterType.Float, false, "Rate 0.5-2.0"), ToolParameter("pitch", ToolParameterType.Float, false, "Pitch 0.5-2.0")), permissions = emptyList()),
        ToolDefinition(name = STOP_SPEAKING, description = "Stop any currently playing text-to-speech.", category = "sound", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = VIBRATE, description = "Make the device vibrate with a pattern.", category = "sound", parameters = listOf(ToolParameter("pattern", ToolParameterType.String, true, "Vibration pattern ms, comma-separated"), ToolParameter("repeatCount", ToolParameterType.Integer, false, "Repeat count")), permissions = emptyList()),
        ToolDefinition(name = PLAY_RINGTONE, description = "Play a system ringtone, notification, or alarm sound.", category = "sound", parameters = listOf(ToolParameter("type", ToolParameterType.String, true, "alarm, notification, ringtone, or all")), permissions = emptyList()),
        ToolDefinition(name = PLAY_SOUND_FILE, description = "Play a sound file from the device.", category = "sound", parameters = listOf(ToolParameter("filePath", ToolParameterType.String, true, "Audio file path")), permissions = emptyList()),
        ToolDefinition(name = STOP_SOUND, description = "Stop any currently playing sound file.", category = "sound", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = GET_AUDIO_INFO, description = "Get comprehensive audio information about the device.", category = "sound", parameters = emptyList(), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = SoundToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}