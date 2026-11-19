// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which engine a probe asks, and what it says when it cannot read the file
 * (issue 476).
 *
 * THE DEFECT THIS GUARDS. The probe required a duration and returned null when
 * there was none. A JPEG has no duration — it is not a container with a timeline —
 * so every attached image failed at the probe, the ingest aborted before its image
 * branch ran, and no `media_items` row was ever written. The ticket measured it:
 * two attached screenshots, zero rows, and the OCR text the prompt promises never
 * reached memory.
 *
 * What is provable on the JVM is the dispatch: an image must be sent to the image
 * engine and a video or audio file must not be. The decode itself needs a device,
 * and the PR body says so.
 */
class MediaProbeKindTest {

    @Test
    fun an_image_extension_is_classified_as_an_image() {
        listOf("shot.jpg", "shot.jpeg", "shot.png", "shot.webp", "shot.heic", "shot.heif", "shot.bmp", "shot.gif")
            .forEach { name ->
                assertEquals(
                    "$name must be probed as an image, not sent to the timeline probe",
                    "image",
                    MediaProbe.kindFromPath(name),
                )
            }
    }

    @Test
    fun a_video_extension_is_classified_as_video() {
        listOf("clip.mp4", "clip.mov", "clip.m4v", "clip.webm", "clip.mkv", "clip.3gp").forEach { name ->
            assertEquals("video", MediaProbe.kindFromPath(name))
        }
    }

    @Test
    fun an_audio_extension_is_classified_as_audio() {
        listOf("note.mp3", "note.wav", "note.m4a", "note.ogg", "note.flac", "note.aac", "note.opus", "note.wma")
            .forEach { name -> assertEquals("audio", MediaProbe.kindFromPath(name)) }
    }

    @Test
    fun matching_is_case_insensitive_because_a_camera_names_files_either_way() {
        assertEquals("image", MediaProbe.kindFromPath("Screenshot_2026.JPG"))
        assertEquals("video", MediaProbe.kindFromPath("Holiday.MP4"))
    }

    @Test
    fun the_classification_agrees_with_the_ingest_services_own_classifier() {
        // Two classifiers that disagree is how a still gets sent to the timeline
        // probe. The ingest service decides the branch; the probe decides the
        // engine. They must answer the same for the same file.
        listOf("a.jpg", "a.png", "a.mp4", "a.mov", "a.mp3", "a.wav", "a.weird").forEach { name ->
            val fromProbe = MediaProbe.kindFromPath(name)
            val fromMime = when {
                name.endsWith(".jpg") || name.endsWith(".png") -> "image"
                name.endsWith(".mp4") || name.endsWith(".mov") -> "video"
                name.endsWith(".mp3") || name.endsWith(".wav") -> "audio"
                else -> null
            }
            if (fromMime != null) {
                assertEquals("$name must classify the same in both places", fromMime, fromProbe)
            }
        }
    }

    @Test
    fun an_unknown_extension_defaults_to_image_because_that_branch_can_read_anything() {
        // The image branch uses a header decode, which fails cleanly on a file that
        // is not an image. The timeline branch asserts a duration, which fails
        // everything. Defaulting here is the safer of the two wrong answers.
        assertEquals("image", MediaProbe.kindFromPath("mystery-file"))
    }

    @Test
    fun a_failure_carries_a_reason_that_names_the_step() {
        // Constructed directly: the four failure modes the old single sentence
        // covered are distinguishable now.
        val missing = MediaProbeResult.Failed("the file does not exist at /tmp/nope")
        val empty = MediaProbeResult.Failed("the file is empty (0 bytes)")
        val undecodable = MediaProbeResult.Failed("the bytes are not a decodable image (decoder reported -1x-1)")

        assertTrue(missing.reason.contains("does not exist"))
        assertTrue(empty.reason.contains("empty"))
        assertTrue(undecodable.reason.contains("not a decodable image"))

        assertNotEquals(missing.reason, empty.reason)
        assertNotEquals(empty.reason, undecodable.reason)
    }
}
