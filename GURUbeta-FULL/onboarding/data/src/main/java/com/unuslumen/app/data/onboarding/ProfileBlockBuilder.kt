// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.onboarding

import com.unuslumen.app.onboarding.domain.OnboardingQuestion
import com.unuslumen.app.onboarding.domain.OnboardingQuestionCatalogue
import com.unuslumen.app.onboarding.domain.QuestionInput
import com.unuslumen.app.onboarding.domain.UserProfile

/**
 * ProfileBlockBuilder — turns the profile row into the block that rides every
 * single send.
 *
 * DESIGN RULE: every question the human actually answered goes in. Not a
 * subset. The point of onboarding is that GURU knows them on the first message,
 * and a semantic search for "alright" will never surface their boundaries, their
 * voice or what they would cancel over.
 *
 * The block is built by walking the catalogue, so adding a question needs no
 * change here. Each answer is resolved from its typed column when one exists,
 * and from the JSON bag when it does not, then rendered through the question's
 * own `factTemplate`. A question with no answer, or an answer that is blank,
 * is skipped entirely.
 *
 * Sliders go through the question's own renderer. A phrase scale arrives as the
 * phrase they picked plus its position, because "Don't dress it up, I can handle
 * it (7 of 10)" tells a model how to speak, and "7" tells it nothing.
 *
 * Pure function, no I/O, no Android types: trivially testable and safe to call
 * on every send.
 */
object ProfileBlockBuilder {

    private const val HEADER = "About your human:"

    private const val FOOTER =
        "These are their own words. Do not reclassify them. " +
            "If a detail is missing it was declined or not reached, so never invent it. " +
            "Do not recite this list back at them; it is background so you already know them."

    /**
     * Build the block. Returns "" when the human has answered nothing, so an
     * untouched install leaves the system message exactly as it was.
     */
    fun build(profile: UserProfile, nowMillis: Long = System.currentTimeMillis()): String {
        val lines = mutableListOf<String>()

        OnboardingQuestionCatalogue.questions.forEach { question ->
            val raw = UserProfileAnswers.read(profile, question.key)
            if (raw.isBlank()) return@forEach

            val rendered = renderAnswer(question, raw)

            val template = question.factTemplate.ifBlank { "{value}" }
            val sentence = template
                .replace("{human}", profile.preferredName.trim().ifBlank { "them" })
                .replace("{value}", rendered)
                .trim()

            lines += if (sentence == rendered) "${question.reviewLabel}: $rendered" else sentence
        }

        if (lines.isEmpty()) return ""

        return buildString {
            append(HEADER)
            append('\n')
            lines.forEach { line ->
                append("- ")
                append(line)
                append('\n')
            }
            append(footerWithAgeCaveat(profile, nowMillis))
        }
    }

    /**
     * The closing line. Normally just the footer, but when the profile carries
     * an age it adds the freshness caveat so the model never treats a stored
     * number from two years ago as current.
     */
    private fun footerWithAgeCaveat(profile: UserProfile, nowMillis: Long): String {
        val age = profile.age
        if (age == null || age <= 0) return FOOTER

        val capturedYear = if (profile.createdAt > 0) {
            java.time.Instant.ofEpochMilli(profile.createdAt)
                .atZone(java.time.ZoneId.systemDefault())
                .year
        } else {
            java.time.Instant.ofEpochMilli(nowMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .year
        }

        val currentYear = java.time.Instant.ofEpochMilli(nowMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .year

        val ageNote = if (currentYear > capturedYear) {
            "They gave their age ($age) in $capturedYear, so treat it as approximate and never correct them on it.\n"
        } else {
            ""
        }

        return ageNote + FOOTER
    }

    /**
     * Turn one stored answer into the words that go in the sentence.
     *
     * Most answers are already prose and pass straight through. Two types need
     * work:
     *  - Slider: the stored value is a stop number, and a number alone says
     *    nothing. [OnboardingQuestion.renderAnswer] owns that rendering so the
     *    brain fact and this block can never drift: the phrase they picked, then
     *    its position, e.g. "Don't dress it up, I can handle it (7 of 10)".
     *  - Chips: the stored value is comma-joined, so it becomes a plain list a
     *    human would read rather than a raw join.
     */
    private fun renderAnswer(question: OnboardingQuestion, raw: String): String {
        return when (question.input) {
            QuestionInput.Slider -> question.renderAnswer(raw)

            QuestionInput.Chips -> raw
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToReadable()

            else -> raw.trim()
        }
    }

    /** "Coffee, Gym, Dog walk" keeps its commas but reads as a list, not a join. */
    private fun List<String>.joinToReadable(): String = when (size) {
        0 -> ""
        1 -> first()
        2 -> "${first()} and ${last()}"
        else -> dropLast(1).joinToString(", ") + " and " + last()
    }
}
