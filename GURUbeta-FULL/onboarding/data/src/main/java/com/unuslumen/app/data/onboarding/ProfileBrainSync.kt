// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.onboarding

import com.unuslumen.app.onboarding.domain.OnboardingQuestion
import com.unuslumen.app.onboarding.domain.OnboardingQuestionCatalogue
import com.unuslumen.app.onboarding.domain.ProfileFactWriter
import com.unuslumen.app.onboarding.domain.UserProfile
import com.unuslumen.app.onboarding.domain.UserProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * ProfileBrainSync — gets every profile answer into Cerebrum, once, honestly.
 *
 * THE PROBLEM THIS SOLVES: onboarding runs before the model is connected, and
 * the brain boots after the app graph is up. So an answer can land in Room
 * while the vault is still opening. Dropping it would leave a human's most
 * personal answers half-remembered, which is the one thing this feature
 * exists to prevent.
 *
 * THE SHAPE:
 *  - every answered question is written to the brain as one prose fact under
 *    the user_profile category
 *  - the returned id is recorded in factsByIdJson, keyed by question
 *  - if the brain is down, the key goes into pendingBrainSyncJson and the
 *    boot pass retries it
 *  - when an answer changes, the OLD fact is archived through the stored id
 *    before the new one is written, so an edit never leaves a ghost
 *
 * All bookkeeping lives on the profile row so it survives process death.
 */
class ProfileBrainSync(
    private val userProfileRepository: UserProfileRepository,
    private val factWriter: ProfileFactWriter
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Write every answered question that is not already in the brain.
     * Returns the number of facts actually written this pass.
     */
    suspend fun syncAll(): Int = withContext(Dispatchers.IO) {
        val profile = userProfileRepository.getProfile()
        if (profile.dealAcceptedAt <= 0) return@withContext 0

        var written = 0
        var mutated = profile

        for (question in OnboardingQuestionCatalogue.questions) {
            if (!question.writeToBrain) continue
            val answer = UserProfileAnswers.read(mutated, question.key)
            if (answer.isBlank()) continue

            val existingId = factIdFor(mutated, question.key)
            val factText = UserProfileAnswers.factText(
                question = question,
                answer = answer,
                humanName = mutated.preferredName
            )

            // Skip when a fact for this exact answer already exists. The id is
            // content-derived, so comparing the stored id to the id this answer
            // WOULD produce is the cheap, honest duplicate check.
            if (existingId != null) {
                val expectedId = expectedFactId(factText)
                if (expectedId == null || existingId == expectedId) continue
            }

            val newId = factWriter.writeFact(
                key = question.key,
                factText = factText,
                category = question.brainCategory
            )

            if (newId == null) {
                // Brain still booting: record the key so boot retries it.
                mutated = withPendingKey(mutated, question.key)
                continue
            }

            if (existingId != null && existingId != newId) {
                factWriter.archiveFact(existingId, "profile answer updated: ${question.key}")
            }

            mutated = withFactId(mutated, question.key, newId)
            mutated = withoutPendingKey(mutated, question.key)
            written++
        }

        if (mutated != profile) {
            userProfileRepository.saveProfile(mutated)
        }
        written
    }

    /** Flush keys left pending by an earlier boot. Idempotent. */
    suspend fun flushPending(): Int = withContext(Dispatchers.IO) {
        val profile = userProfileRepository.getProfile()
        val pending = pendingKeys(profile)
        if (pending.isEmpty()) return@withContext 0
        syncAll()
    }

    /** True when the brain has not yet accepted every answered question. */
    suspend fun hasPending(profile: UserProfile): Boolean = pendingKeys(profile).isNotEmpty()

    // ---------------------------------------------------------------------
    // bookkeeping
    // ---------------------------------------------------------------------

    private fun factIdFor(profile: UserProfile, key: String): String? {
        val map = runCatching { json.parseToJsonElement(profile.factsByIdJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        return (map[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }

    private fun withFactId(profile: UserProfile, key: String, id: String): UserProfile {
        val map = runCatching { json.parseToJsonElement(profile.factsByIdJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
            .toMutableMap()
        map[key] = JsonPrimitive(id)
        return profile.copy(
            factsByIdJson = json.encodeToString(JsonObject.serializer(), JsonObject(map))
        )
    }

    private fun pendingKeys(profile: UserProfile): List<String> {
        return runCatching {
            json.parseToJsonElement(profile.pendingBrainSyncJson)
                .let { it as? kotlinx.serialization.json.JsonArray }
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun withPendingKey(profile: UserProfile, key: String): UserProfile {
        val keys = pendingKeys(profile)
        if (key in keys) return profile
        return profile.copy(pendingBrainSyncJson = encodeStringArray(keys + key))
    }

    private fun withoutPendingKey(profile: UserProfile, key: String): UserProfile {
        val keys = pendingKeys(profile)
        if (key !in keys) return profile
        return profile.copy(pendingBrainSyncJson = encodeStringArray(keys - key))
    }

    private fun encodeStringArray(values: List<String>): String {
        return json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(JsonPrimitive.serializer()),
            values.map { JsonPrimitive(it) }
        )
    }

    /**
     * The id the brain WILL mint for a given fact. The brain derives ids from
     * the content, so this is a local mirror of that one rule. It is only used
     * to skip pointless rewrites; a null here just means "write and find out".
     */
    private fun expectedFactId(factText: String): String? {
        return runCatching {
            val contentBytes = factText.toByteArray(Charsets.UTF_8)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update(contentBytes)
            digest.update(":".toByteArray(Charsets.US_ASCII))
            digest.update(contentBytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }
}
