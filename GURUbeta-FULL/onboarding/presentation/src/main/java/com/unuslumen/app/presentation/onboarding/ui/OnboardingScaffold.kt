// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.onboarding.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle


import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.unuslumen.app.onboarding.domain.OnboardingChapter
import com.unuslumen.app.ui.theme.Fraunces
import com.unuslumen.app.ui.theme.Inter
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * THE ONBOARDING VISUAL LANGUAGE — white ground, colour as material.
 *
 * WHAT CHANGED AND WHY.
 *
 * The previous build was warm paper with the chapter colour used as a thin trim:
 * a node, a rail segment, an underline. Every screen read as white with one dot
 * of colour on it, which is why it looked like a form rather than like a product
 * announcing itself. Colour that thin is decoration, and decoration is what makes
 * a screen feel colourless.
 *
 * Colour here is material. It fills a tile the moment you pick it. It fills the
 * primary button. It fills the slider thumb. It paints the whole mark beat. It
 * accumulates into a picture as you answer. Nothing on any page is colourless
 * except the prose, which should be.
 *
 * THE RULES
 *  - Ground is pure white, every page, every chapter.
 *  - Ink is near-black. Two greys below it, nothing else neutral.
 *  - Each chapter owns one accent. That accent is used at full strength, never
 *    as a 20% tint.
 *  - A label on an accent is white or ink, decided by measured contrast, so a
 *    white label on the amber never ships.
 *  - No black grounds, no dark mode, no neon, no glow. Ever.
 *
 * TYPE
 *  Display is Fraunces, the serif already bundled for the Portal. Body is Inter.
 *  One display serif and one sans is the whole type system. The serif is what
 *  makes the page feel written rather than generated, and it is also why the
 *  questions read like someone asking rather than a form requesting.
 *
 * PROPORTIONS
 *  Three bands: header chrome, the content that scrolls, and a footer action.
 *  The constellation lives at the top of the content band rather than in its own
 *  strip, so the page reads as one column instead of a dashboard. It shrinks to a
 *  short strip when the keyboard opens and never disappears.
 */
object OnboardingSkin {

    /** White. The only ground this flow has. */
    private val White = Color(0xFFFFFFFF)

    /** The chapter's accent, at full strength. */
    fun colourOf(chapter: OnboardingChapter): Color =
        Color(android.graphics.Color.parseColor(chapter.colour))

    /** The page ground. Always white; kept as a function so callers read intent. */
    fun groundOf(chapter: OnboardingChapter): Color =
        Color(android.graphics.Color.parseColor(chapter.ground))

    /** The ink for a chapter's field. */
    fun inkOf(chapter: OnboardingChapter): Color =
        Color(android.graphics.Color.parseColor(chapter.ink))

    /** Ink at reduced presence for secondary copy. */
    fun inkSoftOf(chapter: OnboardingChapter): Color = inkOf(chapter).copy(alpha = 0.72f)

    /** Ink at a whisper, for the smallest labels. */
    fun inkFaintOf(chapter: OnboardingChapter): Color = inkOf(chapter).copy(alpha = 0.48f)

    /** The hairline that separates rows. Visible, never heavy. */
    fun hairlineOf(chapter: OnboardingChapter): Color = inkOf(chapter).copy(alpha = 0.10f)

    /**
     * A wash of the accent, for a resting tile or a soft panel. Strong enough to
     * read as that chapter's colour at a glance rather than as grey that happens
     * to be nearby.
     */
    fun washOf(chapter: OnboardingChapter): Color = colourOf(chapter).copy(alpha = 0.11f)

    /** A firmer wash, for a focused or selected-but-not-filled surface. */
    fun washStrongOf(chapter: OnboardingChapter): Color = colourOf(chapter).copy(alpha = 0.18f)

