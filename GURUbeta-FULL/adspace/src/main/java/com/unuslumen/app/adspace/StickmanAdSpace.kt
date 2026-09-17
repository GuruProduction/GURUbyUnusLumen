package com.unuslumen.app.adspace
 
import android.util.Base64
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieDynamicProperties
import com.airbnb.lottie.compose.LottieDynamicProperty
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.model.KeyPath
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File
import kotlin.math.roundToInt
import kotlin.random.Random

@Composable
fun StickmanAdSpace(
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    val manager = koinInject<AdSpaceManager>()
    val cache = koinInject<AdPackCache>()
    val currentEntry by manager.currentEntry.collectAsState()

    LaunchedEffect(loading) {
        manager.setActive(loading)
    }

    val pack = manager.pack
    if (!loading || currentEntry == null || pack == null) return

    val entry = currentEntry ?: return

    val bgColour = pack.backgroundColour?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }

    Log.d("StickmanAdSpace", "rendering entry=${entry.id}")

    Box(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .background(bgColour ?: Color.Transparent)
            .padding(horizontal = 12.dp),
    ) {
        // Transition wrapper based on pack's transition_type
        val transitionType = pack.transitionType
        val entryKey = entry.id

        when (transitionType) {
            TransitionType.CROSSFADE -> {
                Crossfade(
                    targetState = entryKey,
                    animationSpec = tween(durationMillis = pack.crossfadeDurationMs.coerceAtLeast(1).toInt()),
                    label = "ad_crossfade"
                ) { _ ->
                    AdContent(entry, pack, cache)
                }
            }
            TransitionType.SLIDE -> {
                val slideDurationMs = pack.slideDurationMs.coerceAtLeast(1).toInt()
                val slideDir = pack.slideDirection
                AnimatedContent(
                    targetState = entryKey,
                    transitionSpec = {
                        when (slideDir) {
                            "left" -> {
                                (slideInHorizontally(tween(slideDurationMs)) { it } + fadeIn(tween(slideDurationMs))) togetherWith
                                    (slideOutHorizontally(tween(slideDurationMs)) { -it } + fadeOut(tween(slideDurationMs)))
                            }
                            "right" -> {
                                (slideInHorizontally(tween(slideDurationMs)) { -it } + fadeIn(tween(slideDurationMs))) togetherWith
                                    (slideOutHorizontally(tween(slideDurationMs)) { it } + fadeOut(tween(slideDurationMs)))
                            }
                            "up" -> {
                                (slideInVertically(tween(slideDurationMs)) { it } + fadeIn(tween(slideDurationMs))) togetherWith
                                    (slideOutVertically(tween(slideDurationMs)) { -it } + fadeOut(tween(slideDurationMs)))
                            }
                            "down" -> {
                                (slideInVertically(tween(slideDurationMs)) { -it } + fadeIn(tween(slideDurationMs))) togetherWith
                                    (slideOutVertically(tween(slideDurationMs)) { it } + fadeOut(tween(slideDurationMs)))
                            }
                            else -> {
                                (slideInHorizontally(tween(slideDurationMs)) { it } + fadeIn(tween(slideDurationMs))) togetherWith
                                    (slideOutHorizontally(tween(slideDurationMs)) { -it } + fadeOut(tween(slideDurationMs)))
                            }
                        }
                    },
                    label = "ad_slide"
                ) { _ ->
                    AdContent(entry, pack, cache)
                }
            }
            else -> {
                AdContent(entry, pack, cache)
            }
        }
    }
}

@Composable
private fun AdContent(entry: AdEntry, pack: AdPack, cache: AdPackCache) {
    if (entry.elements.isNotEmpty()) {
        AdCanvas(entry, pack, cache)
        return
    }
    when (entry.textPosition) {
        TextPosition.BESIDE_ANIMATION -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimationEntry(entry, pack, cache, modifier = Modifier.weight(0.4f))
            Spacer(Modifier.width(8.dp))
            SloganText(entry, modifier = Modifier.weight(0.6f))
        }
        TextPosition.ABOVE_ANIMATION -> Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SloganText(entry)
            AnimationEntry(entry, pack, cache)
        }
        TextPosition.BELOW_ANIMATION -> Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimationEntry(entry, pack, cache)
            SloganText(entry)
        }
    }
}

@Composable
private fun AdCanvas(entry: AdEntry, pack: AdPack, cache: AdPackCache) {
    if (entry.elements.isEmpty()) return

    val sortedElements = entry.elements.sortedBy { it.zIndex }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clipToBounds(),
    ) {
        for (element in sortedElements) {
            when (element.type) {
                "text" -> RenderTextElement(element, pack)
                "lottie" -> RenderLottieElement(element, pack, cache)
                "image" -> RenderImageElement(element, pack, cache)
                "video" -> RenderVideoElement(element, pack, cache)
                "script" -> RenderScriptElement(element, pack)
            }
        }
    }
}

