// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.onboarding.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.unuslumen.app.onboarding.domain.BeatEmphasis
import com.unuslumen.app.onboarding.domain.OnboardingBeat
import com.unuslumen.app.onboarding.domain.OnboardingBeatLine
import kotlinx.coroutines.delay

/**
 * OnboardingBeatPage — one page of the welcome pack, typed on as you read it.
 *
 * The whole screen is the field. No button, no chrome: the words are the page and
 * a tap anywhere moves on. Each line types in character by character, in order,
 * so the reading paces itself.
 *
 * THREE KINDS OF PAGE, ONE COMPONENT
 *
 *  - A white page. "Introducing GURU" sits on white, because a hero line behind a
 *    colour field fights for air and the colour should arrive as an event.
 *  - The mark. One full-bleed colour page, the icon at its centre growing until it
 *    fills the frame. This is the moment the product announces itself, and the
 *    only screen in the flow that is a wall of colour on purpose.
 *  - The founder's note. Its own page, drawn to his card: warm cream falling to
 *    peach, the mark on a dark plate at the top right, the heading in a heavy
 *    grotesque, his paragraphs beneath it, and the wordmark ghosted behind them.
 *    This is the page that records agreement, so it is built as a card rather
 *    than as prose floating on a field.
 *
 * The colours all come from the catalogue: [OnboardingBeat.field] and
 * [OnboardingBeat.ink]. Nothing is hardcoded here, so the intro can be recoloured
 * without touching this file.
 *
 * Two behaviours that matter:
 *  - A tap while a line is still typing completes the page at once. A tap after
 *    that advances. The reader is never forced to wait for the animation.
 *  - The leading half of the screen steps back and the trailing half advances, so
 *    a page is never a one-way door and no back chrome is needed.
 *
 * Layout never moves as characters arrive: each line reserves its full text box
 * invisibly and the typed text draws over it. Without that, a paragraph would
 * reflow on every keystroke.
 */
@Composable
fun OnboardingBeatPage(
    beat: OnboardingBeat,
    reducedMotion: Boolean,
    isLast: Boolean,
    canGoBack: Boolean,
    onAdvance: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val field = remember(beat.field) { Color(android.graphics.Color.parseColor(beat.field)) }
    val ink = remember(beat.ink) { Color(android.graphics.Color.parseColor(beat.ink)) }
    val soft = ink.copy(alpha = 0.72f)

    // A beat is either a colour page or a white page. The distinction matters for
    // the hint: on a colour field it has to lift to white, on white it stays ink.
    val isColourPage = remember(field) { OnboardingSkin.onColour(field) == Color.White }
    val hintInk = if (isColourPage) Color.White else ink

    // Characters currently visible per line, and whether the page has finished.
    val typed = remember(beat.id) {
        mutableStateListOf<Int>().apply { repeat(beat.lines.size) { add(0) } }
    }
    var finished by remember(beat.id) { mutableStateOf(reducedMotion) }
    var skipped by remember(beat.id) { mutableStateOf(false) }

    LaunchedEffect(beat.id, reducedMotion, skipped) {
        if (reducedMotion || skipped) {
            beat.lines.forEachIndexed { index, line -> typed[index] = line.text.length }
            finished = true
            return@LaunchedEffect
        }
        finished = false
        for (index in beat.lines.indices) {
            val text = beat.lines[index].text
            for (count in 1..text.length) {
                typed[index] = count
                delay(typeMillisFor(beat.lines[index].emphasis))
            }
            if (index != beat.lines.lastIndex) delay(LINE_GAP_MILLIS)
        }
        finished = true
    }

    // The note carries the deal, so it is its own card rather than prose on a
    // field. Any beat marked as accepting the deal gets that treatment.
    val isNotePage = beat.acceptsDeal && !beat.heroLogo

    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        field,
                        field.copy(
                            red = (field.red * 0.98f).coerceIn(0f, 1f),
                            green = (field.green * 0.98f).coerceIn(0f, 1f),
                            blue = (field.blue * 0.98f).coerceIn(0f, 1f)
                        )
                    )
                )
            )
            .pointerInput(beat.id, finished, canGoBack) {
                // The screen itself is the control. A tap while a line is still
                // typing completes the page. Once the page has finished, the
                // leading half steps back and the trailing half advances. RTL is
                // honoured because size.width is the measured width, not a
                // physical assumption.
                detectTapGestures { offset ->
                    when {
                        !finished -> skipped = true
                        offset.x < size.width / 2f && canGoBack -> onBack()
                        offset.x < size.width / 2f -> Unit
                        else -> onAdvance()
                    }
                }
            }
    ) {
        if (isNotePage) {
            FounderNoteCard(
                beat = beat,
                typed = typed
            )
            return@Box
        }

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 28.dp)
                .padding(top = 90.dp, bottom = 108.dp),
            verticalArrangement = Arrangement.Center
        ) {
            when {
                beat.heroLogo -> BeatHeroMark(
                    reducedMotion = reducedMotion,
                    onDone = onAdvance
                )

                else -> beat.lines.forEachIndexed { index, line ->
                    BeatLine(
                        line = line,
                        visibleChars = typed.getOrElse(index) { 0 },
                        ink = ink,
                        soft = soft
                    )
                    if (index != beat.lines.lastIndex) {
                        Spacer(Modifier.height(gapAfter(line.emphasis)))
                    }
                }
            }
        }

        // The note card carries no hint line: it is a page you read to the end,
        // and the card itself is the whole screen.
        if (!isNotePage) {
            Text(
                text = if (isLast) "TAP TO BEGIN" else "TAP TO CONTINUE",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = hintInk.copy(alpha = 0.42f),
                    fontFamily = OnboardingSkin.Body,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.18.em
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 52.dp)
            )
        }
    }
}

