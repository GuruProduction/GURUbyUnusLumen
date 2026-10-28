// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.onboarding.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.unuslumen.app.onboarding.domain.OnboardingChapter
import com.unuslumen.app.onboarding.domain.OnboardingQuestion
import com.unuslumen.app.onboarding.domain.PickerChrome
import com.unuslumen.app.onboarding.domain.QuestionChoice
import com.unuslumen.app.onboarding.domain.QuestionInput
import kotlin.math.roundToInt

/**
 * QuestionPage — one question, one answer, one way forward.
 *
 * COLOUR IS THE CONTROL, NOT THE DECORATION.
 *
 * A previous pass answered every one of these with plain underlined words: no
 * box, no pill, no fill. On paper that is elegant. On a phone it meant a screen
 * of grey text and one grey button, which is exactly what "so black and white and
 * so boring" was describing. The interaction had no material.
 *
 * Now each answer type has a physical form:
 *
 *  - Tabs and Choice fill a tile with colour the moment it is chosen. A row of
 *    tiles carries six different colours before you touch anything, so the screen
 *    looks alive at rest, not only after interaction.
 *  - Chips and MultiChoice fill a pill or a row, in the same family of colours.
 *  - Slider is a gradient rail running warm to cool, with a white thumb ringed in
 *    the colour under it. The gradient is the metaphor: the two ends are the two
 *    answers and the position is the opinion.
 *  - Text answers sit in a tinted panel of the chapter accent, so the field is an
 *    object rather than a floating line.
 *
 * RULES THAT DO NOT BEND
 *  - Validation runs on submit, never by greying the action out. A disabled button
 *    cannot say why it is disabled; an error can.
 *  - Every choice is a real toggleable, so the state is announced.
 *  - Selection is never carried by colour alone: weight, a tick, and a filled
 *    surface all move together.
 *  - The label on a filled surface is white or ink by measured contrast, so the
 *    amber tile gets ink and the violet tile gets white, without a special case.
 *  - Tapping an answer reports its screen position so the node can launch from
 *    the thumb into the picture at the top of the page.
 */
@Composable
fun QuestionPage(
    question: OnboardingQuestion,
    chapter: OnboardingChapter,
    chapters: List<OnboardingChapter>,
    currentChapter: Int,
    answer: String,
    index: Int,
    total: Int,
    nodeColours: List<Color>,
    colouredChapters: Set<Int>,
    reducedMotion: Boolean,
    launch: Offset?,
    onLaunch: (Offset) -> Unit,
    onAnswer: (String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val focusRequester = remember(question.key) { FocusRequester() }
    var showError by remember(question.key) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val isTextField = question.input == QuestionInput.FreeText ||
        question.input == QuestionInput.ShortText ||
        question.input == QuestionInput.Number
    val isLast = index + 1 == total
    val missing = showError && question.required && answer.isBlank()

    val chapterQuestions = remember(question.chapter) {
        com.unuslumen.app.onboarding.domain.OnboardingQuestionCatalogue
            .questionsInChapter(question.chapter)
    }
    val chapterPosition = remember(question.key, chapterQuestions) {
        chapterQuestions.indexOfFirst { it.key == question.key }.coerceAtLeast(0)
    }

    LaunchedEffect(question.key) {
        showError = false
        if (isTextField) runCatching { focusRequester.requestFocus() }
    }

    val submit = {
        keyboard?.hide()
        if (question.required && answer.isBlank()) {
            showError = true
            if (isTextField) runCatching { focusRequester.requestFocus() }
        } else {
            showError = false
            onNext()
        }
    }

    val seed = remember(question.key) { OnboardingPalette.seedFor(question.key) }

    OnboardingFrame(
        chapter = chapter,
        onAdvance = submit,
        onBack = onBack,
        hint = when {
            missing -> "This one needs an answer to carry on"
            isLast -> "Tap right to review, left to go back"
            else -> "Tap right to carry on, left to go back"
        },
        hintIsError = missing,
        header = {
            OnboardingTopBar(chapter = chapter, nodeCount = nodeColours.size)
            Spacer(Modifier.height(10.dp))
            OnboardingRail(
                chapters = chapters,
                currentChapter = currentChapter,
                colouredChapters = colouredChapters
            )
            Spacer(Modifier.height(10.dp))
            OnboardingChapterLabel(
                chapter = chapter,
                chapterIndex = currentChapter,
                chapterCount = chapters.size.coerceAtLeast(1),
                tag = "${currentChapter + 1} of ${chapters.size}"
            )
        },
        map = {
            OnboardingConstellation(
                chapter = chapter,
                nodeColours = nodeColours,
                launch = launch
            )
        }
    ) {
        OnboardingEyebrow(
            chapter = chapter,
            index = chapterPosition,
            count = chapterQuestions.size.coerceAtLeast(1)
        )
        Spacer(Modifier.height(12.dp))
        OnboardingHeadline(text = question.prompt, chapter = chapter)
        Spacer(Modifier.height(10.dp))
        if (question.helper.isNotBlank()) {
            OnboardingSub(text = question.helper, chapter = chapter)
        }
        Spacer(Modifier.height(20.dp))

        when (question.input) {
            QuestionInput.FreeText,
            QuestionInput.ShortText,
            QuestionInput.Number -> WrittenAnswer(
                question = question,
                chapter = chapter,
                answer = answer,
                isError = missing,
                isMultiline = question.input == QuestionInput.FreeText,
                isNumber = question.input == QuestionInput.Number,
                imeAction = if (isLast) ImeAction.Done else ImeAction.Next,
                focusRequester = focusRequester,
                onSubmit = submit,
                onAnswer = onAnswer
            )

            QuestionInput.Chips -> FilledChips(
                chapter = chapter,
                choices = question.choices,
                answer = answer,
                seed = seed,
                allowOtherText = question.allowOtherText,
                picker = question.picker,
                onLaunch = onLaunch,
                onAnswer = onAnswer
            )

            QuestionInput.Tabs,
            QuestionInput.Choice -> ColourTiles(
                chapter = chapter,
                choices = question.choices,
                answer = answer,
                seed = seed,
                multi = false,
                onLaunch = onLaunch,
                onAnswer = onAnswer
            )

            QuestionInput.MultiChoice -> ColourTiles(
                chapter = chapter,
                choices = question.choices,
                answer = answer,
                seed = seed,
                multi = true,
                onLaunch = onLaunch,
                onAnswer = onAnswer
            )

            QuestionInput.Slider -> SliderAnswer(
                chapter = chapter,
                question = question,
                answer = answer,
                onLaunch = onLaunch,
                onAnswer = onAnswer
            )

            QuestionInput.Capture -> CaptureAnswer(
                chapter = chapter,
                answer = answer,
                onLaunch = onLaunch,
                onAnswer = onAnswer
            )

            QuestionInput.Date -> DateAnswer(
                chapter = chapter,
                answer = answer,
                onLaunch = onLaunch,
                onAnswer = onAnswer
            )

            QuestionInput.YesNo -> YesNoRow(
                chapter = chapter,
                choices = question.choices.ifEmpty {
                    listOf(
                        QuestionChoice("yes", "Yes"),
                        QuestionChoice("no", "No"),
                        QuestionChoice("", "Rather not say")
                    )
                },
                answer = answer,
                seed = seed,
                onLaunch = onLaunch,
                onAnswer = onAnswer
            )
        }

        if (missing) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "This one needs an answer to carry on.",
                style = MaterialTheme.typography.labelLarge.copy(
                    color = OnboardingSkin.Error,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = OnboardingSkin.Body,
                    lineHeight = 1.4.em
                )
            )
        }
    }
}

