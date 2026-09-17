package com.unuslumen.app.data.tools

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Locale

class SoundToolExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }
    private val audioManager: AudioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val vibrator: Vibrator by lazy { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator } else { @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator } }
    private var tts: TextToSpeech? = null; private var ttsReady = false

    private fun parseStreamType(stream: String): Int? = when (stream.lowercase()) { "alarm" -> AudioManager.STREAM_ALARM; "music" -> AudioManager.STREAM_MUSIC; "notification" -> AudioManager.STREAM_NOTIFICATION; "ring" -> AudioManager.STREAM_RING; "system" -> AudioManager.STREAM_SYSTEM; "voice_call" -> AudioManager.STREAM_VOICE_CALL; else -> null }

    private suspend fun initTts() = withContext(Dispatchers.Main) { if (tts != null && ttsReady) return@withContext; suspendCancellableCoroutine { cont -> var ttsInstance: TextToSpeech? = null; ttsInstance = TextToSpeech(context) { status -> if (status == TextToSpeech.SUCCESS) { val localeResult = ttsInstance?.setLanguage(Locale.getDefault()); ttsReady = localeResult != TextToSpeech.LANG_NOT_SUPPORTED && localeResult != TextToSpeech.LANG_MISSING_DATA; if (!ttsReady) { val en = ttsInstance?.setLanguage(Locale.US); ttsReady = en != TextToSpeech.LANG_NOT_SUPPORTED } } else { ttsReady = false }; tts = ttsInstance; if (cont.isActive) cont.resumeWith(Result.success(Unit)) } } }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        SoundToolDefinitions.GET_VOLUME -> getVolume(args)
        SoundToolDefinitions.SET_VOLUME -> setVolume(args)
        SoundToolDefinitions.ADJUST_VOLUME -> adjustVolume(args)
        SoundToolDefinitions.GET_RINGER_MODE -> { val mode = when (audioManager.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; AudioManager.RINGER_MODE_NORMAL -> "normal"; else -> "unknown" }; val r = RingerModeResult(true, mode, null); ToolExecutionResult.success(r, json.encodeToString(RingerModeResult.serializer(), r)) }
        SoundToolDefinitions.SET_RINGER_MODE -> setRingerMode(args)
        SoundToolDefinitions.SPEAK_TEXT -> speakText(args)
        SoundToolDefinitions.STOP_SPEAKING -> { tts?.stop(); val r = SpeakResult(true, null); ToolExecutionResult.success(r, json.encodeToString(SpeakResult.serializer(), r)) }
        SoundToolDefinitions.VIBRATE -> vibrate(args)
        SoundToolDefinitions.PLAY_RINGTONE -> playRingtone(args)
        SoundToolDefinitions.PLAY_SOUND_FILE -> playSoundFile(args)
        SoundToolDefinitions.STOP_SOUND -> { val r = PlaySoundResult(true, "stop", emptyList(), null); ToolExecutionResult.success(r, json.encodeToString(PlaySoundResult.serializer(), r)) }
        SoundToolDefinitions.GET_AUDIO_INFO -> getAudioInfo()
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private fun getVolume(args: Map<String, Any?>): ToolExecutionResult {
        val stream = args["stream"] as? String ?: return ToolExecutionResult.error("Missing 'stream'"); val streamType = parseStreamType(stream) ?: return ToolExecutionResult.error("Unknown stream: $stream")
        val current = audioManager.getStreamVolume(streamType); val max = audioManager.getStreamMaxVolume(streamType); val pct = if (max > 0) current * 100 / max else 0
        val r = VolumeResult(true, current, max, pct, stream, null); return ToolExecutionResult.success(r, json.encodeToString(VolumeResult.serializer(), r))
    }

    private fun setVolume(args: Map<String, Any?>): ToolExecutionResult {
        val stream = args["stream"] as? String ?: return ToolExecutionResult.error("Missing 'stream'"); val level = (args["level"] as? Number)?.toInt() ?: return ToolExecutionResult.error("Missing 'level'"); val streamType = parseStreamType(stream) ?: return ToolExecutionResult.error("Unknown stream: $stream")
        val max = audioManager.getStreamMaxVolume(streamType); try { audioManager.setStreamVolume(streamType, level.coerceIn(0, max), 0) } catch (e: SecurityException) { val r = VolumeResult(false, audioManager.getStreamVolume(streamType), max, audioManager.getStreamVolume(streamType) * 100 / max, stream, "Permission denied: ${e.message}"); return ToolExecutionResult.success(r, json.encodeToString(VolumeResult.serializer(), r)) }
        val newLevel = audioManager.getStreamVolume(streamType); val pct = if (max > 0) newLevel * 100 / max else 0; val r = VolumeResult(true, newLevel, max, pct, stream, null); return ToolExecutionResult.success(r, json.encodeToString(VolumeResult.serializer(), r))
    }

    private fun adjustVolume(args: Map<String, Any?>): ToolExecutionResult {
        val stream = args["stream"] as? String ?: return ToolExecutionResult.error("Missing 'stream'"); val direction = args["direction"] as? String ?: return ToolExecutionResult.error("Missing 'direction'"); val steps = (args["steps"] as? Number)?.toInt() ?: 1; val streamType = parseStreamType(stream) ?: return ToolExecutionResult.error("Unknown stream: $stream")
        val flag = when (direction.lowercase()) { "up" -> AudioManager.ADJUST_RAISE; "down" -> AudioManager.ADJUST_LOWER; else -> return ToolExecutionResult.error("Invalid direction: $direction") }
        repeat(steps) { audioManager.adjustStreamVolume(streamType, flag, 0) }
        val current = audioManager.getStreamVolume(streamType); val max = audioManager.getStreamMaxVolume(streamType); val pct = if (max > 0) current * 100 / max else 0; val r = VolumeResult(true, current, max, pct, stream, null); return ToolExecutionResult.success(r, json.encodeToString(VolumeResult.serializer(), r))
    }

    private fun setRingerMode(args: Map<String, Any?>): ToolExecutionResult {
        val mode = args["mode"] as? String ?: return ToolExecutionResult.error("Missing 'mode'"); val ringerMode = when (mode.lowercase()) { "silent" -> AudioManager.RINGER_MODE_SILENT; "vibrate" -> AudioManager.RINGER_MODE_VIBRATE; "normal" -> AudioManager.RINGER_MODE_NORMAL; else -> return ToolExecutionResult.error("Invalid mode: $mode") }
        try { audioManager.ringerMode = ringerMode; val currentMode = when (audioManager.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; AudioManager.RINGER_MODE_NORMAL -> "normal"; else -> "unknown" }; val r = RingerModeResult(true, currentMode, null); return ToolExecutionResult.success(r, json.encodeToString(RingerModeResult.serializer(), r)) } catch (e: SecurityException) { val r = RingerModeResult(false, mode, "Permission denied: ${e.message}"); return ToolExecutionResult.success(r, json.encodeToString(RingerModeResult.serializer(), r)) }
    }

    private suspend fun speakText(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.Main) {
        val text = args["text"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'text'"); val rate = (args["rate"] as? Number)?.toFloat() ?: 1.0f; val pitch = (args["pitch"] as? Number)?.toFloat() ?: 1.0f
        if (tts == null || !ttsReady) { initTts() }; val engine = tts ?: return@withContext ToolExecutionResult.error("TTS not available"); if (!ttsReady) return@withContext ToolExecutionResult.error("TTS not ready")
        engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f)); engine.setPitch(pitch.coerceIn(0.5f, 2.0f)); engine.setLanguage(Locale.getDefault()); engine.speak(text, TextToSpeech.QUEUE_ADD, null, "guru_speak_${System.currentTimeMillis()}")
        val wordCount = text.split(Regex("\\s+")).size; val estMs = ((wordCount / 150.0) * 60000 / rate).toLong().coerceIn(500, 30000); withContext(Dispatchers.IO) { delay(estMs) }
        val r = SpeakResult(true, null); ToolExecutionResult.success(r, json.encodeToString(SpeakResult.serializer(), r))
    }

    private suspend fun vibrate(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.Main) {
        if (!vibrator.hasVibrator()) return@withContext ToolExecutionResult.error("No vibrator"); val patternStr = args["pattern"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'pattern'"); val pattern = patternStr.split(",").mapNotNull { it.trim().toLongOrNull() }; if (pattern.isEmpty()) return@withContext ToolExecutionResult.error("Invalid pattern"); val totalDuration = pattern.sum(); if (totalDuration > 5000) return@withContext ToolExecutionResult.error("Pattern too long (${totalDuration}ms). Max 5000ms."); val repeatCount = ((args["repeatCount"] as? Number)?.toInt() ?: 1).coerceIn(1, 3)
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) VibrationEffect.createWaveform(pattern.toLongArray(), -1) else null
        repeat(repeatCount) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && effect != null) vibrator.vibrate(effect) else { @Suppress("DEPRECATION") vibrator.vibrate(pattern.toLongArray(), -1) }; delay(totalDuration + 200) }
        val r = VibrateResult(true, null); ToolExecutionResult.success(r, json.encodeToString(VibrateResult.serializer(), r))
    }

    private suspend fun playRingtone(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val type = args["type"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'type'"); val ringtoneType = when (type.lowercase()) { "alarm" -> RingtoneManager.TYPE_ALARM; "notification" -> RingtoneManager.TYPE_NOTIFICATION; "ringtone" -> RingtoneManager.TYPE_RINGTONE; "all" -> null; else -> return@withContext ToolExecutionResult.error("Unknown type: $type") }
        val types = if (ringtoneType != null) listOf(ringtoneType) else listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_NOTIFICATION, RingtoneManager.TYPE_RINGTONE)
        val sounds = mutableListOf<SoundItem>(); for (t in types) { val rm = RingtoneManager(context); rm.setType(t); val cursor = rm.cursor; if (cursor != null && cursor.moveToFirst()) { val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX) ?: "Unknown"; val uri = cursor.getString(RingtoneManager.URI_COLUMN_INDEX) ?: ""; sounds.add(SoundItem(title, uri, type)); val ringtone = RingtoneManager.getRingtone(context, android.net.Uri.parse(uri)); ringtone?.play(); delay(1500); ringtone?.stop() } }
        val r = if (sounds.isEmpty()) PlaySoundResult(false, type, emptyList(), "No ringtones for type: $type") else PlaySoundResult(true, type, sounds, null); ToolExecutionResult.success(r, json.encodeToString(PlaySoundResult.serializer(), r))
    }

    private suspend fun playSoundFile(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val filePath = args["filePath"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'filePath'"); val file = java.io.File(filePath); if (!file.exists()) return@withContext ToolExecutionResult.error("File not found: $filePath")
        try { val mp = MediaPlayer(); mp.setDataSource(filePath); mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()); mp.prepare(); mp.start(); mp.setOnCompletionListener { it.release() }; mp.setOnErrorListener { mp, _, _ -> mp.release(); true }; val r = PlaySoundResult(true, "file", listOf(SoundItem(file.name, filePath, "file")), null); ToolExecutionResult.success(r, json.encodeToString(PlaySoundResult.serializer(), r)) } catch (e: Exception) { val r = PlaySoundResult(false, "file", emptyList(), "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(PlaySoundResult.serializer(), r)) }
    }

    private fun getAudioInfo(): ToolExecutionResult {
        val streams = mapOf("alarm" to AudioManager.STREAM_ALARM, "music" to AudioManager.STREAM_MUSIC, "notification" to AudioManager.STREAM_NOTIFICATION, "ring" to AudioManager.STREAM_RING, "system" to AudioManager.STREAM_SYSTEM, "voice_call" to AudioManager.STREAM_VOICE_CALL)
        val volumes = streams.map { (name, type) -> val cur = audioManager.getStreamVolume(type); val max = audioManager.getStreamMaxVolume(type); VolumeLevel(name, cur, max, if (max > 0) cur * 100 / max else 0, cur == 0) }
        val ringerMode = when (audioManager.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; AudioManager.RINGER_MODE_NORMAL -> "normal"; else -> "unknown" }
        @Suppress("DEPRECATION") val r = AudioInfoResult(true, volumes, ringerMode, audioManager.isMusicActive, audioManager.isSpeakerphoneOn, audioManager.isBluetoothScoOn, vibrator.hasVibrator(), null); return ToolExecutionResult.success(r, json.encodeToString(AudioInfoResult.serializer(), r))
    }
}