    /**
     * The readable label colour on top of [background], by measured luminance.
     *
     * A white label on the amber accent fails 4.5:1 and a dark label on the
     * violet fails too, so this is decided here once rather than guessed at each
     * call site. The 0.179 threshold is the standard sRGB crossover.
     */
    fun onColour(background: Color): Color {
        val linear = { channel: Float ->
            if (channel <= 0.04045f) channel / 12.92f
            else Math.pow(((channel + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        val luminance = 0.2126f * linear(background.red) +
            0.7152f * linear(background.green) +
            0.0722f * linear(background.blue)
        return if (luminance > 0.179f) Color(0xFF17181C) else White
    }

    /** The error colour. Warm red, distinct from every chapter accent. */
    val Error = Color(0xFFB3261E)

    /** The display serif. Fraunces, already bundled in core:ui. */
    val Display: FontFamily = Fraunces

    /** The working sans. Inter, already bundled in core:ui. */
    val Body: FontFamily = Inter
}

/**
 * The full-bleed field every onboarding screen sits on.
 *
 * White, with an imperceptible vertical fall so a tall empty page still has a
 * bottom to it. The fall is 2%, which is invisible as a gradient and stops the
 * page reading as a dead sheet.
 */
@Composable
fun OnboardingField(
    chapter: OnboardingChapter,
    content: @Composable () -> Unit,
) {
    val paper = OnboardingSkin.groundOf(chapter)
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        paper,
                        paper.copy(
                            red = (paper.red * 0.98f).coerceIn(0f, 1f),
                            green = (paper.green * 0.98f).coerceIn(0f, 1f),
                            blue = (paper.blue * 0.98f).coerceIn(0f, 1f)
                        )
                    )
                )
            )
    ) {
        content()
    }
}

/**
 * The page frame: header chrome, the picture, the content, and the tap.
 *
 * THERE ARE NO BUTTONS ANYWHERE IN THIS FLOW.
 *
 * A button is a thing you press, and pressing is what you do to a form. These
 * pages are read and then left, and the gesture for leaving a page you have read
 * is a tap on its trailing side. So the whole screen is the control:
 *
 *  - Tap the leading half: go back.
 *  - Tap the trailing half: go on. On a question that means submit, which also
 *    runs the validation, so a tap on the right of an unanswered required
 *    question shows the reason instead of moving.
 *
 * The answer controls sit on top of that and swallow their own taps, so picking a
 * tile selects it and never advances the page. That falls out of Compose's normal
 * dispatch: a clickable child consumes the down event, and this handler only sees
 * gestures nothing inside claimed. Nothing extra is needed to protect a tile.
 *
 * The hint line at the bottom is the only remaining furniture, and it is a label,
 * not a control. It exists so the halves are discoverable, and it carries the
 * validation message when a required answer is missing.
 *
 * The picture sits inside the scrolling content band rather than in a fixed strip
 * of its own, so the whole page moves as one column. When the keyboard is open it
 * shrinks to a short strip rather than vanishing, because it is the thing an
 * answer lands in and taking it away removes the reward for answering.
 */
@Composable
fun OnboardingFrame(
    chapter: OnboardingChapter,
    onAdvance: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    hint: String = "Tap to continue",
    hintIsError: Boolean = false,
    header: @Composable ColumnScope.() -> Unit = {},
    mapHeight: Dp = 148.dp,
    map: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val keyboardOpen = imeBottom > 0

    val liveMapHeight by animateDpAsState(
        targetValue = if (keyboardOpen) mapHeight * 0.30f else mapHeight,
        animationSpec = tween(durationMillis = 220),
        label = "onboarding-map-height"
    )

    OnboardingField(chapter = chapter) {
        Column(
            modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .pointerInput(onBack, onAdvance, keyboardOpen) {
                    detectTapGestures { offset ->
                        if (offset.x < size.width / 2f) {
                            onBack?.invoke()
                        } else {
                            onAdvance()
                        }
                    }
                }
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 12.dp),
                content = header
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
            ) {
                if (mapHeight > 0.dp) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(liveMapHeight)
                    ) {
                        map()
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.Top,
                    content = content
                )
                Spacer(Modifier.height(16.dp))
            }
            Text(
                text = hint.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = if (hintIsError) {
                        OnboardingSkin.Error
                    } else {
                        OnboardingSkin.inkOf(chapter).copy(alpha = 0.42f)
                    },
                    fontFamily = OnboardingSkin.Body,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.18.em
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 6.dp, bottom = 18.dp)
            )
        }
    }
}

/**
 * The picture: one dot per answer, growing outward as you go.
 *
 * Laid out on a phyllotaxis spiral so it stays even at any count. Lines join each
 * node to its near neighbours so it reads as a graph rather than a scatter. The
 * newest node is larger and painted in the current chapter's accent at full
 * strength; older nodes keep the colour they were born with. That is the whole
 * point of the picture: the page shows you what you have given it, in the colours
 * you gave it in.
 *
 * An answer springs out of the point it was tapped, which is the signature
 * interaction. [launch] arrives in window coordinates and is converted here.
 */