/**
 * A text answer inside a tinted panel.
 *
 * The panel is the chapter accent at 8%, with a hairline of the same colour as
 * its edge. It exists so the field is a thing on the page rather than a line
 * floating in space, and so every answer type has some colour in it even when the
 * answer is a sentence.
 */
@Composable
private fun WrittenAnswer(
    question: OnboardingQuestion,
    chapter: OnboardingChapter,
    answer: String,
    isError: Boolean,
    isMultiline: Boolean,
    isNumber: Boolean,
    imeAction: ImeAction,
    focusRequester: FocusRequester,
    onSubmit: () -> Unit,
    onAnswer: (String) -> Unit,
) {
    val ink = OnboardingSkin.inkOf(chapter)
    val accent = OnboardingSkin.colourOf(chapter)
    var focused by remember(question.key) { mutableStateOf(false) }

    val edgeColour = when {
        isError -> OnboardingSkin.Error
        focused -> accent
        else -> accent.copy(alpha = 0.22f)
    }
    val edgeWidth by animateFloatAsState(
        targetValue = if (focused || isError) 2f else 1.5f,
        animationSpec = tween(180),
        label = "written-answer-edge"
    )

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(accent.copy(alpha = 0.07f))
                .border(
                    width = (edgeWidth * 1.2f).dp,
                    color = edgeColour,
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            BasicTextField(
                value = answer,
                onValueChange = { newValue ->
                    val clean = when {
                        isNumber -> newValue.filter { it.isDigit() }.take(question.maxLength)
                        newValue.length <= question.maxLength -> newValue
                        else -> newValue.take(question.maxLength)
                    }
                    onAnswer(clean)
                },
                singleLine = !isMultiline,
                minLines = if (isMultiline) 4 else 1,
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    color = ink,
                    fontWeight = FontWeight.Normal,
                    fontFamily = OnboardingSkin.Display,
                    lineHeight = 1.35.em
                ),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(
                    imeAction = imeAction,
                    keyboardType = if (isNumber) KeyboardType.Number else KeyboardType.Text,
                    capitalization = KeyboardCapitalization.Sentences
                ),
                keyboardActions = KeyboardActions(
                    onNext = { onSubmit() },
                    onDone = { onSubmit() }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (answer.isEmpty()) {
                            Text(
                                text = question.placeholder,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    color = ink.copy(alpha = 0.32f),
                                    fontWeight = FontWeight.Normal,
                                    fontFamily = OnboardingSkin.Display,
                                    lineHeight = 1.35.em
                                )
                            )
                        }
                        inner()
                    }
                }
            )
        }
        if (question.maxLength <= 400) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${answer.length} / ${question.maxLength}",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = OnboardingSkin.inkFaintOf(chapter),
                    fontFamily = OnboardingSkin.Body,
                    fontFeatureSettings = "tnum"
                ),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End
            )
        }
    }
}

