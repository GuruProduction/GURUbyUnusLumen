package com.unuslumen.app.data.tools

import android.content.Context
import android.speech.tts.TextToSpeech
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import com.unuslumen.app.data.tools.SpeechRecognition.Result.Ok as RecOk
import com.unuslumen.app.data.tools.SpeechRecognition.Result.Empty as RecEmpty
import com.unuslumen.app.data.tools.SpeechRecognition.Result.Failed as RecFailed

class VoiceToolExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        VoiceToolDefinitions.TRANSCRIBE_AUDIO -> transcribeAudio(args)
        VoiceToolDefinitions.TRANSCRIBE_SPEECH -> transcribeSpeech(args)
        VoiceToolDefinitions.HEAR_AUDIO -> hearAudio(args)
        VoiceToolDefinitions.TTS_SPEAK -> ttsSpeak(args)
        VoiceToolDefinitions.TTS_STOP -> { tts?.stop(); val r = TtsResult(true, null); ToolExecutionResult.success(r, json.encodeToString(TtsResult.serializer(), r)) }
        VoiceToolDefinitions.TTS_VOICES -> ttsVoices()
        VoiceToolDefinitions.AUDIO_CONVERT -> audioConvert(args)
        VoiceToolDefinitions.AUDIO_TRIM -> audioTrim(args)
        VoiceToolDefinitions.AUDIO_INFO -> audioInfo(args)
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    /**
     * HEAR the sound on any audio or VIDEO file. 100% on-device, zero cloud, zero
     * API keys, zero network:
     *   1. AudioNative.extractTrackToWav decodes the file's audio track with the
     *      platform MediaExtractor/MediaCodec — works on audio and video
     *      containers alike (mp4/mov/webm/mkv audio tracks included).
     *   2. SpeechRecognitionEngine transcribes the WAV with the bundled Vosk
     *      model, entirely offline.
     * Returns what was SAID. When the file has no audio track at all, returns
     * audioFound=false honestly instead of pretending.
     */
    private suspend fun hearAudio(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val filePath = args["filePath"] as? String
            ?: return@withContext ToolExecutionResult.error("Missing 'filePath'")
        val language = args["language"] as? String
        val file = File(filePath)
        if (!file.exists()) return@withContext ToolExecutionResult.error("File not found: $filePath")

        // 1. Extract the audio track to a WAV, in-process
        val wav = File(context.cacheDir, "hear_${System.currentTimeMillis()}.wav")
        val extractError = AudioNative.extractTrackToWav(file, wav)
        if (extractError != null || !wav.exists() || wav.length() < 100L) {
            val r = HearAudioResult(false, null, null, audioFound = false,
                error = extractError ?: "No audible audio track found in this file")
            wav.delete()
            return@withContext ToolExecutionResult.success(r, json.encodeToString(HearAudioResult.serializer(), r))
        }

        // 2. Transcribe on-device with the bundled engine
        val recognition = SpeechRecognition.transcribeWav(context, wav, language)
        wav.delete()

        val r = when (recognition) {
            is RecOk -> HearAudioResult(
                success = true,
                transcript = recognition.text,
                confidence = recognition.confidence,
                audioFound = true,
                error = null
            )
            is RecEmpty -> HearAudioResult(
                true, null, null, true,
                "Audio track decoded but no recognisable speech in it"
            )
            is RecFailed -> HearAudioResult(
                false, null, null, true,
                "Audio track extracted but transcription failed: ${recognition.reason}"
            )
        }
        ToolExecutionResult.success(r, json.encodeToString(HearAudioResult.serializer(), r))
    }

    private suspend fun transcribeAudio(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val audioPath = args["audioPath"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'audioPath'")
        val language = args["language"] as? String
        val file = File(audioPath); if (!file.exists()) return@withContext ToolExecutionResult.error("File not found: $audioPath")

        // Native containers (mp4/m4a/3gp family) decode straight through the
        // platform pipeline; odd audio containers get converted to WAV first.
        val isVideo = file.extension.lowercase() in FileProcessingToolExecutor.videoExtensions
        val workFile: File
        val wavTemp: File?
        if (isVideo) {
            wavTemp = File(context.cacheDir, "transcribe_${System.currentTimeMillis()}.wav")
            val extractError = AudioNative.extractTrackToWav(file, wavTemp)
            if (extractError != null || !wavTemp.exists() || wavTemp.length() < 100L) {
                wavTemp?.delete()
                val r = TranscribeResult(false, null, language, null, extractError ?: "No audio track found in file")
                return@withContext ToolExecutionResult.success(r, json.encodeToString(TranscribeResult.serializer(), r))
            }
            workFile = wavTemp
        } else {
            workFile = file
            wavTemp = null
        }

        val started = System.currentTimeMillis()
        val recognition = SpeechRecognition.transcribeWav(context, workFile, language)
        if (wavTemp != null) wavTemp.delete()
        val durationSec = ((System.currentTimeMillis() - started) / 1000.0)

        val r = when (recognition) {
            is RecOk -> TranscribeResult(true, recognition.text, language, durationSec, null)
            is RecEmpty -> TranscribeResult(true, "", language, durationSec, "No recognisable speech found")
            is RecFailed -> TranscribeResult(false, null, language, null, recognition.reason)
        }
        ToolExecutionResult.success(r, json.encodeToString(TranscribeResult.serializer(), r))
    }

    private suspend fun transcribeSpeech(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val durationSeconds = (args["durationSeconds"] as? Number)?.toInt() ?: 10; val language = args["language"] as? String
        val audioFile = File(context.cacheDir, "speech_${System.currentTimeMillis()}.m4a")
        val recordError = MicRecorder.record(context, audioFile, durationSeconds)
        if (recordError != null || !audioFile.exists() || audioFile.length() < 100L) {
            audioFile.delete()
            return@withContext ToolExecutionResult.error("Failed to record audio: ${recordError ?: "empty recording"}")
        }

        val wav = File(context.cacheDir, "speech_${System.currentTimeMillis()}.wav")
        val extractError = AudioNative.extractTrackToWav(audioFile, wav)
        audioFile.delete()
        if (extractError != null || !wav.exists() || wav.length() < 100L) {
            wav.delete()
            return@withContext ToolExecutionResult.error("Could not decode recording: ${extractError ?: "empty audio"}")
        }

        val recognition = SpeechRecognition.transcribeWav(context, wav, language)
        wav.delete()

        val r = when (recognition) {
            is RecOk -> TranscribeResult(true, recognition.text, language, durationSeconds.toDouble(), null)
            is RecEmpty -> TranscribeResult(true, "", language, durationSeconds.toDouble(), "No speech captured")
            is RecFailed -> TranscribeResult(false, null, language, null, recognition.reason)
        }
        ToolExecutionResult.success(r, json.encodeToString(TranscribeResult.serializer(), r))
    }

    private suspend fun ttsSpeak(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.Main) {
        val text = args["text"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'text'")
        val rate = (args["rate"] as? Number)?.toFloat() ?: 1.0f; val pitch = (args["pitch"] as? Number)?.toFloat() ?: 1.0f; val language = args["language"] as? String ?: "en-US"
        if (tts == null || !ttsReady) { initTts() }
        val engine = tts ?: return@withContext ToolExecutionResult.error("TTS not available")
        if (!ttsReady) return@withContext ToolExecutionResult.error("TTS not ready")
        engine.setLanguage(Locale.forLanguageTag(language)); engine.setSpeechRate(rate.coerceIn(0.25f, 2.0f)); engine.setPitch(pitch.coerceIn(0.5f, 2.0f))
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, "guru_tts_${System.currentTimeMillis()}")
        val wordCount = text.split(Regex("\\s+")).size; val estMs = ((wordCount / 150.0) * 60000 / rate).toLong().coerceIn(500, 120000)
        withContext(Dispatchers.IO) { delay(estMs) }
        val r = TtsResult(true, null); ToolExecutionResult.success(r, json.encodeToString(TtsResult.serializer(), r))
    }

    private suspend fun ttsVoices(): ToolExecutionResult = withContext(Dispatchers.Main) {
        if (tts == null || !ttsReady) { initTts() }
        val engine = tts ?: return@withContext ToolExecutionResult.error("TTS not available")
        val voices = engine.voices?.map { TtsVoice(it.name, it.locale.toLanguageTag(), it.features?.toList() ?: emptyList()) } ?: emptyList()
        val r = TtsVoicesResult(true, voices, null); ToolExecutionResult.success(r, json.encodeToString(TtsVoicesResult.serializer(), r))
    }

    private suspend fun audioConvert(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val inputPath = args["inputPath"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'inputPath'")
        val format = args["format"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'format'")
        val outputName = args["outputName"] as? String ?: "converted"
        val inputFile = File(inputPath)
        if (!inputFile.exists()) return@withContext ToolExecutionResult.error("File not found: $inputPath")

        // Native conversion via MediaExtractor/MediaCodec/MediaMuxer. Android ships an
        // AAC encoder but no MP3/OGG/FLAC encoder — be explicit instead of pretending.
        val targetFormat = format.lowercase().trim()
        if (targetFormat !in listOf("m4a", "wav", "aac")) {
            val r = AudioConvertResult(
                false, null,
                "Format '$format' is not supported on-device. Android can encode m4a (AAC) and wav (PCM). mp3/ogg/flac need bundled encoders that cannot legally ship as external binaries on Android."
            )
            return@withContext ToolExecutionResult.success(r, json.encodeToString(AudioConvertResult.serializer(), r))
        }
        val dir = File(context.filesDir, "audio"); dir.mkdirs()
        val outputExt = if (targetFormat == "wav") "wav" else "m4a"
        val outputFile = File(dir, "${outputName}_${System.currentTimeMillis()}.$outputExt")
        val conversionError = AudioNative.convert(inputFile, outputFile, targetFormat)
        val r = if (conversionError == null && outputFile.exists() && outputFile.length() > 0) {
            AudioConvertResult(true, outputFile.absolutePath, null)
        } else {
            AudioConvertResult(false, null, conversionError ?: "Conversion produced an empty file")
        }
        ToolExecutionResult.success(r, json.encodeToString(AudioConvertResult.serializer(), r))
    }

    private suspend fun audioTrim(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val inputPath = args["inputPath"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'inputPath'")
        val startSeconds = (args["startSeconds"] as? Number)?.toFloat() ?: return@withContext ToolExecutionResult.error("Missing 'startSeconds'")
        val endSeconds = (args["endSeconds"] as? Number)?.toFloat() ?: return@withContext ToolExecutionResult.error("Missing 'endSeconds'")
        val outputName = args["outputName"] as? String ?: "trimmed"
        val inputFile = File(inputPath)
        if (!inputFile.exists()) return@withContext ToolExecutionResult.error("File not found: $inputPath")
        if (endSeconds <= startSeconds) {
            val r = AudioConvertResult(false, null, "Trim range invalid: end ($endSeconds s) must be after start ($startSeconds s)")
            return@withContext ToolExecutionResult.success(r, json.encodeToString(AudioConvertResult.serializer(), r))
        }
        val ext = when (inputPath.substringAfterLast(".", "").lowercase()) {
            "3gp" -> "3gp"
            else -> "m4a"
        }
        val dir = File(context.filesDir, "audio"); dir.mkdirs()
        val outputFile = File(dir, "${outputName}_${System.currentTimeMillis()}.$ext")
        val error = AudioNative.trim(inputFile, outputFile, startSeconds, endSeconds)
        val r = AudioConvertResult(
            success = error == null && outputFile.exists() && outputFile.length() > 0,
            outputPath = if (error == null && outputFile.exists()) outputFile.absolutePath else null,
            error = error ?: if (outputFile.exists() && outputFile.length() > 0) null else "Trim produced an empty file"
        )
        ToolExecutionResult.success(r, json.encodeToString(AudioConvertResult.serializer(), r))
    }

    private suspend fun audioInfo(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val inputPath = args["inputPath"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'inputPath'")
        val inputFile = File(inputPath)
        if (!inputFile.exists()) return@withContext ToolExecutionResult.error("File not found: $inputPath")
        val retriever = android.media.MediaMetadataRetriever()
        val extractor = android.media.MediaExtractor()
        val r: AudioFileInfoResult = try {
            retriever.setDataSource(inputFile.absolutePath)
            val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val bitrate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
            val sampleRate = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
            } else null
            // The retriever exposes no channel-count key — read it straight off the audio track format
            var channels: Int? = null
            try {
                extractor.setDataSource(inputFile.absolutePath)
                for (i in 0 until extractor.trackCount) {
                    val trackFormat = extractor.getTrackFormat(i)
                    val mime = trackFormat.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        if (trackFormat.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = trackFormat.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        break
                    }
                }
            } catch (_: Exception) { /* channel count stays null — non-fatal */ }
            val mimeType = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            AudioFileInfoResult(
                success = true,
                duration = durationMs?.let { it / 1000.0 },
                bitrate = bitrate,
                sampleRate = sampleRate,
                channels = channels,
                format = mimeType ?: inputFile.extension,
                error = null
            )
        } catch (e: Exception) {
            AudioFileInfoResult(false, null, null, null, null, null, "Failed to read audio metadata: ${e.message}. File may be corrupt or in an unsupported format.")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
        ToolExecutionResult.success(r, json.encodeToString(AudioFileInfoResult.serializer(), r))
    }

    private suspend fun initTts() = withContext(Dispatchers.Main) {
        var initResult = TextToSpeech.LANG_NOT_SUPPORTED
        val ttsInstance = TextToSpeech(context) { status -> initResult = status }
        delay(1000)
        if (initResult == TextToSpeech.SUCCESS) { val localeResult = ttsInstance.setLanguage(Locale.getDefault()); ttsReady = localeResult != TextToSpeech.LANG_NOT_SUPPORTED } else { ttsReady = false }
        tts = ttsInstance
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
}