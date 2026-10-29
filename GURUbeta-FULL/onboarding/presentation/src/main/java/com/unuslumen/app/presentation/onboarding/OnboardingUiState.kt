// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.onboarding

import com.unuslumen.app.onboarding.domain.OnboardingBeat
import com.unuslumen.app.onboarding.domain.OnboardingChapter
import com.unuslumen.app.onboarding.domain.OnboardingQuestion
import com.unuslumen.app.onboarding.domain.OnboardingQuestionCatalogue


/**
 * OnboardingUiState — everything the onboarding screens need to draw, in one
 * immutable snapshot. No Android types, no data access, no logic beyond what
 * the layout needs to decide.
 *
 * The flow has five phases:
 *  INTRO      the welcome beats, tap by tap, ending on the deal
 *  QUESTIONS  one question at a time, tagged with its chapter
 *  REVIEW     read-back of the answers before the save
 *  POWER      the setup stage: model key, local brain, skills, vision, language
 *  BIRTH      the closing screen, once everything is saved
 *
 * [draft] holds the live answer map so a back press never loses typed text.
 * [powerDraft] holds the setup fields, and [powerOn] holds which rows are
 * connected. Both are written to real preferences on finish; neither is
 * decorative.
 */
data class OnboardingUiState(
    val phase: OnboardingPhase = OnboardingPhase.INTRO,
    val chapters: List<OnboardingChapter> = emptyList(),
    val beats: List<OnboardingBeat> = emptyList(),
    val beatIndex: Int = 0,
    val question: OnboardingQuestion? = null,
    val questionIndex: Int = 0,
    val questionTotal: Int = 0,
    val draft: Map<String, String> = emptyMap(),
    val powerDraft: Map<String, String> = emptyMap(),
    // Tools start ON. They used to start off, and because the save writes the
    // switch position verbatim, a human who never touched the row had tools
    // explicitly disabled on their first boot and the agent could not call a
    // single one. Tools are the entire point of the app, so the switch arrives
    // already on and turning it off is a deliberate act.
    val powerOn: Set<String> = setOf("skills"),
    val dealAccepted: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null
) {
    /** True when the current question has a non-blank answer. */
    val currentAnswer: String get() = question?.let { draft[it.key] ?: "" } ?: ""

    /** The beat currently on screen, or null before the list loads. */
    val currentBeat: OnboardingBeat? get() = beats.getOrNull(beatIndex)

    /** Required questions gate the next button; optional ones never do. */
    val canAdvance: Boolean
        get() = question?.let { q -> !q.required || currentAnswer.isNotBlank() } ?: true

    /** The chapter the current phase draws its colour from. */
    val currentChapterIndex: Int
        get() = when (phase) {
            OnboardingPhase.INTRO -> 0
            OnboardingPhase.QUESTIONS -> question?.chapter ?: 0
            OnboardingPhase.REVIEW -> (chapters.size - 3).coerceAtLeast(0)
            OnboardingPhase.POWER -> (chapters.size - 2).coerceAtLeast(0)
            OnboardingPhase.BIRTH -> (chapters.size - 1).coerceAtLeast(0)
        }

    /** The chapter object for the current screen, safe if the list is empty. */
    val chapter: OnboardingChapter
        get() = chapters.getOrElse(currentChapterIndex) {
            chapters.firstOrNull() ?: OnboardingChapter(
                id = "identity",
                name = "Who you are",
                colour = "#E24A2E",
                ink = "#17181C",
                ground = "#FFFFFF"
            )
        }

    val progressFraction: Float
        get() = if (questionTotal <= 0) 0f else (questionIndex.toFloat() / questionTotal).coerceIn(0f, 1f)

    /** True on the beat that records consent. */
    val beatAcceptsDeal: Boolean get() = currentBeat?.acceptsDeal == true

    /** Every answered question as chapter-coloured nodes, in catalogue order. */
    fun answeredChapters(): List<Int> = OnboardingQuestionCatalogue.questions.mapNotNull { q ->
        val answer = draft[q.key]
        if (answer.isNullOrBlank()) null else q.chapter
    }

    /**
     * Which chapters have at least one answer in them.
     *
     * The rail paints a segment in its own colour once that chapter has
     * something in it, so the progress bar records what has been built rather
     * than measuring distance travelled. A chapter only colours once a real
     * answer exists, never because the flow merely passed through it.
     */
    val colouredChapters: Set<Int>
        get() = answeredChapters().toSet()
}

enum class OnboardingPhase {
    INTRO,
    QUESTIONS,
    REVIEW,
    POWER,
    BIRTH
}