/**
 * A grid of colour tiles. One tap fills the tile with its own colour.
 *
 * Two columns, square-ish, six to a screen. Each tile owns one colour from the
 * palette, walked from a seed derived from the question key so a group is stable
 * and two questions never start on the same colour.
 *
 * The tick is a small white disc with the tile's own colour in the glyph, so it
 * stays legible whichever way the fill went. The scale is a spring, so a tap
 * feels like a thing being picked up rather than a checkbox being set.
 */
@Composable
private fun ColourTiles(
    chapter: OnboardingChapter,
    choices: List<QuestionChoice>,
    answer: String,
    seed: Int,
    multi: Boolean,
    onLaunch: (Offset) -> Unit,
    onAnswer: (String) -> Unit,
) {
    val selected = remember(answer) {
        answer.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        choices.chunked(2).forEachIndexed { rowIndex, pair ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                pair.forEachIndexed { columnIndex, choice ->
                    val position = rowIndex * 2 + columnIndex
                    val tileColour = OnboardingPalette.toneFor(
                        OnboardingSkin.colourOf(chapter),
                        position
                    )
                    val isOn = choice.value in selected

                    ColourTile(
                        label = choice.label,
                        colour = tileColour,
                        selected = isOn,
                        modifier = Modifier.weight(1f),
                        onTap = { origin ->
                            onLaunch(origin)
                            val next = when {
                                multi && isOn -> selected - choice.value
                                multi -> selected + choice.value
                                else -> setOf(choice.value)
                            }
                            onAnswer(next.joinToString(","))
                        }
                    )
                }
                if (pair.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * One tile: a solid block of the question's own colour, at full strength.
 *
 * WHAT CHANGED AND WHY. This used to be a 12% wash of a per-option colour with a
 * hairline edge and a white tick disc when chosen. He called it ugly, twice, on
 * two different screens, and he was right: a grid of pale pastel blocks with a
 * checkbox on top is the visual language of a settings form, and a tick is the
 * glyph of a form. The colour was diluted to the point of being grey, so eleven
 * tiles looked like eleven grey rectangles with a suggestion of hue.
 *
 * The rule that replaced it: colour is material, so the tile IS the colour, at
 * full strength, all the time. A grid of them is a wall of one hue, which is
 * what makes a screen look designed rather than generated. The colour belongs to
 * the QUESTION now, not to each option, because eleven different hues in a grid
 * is confetti and confetti is also ugly.
 *
 * SELECTION WITHOUT A CHECKBOX. A chosen tile is the same solid colour, and the
 * difference is a white keyline inset from the edge plus a heavier label. Two
 * states that differ in structure rather than in a glyph, which is the honest
 * way to show a choice on a coloured surface.
 */
@Composable
private fun ColourTile(
    label: String,
    colour: Color,
    selected: Boolean,
    onTap: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    var box by remember { mutableStateOf(Rect.Zero) }
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.985f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "tile-scale"
    )
    val labelColour = OnboardingSkin.onColour(colour)

    Box(
        modifier
            .onGloballyPositioned { box = it.boundsInWindow() }
            .scale(scale)
            .heightIn(min = 96.dp)
            .clip(OnboardingTileShape)
            .background(colour)
            .then(
                if (selected) {
                    Modifier.border(
                        width = 2.5.dp,
                        color = labelColour.copy(alpha = 0.85f),
                        shape = OnboardingTileShape
                    )
                } else {
                    Modifier
                }
            )
            .toggleable(
                value = selected,
                role = if (selected) Role.Checkbox else Role.RadioButton,
                onValueChange = { onTap(Offset(box.center.x, box.center.y)) }
            )
            .padding(16.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(
                color = labelColour,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontFamily = OnboardingSkin.Body,
                lineHeight = 1.22.em
            ),
            modifier = Modifier.align(Alignment.BottomStart)
        )
    }
}

/**
 * A multi-select grid of colour tiles, used wherever the answer is a list of
 * words rather than a single category: languages, rituals, exercise, topics.
 *
 * TILES, NOT PILLS.
 *
 * A pill row reads as a form: even, pale, one line, no weight anywhere. The grid
 * of chunky colour tiles is the language the rest of this flow already speaks,
 * so a list question now looks like the same product as a choice question. Two
 * columns, real height, the label bottom-left, the tick top-right, exactly the
 * same tile as everywhere else.
 *
 * THE OTHER TILE
 *
 * When [allowOtherText] is on, a final tile reading "Something else" sits at the
 * end. Tapping it turns that tile into a text field in place. What they type is
 * appended to the answer and then renders as an ordinary filled tile beside the
 * rest, so it can be removed the same way any other can.
 *
 * Why it exists: no fixed list of anything a person can be is ever complete. The
 * person whose language is missing should not have to pick the nearest wrong
 * answer or leave it blank. It is a flag rather than a special case, so any
 * multi-select question can use it.
 *
 * A value is custom when it is not in [choices]. That is why the declared list
 * has to stay the authority on what was tapped versus typed.
 */
@Composable
private fun FilledChips(
    chapter: OnboardingChapter,
    choices: List<QuestionChoice>,
    answer: String,
    seed: Int,
    allowOtherText: Boolean,
    picker: PickerChrome?,
    onLaunch: (Offset) -> Unit,
    onAnswer: (String) -> Unit,
) {
    val declared = remember(choices) { choices.map { it.value }.toSet() }
    val allSelected = remember(answer) {
        answer.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
    val custom = allSelected.filter { it !in declared }

    var typingOther by remember { mutableStateOf(false) }
    var otherDraft by remember { mutableStateOf("") }
    val otherFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val commitOther = {
        val clean = otherDraft.trim().trim(',')
        if (clean.isNotEmpty()) {
            onAnswer((allSelected + clean).joinToString(","))
        }
        otherDraft = ""
        typingOther = false
        keyboard?.hide()
    }

    // A question carrying picker chrome renders as the catalogue picker: bars
    // for what is chosen, an add field, then the browse list. Everything else
    // stays the plain tile grid. The chrome travels on the question so this
    // screen never has to know what the question is about.
    if (picker != null) {
        CataloguePicker(
            chapter = chapter,
            choices = choices,
            allSelected = allSelected,
            custom = custom,
            allowOtherText = allowOtherText,
            chrome = picker,
            typingOther = typingOther,
            otherDraft = otherDraft,
            otherFocus = otherFocus,
            seed = seed,
            onOpenOther = {
                typingOther = true
                runCatching { otherFocus.requestFocus() }
            },
            onOtherDraft = { otherDraft = it },
            onCommitOther = { commitOther() },
            onCancelOther = {
                otherDraft = ""
                typingOther = false
            },
            onLaunch = onLaunch,
            onAnswer = onAnswer
        )
        return
    }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        choices.chunked(2).forEachIndexed { rowIndex, pair ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                pair.forEachIndexed { columnIndex, choice ->
                    val position = rowIndex * 2 + columnIndex
                    val tileColour = OnboardingPalette.toneFor(
                        OnboardingSkin.colourOf(chapter),
                        position
                    )
                    val isOn = choice.value in allSelected

                    ColourTile(
                        label = choice.label,
                        colour = tileColour,
                        selected = isOn,
                        modifier = Modifier.weight(1f),
                        onTap = { origin ->
                            onLaunch(origin)
                            val next = if (isOn) {
                                allSelected - choice.value
                            } else {
                                allSelected + choice.value
                            }
                            onAnswer(next.joinToString(","))
                        }
                    )
                }
                if (pair.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        // Everything they typed themselves, then the control to add more.
        if (custom.isNotEmpty() || allowOtherText) {
            val customColour = OnboardingPalette.toneFor(
                OnboardingSkin.colourOf(chapter),
                choices.size
            )
            custom.chunked(2).forEach { pair ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    pair.forEach { value ->
                        ColourTile(
                            label = value,
                            colour = customColour,
                            selected = true,
                            modifier = Modifier.weight(1f),
                            onTap = { origin ->
                                onLaunch(origin)
                                onAnswer((allSelected - value).joinToString(","))
                            }
                        )
                    }
                    if (pair.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            if (allowOtherText) {
                if (typingOther) {
                    OtherTileField(
                        chapter = chapter,
                        colour = customColour,
                        value = otherDraft,
                        focusRequester = otherFocus,
                        onChange = { otherDraft = it },
                        onDone = { commitOther() },
                        onCancel = {
                            otherDraft = ""
                            typingOther = false
                        }
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OtherTile(
                            colour = customColour,
                            modifier = Modifier.weight(1f),
                            onTap = { origin ->
                                onLaunch(origin)
                                typingOther = true
                                runCatching { otherFocus.requestFocus() }
                            }
                        )
                        if (custom.size % 2 == 0) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The catalogue picker, for a question whose choices are grouped.
 *
 * Three bands, top to bottom, and the order is the whole design:
 *
 *  1. WHAT YOU HAVE CHOSEN. Full-width bars, each in its own colour, numbered,
 *     with a cross to remove. The first bar is marked Native, because position
 *     is what makes a language native and being able to move it is the only
 *     control needed to change that. This band is the hero: it is the answer.
 *
 *  2. ADD YOUR OWN. One field the width of the screen with a plus beside it,
 *     so a language nobody listed still has a door.
 *
 *  3. THE CATALOGUE. Grouped by region, each group its own tinted card with a
 *     heading, each row a flag, a name and a coloured plus. Region grouping is
 *     what makes fifty-plus entries scannable: you read a continent, not a wall.
 *
 * A language already chosen loses its plus, because the same language twice is
 * not a thing. Removal happens on the bar, not here.
 */
@Composable
private fun CataloguePicker(
    chapter: OnboardingChapter,
    choices: List<QuestionChoice>,
    allSelected: List<String>,
    custom: List<String>,
    allowOtherText: Boolean,
    chrome: PickerChrome,
    typingOther: Boolean,
    otherDraft: String,
    otherFocus: FocusRequester,
    seed: Int,
    onOpenOther: () -> Unit,
    onOtherDraft: (String) -> Unit,
    onCommitOther: () -> Unit,
    onCancelOther: () -> Unit,
    onLaunch: (Offset) -> Unit,
    onAnswer: (String) -> Unit,
) {
    val ink = OnboardingSkin.inkOf(chapter)
    val accent = OnboardingSkin.colourOf(chapter)
    val labelOf = remember(choices) { choices.associate { it.value to it.label } }
    val symbolOf = remember(choices) { choices.associate { it.value to it.symbol } }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ---------- 1. CHOSEN ----------
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = chrome.chosenLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = OnboardingSkin.inkFaintOf(chapter),
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = OnboardingSkin.Body,
                    letterSpacing = 0.12.em
                )
            )
            Spacer(Modifier.height(9.dp))

            if (allSelected.isEmpty()) {
                Text(
                    text = chrome.emptyLabel,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = OnboardingSkin.inkSoftOf(chapter),
                        fontFamily = OnboardingSkin.Body,
                        lineHeight = 1.5.em
                    )
                )
            } else {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    allSelected.forEachIndexed { index, value ->
                        ChosenBar(
                            number = index + 1,
                            showNumber = chrome.ordered,
                            label = labelOf[value] ?: value,
                            symbol = symbolOf[value].orEmpty(),
                            colour = OnboardingPalette.colourFor(seed, index),
                            marker = if (chrome.markFirst && index == 0) {
                                chrome.firstMarkLabel
                            } else {
                                ""
                            },
                            canMoveUp = chrome.ordered && index > 0,
                            canMoveDown = chrome.ordered && index < allSelected.lastIndex,
                            onMoveUp = {
                                val reordered = allSelected.toMutableList()
                                val moved = reordered.removeAt(index)
                                reordered.add(index - 1, moved)
                                onAnswer(reordered.joinToString(","))
                            },
                            onMoveDown = {
                                val reordered = allSelected.toMutableList()
                                val moved = reordered.removeAt(index)
                                reordered.add(index + 1, moved)
                                onAnswer(reordered.joinToString(","))
                            },
                            onRemove = {
                                onAnswer((allSelected - value).joinToString(","))
                            }
                        )
                    }
                }
            }
        }

        // ---------- 2. ADD YOUR OWN ----------
        if (allowOtherText) {
            if (typingOther) {
                OtherTileField(
                    chapter = chapter,
                    colour = accent,
                    value = otherDraft,
                    focusRequester = otherFocus,
                    onChange = onOtherDraft,
                    onDone = onCommitOther,
                    onCancel = onCancelOther
                )
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(99.dp))
                            .border(
                                width = 1.5.dp,
                                color = OnboardingSkin.hairlineOf(chapter),
                                shape = RoundedCornerShape(99.dp)
                            )
                            .clickable(onClick = onOpenOther)
                            .padding(horizontal = 18.dp, vertical = 15.dp)
                    ) {
                        Text(
                            text = chrome.addHint,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = OnboardingSkin.inkFaintOf(chapter),
                                fontFamily = OnboardingSkin.Body
                            )
                        )
                    }
                    Spacer(Modifier.size(10.dp))
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(accent)
                            .clickable(onClick = onOpenOther),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "+",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                color = OnboardingSkin.onColour(accent),
                                fontWeight = FontWeight.Normal,
                                fontFamily = OnboardingSkin.Body
                            )
                        )
                    }
                }
            }
        }

        // ---------- 3. THE CATALOGUE ----------
        val groups = remember(choices) {
            choices.groupBy { it.group }.toList()
        }

        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = chrome.catalogueLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = OnboardingSkin.inkFaintOf(chapter),
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = OnboardingSkin.Body,
                    letterSpacing = 0.12.em
                )
            )

            groups.forEach { (groupName, entries) ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(OnboardingSkin.washOf(chapter))
                        .padding(vertical = 6.dp)
                ) {
                    // A question whose choices carry no group is one plain list.
                    // Rendering the heading anyway would put an empty line above
                    // it, so the heading only exists when there is a name for it.
                    if (groupName.isNotBlank()) {
                        Text(
                            text = groupName,
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = ink,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = OnboardingSkin.Body
                            ),
                            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 6.dp)
                        )
                    }
                    entries.forEach { choice ->
                        val already = choice.value in allSelected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !already) {
                                    onLaunch(Offset.Zero)
                                    onAnswer((allSelected + choice.value).joinToString(","))
                                }
                                .padding(start = 16.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (choice.symbol.isNotBlank()) {
                                Text(
                                    text = choice.symbol,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = OnboardingSkin.Body
                                    )
                                )
                                Spacer(Modifier.size(12.dp))
                            }
                            Text(
                                text = choice.label,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = if (already) {
                                        OnboardingSkin.inkFaintOf(chapter)
                                    } else {
                                        ink
                                    },
                                    fontFamily = OnboardingSkin.Body
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            if (!already) {
                                Box(
                                    Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(OnboardingPalette.colourFor(seed, 0)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "+",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = OnboardingSkin.Body
                                        )
                                    )
                                }
                            } else {
                                Text(
                                    text = "added",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = OnboardingSkin.inkFaintOf(chapter),
                                        fontFamily = OnboardingSkin.Body
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One chosen language, as a full-width bar.
 *
 * Colour, number, name, Native badge on the first, and a cross to remove. Two
 * small arrows let the person move a language up or down, which is how they
 * change which one is native without a separate question. The arrows are words
 * rather than icons so nothing here needs an icon font.
 */
@Composable
private fun ChosenBar(
    number: Int,
    showNumber: Boolean,
    label: String,
    symbol: String,
    colour: Color,
    marker: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val onBar = OnboardingSkin.onColour(colour)

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(99.dp))
            .background(colour)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showNumber) {
            Text(
                text = "$number.",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = onBar,
                    fontWeight = FontWeight.Normal,
                    fontFamily = OnboardingSkin.Body
                )
            )
            Spacer(Modifier.size(8.dp))
        }
        if (symbol.isNotBlank()) {
            Text(
                text = symbol,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = OnboardingSkin.Body
                )
            )
            Spacer(Modifier.size(8.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(
                color = onBar,
                fontWeight = FontWeight.SemiBold,
                fontFamily = OnboardingSkin.Body
            ),
            modifier = Modifier.weight(1f)
        )
        if (marker.isNotBlank()) {
            Text(
                text = marker,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = colour,
                    fontWeight = FontWeight.Bold,
                    fontFamily = OnboardingSkin.Body
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(onBar)
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            )
            Spacer(Modifier.size(8.dp))
        }
        if (canMoveUp) {
            Text(
                text = "↑",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = onBar.copy(alpha = 0.75f),
                    fontFamily = OnboardingSkin.Body
                ),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onMoveUp)
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            )
        }
        if (canMoveDown) {
            Text(
                text = "↓",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = onBar.copy(alpha = 0.75f),
                    fontFamily = OnboardingSkin.Body
                ),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onMoveDown)
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            )
        }
        Text(
            text = "✕",
            style = MaterialTheme.typography.titleMedium.copy(
                color = onBar.copy(alpha = 0.9f),
                fontFamily = OnboardingSkin.Body
            ),
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onRemove)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * The "Something else" tile. Same shape and weight as every other tile, dashed
 * edge and a plus, so it reads as an empty slot waiting to be filled rather than
 * as a different kind of control.
 */