@Composable
fun OnboardingConstellation(
    chapter: OnboardingChapter,
    nodeColours: List<Color>,
    modifier: Modifier = Modifier,
    launch: Offset? = null,
) {
    val ink = OnboardingSkin.inkOf(chapter)
    val total = nodeColours.size.coerceAtLeast(1)
    val radius = when {
        total < 22 -> 8f
        total < 45 -> 6.5f
        total < 80 -> 5f
        else -> 4f
    }

    val launches = remember(nodeColours.size) {
        List(nodeColours.size) { Animatable(1f) }
    }
    var originInWindow by remember { mutableStateOf(Offset.Zero) }
    val localLaunch = remember(launch, originInWindow) {
        launch?.let { Offset(it.x - originInWindow.x, it.y - originInWindow.y) }
    }
    LaunchedEffect(nodeColours.size, launch) {
        if (launch != null && launches.isNotEmpty()) {
            val last = launches.last()
            last.snapTo(0f)
            last.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
            )
        }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { originInWindow = it.boundsInWindow().topLeft }
    ) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.52f
        val maxRadiusX = size.width * 0.42f
        val maxRadiusY = size.height * 0.40f
        val minRadius = 13f

        val points = nodeColours.mapIndexed { index, _ ->
            val angle = index * 2.399963f
            val t = if (total == 1) 0f else index.toFloat() / (total - 1).toFloat()
            val r = minRadius + sqrt(t) * (size.height * 0.38f - minRadius)
            Offset(
                x = cx + cos(angle) * (r * 1.6f).coerceAtMost(maxRadiusX),
                y = cy + sin(angle) * r.coerceAtMost(maxRadiusY)
            )
        }

        points.forEachIndexed { index, point ->
            listOf(index - 1, index - 3, index - 7).forEach { other ->
                if (other < 0) return@forEach
                drawLine(
                    color = ink.copy(alpha = 0.14f),
                    start = points[other],
                    end = point,
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        points.forEachIndexed { index, target ->
            val progress = launches.getOrNull(index)?.value ?: 1f
            val origin = localLaunch ?: target
            val point = Offset(
                x = origin.x + (target.x - origin.x) * progress,
                y = origin.y + (target.y - origin.y) * progress
            )
            val isNewest = index == points.lastIndex
            val r = (if (isNewest) radius * 1.55f else radius) * progress

            if (isNewest) {
                // A soft ring behind the newest node, so the eye finds it without
                // needing a glow. Flat alpha, no blur, no bloom.
                drawCircle(
                    color = nodeColours[index].copy(alpha = 0.18f),
                    radius = r * 2.6f,
                    center = point
                )
            }
            drawCircle(
                color = nodeColours[index],
                radius = r,
                center = point
            )
        }

        if (points.isEmpty()) {
            drawCircle(
                color = ink.copy(alpha = 0.10f),
                radius = size.height * 0.16f,
                center = Offset(cx, cy),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
            )
            drawCircle(
                color = OnboardingSkin.colourOf(chapter),
                radius = size.height * 0.05f,
                center = Offset(cx, cy)
            )
        }
    }
}

/** True when Android's "Remove animations" setting is on. */
@Composable
fun rememberReducedMotion(): Boolean {
    var animatorsEnabled by remember {
        mutableStateOf(runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                animatorsEnabled = runCatching { ValueAnimator.areAnimatorsEnabled() }
                    .getOrDefault(true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return !animatorsEnabled
}

/**
 * The progress line: completed chapters in their own colours, the rest in hairline
 * grey. Not a neutral bar, because a bar tells you how far through a form you are
 * and this one tells you what you have built so far.
 */
@Composable
fun OnboardingRail(
    chapters: List<OnboardingChapter>,
    currentChapter: Int,
    modifier: Modifier = Modifier,
    colouredChapters: Set<Int> = emptySet(),
) {
    val ink = OnboardingSkin.inkOf(chapters.getOrElse(currentChapter) { chapters.first() })
    Row(
        modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Chapter ${currentChapter + 1} of ${chapters.size}"
            },
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        chapters.forEachIndexed { index, chapter ->
            val done = index in colouredChapters
            val active = index == currentChapter
            Box(
                Modifier
                    .weight(1f)
                    .height(if (active) 4.dp else 3.dp)
                    .clip(RoundedCornerShape(99))
                    .background(
                        when {
                            active -> OnboardingSkin.colourOf(chapter)
                            done -> OnboardingSkin.colourOf(chapter).copy(alpha = 0.45f)
                            else -> ink.copy(alpha = 0.12f)
                        }
                    )
            )
        }
    }
}

/** The chapter name on the left, the running count on the right. */
@Composable
fun OnboardingChapterLabel(
    chapter: OnboardingChapter,
    chapterIndex: Int,
    chapterCount: Int,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val accent = OnboardingSkin.colourOf(chapter)
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = chapter.name,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = OnboardingSkin.inkOf(chapter),
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = OnboardingSkin.Body,
                    letterSpacing = 0.01.em
                )
            )
        }
        Text(
            text = tag.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                color = OnboardingSkin.inkFaintOf(chapter),
                fontWeight = FontWeight.Medium,
                fontFamily = OnboardingSkin.Body,
                letterSpacing = 0.11.em
            )
        )
    }
}

