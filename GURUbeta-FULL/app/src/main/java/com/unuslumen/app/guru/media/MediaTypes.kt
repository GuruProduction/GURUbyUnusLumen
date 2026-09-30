package com.unuslumen.app.guru.media

import java.io.File
import java.util.UUID

/**
 * MediaTypes data class — data-shape source of truth for the whole media
 * pipeline. Pure data, no behavior, no dependencies. Every module in
 * com.unuslumen.app.guru.media imports from here.
 */

/** Truth about a probed media file, never guessed. Probe of a real file never invents dims. */
data class MediaProbeInfo(
    val durationSec: Double,
    val width: Int,
    val height: Int,
    val fps: Double,
    val hasAudio: Boolean
)

/** One probe result (one keyframe) in the scenes strip. */
data class SceneKeyFrame(
    /** Second offset in the timeline this keyframe represents. */
    val startSec: Double,
    /** Second offset in the timeline this keyframe covers through its endSec (inclusive last visible frame). */
    val endSec: Double,
    /** Absolute path to the downscaled keyframe file on disk. */
    val keyframePath: String,
    /** OCR text of this keyframe; empty when nothing on screen is legible. */
    val ocrText: String
)

/** One piece of the transcribed timeline from Vosk, timestamps preserved. */
data class TranscriptChunk(
    /** Start offset seconds into the media for this chunk. */
    val startSec: Double,
    /** End offset seconds, inclusive of media timeline range this chunk spans. */
    val endSec: Double,
    /** Space-joined recognized words of this chunk. */
    val text: String
)

/** One block of the strip document — keyframe or pure-audio/text timeline row. */
data class TimelineEntry(
    val startSec: Double,
    val endSec: Double,
    /** Null on pure-audio / no-keyframe entries so UI skips image slot rendering. */
    val keyframePath: String?,
    /** Null on keyframes without any OCR text. */
    val keyframeOcrText: String?,
    /** Null on a keyframe-only row with no transcript coverage — real not-null discipline. */
    val transcriptChunk: TranscriptChunk?,
    /** Empty text of a block carries a space-joined transcript text of the chunks inside the block range. */
    val transcriptText: String?
)

/** The full doc of a media item in strips form — the document Guru receives. */
data class TimelineData(
    val title: String,
    val mediaId: String,
    /** video/audio duration. Images 0.0. Real probed value, never faked. */
    val durationSec: Double,
    /** Real probe width/height, not synthetic. */
    val width: Int,
    val height: Int,
    val fps: Double,
    val hasAudio: Boolean,
    val mediaKind: String,
    val mimeType: String,
    val sourceFilename: String,
    val entries: List<TimelineEntry>
)

/** Result of an ingest run to return from MediaIngestService.ingest(). */
data class IngestResult(
    /** Media item ID; stable across idempotent re-ingest of the same bytes. */
    val mediaId: String,
    /** Real probed kind of the attached media. */
    val mediaKind: String,
    /** Real duration. 0.0 for images. */
    val durationSec: Double,
    /** The Guru-facing rendering of this item's Timeline document, in strips. */
    val timelineText: String,
    /** True when the ingest rebuilt (not skipped). False means an existing record for those bytes was reused. */
    val didSkipRebuild: Boolean,
    /** INGESTED / FAILED / PROCESSING — the ingest contract, never silent. */
    val status: String,
    /** Error message on FAILED, empty otherwise. */
    val errorMessage: String
)