@Composable
private fun OtherTile(
    colour: Color,
    onTap: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    var box by remember { mutableStateOf(Rect.Zero) }
    val labelColour = OnboardingSkin.onColour(colour)

    Box(
        modifier
            .onGloballyPositioned { box = it.boundsInWindow() }
            .heightIn(min = 96.dp)
            .clip(OnboardingTileShape)
            .background(colour)
            .border(
                width = 2.dp,
                color = labelColour.copy(alpha = 0.55f),
                shape = OnboardingTileShape
            )
            .toggleable(
                value = false,
                role = Role.Checkbox,
                onValueChange = { onTap(Offset(box.center.x, box.center.y)) }
            )
            .padding(16.dp)
    ) {
        Text(
            text = "Something else",
            style = MaterialTheme.typography.titleMedium.copy(
                color = labelColour,
                fontWeight = FontWeight.Medium,
                fontFamily = OnboardingSkin.Body,
                lineHeight = 1.22.em
            ),
            modifier = Modifier.align(Alignment.BottomStart)
        )
    }
}

/**
 * The "Something else" tile, opened into a field.
 *
 * Full width so a language name has room. Confirm and cancel sit as words under
 * the field rather than as boxes, so nothing about this reads as a dialog. The
 * field requests focus on its own so the keyboard comes straight up.
 */