@Composable
private fun RenderTextElement(element: AdElement, pack: AdPack) {
    val text = element.text ?: return
    if (text.isBlank()) return

    val fontFamily: FontFamily? = element.fontFamily?.let { name ->
        when (name.lowercase()) {
            "serif" -> FontFamily.Serif
            "sans-serif", "sansserif", "system" -> FontFamily.SansSerif
            "monospace", "mono" -> FontFamily.Monospace
            "cursive" -> FontFamily.Cursive
            else -> null
        }
    }

    val colours = element.textColours ?: listOf("#DAA520")
    val colourMode = element.textColourMode ?: "single"
    val animTypeStr = element.textAnimation ?: "static"
    val textAnimType = runCatching { TextAnimationType.valueOf(animTypeStr.uppercase()) }.getOrDefault(TextAnimationType.STATIC)

    val colourIndex = remember { mutableIntStateOf(0) }
    val typedText = remember { mutableStateOf("") }
    val visibleWordCount = remember { mutableIntStateOf(0) }
    val charWaveIndex = remember { mutableIntStateOf(0) }
    val glitchText = remember { mutableStateOf("") }
    val showCursor = remember { mutableStateOf(false) }

    val fadeInAlpha = remember { Animatable(0f) }
    val bounceScale = remember { Animatable(0f) }
    val slideOffset = remember { Animatable(0f) }
    val zoomBlurAmount = remember { Animatable(20f) }
    val springScale = remember { Animatable(0f) }
    val flipRotation = remember { Animatable(90f) }

    val shimmerTransition = rememberInfiniteTransition(label = "el_shimmer")
    val shimmerProgress by shimmerTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "el_shimmerProgress"
    )

    val textColour: Color = when (colourMode) {
        "cycling" -> {
            val parsed = colours.mapNotNull { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
            if (parsed.isNotEmpty()) parsed[colourIndex.intValue % parsed.size] else Color(0xFFDAA520)
        }
        "gradient" -> {
            runCatching { Color(android.graphics.Color.parseColor(colours.getOrElse(0) { "#DAA520" })) }
                .getOrDefault(Color(0xFFDAA520))
        }
        else -> {
            runCatching { Color(android.graphics.Color.parseColor(colours.getOrElse(0) { "#DAA520" })) }
                .getOrDefault(Color(0xFFDAA520))
        }
    }

    val gradientBrush: Brush? = if (colourMode == "gradient" && colours.size >= 2) {
        val parsed = colours.mapNotNull { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
        if (parsed.size >= 2) {
            val dir = element.gradientDirection ?: 0f
            Brush.linearGradient(
                colors = parsed,
                start = Offset.Zero,
                end = Offset(
                    kotlin.math.cos(Math.toRadians(dir.toDouble())).toFloat() * 1000f,
                    kotlin.math.sin(Math.toRadians(dir.toDouble())).toFloat() * 1000f
                )
            )
        } else null
    } else null

    val rainbowBrush: Brush? = if (textAnimType == TextAnimationType.RAINBOW_SHIMMER) {
        val hue = (shimmerProgress * 360f)
        val hue2 = ((shimmerProgress * 360f) + 120f) % 360f
        val hue3 = ((shimmerProgress * 360f) + 240f) % 360f
        Brush.linearGradient(
            colors = listOf(
                Color.hsv(hue, 0.8f, 1f),
                Color.hsv(hue2, 0.8f, 1f),
                Color.hsv(hue3, 0.8f, 1f),
                Color.hsv(hue, 0.8f, 1f),
            ),
            start = Offset.Zero,
            end = Offset(1000f, 0f)
        )
    } else null

    LaunchedEffect(colourMode, element.textColourCycleMs) {
        if (colourMode == "cycling" && colours.size > 1) {
            while (true) {
                delay(element.textColourCycleMs ?: 2000)
                colourIndex.intValue = (colourIndex.intValue + 1) % colours.size
            }
        }
    }

    LaunchedEffect(element.id, text, animTypeStr) {
        typedText.value = ""
        visibleWordCount.intValue = 0
        charWaveIndex.intValue = 0
        glitchText.value = ""
        showCursor.value = false
        fadeInAlpha.snapTo(0f)
        bounceScale.snapTo(0f)
        slideOffset.snapTo(0f)
        zoomBlurAmount.snapTo(20f)
        springScale.snapTo(0f)
        flipRotation.snapTo(90f)

        val expandDur = (element.expandDurationMs ?: 800).coerceAtLeast(100).toInt()
        val fadeDur = (element.fadeDurationMs ?: 500).coerceAtLeast(100).toInt()
        val slideDur = (element.textSlideDurationMs ?: 400).coerceAtLeast(100).toInt()

        when (textAnimType) {
            TextAnimationType.STATIC -> { typedText.value = text }

            TextAnimationType.TYPING -> {
                val typeSpeed = (element.typingSpeedMs ?: 80).coerceAtLeast(1)
                val deleteSpeed = (element.typingDeleteSpeedMs ?: 40).coerceAtLeast(1)
                for (i in 1..text.length) { typedText.value = text.substring(0, i); delay(typeSpeed) }
                delay(2000)
                while (true) {
                    for (i in 0..text.length) { typedText.value = text.substring(0, text.length - i); delay(deleteSpeed) }
                    delay(500)
                    for (i in 1..text.length) { typedText.value = text.substring(0, i); delay(typeSpeed) }
                    delay(2000)
                }
            }

            TextAnimationType.TYPEWRITER_CURSOR -> {
                val typeSpeed = (element.typingSpeedMs ?: 80).coerceAtLeast(1)
                val deleteSpeed = (element.typingDeleteSpeedMs ?: 40).coerceAtLeast(1)
                showCursor.value = true
                for (i in 1..text.length) { typedText.value = text.substring(0, i); delay(typeSpeed) }
                delay(2000)
                while (true) {
                    for (i in 0..text.length) { typedText.value = text.substring(0, text.length - i); delay(deleteSpeed) }
                    delay(500)
                    for (i in 1..text.length) { typedText.value = text.substring(0, i); delay(typeSpeed) }
                    delay(2000)
                }
            }

            TextAnimationType.FADING -> {
                while (true) {
                    typedText.value = text
                    fadeInAlpha.snapTo(0f)
                    fadeInAlpha.animateTo(1f, tween(fadeDur, easing = FastOutSlowInEasing))
                    delay(2000)
                    fadeInAlpha.animateTo(0f, tween(fadeDur, easing = FastOutSlowInEasing))
                    delay(300)
                }
            }

            TextAnimationType.EXPANDING -> {
                while (true) {
                    typedText.value = text
                    bounceScale.snapTo(0.3f)
                    bounceScale.animateTo(1f, tween(expandDur, easing = FastOutSlowInEasing))
                    delay(2000)
                    bounceScale.animateTo(0.3f, tween(expandDur / 2, easing = FastOutSlowInEasing))
                    delay(300)
                }
            }

            TextAnimationType.SLIDING, TextAnimationType.SLIDE_LEFT, TextAnimationType.SLIDE_RIGHT,
            TextAnimationType.SLIDE_UP, TextAnimationType.SLIDE_DOWN -> {
                while (true) {
                    typedText.value = text
                    slideOffset.snapTo(1f)
                    slideOffset.animateTo(0f, tween(slideDur, easing = FastOutSlowInEasing))
                    delay(2000)
                    slideOffset.animateTo(1f, tween(slideDur / 2, easing = FastOutSlowInEasing))
                    delay(300)
                }
            }

            TextAnimationType.WORD_BY_WORD -> {
                val words = text.split(" ")
                while (true) {
                    visibleWordCount.intValue = 0
                    for (i in 1..words.size) { visibleWordCount.intValue = i; delay(400) }
                    delay(2000)
                    for (i in words.size downTo 0) { visibleWordCount.intValue = i; delay(150) }
                    delay(500)
                }
            }

            TextAnimationType.CHARACTER_WAVE -> {
                typedText.value = text
                while (true) {
                    for (i in 0..text.length) { charWaveIndex.intValue = i; delay(100) }
                    delay(2000)
                }
            }

            TextAnimationType.BOUNCE_IN -> {
                while (true) {
                    typedText.value = text
                    bounceScale.snapTo(0f)
                    bounceScale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 1500f))
                    delay(3000)
                    bounceScale.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 1500f))
                    delay(300)
                }
            }

            TextAnimationType.RAINBOW_SHIMMER -> { typedText.value = text }

            TextAnimationType.GLITCH -> {
                val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ#$%&@!?*"
                while (true) {
                    for (frame in 0..15) {
                        val sb = StringBuilder()
                        for (c in text) {
                            if (c == ' ') { sb.append(' '); continue }
                            if (frame > 12) sb.append(c) else sb.append(chars[Random.nextInt(chars.length)])
                        }
                        glitchText.value = sb.toString()
                        delay(50)
                    }
                    glitchText.value = text
                    delay(2500)
                }
            }

            TextAnimationType.ZOOM_BLUR -> {
                while (true) {
                    typedText.value = text
                    zoomBlurAmount.snapTo(20f)
                    fadeInAlpha.snapTo(0f)
                    kotlinx.coroutines.coroutineScope {
                        launch { zoomBlurAmount.animateTo(0f, tween(expandDur, easing = FastOutSlowInEasing)) }
                        launch { fadeInAlpha.animateTo(1f, tween(fadeDur, easing = FastOutSlowInEasing)) }
                    }
                    delay(2500)
                    fadeInAlpha.animateTo(0f, tween(fadeDur / 2))
                    delay(300)
                }
            }

            TextAnimationType.SPRING -> {
                while (true) {
                    typedText.value = text
                    springScale.snapTo(0f)
                    springScale.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = 400f))
                    delay(3000)
                    springScale.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 1500f))
                    delay(300)
                }
            }

            TextAnimationType.FLIP_3D -> {
                while (true) {
                    typedText.value = text
                    flipRotation.snapTo(90f)
                    fadeInAlpha.snapTo(0f)
                    kotlinx.coroutines.coroutineScope {
                        launch { flipRotation.animateTo(0f, tween(expandDur, easing = FastOutSlowInEasing)) }
                        launch { fadeInAlpha.animateTo(1f, tween(fadeDur, easing = FastOutSlowInEasing)) }
                    }
                    delay(3000)
                    flipRotation.animateTo(-90f, tween(expandDur / 2, easing = FastOutSlowInEasing))
                    fadeInAlpha.animateTo(0f, tween(fadeDur / 2))
                    delay(300)
                }
            }
        }
    }

    val textStyle = androidx.compose.ui.text.TextStyle(
        color = textColour,
        fontSize = (element.fontSizeSp ?: 12f).sp,
        fontWeight = FontWeight(element.fontWeight ?: 400),
        letterSpacing = (element.letterSpacing ?: 0f).sp,
        fontFamily = fontFamily,
    )

    val finalStyle = when {
        rainbowBrush != null -> textStyle.copy(brush = rainbowBrush)
        gradientBrush != null -> textStyle.copy(brush = gradientBrush)
        else -> textStyle
    }

    val displayText = when (textAnimType) {
        TextAnimationType.WORD_BY_WORD -> {
            val words = text.split(" ")
            words.take(visibleWordCount.intValue).joinToString(" ")
        }
        TextAnimationType.GLITCH -> glitchText.value
        else -> typedText.value
    }

    val baseModifier = Modifier
        .alpha(element.opacity)

    val animModifier: Modifier = when (textAnimType) {
        TextAnimationType.FADING -> baseModifier.alpha(fadeInAlpha.value)
        TextAnimationType.EXPANDING -> baseModifier.graphicsLayer { scaleX = bounceScale.value; scaleY = bounceScale.value }
        TextAnimationType.BOUNCE_IN -> baseModifier.graphicsLayer { scaleX = bounceScale.value; scaleY = bounceScale.value }
        TextAnimationType.SPRING -> baseModifier.graphicsLayer { scaleX = springScale.value; scaleY = springScale.value }
        TextAnimationType.SLIDING -> baseModifier.graphicsLayer { translationX = slideOffset.value * 300f }
        TextAnimationType.SLIDE_LEFT -> baseModifier.graphicsLayer { translationX = slideOffset.value * 300f }
        TextAnimationType.SLIDE_RIGHT -> baseModifier.graphicsLayer { translationX = -slideOffset.value * 300f }
        TextAnimationType.SLIDE_UP -> baseModifier.graphicsLayer { translationY = slideOffset.value * 100f }
        TextAnimationType.SLIDE_DOWN -> baseModifier.graphicsLayer { translationY = -slideOffset.value * 100f }
        TextAnimationType.ZOOM_BLUR -> baseModifier
            .alpha(fadeInAlpha.value)
            .graphicsLayer { scaleX = 1f + zoomBlurAmount.value / 20f; scaleY = 1f + zoomBlurAmount.value / 20f }
            .blur(if (zoomBlurAmount.value > 1f) zoomBlurAmount.value.dp else 0.dp)
        TextAnimationType.FLIP_3D -> baseModifier
            .alpha(fadeInAlpha.value)
            .graphicsLayer { rotationY = flipRotation.value }
        TextAnimationType.CHARACTER_WAVE -> baseModifier.graphicsLayer {
            val wave = kotlin.math.sin((charWaveIndex.intValue) * 0.5f) * 10f
            translationY = wave
        }
        else -> baseModifier
    }

    Box(
        modifier = Modifier
            .offset(x = element.xDp.dp, y = element.yDp.dp)
            .size(width = element.widthDp.dp, height = element.heightDp.dp),
    ) {
        if (textAnimType == TextAnimationType.TYPEWRITER_CURSOR && showCursor.value) {
            Row(
                modifier = animModifier,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = displayText,
                    style = finalStyle,
                    maxLines = 5,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                val cursorAlpha by animateFloatAsState(
                    targetValue = if (typedText.value.length < text.length) 1f else 0f,
                    animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
                    label = "el_cursor"
                )
                Text(
                    text = "|",
                    style = finalStyle.copy(color = textColour.copy(alpha = cursorAlpha)),
                    maxLines = 1,
                )
            }
        } else {
            Text(
                text = displayText,
                style = finalStyle,
                maxLines = 5,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = animModifier,
            )
        }
    }
}