/** The GURU logo and the live node count. */
@Composable
fun OnboardingTopBar(
    chapter: OnboardingChapter,
    nodeCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = com.unuslumen.app.ui.R.drawable.guru_logo),
            contentDescription = "GURU",
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(18.dp)
        )
        if (nodeCount > 0) {
            Text(
                text = if (nodeCount == 1) "1 answer" else "$nodeCount answers",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = OnboardingSkin.inkFaintOf(chapter),
                    fontWeight = FontWeight.Medium,
                    fontFamily = OnboardingSkin.Body,
                    letterSpacing = 0.06.em
                )
            )
        }
    }
}

/**
 * The question headline, in the display serif.
 *
 * This is the single biggest change from the previous build. A sans headline at
 * 28sp bold reads as a form label. The same words in a 34sp serif read as a
 * person asking. Same content, entirely different relationship.
 */
@Composable
fun OnboardingHeadline(
    text: String,
    chapter: OnboardingChapter,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium.copy(
            color = OnboardingSkin.inkOf(chapter),
            fontWeight = FontWeight.Normal,
            fontFamily = OnboardingSkin.Display,
            fontSize = MaterialTheme.typography.headlineMedium.fontSize * 1.22f,
            lineHeight = 1.10.em,
            letterSpacing = (-0.015).em
        ),
        modifier = modifier.semantics { heading() }
    )
}

/** The supporting line under a question. */
@Composable
fun OnboardingSub(
    text: String,
    chapter: OnboardingChapter,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(
            color = OnboardingSkin.inkSoftOf(chapter),
            fontWeight = FontWeight.Normal,
            fontFamily = OnboardingSkin.Body,
            fontSize = MaterialTheme.typography.bodyMedium.fontSize * 1.07f,
            lineHeight = 1.55.em
        ),
        modifier = modifier
    )
}

/** Body copy on an intro page. */
@Composable
fun OnboardingBody(
    text: String,
    chapter: OnboardingChapter,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(
            color = OnboardingSkin.inkSoftOf(chapter),
            fontWeight = FontWeight.Normal,
            fontFamily = OnboardingSkin.Body,
            lineHeight = 1.55.em
        ),
        textAlign = textAlign,
        modifier = modifier
    )
}

/** A kicker above an intro headline, with the chapter's dot. */
@Composable
fun OnboardingKicker(
    text: String,
    chapter: OnboardingChapter,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(OnboardingSkin.colourOf(chapter))
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                color = OnboardingSkin.inkOf(chapter),
                fontWeight = FontWeight.Bold,
                fontFamily = OnboardingSkin.Body,
                letterSpacing = 0.14.em
            )
        )
    }
}

/** A numbered eyebrow: "Who you are · 1 of 5". */
@Composable
fun OnboardingEyebrow(
    chapter: OnboardingChapter,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(OnboardingSkin.colourOf(chapter))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${chapter.name.uppercase()} · ${index + 1} OF $count",
            style = MaterialTheme.typography.labelSmall.copy(
                color = OnboardingSkin.inkFaintOf(chapter),
                fontWeight = FontWeight.SemiBold,
                fontFamily = OnboardingSkin.Body,
                letterSpacing = 0.10.em
            )
        )
    }
}

/** The closing line of a page. */
@Composable
fun OnboardingFootnote(
    text: String,
    chapter: OnboardingChapter,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            color = OnboardingSkin.inkSoftOf(chapter),
            fontWeight = FontWeight.Normal,
            fontFamily = OnboardingSkin.Body,
            letterSpacing = 0.01.em,
            lineHeight = 1.5.em
        ),
        modifier = modifier
    )
}