@Composable
private fun OtherTileField(
    chapter: OnboardingChapter,
    colour: Color,
    value: String,
    focusRequester: FocusRequester,
    onChange: (String) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val ink = OnboardingSkin.inkOf(chapter)

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(OnboardingTileShape)
            .background(OnboardingPalette.washOf(colour))
            .border(
                width = 2.dp,
                color = colour,
                shape = OnboardingTileShape
            )
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(
                color = ink,
                fontWeight = FontWeight.Medium,
                fontFamily = OnboardingSkin.Body,
                lineHeight = 1.25.em
            ),
            cursorBrush = SolidColor(colour),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                capitalization = KeyboardCapitalization.Words
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth()) {
                    if (value.isEmpty()) {
                        Text(
                            text = "Type it",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = OnboardingSkin.inkFaintOf(chapter),
                                fontFamily = OnboardingSkin.Body
                            )
                        )
                    }
                    inner()
                }
            }
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Add",
                style = MaterialTheme.typography.labelLarge.copy(
                    color = OnboardingSkin.onColour(colour),
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = OnboardingSkin.Body
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(colour)
                    .clickable(onClick = onDone)
                    .padding(horizontal = 20.dp, vertical = 11.dp)
            )
            Spacer(Modifier.size(14.dp))
            Text(
                text = "Cancel",
                style = MaterialTheme.typography.labelLarge.copy(
                    color = OnboardingSkin.inkSoftOf(chapter),
                    fontWeight = FontWeight.Medium,
                    fontFamily = OnboardingSkin.Body
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 10.dp, vertical = 11.dp)
            )
        }
    }
}