@Composable
private fun RenderLottieElement(element: AdElement, pack: AdPack, cache: AdPackCache) {
    // Load lottie from cached file instead of inline base64
    val lottieJson = remember(element.id) {
        val lottieFile = cache.getElementLottieFile(element.id)
        if (lottieFile != null) {
            try { lottieFile.readText() } catch (_: Exception) { null }
        } else {
            // Fallback to inline data if present (backward compat during transition)
            val lottieBase64 = element.lottieData
            if (!lottieBase64.isNullOrBlank()) {
                try {
                    val decoded = Base64.decode(lottieBase64, Base64.NO_WRAP)
                    String(decoded, Charsets.UTF_8)
                } catch (_: Exception) { null }
            } else null
        }
    }

    if (lottieJson == null) {
        Log.w("StickmanAdSpace", "Lottie element data corrupt for element ${element.id}, skipping animation")
        return
    }

    val composition by rememberLottieComposition(
        LottieCompositionSpec.JsonString(lottieJson)
    )

    val loopMode = element.lottieLoopMode ?: "infinite"
    val iterations = when (loopMode) {
        "once" -> 1
        "n_times" -> (element.lottieLoopCount ?: 1).coerceAtLeast(1)
        else -> Int.MAX_VALUE
    }

    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = iterations,
    )

    val dynamicProperties: LottieDynamicProperties? = element.stickmanColour?.let { colourHex ->
        val colourInt = runCatching { android.graphics.Color.parseColor(colourHex) }.getOrNull() ?: return@let null
        LottieDynamicProperties(
            listOf(
                LottieDynamicProperty(
                    property = LottieProperty.COLOR,
                    value = colourInt,
                    keyPath = KeyPath("**"),
                )
            )
        )
    }

    Box(
        modifier = Modifier
            .offset(x = element.xDp.dp, y = element.yDp.dp)
            .size(width = element.widthDp.dp, height = element.heightDp.dp),
    ) {
        LottieAnimation(
            composition = composition,
            progress = { progress },
            dynamicProperties = dynamicProperties,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .alpha(element.opacity),
        )
    }
}

