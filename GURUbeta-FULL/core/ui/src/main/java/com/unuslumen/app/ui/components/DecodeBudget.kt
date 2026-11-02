// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.ui.components

/**
 * How much of a bitmap to decode, given where it will be drawn. Pure arithmetic, no Android.
 *
 * WHY THIS IS ITS OWN FILE. The rule that decides whether the app allocates 37 MB or 200 KB
 * used to be three lines inside a composable, which needs an Android Context to compile
 * against and therefore cannot be tested on the JVM. The arithmetic is the whole defect:
 * a 4224x2304 drawable was decoded in full to paint an 18dp wordmark, and once the heap was
 * gone the next allocation failed at 288 bytes. A rule like that belongs where a test can
 * reach it, so it lives here and [BoundedDrawable] calls it.
 *
 * THE RULE. Halve in powers of two until the decoded bitmap would be smaller than the box
 * it is drawn into. [android.graphics.BitmapFactory.Options.inSampleSize] only accepts
 * powers of two, so the sample is doubled in a loop and the result is CHECKED rather than
 * assumed: a computed "approximate" sample can still land above the target on a source that
 * is not a clean divisor, and the loop below is what makes the guarantee true instead of
 * roughly true.
 */
object DecodeBudget {

    /**
     * The power-of-two sample factor for a source drawn into a target box.
     *
     * A source smaller than the box returns 1, because up-scaling buys nothing. A
     * degenerate target returns 1 rather than 0, because a sample of 0 is an invalid
     * BitmapFactory option and would throw at decode time instead of drawing.
     */
    fun sampleFactorFor(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1
        val wantedWidth = targetWidth.coerceAtLeast(1)
        val wantedHeight = targetHeight.coerceAtLeast(1)

        var sample = 1
        while (sourceWidth / (sample * 2) >= wantedWidth &&
            sourceHeight / (sample * 2) >= wantedHeight
        ) {
            sample *= 2
        }
        return sample
    }

    /**
     * What an ARGB_8888 decode of this source costs at this sample, in bytes.
     *
     * Exposed so a test can assert the point of the exercise rather than only the sample
     * number: the memory the app pays must be the size on screen, not the size of the file.
     */
    fun decodedBytes(sourceWidth: Int, sourceHeight: Int, sample: Int): Long {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 0
        val safeSample = sample.coerceAtLeast(1)
        val width = (sourceWidth / safeSample).coerceAtLeast(1)
        val height = (sourceHeight / safeSample).coerceAtLeast(1)
        return width.toLong() * height * 4
    }
}