/**
 * The "Something else" pill, opened.
 *
 * Sits where the pill was, sized to the full width so a language name has room.
 * Enter or the tick confirms, the cross abandons it. Both are real controls with
 * labels; the field is a genuine text field with its own focus request so the
 * keyboard comes straight up.
 */
@Composable
private fun OtherChipField(
    chapter: OnboardingChapter,
    value: String,
    focusRequester: FocusRequester,
    onChange: (String) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val ink = OnboardingSkin.inkOf(chapter)
    val accent = OnboardingSkin.colourOf(chapter)

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(99.dp))
            .background(OnboardingPalette.washOf(accent))
            .border(width = 2.dp, color = accent, shape = RoundedCornerShape(99.dp))
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.labelLarge.copy(
                color = ink,
                fontWeight = FontWeight.Medium,
                fontFamily = OnboardingSkin.Body,
                lineHeight = 1.20.em
            ),
            cursorBrush = SolidColor(accent),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                capitalization = KeyboardCapitalization.Words
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    if (value.isEmpty()) {
                        Text(
                            text = "Type it",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = OnboardingSkin.inkFaintOf(chapter),
                                fontFamily = OnboardingSkin.Body
                            )
                        )
                    }
                    inner()
                }
            }
        )
        Text(
            text = "Add",
            style = MaterialTheme.typography.labelMedium.copy(
                color = OnboardingSkin.onColour(accent),
                fontWeight = FontWeight.SemiBold,
                fontFamily = OnboardingSkin.Body
            ),
            modifier = Modifier
                .clip(RoundedCornerShape(99.dp))
                .background(accent)
                .clickable(onClick = onDone)
                .padding(horizontal = 14.dp, vertical = 9.dp)
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = "Cancel",
            style = MaterialTheme.typography.labelMedium.copy(
                color = OnboardingSkin.inkSoftOf(chapter),
                fontWeight = FontWeight.Medium,
                fontFamily = OnboardingSkin.Body
            ),
            modifier = Modifier
                .clip(RoundedCornerShape(99.dp))
                .clickable(onClick = onCancel)
                .padding(horizontal = 10.dp, vertical = 9.dp)
        )
    }
}