@Composable
private fun RenderImageElement(element: AdElement, pack: AdPack, cache: AdPackCache) {
    // Load image from cached file instead of inline base64
    val imageBytes = remember(element.id) {
        val imageFile = cache.getElementImageFile(element.id)
        if (imageFile != null) {
            try { imageFile.readBytes() } catch (_: Exception) { null }
        } else {
            // Fallback to inline data if present
            val imageBase64 = element.imageData
            if (!imageBase64.isNullOrBlank()) {
                runCatching { Base64.decode(imageBase64, Base64.NO_WRAP) }.getOrNull()
            } else null
        }
    }
    if (imageBytes == null) return

    // GIF/WBPM animated images need a Coil ImageLoader with GifDecoder registered,
    // otherwise Coil falls back to the static decoder and renders frame one as a still.
    val context = androidx.compose.ui.platform.LocalContext.current
    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(GifDecoder.Factory()) }
            .build()
    }

    Box(
        modifier = Modifier
            .offset(x = element.xDp.dp, y = element.yDp.dp)
            .size(width = element.widthDp.dp, height = element.heightDp.dp),
    ) {
        AsyncImage(
            model = imageBytes,
            contentDescription = element.imageFileName,
            imageLoader = imageLoader,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .alpha(element.opacity),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        )
    }
}

@Composable
private fun RenderVideoElement(element: AdElement, pack: AdPack, cache: AdPackCache) {
    // Use cached video file directly instead of decoding base64
    val context = androidx.compose.ui.platform.LocalContext.current
    val videoFile = remember(element.id) {
        val cachedFile = cache.getElementVideoFile(element.id)
        if (cachedFile != null) {
            cachedFile
        } else {
            // Fallback to inline data if present
            val videoBase64 = element.videoData
            if (!videoBase64.isNullOrBlank()) {
                try {
                    val bytes = Base64.decode(videoBase64, Base64.NO_WRAP)
                    val file = File(context.cacheDir, "adspace_video_${element.id}.mp4")
                    file.writeBytes(bytes)
                    file
                } catch (_: Exception) { null }
            } else null
        }
    }
    if (videoFile == null) {
        Log.w("StickmanAdSpace", "Video element data corrupt for element ${element.id}, skipping")
        return
    }

    val loopMode = element.lottieLoopMode ?: "infinite"
    val player = remember(element.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoFile.toUri()))
            repeatMode = if (loopMode == "once") Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(element.id) {
        onDispose {
            player.release()
        }
    }

    Box(
        modifier = Modifier
            .offset(x = element.xDp.dp, y = element.yDp.dp)
            .size(width = element.widthDp.dp, height = element.heightDp.dp),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .alpha(element.opacity),
        )
    }
}

@Composable
private fun RenderScriptElement(element: AdElement, pack: AdPack) {
    val scriptContent = element.scriptContent
    Log.d("StickmanAdSpace", "RenderScriptElement id=${element.id} scriptContent=${if (scriptContent == null) "NULL" else if (scriptContent.isBlank()) "BLANK" else "len=${scriptContent.length}"}")
    if (scriptContent.isNullOrBlank()) return

    val html = remember(element.id, scriptContent) {
        parsePortalScript(scriptContent)
    }
    Log.d("StickmanAdSpace", "RenderScriptElement id=${element.id} html len=${html.length}")

    var webViewRef by remember { mutableStateOf<android.webkit.WebView?>(null) }

    Box(
        modifier = Modifier
            .offset(x = element.xDp.dp, y = element.yDp.dp)
            .size(width = element.widthDp.dp, height = element.heightDp.dp),
    ) {
        AndroidView(
            factory = { ctx ->
                android.webkit.WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                            Log.d("StickmanAdSpace", "WebView onPageFinished base=$url")
                            view?.evaluateJavascript(
                                "(function(){ var b=document.body; var e=b&&b.firstElementChild; var r=e?e.getBoundingClientRect():null; return JSON.stringify({textLen:(b?b.textContent.trim().length:-1), firstTag:(e?e.tagName:null), colour:(e?getComputedStyle(e).color:null), fontSize:(e?getComputedStyle(e).fontSize:null), rect:(r?{x:r.x,y:r.y,w:r.width,h:r.height}:null), html:(b?b.innerHTML.substring(0,400):null)}); })()"
                            ) { r -> Log.d("StickmanAdSpace", "ScriptAd DOM probe: $r") }
                        }
                        override fun onReceivedError(view: android.webkit.WebView?, req: android.webkit.WebResourceRequest?, err: android.webkit.WebResourceError?) {
                            Log.w("StickmanAdSpace", "ScriptAd WebView error: ${err?.description} (${req?.url})")
                        }
                    }
                    tag = html
                    // Base URL must be a real file:// asset path, never about:blank.
                    // A transparent-background WebView loading data from about:blank
                    // does not get an alpha-composited surface in hardware mode and
                    // paints nothing — every working WebView in this app
                    // (PortalCanvas, PortalWebView) loads from the portal asset base.
                    loadDataWithBaseURL("file:///android_asset/portal/", html, "text/html", "UTF-8", null)
                    webViewRef = this
                }
            },
            update = { web ->
                // The update block runs on every recomposition (streaming tokens,
                // rotation ticks, UI state changes). Reloading the WebView each time
                // restarts the page load before it can finish, so the script never
                // renders. Only reload when the HTML actually changed.
                val lastLoaded = web.tag as? String
                if (lastLoaded != html) {
                    web.tag = html
                    web.loadDataWithBaseURL("file:///android_asset/portal/", html, "text/html", "UTF-8", null)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .alpha(element.opacity),
        )
    }

    // Destroy the WebView when the element leaves composition (entry rotation,
    // pack change, screen close). Without this every rotation leaks a WebView.
    DisposableEffect(element.id) {
        onDispose {
            webViewRef?.let { wv ->
                (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                wv.destroy()
            }
            webViewRef = null
        }
    }
}

