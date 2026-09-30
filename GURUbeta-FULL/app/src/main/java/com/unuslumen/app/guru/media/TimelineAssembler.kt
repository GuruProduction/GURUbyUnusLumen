package com.unuslumen.app.guru.media

/**
 * TimelineAssembler — pure merge of the keyframe scene strip and the
 * transcript chunk strip into one chronological document, zero I/O, zero
 * dependencies on Android. The only place in the pipeline where the Guru-facing
 * reading order is decided.
 */
object TimelineAssembler {

    /**
     * Build the strip: for every keyframe we attach one [TimelineEntry]; spans
     * without keyframes (audio-only chunks past the last keyframe) become
     * timeline entries without a frame. Transcript chunk boundaries ride
     * through unchanged because chunk times never leave the real WAV clock.
     */
    fun assemble(scenes: List<SceneKeyFrame>, chunks: List<TranscriptChunk>, header: TimelineDataHeader): TimelineData {
        val entries = mutableListOf<TimelineEntry>()

        // 1) Every keyframe block with the transcript content inside its range.
        //    Keyframe spans are ordered, non-overlapping, endSec exclusive of next start.
        for (scene in scenes) {
            val overlappingChunks = chunks.filter { chunk ->
                // Chunks overlap the keyframe range by the time axis with at least half the chunk covered
                chunk.startSec < scene.endSec && chunk.endSec > scene.startSec
            }
            val joined = if (overlappingChunks.isEmpty()) null
                else overlappingChunks.joinToString(" ") { it.text }.ifBlank { null }
            entries += TimelineEntry(
                startSec = scene.startSec,
                endSec = scene.endSec,
                keyframePath = scene.keyframePath,
                keyframeOcrText = scene.ocrText.ifBlank { null },
                transcriptChunk = overlappingChunks.firstOrNull(),
                transcriptText = joined
            )
        }

        // 2) Any audio past the last keyframe gets one entry per chunk so 100% of
        //    speech land (even when a strip ends early).
        val lastScene = scenes.lastOrNull()
        val lastKeyframeSec = lastScene?.endSec ?: 0.0
        val tailChunks = chunks.filter { it.startSec >= lastKeyframeSec }
        for (chunk in tailChunks) {
            entries += TimelineEntry(
                startSec = chunk.startSec,
                endSec = chunk.endSec,
                keyframePath = null,
                keyframeOcrText = null,
                transcriptChunk = chunk,
                transcriptText = chunk.text
            )
        }

        // Timeline order is chronological.
        val sorted = entries.sortedBy { it.startSec }
        return TimelineData(
            title = header.title,
            mediaId = header.mediaId,
            durationSec = header.durationSec,
            width = header.width,
            height = header.height,
            fps = header.fps,
            hasAudio = header.hasAudio,
            mediaKind = header.mediaKind,
            mimeType = header.mimeType,
            sourceFilename = header.sourceFilename,
            entries = sorted
        )
    }
}

/**
 * Input header facts assembled by MediaIngestService from the real probe info
 * and ingest-side parameters; nothing here is invented.
 */
data class TimelineDataHeader(
    val title: String,
    val mediaId: String,
    val durationSec: Double,
    val width: Int,
    val height: Int,
    val fps: Double,
    val hasAudio: Boolean,
    val mediaKind: String,
    val mimeType: String,
    val sourceFilename: String
)