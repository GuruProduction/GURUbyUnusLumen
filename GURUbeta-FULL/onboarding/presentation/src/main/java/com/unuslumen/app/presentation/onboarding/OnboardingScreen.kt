// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.unuslumen.app.onboarding.domain.OnboardingQuestionCatalogue
import com.unuslumen.app.presentation.onboarding.ui.BirthPage
import com.unuslumen.app.presentation.onboarding.ui.OnboardingBeatPage
import com.unuslumen.app.presentation.onboarding.ui.OnboardingField
import com.unuslumen.app.presentation.onboarding.ui.OnboardingSkin
import com.unuslumen.app.presentation.onboarding.ui.PowerPage
import com.unuslumen.app.presentation.onboarding.ui.QuestionPage
import com.unuslumen.app.presentation.onboarding.ui.ReviewPage
import com.unuslumen.app.presentation.onboarding.ui.chapterAt
import com.unuslumen.app.presentation.onboarding.ui.rememberReducedMotion
import org.koin.androidx.compose.koinViewModel

/**
 * OnboardingScreen — the whole first-boot experience.
 *
 * Five phases, one white ground, a horizontal slide between them. The screen
 * holds no business logic: it reads state, forwards events, and draws. Every
 * word it shows comes from OnboardingQuestionCatalogue, so copy changes never
 * touch this file.
 *
 * The picture is derived here rather than inside each page. [nodeColours] maps
 * every answered question to the accent of the chapter it belongs to, in
 * catalogue order, so the constellation grows in the order the answers arrived
 * and every node keeps the colour of the part of your life it came from. That
 * one list is what makes the page feel like a thing being built rather than a
 * form being filled.
 *
 * Motion: forward moves in from the trailing edge, back from the leading edge.
 * With Android's Remove animations setting on, both become a cross-fade. The
 * phase change is never the only signal of progress: the rail, the chapter label
 * and the picture all state where you are.
 *
 * Back is intercepted in every phase. A human is never dropped out of their own
 * onboarding into a usable-looking app with no profile.
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val reducedMotion = rememberReducedMotion()
    var forward by remember { mutableStateOf(true) }
    var launch by remember { mutableStateOf<Offset?>(null) }

    BackHandler {
        forward = false
        when (state.phase) {
            OnboardingPhase.INTRO -> viewModel.previousIntro()
            OnboardingPhase.QUESTIONS -> viewModel.previousQuestion()
            OnboardingPhase.REVIEW -> viewModel.previousQuestion()
            OnboardingPhase.POWER -> viewModel.backToReview()
            OnboardingPhase.BIRTH -> viewModel.backToPower()
        }
    }

    val chapter = state.chapter
    val chapters = OnboardingQuestionCatalogue.chapters
    val colouredChapters = state.colouredChapters
    val nodeColours = remember(state.draft) {
        state.answeredChapters().map { index ->
            OnboardingSkin.colourOf(chapterAt(chapters, index))
        }
    }

    OnboardingField(chapter = chapter) {
        AnimatedContent(
            targetState = state.phase,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                if (reducedMotion) {
                    fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                } else {
                    val direction = if (forward) 1 else -1
                    val enter = fadeIn(tween(220)) +
                        slideInHorizontally(tween(320)) { fullWidth -> fullWidth / 7 * direction }
                    val exit = fadeOut(tween(180)) +
                        slideOutHorizontally(tween(320)) { fullWidth -> -fullWidth / 9 * direction }
                    enter togetherWith exit
                }
            },
            label = "onboarding-phase"
        ) { phase ->
            when (phase) {
                OnboardingPhase.INTRO -> {
                    val beat = state.currentBeat
                    if (beat != null) {
                        OnboardingBeatPage(
                            beat = beat,
                            reducedMotion = reducedMotion,
                            isLast = state.beatIndex == state.beats.lastIndex,
                            canGoBack = state.beatIndex > 0,
                            onAdvance = {
                                forward = true
                                if (beat.acceptsDeal && !state.dealAccepted) {
                                    viewModel.acceptDeal()
                                } else {
                                    viewModel.nextIntro()
                                }
                            },
                            onBack = {
                                forward = false
                                viewModel.previousIntro()
                            }
                        )
                    }
                }

                OnboardingPhase.QUESTIONS -> {
                    val question = state.question
                    if (question != null) {
                        QuestionPage(
                            question = question,
                            chapter = chapter,
                            chapters = chapters,
                            currentChapter = question.chapter,
                            answer = state.currentAnswer,
                            index = state.questionIndex,
                            total = state.questionTotal,
                            nodeColours = nodeColours,
                            colouredChapters = colouredChapters,
                            reducedMotion = reducedMotion,
                            launch = launch,
                            onLaunch = { origin -> launch = origin },
                            onAnswer = { value ->
                                viewModel.setAnswer(question.key, value)
                            },
                            onNext = {
                                forward = true
                                launch = null
                                viewModel.nextQuestion()
                            },
                            onBack = {
                                forward = false
                                launch = null
                                viewModel.previousQuestion()
                            }
                        )
                    }
                }

                OnboardingPhase.REVIEW -> {
                    ReviewPage(
                        answers = viewModel.answeredQuestions(),
                        chapter = chapter,
                        chapters = chapters,
                        currentChapter = state.currentChapterIndex,
                        nodeColours = nodeColours,
                        colouredChapters = colouredChapters,
                        saving = state.saving,
                        error = state.error,
                        onEdit = { key ->
                            forward = false
                            viewModel.editQuestion(key)
                        },
                        onContinue = {
                            forward = true
                            viewModel.toPower()
                        },
                        onBack = {
                            forward = false
                            viewModel.previousQuestion()
                        }
                    )
                }

                OnboardingPhase.POWER -> {
                    PowerPage(
                        chapter = chapter,
                        chapters = chapters,
                        currentChapter = state.currentChapterIndex,
                        items = viewModel.powerItems(),
                        powerOn = state.powerOn,
                        draft = state.powerDraft,
                        nodeColours = nodeColours,
                        colouredChapters = colouredChapters,
                        saving = state.saving,
                        error = state.error,
                        canConnect = { key -> viewModel.canConnect(key) },
                        onField = { key, value -> viewModel.setPowerField(key, value) },
                        onToggle = { key -> viewModel.togglePower(key) },
                        onFinish = { viewModel.finish() },
                        onBack = {
                            forward = false
                            viewModel.backToReview()
                        }
                    )
                }

                OnboardingPhase.BIRTH -> {
                    BirthPage(
                        chapter = chapter,
                        nodeCount = nodeColours.size,
                        linkCount = (nodeColours.size - 1).coerceAtLeast(0),
                        nodeColours = nodeColours,
                        saving = state.saving,
                        error = state.error,
                        onOpen = { viewModel.openGuru(onComplete) },
                        onBack = {
                            forward = false
                            viewModel.backToPower()
                        }
                    )
                }
            }
        }
    }
}