/**
 * THERE ARE NO BUTTON COMPOSABLES IN THIS FLOW.
 *
 * A previous build shipped a filled pill on every page, with a second line under
 * it for Back. That is a form, and a form is the thing this onboarding is not.
 * Every page is now advanced by tapping the trailing half of the screen and
 * reversed by tapping the leading half, handled once inside [OnboardingFrame].
 *
 * The composables are deleted rather than left unused, because an unused button
 * helper is an invitation for the next pass to put buttons back.
 *
 * For the record, if a control is ever genuinely needed on one page, it belongs
 * there as a real, named, focusable control with a visible focus ring, not as a
 * re-introduction of a global action bar at the bottom of every screen.
 */

/** A chevron that mirrors under RTL. */
@Composable
fun OnboardingBackChevron(chapter: OnboardingChapter, onBack: () -> Unit) {
    val direction = LocalLayoutDirection.current
    val ink = OnboardingSkin.inkOf(chapter)
    IconButton(
        onClick = onBack,
        modifier = Modifier
            .size(44.dp)
            .semantics { contentDescription = "Go back" }
    ) {
        Canvas(Modifier.size(18.dp)) {
            val stroke = 2.dp.toPx()
            val apexX = if (direction == LayoutDirection.Rtl) size.width * 0.68f else size.width * 0.32f
            val tailX = if (direction == LayoutDirection.Rtl) size.width * 0.32f else size.width * 0.68f
            drawLine(
                color = ink,
                start = Offset(tailX, size.height * 0.2f),
                end = Offset(apexX, size.height * 0.5f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = ink,
                start = Offset(apexX, size.height * 0.5f),
                end = Offset(tailX, size.height * 0.8f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

/** A progress line filled with the accent, announced as a progress bar. */
@Composable
fun OnboardingProgress(
    chapter: OnboardingChapter,
    fraction: Float,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    val accent = OnboardingSkin.colourOf(chapter)
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(99))
            .background(OnboardingSkin.inkOf(chapter).copy(alpha = 0.10f))
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f, 0)
                if (label != null) contentDescription = label
            }
    ) {
        Box(
            Modifier
                .fillMaxWidth(clamped)
                .height(4.dp)
                .clip(RoundedCornerShape(99))
                .background(accent)
        )
    }
}

/** A tap target that reports where on screen it was pressed, for the launch. */
@Composable
fun rememberTapOrigin(onTap: (Offset) -> Unit): Modifier =
    Modifier.pointerInput(Unit) {
        detectTapGestures(onTap = onTap)
    }

/** Spacer helper so pages share one rhythm. */
@Composable
fun OnboardingGap(height: Int = 16) {
    Spacer(Modifier.height(height.dp))
}

/** A stat for the birth screen. */
@Composable
fun OnboardingStat(
    value: String,
    label: String,
    chapter: OnboardingChapter,
    modifier: Modifier = Modifier,
    accent: Color = OnboardingSkin.colourOf(chapter),
) {
    Column(modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.displaySmall.copy(
                color = accent,
                fontWeight = FontWeight.Normal,
                fontFamily = OnboardingSkin.Display,
                letterSpacing = (-0.02).em
            )
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                color = OnboardingSkin.inkFaintOf(chapter),
                fontWeight = FontWeight.Medium,
                fontFamily = OnboardingSkin.Body,
                letterSpacing = 0.10.em
            )
        )
    }
}

/** Convenience: the accent colour for a chapter index, safe out of range. */
fun chapterColour(chapters: List<OnboardingChapter>, index: Int): Color {
    val chapter = chapters.getOrElse(index) { chapters.first() }
    return OnboardingSkin.colourOf(chapter)
}

/** Convenience: the chapter object for an index, safe out of range. */
fun chapterAt(chapters: List<OnboardingChapter>, index: Int): OnboardingChapter =
    chapters.getOrElse(index) { chapters.first() }

/** A circle shape, used by the small dot marks. */
val OnboardingCircle = CircleShape

/** The standard corner radius for a filled tile. Large enough to read as an object. */
val OnboardingTileShape = RoundedCornerShape(24.dp)

/** The corner radius for a panel or a card. */
val OnboardingPanelShape = RoundedCornerShape(26.dp)
