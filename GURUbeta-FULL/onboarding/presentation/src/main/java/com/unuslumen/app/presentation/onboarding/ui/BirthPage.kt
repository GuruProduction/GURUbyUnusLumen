// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.unuslumen.app.onboarding.domain.OnboardingChapter

/**
 * BirthPage — it knows you now.
 *
 * The closing screen. The picture is at full strength and every node keeps the
 * colour of the chapter that produced it, so the payoff is the accumulated colour
 * rather than a celebration graphic. The three stats are real counts, and the one
 * action opens the app.
 *
 * No confetti, no card, no congratulation. The map itself is the reward.
 *
 * IT IS NOT A DEAD END. The leading half of the screen goes back to the setup
 * stage, because this screen is drawn before the app gate opens and a human who
 * tapped past the API step has to be able to return and paste their key. A
 * closing page you cannot leave backwards is a trap, not a reward.
 */
@Composable
fun BirthPage(
    chapter: OnboardingChapter,
    nodeCount: Int,
    linkCount: Int,
    nodeColours: List<Color>,
    saving: Boolean,
    error: String?,
    onOpen: () -> Unit,
    onBack: () -> Unit,
) {
    OnboardingFrame(
        chapter = chapter,
        onAdvance = { if (!saving) onOpen() },
        onBack = onBack,
        hint = when {
            saving -> "Waking GURU up…"
            !error.isNullOrBlank() -> "Could not finish. Tap right to try again"
            else -> "Tap right to open GURU, left to go back"
        },
        hintIsError = !error.isNullOrBlank(),
        mapHeight = 260.dp,
        map = {
            OnboardingConstellation(
                chapter = chapter,
                nodeColours = nodeColours,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    ) {
        OnboardingEyebrow(chapter = chapter, index = 2, count = 3)
        Spacer(Modifier.height(12.dp))
        OnboardingHeadline(text = "GURU knows you now.", chapter = chapter)
        Spacer(Modifier.height(10.dp))
        OnboardingBody(
            text = "Everything you just gave GURU is on this device. This is the last time you explain yourself.",
            chapter = chapter
        )
        Spacer(Modifier.height(24.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            OnboardingStat(
                value = nodeCount.toString(),
                label = if (nodeCount == 1) "Answer" else "Answers",
                chapter = chapter,
                accent = OnboardingSkin.colourOf(
                    com.unuslumen.app.onboarding.domain.OnboardingQuestionCatalogue.chapters
                        .getOrElse(0) {
                            com.unuslumen.app.onboarding.domain.OnboardingQuestionCatalogue.chapters.first()
                        }
                )
            )
            OnboardingStat(
                value = linkCount.toString(),
                label = "Links",
                chapter = chapter,
                accent = OnboardingSkin.colourOf(
                    com.unuslumen.app.onboarding.domain.OnboardingQuestionCatalogue.chapters
                        .getOrElse(1) {
                            com.unuslumen.app.onboarding.domain.OnboardingQuestionCatalogue.chapters.first()
                        }
                )
            )
            OnboardingStat(
                value = "1",
                label = "GURU",
                chapter = chapter,
                accent = OnboardingSkin.colourOf(
                    com.unuslumen.app.onboarding.domain.OnboardingQuestionCatalogue.chapters
                        .getOrElse(2) {
                            com.unuslumen.app.onboarding.domain.OnboardingQuestionCatalogue.chapters.first()
                        }
                )
            )
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

        Spacer(Modifier.height(22.dp))
        OnboardingFootnote(
            text = "Built in Bristol. Runs on your hardware. Answers to you.",
            chapter = chapter
        )
    }
}
