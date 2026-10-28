// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.onboarding

import com.unuslumen.app.onboarding.domain.OnboardingQuestion
import com.unuslumen.app.onboarding.domain.OnboardingQuestionCatalogue
import com.unuslumen.app.onboarding.domain.UserProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * UserProfileAnswers — the bridge between the catalogue's question keys and
 * the typed columns on the profile row.
 *
 * Two things live here and nowhere else:
 *  1. the key-to-field mapping, so a question's answer reaches its column
 *  2. the fact text builder, so every answer turns into one clean prose
 *     sentence for the brain
 *
 * Keeping both here means the catalogue can be rewritten freely: as long as
 * the keys stay stable, the answers keep landing where they should. A key
 * that has no typed column falls through to the JSON bag automatically, so
 * adding a question to the catalogue never requires a schema change.
 */
object UserProfileAnswers {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The typed answer for a question key, or "" when unanswered. */
    fun read(profile: UserProfile, key: String): String = when (key) {
        "preferred_name" -> profile.preferredName
        "full_name" -> profile.fullName
        "pronouns" -> profile.pronouns
        "location" -> profile.location
        "age" -> profile.age?.toString() ?: ""
        "identity" -> profile.identity
        "neurodivergence" -> profile.neurodivergence
        "beliefs" -> profile.beliefs
        "sexuality" -> profile.sexuality
        "politics" -> profile.politics
        "family" -> profile.family
        "work" -> profile.work
        "current_focus" -> profile.currentFocus
        "adjustments" -> profile.adjustments
        "health_or_legal" -> profile.healthOrLegal
        "interests" -> profile.interests
        "news_sources" -> profile.newsSources
        "socials" -> profile.socials
        "never_ask_again" -> profile.neverAskAgain
        else -> readExtra(profile.extraAnswersJson, key)
    }

    /** Return a copy of the profile with one answer written into its field. */
    fun write(profile: UserProfile, key: String, value: String): UserProfile {
        val trimmed = value.trim()
        return when (key) {
            "preferred_name" -> profile.copy(preferredName = trimmed)
            "full_name" -> profile.copy(fullName = trimmed)
            "pronouns" -> profile.copy(pronouns = trimmed)
            "location" -> profile.copy(location = trimmed)
            "age" -> profile.copy(age = trimmed.toIntOrNull())
            "identity" -> profile.copy(identity = trimmed)
            "neurodivergence" -> profile.copy(neurodivergence = trimmed)
            "beliefs" -> profile.copy(beliefs = trimmed)
            "sexuality" -> profile.copy(sexuality = trimmed)
            "politics" -> profile.copy(politics = trimmed)
            "family" -> profile.copy(family = trimmed)
            "work" -> profile.copy(work = trimmed)
            "current_focus" -> profile.copy(currentFocus = trimmed)
            "adjustments" -> profile.copy(adjustments = trimmed)
            "health_or_legal" -> profile.copy(healthOrLegal = trimmed)
            "interests" -> profile.copy(interests = trimmed)
            "news_sources" -> profile.copy(newsSources = trimmed)
            "socials" -> profile.copy(socials = trimmed)
            "never_ask_again" -> profile.copy(neverAskAgain = trimmed)
            else -> profile.copy(extraAnswersJson = writeExtra(profile.extraAnswersJson, key, trimmed))
        }
    }

    /** Every answered question, in catalogue order, with its real answer. */
    fun answeredPairs(profile: UserProfile): List<Pair<OnboardingQuestion, String>> =
        OnboardingQuestionCatalogue.questions.mapNotNull { question ->
            val answer = read(profile, question.key)
            if (answer.isBlank()) null else question to answer
        }

    /**
     * The one-sentence fact for the brain.
     *
     * Uses the catalogue's own template so the prose reads like GURU talking
     * about his human, not like a form dump. A blank template falls back to
     * a plain "Label: value" line so a new question still lands sensibly.
     *
     * The answer goes through [OnboardingQuestion.renderAnswer] first, so a
     * slider lands as the phrase they picked plus its number rather than a bare
     * digit. Both the words and the position matter, and a fact holding only
     * "7" would lose the meaning the human actually chose.
     */
    fun factText(
        question: OnboardingQuestion,
        answer: String,
        humanName: String = ""
    ): String {
        val rendered = question.renderAnswer(answer)
        val name = humanName.trim().ifBlank { "them" }
        val template = question.factTemplate.ifBlank { "{value}" }
        val sentence = template
            .replace("{human}", name)
            .replace("{value}", rendered)
        return if (sentence == rendered) "${question.reviewLabel}: $rendered" else sentence
    }

    // ---- JSON bag helpers -------------------------------------------------

    private fun readExtra(raw: String, key: String): String {
        return runCatching {
            json.parseToJsonElement(raw).jsonObject[key]
                ?.let { element -> (element as? JsonPrimitive)?.content }
                ?: ""
        }.getOrElse { "" }
    }

    private fun writeExtra(raw: String, key: String, value: String): String {
        val current: JsonObject = runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }
        val updated = current.toMutableMap()
        if (value.isBlank()) updated.remove(key) else updated[key] = JsonPrimitive(value)
        return json.encodeToString(JsonObject.serializer(), JsonObject(updated))
    }
}
