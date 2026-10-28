// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.onboarding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.unuslumen.app.onboarding.domain.OnboardingChapter
import com.unuslumen.app.onboarding.domain.OnboardingQuestion

/**
 * ReviewPage — the last honest look before the setup stage.
 *
 * Shows exactly what GURU will remember, in the human's own words. Questions that
 * were declined are absent rather than blank, because "not answered" is not a
 * value.
 *
 * NO BUTTONS. There is no Edit control beside each row, because there are no
 * buttons anywhere in this flow. A row is itself the control: tap any answer to
 * go back to the question that produced it. That is one gesture instead of two,
 * and it removes the only remaining piece of button chrome from the page.
 *
 * Each row carries a dot in the colour of the chapter it came from, so the list
 * reads as the same picture the constellation has been drawing the whole way
 * through. This page is the payoff for that picture, and a column of grey rows
 * would throw it away at the exact moment it completes.
 *
 * A long answer clamps to four lines, and tapping the row is the way back to the
 * whole of it.
 */
@Composable
fun ReviewPage(
    answers: List<Pair<OnboardingQuestion, String>>,
    chapter: OnboardingChapter,
    chapters: List<OnboardingChapter>,
    currentChapter: Int,
    nodeColours: List<Color>,
    colouredChapters: Set<Int>,
    saving: Boolean,
    error: String?,
    onEdit: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    OnboardingFrame(
        chapter = chapter,
        onAdvance = { if (!saving) onContinue() },
        onBack = onBack,
        hint = if (saving) "Saving…" else "Tap right to plug it in, left to go back",
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
                tag = "Review"
            )
        },
        map = {
            OnboardingConstellation(
                chapter = chapter,
                nodeColours = nodeColours
            )
        }
    ) {
        OnboardingEyebrow(chapter = chapter, index = 0, count = 3)
        Spacer(Modifier.height(12.dp))
        OnboardingHeadline(
            text = "This is what you told me.",
            chapter = chapter
        )
        Spacer(Modifier.height(10.dp))
        OnboardingSub(
            text = "It stays on this device. Tap anything to change it.",
            chapter = chapter
        )
        Spacer(Modifier.height(22.dp))

        if (answers.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(OnboardingSkin.washOf(chapter))
                    .padding(18.dp)
            ) {
                Text(
                    text = "You skipped every question. That's allowed. GURU will learn you as you go.",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = OnboardingSkin.inkSoftOf(chapter),
                        fontFamily = OnboardingSkin.Body,
                        lineHeight = 1.55.em
                    )
                )
            }
        } else {
            answers.forEachIndexed { index, pair ->
                val sourceChapter = chapterAt(chapters, pair.first.chapter)
                ReviewRow(
                    question = pair.first,
                    answer = pair.first.displayAnswer(pair.second),
                    chapter = chapter,
                    dotColour = OnboardingSkin.colourOf(sourceChapter),
                    onEdit = { onEdit(pair.first.key) }
                )
                if (index != answers.lastIndex) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(OnboardingSkin.hairlineOf(chapter))
                    )
                }
            }
        }

        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.labelLarge.copy(
                    color = OnboardingSkin.Error,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = OnboardingSkin.Body,
                    lineHeight = 1.45.em
                )
            )
        }
    }
}

/**
 * One answer: a coloured dot, the label, and the answer in the human's own words.
 *
 * The whole row is the tap target, so there is no separate Edit affordance and no
 * button. Tapping anywhere on it reopens the question that produced it.
 */
@Composable
private fun ReviewRow(
    question: OnboardingQuestion,
    answer: String,
    chapter: OnboardingChapter,
    dotColour: Color,
    onEdit: () -> Unit,
) {
    val ink = OnboardingSkin.inkOf(chapter)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(9.dp)
                .clip(CircleShape)
                .background(dotColour)
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = question.reviewLabel.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = OnboardingSkin.inkFaintOf(chapter),
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = OnboardingSkin.Body,
                    letterSpacing = 0.09.em
                )
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = answer,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = ink,
                    fontFamily = OnboardingSkin.Body,
                    lineHeight = 1.45.em
                ),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
