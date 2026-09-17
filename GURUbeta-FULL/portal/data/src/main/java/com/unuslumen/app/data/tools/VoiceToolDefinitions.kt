package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object VoiceToolDefinitions : ToolSetRegistration {
    const val TRANSCRIBE_AUDIO = "transcribeAudio"; const val TRANSCRIBE_SPEECH = "transcribeSpeech"
    const val TTS_SPEAK = "ttsSpeak"
    const val TTS_STOP = "ttsStop"; const val TTS_VOICES = "ttsVoices"
    const val AUDIO_CONVERT = "audioConvert"; const val AUDIO_TRIM = "audioTrim"; const val AUDIO_INFO = "audioInfo"
    const val HEAR_AUDIO = "hearAudio"

    override val definitions = listOf(
        ToolDefinition(name = TRANSCRIBE_AUDIO, description = "Transcribe an audio file to text fully on-device using the bundled Vosk speech engine. No network, no API key, no cloud. Works on audio files; for video files pass the video path and the audio track is extracted first.", category = "voice", parameters = listOf(ToolParameter("audioPath", ToolParameterType.String, true, "Path to audio or video file"), ToolParameter("language", ToolParameterType.String, false, "Language code, e.g. en-GB")), permissions = emptyList()),
        ToolDefinition(name = TRANSCRIBE_SPEECH, description = "Record audio from the microphone on-device and transcribe it with the bundled Vosk engine. No network, no API key.", category = "voice", parameters = listOf(ToolParameter("durationSeconds", ToolParameterType.Integer, false, "Recording duration, default 10"), ToolParameter("language", ToolParameterType.String, false, "Language code, e.g. en-GB")), permissions = listOf("android.permission.RECORD_AUDIO")),
        ToolDefinition(name = HEAR_AUDIO, description = "Hear the sound on any audio OR VIDEO file. Extracts the audio track on-device and transcribes it with the bundled Vosk speech engine — 100% on-device, no API key, no network. Works on video files too: pass the video path and this returns what is said in it. Use this FIRST for any video with speech.", category = "voice", parameters = listOf(ToolParameter("filePath", ToolParameterType.String, true, "Path to audio or video file"), ToolParameter("language", ToolParameterType.String, false, "Language code, e.g. en-GB")), permissions = emptyList()),
        ToolDefinition(name = TTS_SPEAK, description = "Speak text aloud using TTS with control over voice, speed, and pitch.", category = "voice", parameters = listOf(ToolParameter("text", ToolParameterType.String, true, "Text to speak"), ToolParameter("rate", ToolParameterType.Float, false, "Rate 0.25-2.0"), ToolParameter("pitch", ToolParameterType.Float, false, "Pitch 0.5-2.0"), ToolParameter("language", ToolParameterType.String, false, "Language tag")), permissions = emptyList()),
        ToolDefinition(name = TTS_STOP, description = "Stop any ongoing text-to-speech immediately.", category = "voice", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = TTS_VOICES, description = "List available TTS voices/languages on the device.", category = "voice", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = AUDIO_CONVERT, description = "Convert audio between formats.", category = "voice", parameters = listOf(ToolParameter("inputPath", ToolParameterType.String, true, "Source audio file"), ToolParameter("format", ToolParameterType.String, true, "Output format: mp3, wav, m4a, ogg, flac"), ToolParameter("outputName", ToolParameterType.String, false, "Output filename")), permissions = emptyList()),
        ToolDefinition(name = AUDIO_TRIM, description = "Trim an audio file to a specific time range.", category = "voice", parameters = listOf(ToolParameter("inputPath", ToolParameterType.String, true, "Source audio"), ToolParameter("startSeconds", ToolParameterType.Float, true, "Start time"), ToolParameter("endSeconds", ToolParameterType.Float, true, "End time"), ToolParameter("outputName", ToolParameterType.String, false, "Output filename")), permissions = emptyList()),
        ToolDefinition(name = AUDIO_INFO, description = "Get information about an audio file.", category = "voice", parameters = listOf(ToolParameter("inputPath", ToolParameterType.String, true, "Audio file path")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = VoiceToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}