/**
 * THE FOUNDER'S NOTE CARD — the page that carries the deal.
 *
 * This is not a beat laid out like the others. It is a card: a warm cream ground
 * that falls to peach at the foot, the mark on a near-black plate at the top
 * right, a masthead, then his paragraphs in a small working sans with a blank
 * line of air between each one. The wordmark is ghosted low behind the middle of
 * the copy, so the surface has a watermark rather than a decoration.
 *
 * WHY THE HEADING IS A GROTESQUE, NOT THE SERIF
 *
 * The rest of the welcome pack is a written note in the serif. This page is the
 * one place that announces something, so the heading takes the heaviest
 * grotesque already bundled rather than borrowing the prose face and pretending
 * a display line is a paragraph.
 *
 * The paragraphs still type on, in order, at reading pace, and the full
 * sentence holds the layout under the typed prefix so nothing reflows.
 */
@Composable
private fun FounderNoteCard(
    beat: OnboardingBeat,
    typed: List<Int>,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                // The card's wash, read straight off his reference: pale cream at
                // the top, a warm rose passing through the middle where the
                // watermark sits, then peach closing the foot. The stops are
                // explicit rather than an even blend, because an even blend puts
                // the rose too high and the peach too low.
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to NoteCreamTop,
                        0.28f to NoteCream,
                        0.50f to NoteRose,
                        0.76f to NoteWarm,
                        1.00f to NotePeach
                    )
                )
            )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(top = 22.dp, bottom = 34.dp)
        ) {
            // The mark, alone at the trailing edge. No heading above it, no rule
            // under it: it is the chip at the top of the card and nothing else.
            //
            // The icon already IS the dark plate with the white face cut into it,
            // so nothing is drawn behind it and nothing is tinted. Wrapping it in
            // a plate of its own would double the square, and tinting it white
            // would flood the face out of the middle of its own mark.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Image(
                    painter = painterResource(id = com.unuslumen.app.ui.R.drawable.guru_icon),
                    contentDescription = "GURU",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(74.dp)
                )
            }

            Spacer(Modifier.height(18.dp))

            Box(Modifier.fillMaxWidth()) {
                // The ghosted wordmark sits behind the copy, dropped below centre
                // so it falls across the middle paragraphs as it does on the card.
                Image(
                    painter = painterResource(id = com.unuslumen.app.ui.R.drawable.guru_logo),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = 300.dp)
                        .fillMaxWidth(0.92f)
                        .alpha(0.06f)
                )

                Column(Modifier.fillMaxWidth()) {
                    beat.lines.forEachIndexed { index, line ->
                        NoteLine(
                            line = line,
                            visibleChars = typed.getOrElse(index) { 0 }
                        )
                        if (index != beat.lines.lastIndex) {
                            Spacer(Modifier.height(noteGapAfter(line.emphasis)))
                        }
                    }
                }
            }
        }
    }
}