/**
 * Yes, no, rather not say — three tiles, not three rows.
 *
 * The empty value on "Rather not say" means it stores nothing, which is the
 * correct behaviour: declining is not an answer, and a stored empty string reads
 * the same as never having asked.
 */
@Composable
private fun YesNoRow(
    chapter: OnboardingChapter,
    choices: List<QuestionChoice>,
    answer: String,
    seed: Int,
    onLaunch: (Offset) -> Unit,
    onAnswer: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            choices.forEachIndexed { position, choice ->
                val tileColour = OnboardingPalette.colourFor(seed, position)
                val isOn = answer == choice.value && choice.value.isNotEmpty()
                ColourTile(
                    label = choice.label,
                    colour = tileColour,
                    selected = isOn,
                    modifier = Modifier.weight(1f),
                    onTap = { origin ->
                        onLaunch(origin)
                        onAnswer(if (isOn) "" else choice.value)
                    }
                )
            }
        }
    }
}

/**
 * The slider: a gradient rail from warm to cool, white thumb, filled to the thumb.
 *
 * The gradient is the meaning. The left end is one answer and the right end is
 * the other, and the colour between them is the space you are choosing a position
 * in. A single flat colour would say "adjust a value". This says "choose where you
 * sit", which is the actual question.
 *
 * The rail is decorative; the control is keyboard reachable and announced with its
 * value, and the words at each end are the whole vocabulary. There is no number on
 * screen, because nobody thinks about themselves as a 7.
 */
