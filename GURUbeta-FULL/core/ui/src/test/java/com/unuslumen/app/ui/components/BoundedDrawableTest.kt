// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decode arithmetic behind [BoundedDrawable], proven without a device.
 *
 * WHY THIS IS A TEST AND NOT A COMMENT. The defect was that a 4224x2304 drawable was
 * decoded in full to paint an 18dp wordmark and a 400dp watermark, and the allocation
 * that finally failed was 288 bytes because the heap was already gone. That is a rule
 * about arithmetic, so it can be pinned on the JVM: given a source size and a target box,
 * what sample is chosen and how big does the decode end up.
 *
 * WHAT IT CANNOT PROVE, stated so it is never mistaken for device evidence: that
 * BitmapFactory honours the sample factor. It does, and it is documented, but the proof of
 * that is the app not crashing on the phone.
 */
class BoundedDrawableTest {

    /** The real house logo, which is the artwork that caused the crash. */
    private val logoWidth = 4224
    private val logoHeight = 2304

    /**
     * THE REGRESSION. A 400dp watermark must not decode the full 37 MB bitmap.
     *
     * If this fails, every screen showing the logo is paying for the whole file again.
     */
    @Test
    fun aWatermarkSizedBoxDoesNotDecodeTheFullArtwork() {
        val sample = DecodeBudget.sampleFactorFor(logoWidth, logoHeight, targetWidth = 400, targetHeight = 218)
        assertTrue("a 400dp box must sub-sample, got $sample", sample > 1)

        val decodedBytes = DecodeBudget.decodedBytes(logoWidth, logoHeight, sample)
        val fullBytes = logoWidth.toLong() * logoHeight * 4
        assertTrue(
            "the decode must be far smaller than the file, and was $decodedBytes of $fullBytes",
            decodedBytes < fullBytes / 8,
        )
    }

    /** An 18dp top-bar wordmark must sub-sample hard, because it is drawn tiny. */
    @Test
    fun anEighteenDpTallyDoesNotDecodeTheFullArtwork() {
        val sample = DecodeBudget.sampleFactorFor(logoWidth, logoHeight, targetWidth = 160, targetHeight = 18)
        assertTrue("an 18dp strip must sub-sample heavily, got $sample", sample >= 8)
        val decodedBytes = DecodeBudget.decodedBytes(logoWidth, logoHeight, sample)
        assertTrue(
            "an 18dp wordmark must cost megabytes at most, and was ${decodedBytes / 1024} KB",
            decodedBytes < 4L * 1024 * 1024,
        )
    }

    /**
     * A source already smaller than the box is not up-scaled and the sample stays 1.
     *
     * Clamping matters because a sample of 0 would be an invalid BitmapFactory option and
     * a negative one would throw at decode time on the device.
     */
    @Test
    fun aSmallSourceIsNotSubSampled() {
        assertEquals(1, DecodeBudget.sampleFactorFor(100, 100, targetWidth = 400, targetHeight = 400))
        assertEquals(1, DecodeBudget.sampleFactorFor(400, 400, targetWidth = 400, targetHeight = 400))
    }

    /** A zero or negative target cannot produce a zero sample. */
    @Test
    fun aDegenerateTargetStillProducesAValidSample() {
        assertTrue(DecodeBudget.sampleFactorFor(logoWidth, logoHeight, 0, 0) >= 1)
        assertTrue(DecodeBudget.sampleFactorFor(logoWidth, logoHeight, -5, -5) >= 1)
    }

}