/**
 * One line of the note: the full sentence holds the layout, the typed prefix
 * draws over it. Same mechanism as the rest of the pack, different type.
 */
@Composable
private fun NoteLine(
    line: OnboardingBeatLine,
    visibleChars: Int,
) {
    val style = when (line.emphasis) {
        BeatEmphasis.Hero -> noteHeadingStyle()
        BeatEmphasis.Soft -> noteSignOffStyle()
        else -> noteBodyStyle()
    }
    val shown = line.text.take(visibleChars.coerceIn(0, line.text.length))

    Box(Modifier.fillMaxWidth()) {
        Text(
            text = line.text,
            style = style.copy(color = Color.Transparent)
        )
        Text(
            text = shown,
            style = style
        )
    }
}

/** The masthead: heavy grotesque filling the measure, tight tracking. */
private fun noteHeadingStyle(): TextStyle = TextStyle(
    color = NoteInk,
    fontFamily = NoteDisplay,
    fontSize = 41.sp,
    lineHeight = 47.sp,
    fontWeight = FontWeight.Normal,
    letterSpacing = (-0.03).em
)

/** His paragraphs: the working sans at reading size, air between the lines. */
private fun noteBodyStyle(): TextStyle = TextStyle(
    color = NoteInk,
    fontFamily = OnboardingSkin.Body,
    fontSize = 15.5.sp,
    lineHeight = 25.sp,
    fontWeight = FontWeight.Normal
)

/** The sign-off, quieter than the paragraphs it follows. */
private fun noteSignOffStyle(): TextStyle = TextStyle(
    color = NoteInk.copy(alpha = 0.55f),
    fontFamily = OnboardingSkin.Body,
    fontSize = 14.sp,
    lineHeight = 21.sp,
    fontWeight = FontWeight.Normal
)

/** Space after a note line: tight under the masthead, a blank line between paras. */
private fun noteGapAfter(emphasis: BeatEmphasis): Dp = when (emphasis) {
    BeatEmphasis.Hero -> 10.dp
    BeatEmphasis.Soft -> 0.dp
    BeatEmphasis.Normal, BeatEmphasis.HeroSub -> 26.dp
}

/**
 * Archivo Black, the heaviest grotesque already bundled in core:ui.
 *
 * Held here rather than in the shared skin because it belongs to this one page.
 */
private val NoteDisplay = FontFamily(
    Font(com.unuslumen.app.ui.R.font.archivo_black)
)

/** The ink of the card: a soft charcoal, warmer than the flow's near-black. */
private val NoteInk = Color(0xFF2B2B31)

/** The card's top: a warm off-white cream. */
private val NoteCreamTop = Color(0xFFFDF8EC)

/** The body cream, holding the top third. */
private val NoteCream = Color(0xFFFCF6EA)

/** The warm rose that passes through the middle of the card. */
private val NoteRose = Color(0xFFFAF0EE)

/** Where the warmth turns to peach, low on the card. */
private val NoteWarm = Color(0xFFFAECD9)

/** The peach that closes the card at the foot. */
private val NotePeach = Color(0xFFF7E6C8)

/**
 * One line: the full text holds the layout while the typed prefix draws on top,
 * so nothing reflows as characters arrive.
 */
@Composable
private fun BeatLine(
    line: OnboardingBeatLine,
    visibleChars: Int,
    ink: Color,
    soft: Color,
) {
    val style = lineStyle(line.emphasis, ink, soft)
    val shown = line.text.take(visibleChars.coerceIn(0, line.text.length))

    Box(Modifier.fillMaxWidth()) {
        // Layout holder: invisible, always the full sentence.
        Text(
            text = line.text,
            style = style.copy(color = Color.Transparent)
        )
        // The text as far as it has been typed.
        Text(
            text = shown,
            style = style
        )
    }
}

/**
 * The full type style for an emphasis level.
 *
 * The hero and the prose are both the display serif, because the intro is a note
 * from a person and a sans hero would make it read as a product splash. The hero
 * is set tight and large; the prose keeps a generous line height so a paragraph
 * still reads as a paragraph at 22sp.
 *
 * Line heights are set in sp rather than as a multiple because Compose clips the
 * glyph box below about 1.08x on a large face, and a hero with a descender loses
 * its tail at 1.0.
 */