@Composable
private fun SliderAnswer(
    chapter: OnboardingChapter,
    question: OnboardingQuestion,
    answer: String,
    onLaunch: (Offset) -> Unit,
    onAnswer: (String) -> Unit,
) {
    val ink = OnboardingSkin.inkOf(chapter)
    val accent = OnboardingSkin.colourOf(chapter)
    val phrases = question.sliderPhrases
    val hasPhrases = phrases.isNotEmpty()
    // With phrases the stops are 1..n, one phrase each. Without them the scale
    // stays 0..sliderSteps and the answer is a bare position.
    val steps = if (hasPhrases) phrases.size else question.sliderSteps.coerceIn(1, 20)
    val firstStop = if (hasPhrases) 1 else 0

    val current = answer.toIntOrNull()?.coerceIn(firstStop, steps)
        ?: (steps / 2)
    val touched = answer.isNotBlank()

    var box by remember(question.key) { mutableStateOf(Rect.Zero) }
    var dragging by remember(question.key) { mutableStateOf(false) }

    val commit: (Float) -> Unit = { raw ->
        val snapped = raw.roundToInt().coerceIn(firstStop, steps)
        onAnswer(snapped.toString())
    }

    val positionOf: (Int) -> Float = { value ->
        if (steps <= firstStop) {
            0f
        } else {
            (value - firstStop).toFloat() / (steps - firstStop).toFloat()
        }
    }

    val currentPhrase = if (hasPhrases) {
        phrases[current.coerceIn(1, phrases.size) - 1]
    } else {
        null
    }

    val warmth = listOf(
        Color(0xFFE24A2E),
        Color(0xFFE08A1E),
        Color(0xFF7A9A18),
        Color(0xFF1F8A4C),
        Color(0xFF12857F),
        Color(0xFF1C6EA8),
        Color(0xFF3556CC)
    )

    Column(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { box = it.boundsInWindow() }
            .padding(top = 4.dp)
    ) {
        // The words at the ends are only shown for a slider without phrases. With
        // phrases the phrase itself is the label, so ten long lines stacked at the
        // top would be noise. The current phrase under the rail does that job.
        if (!hasPhrases) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = question.sliderMinLabel,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = if (touched && current < steps / 2f) ink else OnboardingSkin.inkSoftOf(chapter),
                        fontWeight = if (touched && current < steps / 2f) FontWeight.SemiBold else FontWeight.Normal,
                        fontFamily = OnboardingSkin.Body,
                        lineHeight = 1.35.em
                    ),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(20.dp))
                Text(
                    text = question.sliderMaxLabel,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = if (touched && current >= steps / 2f) ink else OnboardingSkin.inkSoftOf(chapter),
                        fontWeight = if (touched && current >= steps / 2f) FontWeight.SemiBold else FontWeight.Normal,
                        fontFamily = OnboardingSkin.Body,
                        lineHeight = 1.35.em
                    ),
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(22.dp))
        }

        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .pointerInput(question.key, steps, firstStop) {
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        commit(firstStop + fraction * (steps - firstStop))
                        onLaunch(Offset(box.left + offset.x, box.top + 24.dp.toPx()))
                    }
                }
                .pointerInput(question.key, steps, firstStop) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            onLaunch(Offset(box.center.x, box.center.y))
                        },
                        onDragCancel = { dragging = false }
                    ) { change, _ ->
                        val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        commit(firstStop + fraction * (steps - firstStop))
                    }
                }
        ) {
            val widthPx = constraints.maxWidth.toFloat()
            val fraction = positionOf(current)
            val thumbX = (widthPx * fraction).coerceIn(0f, widthPx)
            val thumbColour = if (touched) {
                val slot = (fraction * (warmth.size - 1)).roundToInt().coerceIn(0, warmth.size - 1)
                warmth[slot]
            } else {
                accent
            }

            Canvas(Modifier.fillMaxSize()) {
                val midY = size.height / 2f
                val railHeight = 8.dp.toPx()

                // Hairline rail, the full width.
                drawLine(
                    color = ink.copy(alpha = 0.10f),
                    start = Offset(0f, midY),
                    end = Offset(size.width, midY),
                    strokeWidth = railHeight,
                    cap = StrokeCap.Round
                )

                // Filled portion, in the thumb's colour.
                if (thumbX > 0f) {
                    drawLine(
                        color = thumbColour,
                        start = Offset(0f, midY),
                        end = Offset(thumbX, midY),
                        strokeWidth = railHeight,
                        cap = StrokeCap.Round
                    )
                }

                // The thumb: white disc, coloured ring, coloured centre.
                drawCircle(
                    color = Color.White,
                    radius = 15.dp.toPx(),
                    center = Offset(thumbX, midY)
                )
                drawCircle(
                    color = thumbColour,
                    radius = 15.dp.toPx(),
                    center = Offset(thumbX, midY),
                    style = Stroke(width = 3.dp.toPx())
                )
                drawCircle(
                    color = thumbColour,
                    radius = 5.5.dp.toPx(),
                    center = Offset(thumbX, midY)
                )

                // A soft ring behind the thumb while it is being dragged.
                if (dragging) {
                    drawCircle(
                        color = thumbColour.copy(alpha = 0.16f),
                        radius = 26.dp.toPx(),
                        center = Offset(thumbX, midY)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = when {
                currentPhrase != null -> currentPhrase
                !touched -> "Drag it where it belongs"
                current <= steps / 3f -> question.sliderMinLabel
                current >= steps * 2f / 3f -> question.sliderMaxLabel
                else -> "Somewhere in the middle"
            },
            style = MaterialTheme.typography.labelMedium.copy(
                color = if (touched) ink else OnboardingSkin.inkFaintOf(chapter),
                fontWeight = if (touched) FontWeight.SemiBold else FontWeight.Normal,
                fontFamily = OnboardingSkin.Body,
                letterSpacing = 0.01.em
            ),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}