/**
 * Parse a portal-format script (YAML-like frontmatter + HTML body) into a
 * complete HTML document string for WebView loadDataWithBaseURL.
 *
 * The frontmatter delimiter is a line containing only `---`. Fields are
 * multiline string values indented with `|` or single-line values. We extract
 * `css` and `js` blocks and inject them into a standard HTML skeleton wrapped
 * around the body content. Everything runs inside the WebView sandbox.
 */
private fun parsePortalScript(content: String): String {
    val lines = content.split("\n")
    var css = ""
    var js = ""
    var body = ""

    var fmStart = -1
    var fmEnd = -1
    for (i in lines.indices) {
        if (lines[i].trim() == "---") {
            if (fmStart == -1) { fmStart = i; continue }
            if (fmEnd == -1) { fmEnd = i; break }
        }
    }

    if (fmStart != -1 && fmEnd != -1) {
        var i = fmStart + 1
        while (i < fmEnd) {
            val line = lines[i]
            val match = Regex("^(\\w+):\\s*(.*)$").find(line)
            if (match != null) {
                val key = match.groupValues[1]
                val `val` = match.groupValues[2].trim()
                if (`val` == "|" || `val` == ">") {
                    val blockLines = mutableListOf<String>()
                    i++
                    while (i < fmEnd && (lines[i].startsWith("  ") || lines[i].startsWith("\t") || lines[i].isEmpty())) {
                        blockLines.add(lines[i].removePrefix("  "))
                        i++
                    }
                    when (key) {
                        "css" -> css = blockLines.joinToString("\n")
                        "js" -> js = blockLines.joinToString("\n")
                    }
                    continue
                }
            }
            i++
        }
        body = lines.drop(fmEnd + 1).joinToString("\n")
    } else {
        body = content
    }

    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
        <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        html, body { width: 100%; height: 100%; overflow: hidden; background: transparent; }
        $css
        </style>
        </head>
        <body>
        $body
        <script>
        $js
        </script>
        </body>
        </html>
    """.trimIndent()
}

@Composable
private fun AnimationEntry(entry: AdEntry, pack: AdPack, cache: AdPackCache, modifier: Modifier = Modifier) {
    // Use try-catch for Lottie loading to prevent crashes on corrupt data
    if (entry.animationConfig != null && entry.animationConfig?.type == "custom") {
        CustomAnimationEntry(entry, pack, modifier)
    } else if (entry.lottieData.isNotEmpty()) {
        LottieEntry(entry, pack, cache, modifier)
    }
    // If no animation config and no lottie data, render nothing
}

@Composable
private fun CustomAnimationEntry(entry: AdEntry, pack: AdPack, modifier: Modifier = Modifier) {
    val config = entry.animationConfig ?: return
    val keyframes = config.keyframes
    val stickmanColour = remember(config.stickmanColour) {
        runCatching { Color(android.graphics.Color.parseColor(config.stickmanColour)) }.getOrDefault(Color(0xFFDAA520))
    }
    val durationMs = config.durationMs
    val animTime = remember { mutableStateOf(0L) }

    LaunchedEffect(config) {
        val startTime = System.currentTimeMillis()
        while (true) {
            animTime.value = (System.currentTimeMillis() - startTime) % durationMs.coerceAtLeast(1)
            delay(16)
        }
    }

    val pose = remember(animTime.value, keyframes) {
        getPoseAtTime(keyframes, animTime.value)
    }

    // Calculate height from formula
    val textHeightDp = entry.fontSizeSp * 1.5f
    val availableHeightDp = if (entry.textPosition == TextPosition.BESIDE_ANIMATION) {
        pack.boxHeightDp.toFloat()
    } else {
        (pack.boxHeightDp - textHeightDp).coerceAtLeast(1f)
    }
    val stickmanHeight = (availableHeightDp * (if (entry.stickmanScale > 0) entry.stickmanScale else config.stickmanScale)).dp

    Canvas(modifier = modifier.fillMaxWidth().height(stickmanHeight)) {
        val L = StickmanLayout
        val w = size.width
        val h = size.height
        val scale = minOf(w / 100f, h / 100f)
        val offsetX = (w - 100f * scale) / 2f
        val offsetY = (h - 100f * scale) / 2f

        fun sx(x: Float) = offsetX + x * scale
        fun sy(y: Float) = offsetY + y * scale

        val strokeWidth = 2.5f * scale

        val shoulder = L.shoulder
        val hip = L.hip
        val leftElbow = getEndpoint(shoulder.x, shoulder.y, L.upperArmLength, pose.leftArm)
        val leftHand = getEndpoint(leftElbow.x, leftElbow.y, L.forearmLength, pose.leftArm + pose.leftForearm)
        val rightElbow = getEndpoint(shoulder.x, shoulder.y, L.upperArmLength, pose.rightArm)
        val rightHand = getEndpoint(rightElbow.x, rightElbow.y, L.forearmLength, pose.rightArm + pose.rightForearm)
        val leftKnee = getEndpoint(hip.x, hip.y, L.upperLegLength, pose.leftLeg)
        val leftFoot = getEndpoint(leftKnee.x, leftKnee.y, L.shinLength, pose.leftLeg + pose.leftShin)
        val rightKnee = getEndpoint(hip.x, hip.y, L.upperLegLength, pose.rightLeg)
        val rightFoot = getEndpoint(rightKnee.x, rightKnee.y, L.shinLength, pose.rightLeg + pose.rightShin)
        val headCx = L.neck.x
        val headCy = L.neck.y - L.headRadius

        // Spine
        drawLine(stickmanColour, Offset(sx(L.neck.x), sy(L.neck.y)), Offset(sx(hip.x), sy(hip.y)), strokeWidth)
        // Left arm
        drawLine(stickmanColour, Offset(sx(shoulder.x), sy(shoulder.y)), Offset(sx(leftElbow.x), sy(leftElbow.y)), strokeWidth)
        drawLine(stickmanColour, Offset(sx(leftElbow.x), sy(leftElbow.y)), Offset(sx(leftHand.x), sy(leftHand.y)), strokeWidth)
        // Right arm
        drawLine(stickmanColour, Offset(sx(shoulder.x), sy(shoulder.y)), Offset(sx(rightElbow.x), sy(rightElbow.y)), strokeWidth)
        drawLine(stickmanColour, Offset(sx(rightElbow.x), sy(rightElbow.y)), Offset(sx(rightHand.x), sy(rightHand.y)), strokeWidth)
        // Left leg
        drawLine(stickmanColour, Offset(sx(hip.x), sy(hip.y)), Offset(sx(leftKnee.x), sy(leftKnee.y)), strokeWidth)
        drawLine(stickmanColour, Offset(sx(leftKnee.x), sy(leftKnee.y)), Offset(sx(leftFoot.x), sy(leftFoot.y)), strokeWidth)
        // Right leg
        drawLine(stickmanColour, Offset(sx(hip.x), sy(hip.y)), Offset(sx(rightKnee.x), sy(rightKnee.y)), strokeWidth)
        drawLine(stickmanColour, Offset(sx(rightKnee.x), sy(rightKnee.y)), Offset(sx(rightFoot.x), sy(rightFoot.y)), strokeWidth)
        // Head
        drawCircle(stickmanColour, L.headRadius * scale, Offset(sx(headCx), sy(headCy)), style = Stroke(width = strokeWidth))
    }
}

private data class StickmanLayoutData(
    val headRadius: Float = 8f,
    val neck: Offset = Offset(50f, 22f),
    val hip: Offset = Offset(50f, 62f),
    val shoulder: Offset = Offset(50f, 28f),
    val upperArmLength: Float = 16f,
    val forearmLength: Float = 14f,
    val upperLegLength: Float = 18f,
    val shinLength: Float = 16f,
)

private val StickmanLayout = StickmanLayoutData()

private fun getEndpoint(sx: Float, sy: Float, len: Float, angleDeg: Float): Offset {
    val rad = (angleDeg - 90f) * (Math.PI / 180.0).toFloat()
    return Offset(sx + kotlin.math.cos(rad) * len, sy + kotlin.math.sin(rad) * len)
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun lerpPose(p1: Pose, p2: Pose, t: Float): Pose {
    return Pose(
        head = lerp(p1.head, p2.head, t),
        spine = lerp(p1.spine, p2.spine, t),
        leftArm = lerp(p1.leftArm, p2.leftArm, t),
        rightArm = lerp(p1.rightArm, p2.rightArm, t),
        leftForearm = lerp(p1.leftForearm, p2.leftForearm, t),
        rightForearm = lerp(p1.rightForearm, p2.rightForearm, t),
        leftLeg = lerp(p1.leftLeg, p2.leftLeg, t),
        rightLeg = lerp(p1.rightLeg, p2.rightLeg, t),
        leftShin = lerp(p1.leftShin, p2.leftShin, t),
        rightShin = lerp(p1.rightShin, p2.rightShin, t),
    )
}

private fun getPoseAtTime(keyframes: List<Keyframe>, timeMs: Long): Pose {
    if (keyframes.isEmpty()) return Pose()
    if (keyframes.size == 1) return keyframes[0].pose
    val sorted = keyframes.sortedBy { it.time }
    val duration = sorted.last().time
    if (duration <= 0) return sorted.first().pose
    val t = timeMs % duration
    var prev = sorted.first()
    var next = sorted[1]
    for (i in 0 until sorted.size - 1) {
        if (sorted[i].time <= t && sorted[i + 1].time >= t) {
            prev = sorted[i]
            next = sorted[i + 1]
            break
        }
    }
    val range = next.time - prev.time
    val ratio = if (range > 0) (t - prev.time).toFloat() / range.toFloat() else 0f
    return lerpPose(prev.pose, next.pose, ratio)
}

@Composable
private fun LottieEntry(entry: AdEntry, pack: AdPack, cache: AdPackCache, modifier: Modifier = Modifier) {
    // Load lottie from cached file, falling back to inline data if present
    val lottieJson = remember(entry.id) {
        val lottieFile = cache.getEntryLottieFile(entry.id)
        if (lottieFile != null) {
            try { lottieFile.readText() } catch (_: Exception) { null }
        } else if (entry.lottieData.isNotEmpty()) {
            try {
                val decoded = Base64.decode(entry.lottieData, Base64.NO_WRAP)
                String(decoded, Charsets.UTF_8)
            } catch (_: Exception) { null }
        } else null
    }

    if (lottieJson == null) {
        // Corrupt Lottie — render nothing, slogan still shows
        Log.w("StickmanAdSpace", "Lottie data corrupt for entry ${entry.id}, skipping animation")
        return
    }

    // Dynamic colour properties for stickman_colour override
    val dynamicProperties: LottieDynamicProperties? = entry.stickmanColour?.let { colourHex ->
        val colourInt = runCatching { android.graphics.Color.parseColor(colourHex) }.getOrNull() ?: return@let null
        LottieDynamicProperties(
            listOf(
                LottieDynamicProperty(
                    property = LottieProperty.COLOR,
                    value = colourInt,
                    keyPath = KeyPath("**"),
                )
            )
        )
    }

    val composition by rememberLottieComposition(
        LottieCompositionSpec.JsonString(lottieJson)
    )

    // Calculate loop iterations
    val loopMode = entry.lottieLoopMode
    val iterations = when (loopMode) {
        "once" -> 1
        "n_times" -> entry.lottieLoopCount.coerceAtLeast(1)
        else -> Int.MAX_VALUE
    }

    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = iterations,
    )

    // Calculate Lottie height from formula
    val textHeightDp = entry.fontSizeSp * 1.5f
    val availableHeightDp = if (entry.textPosition == TextPosition.BESIDE_ANIMATION) {
        pack.boxHeightDp.toFloat()
    } else {
        (pack.boxHeightDp - textHeightDp).coerceAtLeast(1f)
    }
    val lottieHeight = (availableHeightDp * entry.stickmanScale).coerceAtLeast(1f).dp

    LottieAnimation(
        composition = composition,
        progress = { progress },
        modifier = modifier.fillMaxWidth().height(lottieHeight),
        dynamicProperties = dynamicProperties,
    )
}

@Composable
private fun SloganText(entry: AdEntry, modifier: Modifier = Modifier) {
    val colourIndex = remember { mutableIntStateOf(0) }
    val typedText = remember { mutableStateOf("") }
    val visibleWordCount = remember { mutableIntStateOf(0) }
    val charWaveIndex = remember { mutableIntStateOf(0) }
    val glitchText = remember { mutableStateOf("") }
    val showCursor = remember { mutableStateOf(false) }

    // Animation progress states
    val fadeInAlpha = remember { Animatable(0f) }
    val bounceScale = remember { Animatable(0f) }
    val slideOffset = remember { Animatable(0f) }
    val zoomBlurAmount = remember { Animatable(20f) }
    val springScale = remember { Animatable(0f) }
    val flipRotation = remember { Animatable(90f) }

    // Rainbow shimmer infinite transition
    val shimmerTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerProgress by shimmerTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerProgress"
    )

    // Text colour based on colour mode
    val textColour: Color = when (entry.textColourMode) {
        TextColourMode.SINGLE -> {
            runCatching { Color(android.graphics.Color.parseColor(entry.textColours.getOrElse(0) { "#DAA520" })) }
                .getOrDefault(Color(0xFFDAA520))
        }
        TextColourMode.CYCLING -> {
            val colours = entry.textColours.mapNotNull { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
            if (colours.isNotEmpty()) colours[colourIndex.intValue % colours.size] else Color(0xFFDAA520)
        }
        TextColourMode.GRADIENT -> {
            runCatching { Color(android.graphics.Color.parseColor(entry.textColours.getOrElse(0) { "#DAA520" })) }
                .getOrDefault(Color(0xFFDAA520))
        }
    }

    // Gradient brush for gradient mode
    val gradientBrush: Brush? = if (entry.textColourMode == TextColourMode.GRADIENT && entry.textColours.size >= 2) {
        val colours = entry.textColours.mapNotNull { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
        if (colours.size >= 2) {
            Brush.linearGradient(
                colors = colours,
                start = Offset.Zero,
                end = Offset(
                    kotlin.math.cos(Math.toRadians(entry.gradientDirection.toDouble())).toFloat() * 1000f,
                    kotlin.math.sin(Math.toRadians(entry.gradientDirection.toDouble())).toFloat() * 1000f
                )
            )
        } else null
    } else null

    // Rainbow shimmer brush
    val rainbowBrush: Brush? = if (entry.textAnimation == TextAnimationType.RAINBOW_SHIMMER) {
        val hue = (shimmerProgress * 360f)
        val hue2 = ((shimmerProgress * 360f) + 120f) % 360f
        val hue3 = ((shimmerProgress * 360f) + 240f) % 360f
        Brush.linearGradient(
            colors = listOf(
                Color.hsv(hue, 0.8f, 1f),
                Color.hsv(hue2, 0.8f, 1f),
                Color.hsv(hue3, 0.8f, 1f),
                Color.hsv(hue, 0.8f, 1f),
            ),
            start = Offset.Zero,
            end = Offset(1000f, 0f)
        )
    } else null

    LaunchedEffect(entry.textColourMode, entry.textColourCycleMs) {
        if (entry.textColourMode == TextColourMode.CYCLING && entry.textColours.size > 1) {
            while (true) {
                delay(entry.textColourCycleMs)
                colourIndex.intValue = (colourIndex.intValue + 1) % entry.textColours.size
            }
        }
    }

    // Animation launcher
    LaunchedEffect(entry.id, entry.slogan, entry.textAnimation) {
        // Reset all states
        typedText.value = ""
        visibleWordCount.intValue = 0
        charWaveIndex.intValue = 0
        glitchText.value = ""
        showCursor.value = false
        fadeInAlpha.snapTo(0f)
        bounceScale.snapTo(0f)
        slideOffset.snapTo(0f)
        zoomBlurAmount.snapTo(20f)
        springScale.snapTo(0f)
        flipRotation.snapTo(90f)

        val slogan = entry.slogan
        val expandDur = entry.expandDurationMs.coerceAtLeast(100).toInt()
        val fadeDur = entry.fadeDurationMs.coerceAtLeast(100).toInt()
        val slideDur = entry.textSlideDurationMs.coerceAtLeast(100).toInt()

        when (entry.textAnimation) {
            TextAnimationType.STATIC -> {
                typedText.value = slogan
            }

            TextAnimationType.TYPING -> {
                val typeSpeed = entry.typingSpeedMs.coerceAtLeast(1)
                val deleteSpeed = entry.typingDeleteSpeedMs.coerceAtLeast(1)
                for (i in 1..slogan.length) {
                    typedText.value = slogan.substring(0, i)
                    delay(typeSpeed)
                }
                delay(2000)
                while (true) {
                    for (i in 0..slogan.length) {
                        typedText.value = slogan.substring(0, slogan.length - i)
                        delay(deleteSpeed)
                    }
                    delay(500)
                    for (i in 1..slogan.length) {
                        typedText.value = slogan.substring(0, i)
                        delay(typeSpeed)
                    }
                    delay(2000)
                }
            }

            TextAnimationType.TYPEWRITER_CURSOR -> {
                val typeSpeed = entry.typingSpeedMs.coerceAtLeast(1)
                val deleteSpeed = entry.typingDeleteSpeedMs.coerceAtLeast(1)
                showCursor.value = true
                for (i in 1..slogan.length) {
                    typedText.value = slogan.substring(0, i)
                    delay(typeSpeed)
                }
                delay(2000)
                while (true) {
                    for (i in 0..slogan.length) {
                        typedText.value = slogan.substring(0, slogan.length - i)
                        delay(deleteSpeed)
                    }
                    delay(500)
                    for (i in 1..slogan.length) {
                        typedText.value = slogan.substring(0, i)
                        delay(typeSpeed)
                    }
                    delay(2000)
                }
            }

            TextAnimationType.FADING -> {
                while (true) {
                    typedText.value = slogan
                    fadeInAlpha.snapTo(0f)
                    fadeInAlpha.animateTo(1f, tween(fadeDur, easing = FastOutSlowInEasing))
                    delay(2000)
                    fadeInAlpha.animateTo(0f, tween(fadeDur, easing = FastOutSlowInEasing))
                    delay(300)
                }
            }

            TextAnimationType.EXPANDING -> {
                while (true) {
                    typedText.value = slogan
                    bounceScale.snapTo(0.3f)
                    bounceScale.animateTo(1f, tween(expandDur, easing = FastOutSlowInEasing))
                    delay(2000)
                    bounceScale.animateTo(0.3f, tween(expandDur / 2, easing = FastOutSlowInEasing))
                    delay(300)
                }
            }

            TextAnimationType.SLIDING,
            TextAnimationType.SLIDE_LEFT,
            TextAnimationType.SLIDE_RIGHT,
            TextAnimationType.SLIDE_UP,
            TextAnimationType.SLIDE_DOWN -> {
                while (true) {
                    typedText.value = slogan
                    slideOffset.snapTo(1f)
                    slideOffset.animateTo(0f, tween(slideDur, easing = FastOutSlowInEasing))
                    delay(2000)
                    slideOffset.animateTo(1f, tween(slideDur / 2, easing = FastOutSlowInEasing))
                    delay(300)
                }
            }

            TextAnimationType.WORD_BY_WORD -> {
                val words = slogan.split(" ")
                while (true) {
                    visibleWordCount.intValue = 0
                    for (i in 1..words.size) {
                        visibleWordCount.intValue = i
                        delay(400)
                    }
                    delay(2000)
                    for (i in words.size downTo 0) {
                        visibleWordCount.intValue = i
                        delay(150)
                    }
                    delay(500)
                }
            }

            TextAnimationType.CHARACTER_WAVE -> {
                typedText.value = slogan
                while (true) {
                    for (i in 0..slogan.length) {
                        charWaveIndex.intValue = i
                        delay(100)
                    }
                    delay(2000)
                }
            }

            TextAnimationType.BOUNCE_IN -> {
                while (true) {
                    typedText.value = slogan
                    bounceScale.snapTo(0f)
                    bounceScale.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = 0.5f,
                            stiffness = 1500f
                        )
                    )
                    delay(3000)
                    bounceScale.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = 0.5f,
                            stiffness = 1500f
                        )
                    )
                    delay(300)
                }
            }

            TextAnimationType.RAINBOW_SHIMMER -> {
                typedText.value = slogan
                // The shimmer is handled by the infinite transition, just keep text visible
            }

            TextAnimationType.GLITCH -> {
                val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ#$%&@!?*"
                while (true) {
                    // Scramble phase
                    for (frame in 0..15) {
                        val sb = StringBuilder()
                        for (c in slogan) {
                            if (c == ' ') { sb.append(' '); continue }
                            if (frame > 12) {
                                sb.append(c)
                            } else {
                                sb.append(chars[Random.nextInt(chars.length)])
                            }
                        }
                        glitchText.value = sb.toString()
                        delay(50)
                    }
                    glitchText.value = slogan
                    delay(2500)
                }
            }

            TextAnimationType.ZOOM_BLUR -> {
                while (true) {
                    typedText.value = slogan
                    zoomBlurAmount.snapTo(20f)
                    fadeInAlpha.snapTo(0f)
                    kotlinx.coroutines.coroutineScope {
                        launch { zoomBlurAmount.animateTo(0f, tween(expandDur, easing = FastOutSlowInEasing)) }
                        launch { fadeInAlpha.animateTo(1f, tween(fadeDur, easing = FastOutSlowInEasing)) }
                    }
                    delay(2500)
                    fadeInAlpha.animateTo(0f, tween(fadeDur / 2))
                    delay(300)
                }
            }

            TextAnimationType.SPRING -> {
                while (true) {
                    typedText.value = slogan
                    springScale.snapTo(0f)
                    springScale.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = 0.3f,
                            stiffness = 400f
                        )
                    )
                    delay(3000)
                    springScale.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = 0.5f,
                            stiffness = 1500f
                        )
                    )
                    delay(300)
                }
            }

            TextAnimationType.FLIP_3D -> {
                while (true) {
                    typedText.value = slogan
                    flipRotation.snapTo(90f)
                    fadeInAlpha.snapTo(0f)
                    kotlinx.coroutines.coroutineScope {
                        launch { flipRotation.animateTo(0f, tween(expandDur, easing = FastOutSlowInEasing)) }
                        launch { fadeInAlpha.animateTo(1f, tween(fadeDur, easing = FastOutSlowInEasing)) }
                    }
                    delay(3000)
                    flipRotation.animateTo(-90f, tween(expandDur / 2, easing = FastOutSlowInEasing))
                    fadeInAlpha.animateTo(0f, tween(fadeDur / 2))
                    delay(300)
                }
            }
        }
    }

    // Font family mapping
    val composeFontFamily: FontFamily? = entry.fontFamily?.let { name ->
        when (name.lowercase()) {
            "serif" -> FontFamily.Serif
            "sans-serif", "sansserif", "system" -> FontFamily.SansSerif
            "monospace", "mono" -> FontFamily.Monospace
            "cursive" -> FontFamily.Cursive
            "default", "" -> null
            else -> null
        }
    }

    val textStyle = androidx.compose.ui.text.TextStyle(
        color = textColour,
        fontSize = entry.fontSizeSp.sp,
        fontWeight = FontWeight(entry.fontWeight),
        letterSpacing = entry.letterSpacing.sp,
        fontFamily = composeFontFamily,
    )

    val finalStyle = when {
        rainbowBrush != null -> textStyle.copy(brush = rainbowBrush)
        gradientBrush != null -> textStyle.copy(brush = gradientBrush)
        else -> textStyle
    }

    // Determine display text based on animation type
    val displayText = when (entry.textAnimation) {
        TextAnimationType.WORD_BY_WORD -> {
            val words = entry.slogan.split(" ")
            words.take(visibleWordCount.intValue).joinToString(" ")
        }
        TextAnimationType.GLITCH -> glitchText.value
        else -> typedText.value
    }

    // Base modifier with text offset applied to all animation types
    val baseModifier = modifier.offset(
        x = entry.textXOffsetDp.dp,
        y = entry.textYOffsetDp.dp,
    )

    // Build modifier based on animation type
    val animModifier: Modifier = when (entry.textAnimation) {
        TextAnimationType.FADING -> baseModifier.alpha(fadeInAlpha.value)
        TextAnimationType.EXPANDING -> baseModifier.graphicsLayer { scaleX = bounceScale.value; scaleY = bounceScale.value }
        TextAnimationType.BOUNCE_IN -> baseModifier.graphicsLayer { scaleX = bounceScale.value; scaleY = bounceScale.value }
        TextAnimationType.SPRING -> baseModifier.graphicsLayer { scaleX = springScale.value; scaleY = springScale.value }
        TextAnimationType.SLIDING -> baseModifier.graphicsLayer { translationX = slideOffset.value * 300f }
        TextAnimationType.SLIDE_LEFT -> baseModifier.graphicsLayer { translationX = slideOffset.value * 300f }
        TextAnimationType.SLIDE_RIGHT -> baseModifier.graphicsLayer { translationX = -slideOffset.value * 300f }
        TextAnimationType.SLIDE_UP -> baseModifier.graphicsLayer { translationY = slideOffset.value * 100f }
        TextAnimationType.SLIDE_DOWN -> baseModifier.graphicsLayer { translationY = -slideOffset.value * 100f }
        TextAnimationType.ZOOM_BLUR -> baseModifier
            .alpha(fadeInAlpha.value)
            .graphicsLayer { scaleX = 1f + zoomBlurAmount.value / 20f; scaleY = 1f + zoomBlurAmount.value / 20f }
            .blur(if (zoomBlurAmount.value > 1f) zoomBlurAmount.value.dp else 0.dp)
        TextAnimationType.FLIP_3D -> baseModifier
            .alpha(fadeInAlpha.value)
            .graphicsLayer { rotationY = flipRotation.value }
        TextAnimationType.CHARACTER_WAVE -> baseModifier.graphicsLayer {
            val wave = kotlin.math.sin((charWaveIndex.intValue) * 0.5f) * 10f
            translationY = wave
        }
        else -> baseModifier
    }

    // Render text with optional cursor
    if (entry.textAnimation == TextAnimationType.TYPEWRITER_CURSOR && showCursor.value) {
        Row(
            modifier = animModifier,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = displayText,
                style = finalStyle,
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            val cursorAlpha by animateFloatAsState(
                targetValue = if (typedText.value.length < entry.slogan.length) 1f else 0f,
                animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
                label = "cursor"
            )
            Text(
                text = "|",
                style = finalStyle.copy(color = textColour.copy(alpha = cursorAlpha)),
                maxLines = 1,
            )
        }
    } else {
        Text(
            text = displayText,
            style = finalStyle,
            maxLines = 3,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = animModifier,
        )
    }
}