@Composable
private fun lineStyle(
    emphasis: BeatEmphasis,
    ink: Color,
    soft: Color,
): TextStyle {
    val size = when (emphasis) {
        BeatEmphasis.Hero -> 62.sp
        BeatEmphasis.HeroSub -> 19.sp
        BeatEmphasis.Normal, BeatEmphasis.Soft -> 22.sp
    }
    val height = when (emphasis) {
        BeatEmphasis.Hero -> 66.sp
        BeatEmphasis.HeroSub -> 27.sp
        BeatEmphasis.Normal, BeatEmphasis.Soft -> 34.sp
    }
    val weight = when (emphasis) {
        BeatEmphasis.Hero -> FontWeight.Normal
        BeatEmphasis.HeroSub -> FontWeight.Medium
        BeatEmphasis.Normal, BeatEmphasis.Soft -> FontWeight.Normal
    }
    val spacing = when (emphasis) {
        BeatEmphasis.Hero -> (-0.02).em
        BeatEmphasis.HeroSub -> 0.04.em
        BeatEmphasis.Normal, BeatEmphasis.Soft -> (-0.005).em
    }
    val colour = when (emphasis) {
        BeatEmphasis.Hero -> ink
        BeatEmphasis.HeroSub -> soft
        BeatEmphasis.Normal -> ink
        BeatEmphasis.Soft -> soft
    }
    val family = when (emphasis) {
        BeatEmphasis.Hero, BeatEmphasis.Normal, BeatEmphasis.Soft -> OnboardingSkin.Display
        BeatEmphasis.HeroSub -> OnboardingSkin.Body
    }
    return MaterialTheme.typography.bodyLarge.copy(
        color = colour,
        fontSize = size,
        lineHeight = height,
        fontWeight = weight,
        letterSpacing = spacing,
        fontFamily = family
    )
}

/** Space between lines, the paragraph rhythm of the intro. */
private fun gapAfter(emphasis: BeatEmphasis): Dp = when (emphasis) {
    BeatEmphasis.Hero -> 0.dp
    BeatEmphasis.HeroSub -> 10.dp
    BeatEmphasis.Normal, BeatEmphasis.Soft -> 22.dp
}

/** Milliseconds per character. The hero types slower because it is a headline. */
private fun typeMillisFor(emphasis: BeatEmphasis): Long = when (emphasis) {
    BeatEmphasis.Hero -> 78L
    BeatEmphasis.HeroSub -> 42L
    BeatEmphasis.Normal, BeatEmphasis.Soft -> 18L
}

/** Pause between finishing one line and starting the next. */
private const val LINE_GAP_MILLIS = 320L

/**
 * The GURU icon alone on the field, growing until it fills the screen and the
 * next page takes over.
 *
 * THE ICON SITS ON THE COLOUR. NOTHING SITS BEHIND IT.
 *
 * A previous pass wrapped it in a white circle so it would "read" against the
 * colour field. That was wrong twice over: it put a blob of white in the middle
 * of the only full-colour page in the flow, and it turned the mark into a badge
 * on a plate rather than the mark itself. The icon is transparent, so it
 * composites straight onto the field.
 *
 * The sequence is one movement, not an entrance: the icon arrives small at the
 * centre, then grows past the frame. When the growth completes the page advances
 * itself, so the icon is what carries the reader forward rather than a page they
 * have to tap past. Reduced motion advances immediately.
 */
@Composable
private fun BeatHeroMark(
    reducedMotion: Boolean,
    onDone: () -> Unit,
) {
    val grow = remember { Animatable(if (reducedMotion) 1f else 0f) }
    var started by remember { mutableStateOf(reducedMotion) }

    LaunchedEffect(reducedMotion) {
        if (reducedMotion) {
            onDone()
            return@LaunchedEffect
        }
        started = true
        grow.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = MARK_GROW_MILLIS,
                easing = FastOutSlowInEasing
            )
        )
        onDone()
    }

    val scale = 0.40f + (2.7f * grow.value)
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = com.unuslumen.app.ui.R.drawable.guru_icon),
            contentDescription = "GURU",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(120.dp)
                .scale(scale)
                .alpha(if (started) 1f else 0f)
        )
    }
}

/** How long the icon takes to grow past the frame, in whole milliseconds. */
private const val MARK_GROW_MILLIS = 1100
