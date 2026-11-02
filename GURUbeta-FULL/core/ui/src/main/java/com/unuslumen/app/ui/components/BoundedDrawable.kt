// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * Decode a drawable bitmap bounded to a display size, so its memory is the size on
 * screen rather than the size of the file on disk.
 *
 * WHY THIS EXISTS, and it is an OutOfMemoryError rather than a nicety. `painterResource`
 * decodes a drawable at its intrinsic size and then scales it with contentScale. The
 * house logo is 4224x2304, which is 37 MB of ARGB_8888 for one bitmap, and it is drawn
 * as a watermark inside a 400dp box at 15% alpha. Three screens reference it, the app
 * draws its background on every page, and the heap ceiling is 256 MB. A person opening
 * the app therefore pays tens of megabytes to show a faint logo, and once the heap is
 * gone the very next allocation fails whatever its size: the crash reported a 288-byte
 * allocation failing with 263 KB left.
 *
 * THE RULE. Never decode at more than [targetWidth] by [targetHeight]. A decode of a
 * large source into a small box is the defect, not the size of the artwork, so the fix
 * belongs here rather than in the asset: the owner can drop any artwork in and the app
 * still only pays for what it draws.
 *
 * SAMPLING IS CHECKED RATHER THAN ASSUMED. `inSampleSize` halves in powers of two, so a
 * computed sample can still land well above the target. The loop below increases the
 * sample until the decoded size actually fits, which is the only way to be certain
 * rather than approximately certain.
 */
@Composable
fun rememberBoundedImageBitmap(
    drawableId: Int,
    targetWidth: Int,
    targetHeight: Int,
): ImageBitmap? {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(drawableId, targetWidth, targetHeight) {
        boundedBitmap(context, drawableId, targetWidth, targetHeight)
    }
}

/**
 * The decode itself, with a cache.
 *
 * A CACHE BECAUSE THE SAME ARTWORK IS DRAWN ON EVERY PAGE. Without it, navigating between
 * screens would repeat the decode and re-allocate each time, which is how a memory fault
 * becomes a jank fault as well once the allocation is small enough to survive.
 */
private val bitmapCache = LruCache<String, ImageBitmap>(6)

private fun boundedBitmap(
    context: Context,
    drawableId: Int,
    targetWidth: Int,
    targetHeight: Int,
): ImageBitmap? {
    val key = "$drawableId:${targetWidth}x$targetHeight"
    bitmapCache.get(key)?.let { return it }

    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching {
        context.resources.openRawResource(drawableId).use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    }
    val sourceWidth = options.outWidth
    val sourceHeight = options.outHeight
    if (sourceWidth <= 0 || sourceHeight <= 0) return null

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = DecodeBudget.sampleFactorFor(sourceWidth, sourceHeight, targetWidth, targetHeight)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    val decoded = runCatching {
        context.resources.openRawResource(drawableId).use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        }
    }.getOrNull() ?: return null

    val image = decoded.asImageBitmap()
    bitmapCache.put(key, image)
    return image
}

/**
 * A drawable painted bounded to the size it is displayed at.
 *
 * THE TWO BRANCHES ARE DELIBERATE. A bounded decode is used when one is possible, and
 * `painterResource` is the fallback for anything the decoder cannot read, so a vector or
 * a nine-patch still draws instead of vanishing. The fallback is the old behaviour,
 * which is correct for a small drawable and is exactly why this is a fallback rather
 * than the path.
 */
@Composable
fun BoundedDrawable(
    drawableId: Int,
    targetWidthDp: Int,
    targetHeightDp: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = 1f,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val widthPx = with(density) { targetWidthDp.dp.roundToPx() }
    val heightPx = with(density) { targetHeightDp.dp.roundToPx() }

    val bounded = rememberBoundedImageBitmap(drawableId, widthPx, heightPx)
    if (bounded != null) {
        Image(
            bitmap = bounded,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            alpha = alpha,
        )
    } else {
        Image(
            painter = painterResource(id = drawableId),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            alpha = alpha,
        )
    }
}
