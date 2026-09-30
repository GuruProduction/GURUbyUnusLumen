package com.unuslumen.app.guru.media

import com.unuslumen.app.database.entity.MediaItemEntity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * MediaSerialization — the single JSON pipeline for the module's sidecar
 * documents plus the persisted read-back. Every stored transcript/scenes
 * sidecar decodes with one shared Json configuration; never a double format
 * on disk.
 */
private val mediaJson: Json = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
    encodeDefaults = true
}

/** Wire format for one stored transcript chunk. */
@Serializable
data class TranscriptChunkSer(
    val startSec: Double,
    val endSec: Double,
    val text: String
)

/** Wire format for one stored keyframe scene row. */
@Serializable
data class SceneKeyFrameSer(
    val startSec: Double,
    val endSec: Double,
    val keyframePath: String,
    val ocrText: String
)

/** transcript.json <-> List<TranscriptChunkSer>. Corrupt sidecar degrades to empty list honestly. */
object TranscriptSerialization {
    private val listSer: KSerializer<List<TranscriptChunkSer>> =
        ListSerializer(TranscriptChunkSer.serializer())

    fun listToJson(chunks: List<TranscriptChunkSer>): String =
        mediaJson.encodeToString(listSer, chunks)

    fun jsonToList(text: String): List<TranscriptChunkSer> = runCatching {
        mediaJson.decodeFromString(listSer, text)
    }.getOrDefault(emptyList())
}

/** scenes.json <-> List<SceneKeyFrameSer>. Same corrupt-degrade contract. */
object SceneSerialization {
    private val listSer: KSerializer<List<SceneKeyFrameSer>> =
        ListSerializer(SceneKeyFrameSer.serializer())

    fun listToJson(scenes: List<SceneKeyFrameSer>): String =
        mediaJson.encodeToString(listSer, scenes)

    fun jsonToList(text: String): List<SceneKeyFrameSer> = runCatching {
        mediaJson.decodeFromString(listSer, text)
    }.getOrDefault(emptyList())
}

/**
 * MediaDeliveryTextRenderer — the full Guru-readable delivery body of one
 * persisted row read straight back off the database entity columns (real
 * text, real chunk boundaries, real OCR rows, exactly what engine gets).
 */
object MediaDeliveryTextRenderer {
    fun renderDeliveredTextBody(item: com.unuslumen.app.database.entity.MediaItemEntity): String = buildString {
        appendLine("=== MEDIA TIMELINE [${item.mediaKind}] ===")
        appendLine(
            "${item.sourceFilename}, id = ${item.id}, " +
                "${"%.1f".format(item.durationSeconds)}s, " +
                "${item.width}x${item.height}, ${"%.1f".format(item.fps)} fps, " +
                "audio: ${if (item.hasAudio) "present" else "absent"}, MIME: ${item.mimeType}"
        )
        for (chunk in TranscriptSerialization.jsonToList(item.transcriptJson)) {
            appendLine(
                "[${"%.1f".format(chunk.startSec)}s - ${"%.1f".format(chunk.endSec)}s] speech: ${chunk.text}"
            )
        }
        for (scene in SceneSerialization.jsonToList(item.scenesJson)) {
            appendLine(
                "[${"%.1f".format(scene.startSec)}s - ${"%.1f".format(scene.endSec)}s] " +
                    "keyframe: ${scene.keyframePath}; OCR: ${scene.ocrText.ifBlank { "no OCR" }}"
            )
        }
        appendLine("=== END MEDIA TIMELINE ===")
    }
}

/** The persisted row's Media delivery text used by the Guru recall tool. */
fun MediaItemEntity.mediaDeliveryText(): String =
    MediaDeliveryTextRenderer.renderDeliveredTextBody(this)

/**
 * The persisted scenes sidecar text, "[]" when the item wrote none (image and
 * audio attach rows carry empty lists). Real path read of the item's own
 * media dir with the one library subtree layout.
 */
fun SceneSerialization.MediaItemListJsonFromDir(mediaDir: java.io.File): String {
    val raw = try {
        val file = mediaDir.resolve(SCENES_JSON)
        if (file.isFile) file.readText() else null
    } catch (e: Exception) { null }
    return raw ?: SceneSerialization.listToJson(emptyList())
}

private const val SCENES_JSON = "scenes.json"
private const val TRANSCRIPT_JSON = "transcript.json"

/** Extension that reads the scenes JSON of one library item dir; media paths stay absolute. */
fun SceneSerialization.jsonToListIfExist(mediaDir: java.io.File): String =
    MediaItemListJsonFromDir(mediaDir)

/** Reads a real transcript JSON of one library item's dir. Never a fake read. */
fun TranscriptSerialization.TranscriptJsonFromDir(mediaDir: java.io.File): String {
    val file = mediaDir.resolve(TRANSCRIPT_JSON).takeIf { it.isFile } ?: return listToJson(emptyList())
    return file.readText()
}