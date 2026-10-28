// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.onboarding

import com.unuslumen.app.data.brain.BrainService
import com.unuslumen.app.domain.memory.MemoryFact
import com.unuslumen.app.onboarding.domain.OnboardingQuestion
import com.unuslumen.app.onboarding.domain.ProfileFactWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ProfileFactWriterImpl — the brain side of the onboarding profile.
 *
 * Lives in portal data because it wraps BrainService, and the onboarding data
 * module must not depend on portal. The onboarding module binds against the
 * ProfileFactWriter interface; this class is the implementation that gets
 * wired to it here by the Koin binding in AiDataModule.
 *
 * It writes through the same BrainService.storeFact path every other memory
 * writer uses, so the profile facts inherit the whole engine: the write gate,
 * deterministic ids, decay, graph edges, episodic records, dream merges.
 * Onboarding gets no special lane and no privileged storage.
 *
 * Brain-down is a normal condition here, not an error. The caller records the
 * key as pending and the boot pass retries; null is the honest answer.
 */
class ProfileFactWriterImpl(
    private val brainService: BrainService
) : ProfileFactWriter {

    override suspend fun writeFact(key: String, factText: String, category: String): String? =
        withContext(Dispatchers.Default) {
            if (factText.isBlank()) return@withContext null
            runCatching {
                val fact = MemoryFact(
                    id = "",
                    category = category.ifBlank { OnboardingQuestion.PROFILE_CATEGORY },
                    fact = factText,
                    confidence = 1.0f,
                    extractedDate = System.currentTimeMillis(),
                    source = "onboarding",
                    trigger = key,
                    domain = OnboardingQuestion.PROFILE_DOMAIN,
                    topic = OnboardingQuestion.PROFILE_TOPIC,
                    subtopic = key
                )
                val stored = brainService.storeFact(fact, role = "onboarding")
                // storeFact returns the real deterministic hex id, or throws
                // when the brain rejected the write. A blank id is a failure.
                stored.id.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } }
            }.getOrElse { error ->
                android.util.Log.w(
                    "guru_onboarding",
                    "profile fact write failed for '$key' (will retry at boot): ${error.message}"
                )
                null
            }
        }

    override suspend fun archiveFact(idHex: String, reason: String) = withContext(Dispatchers.Default) {
        if (idHex.length != 64 || idHex.any { it !in "0123456789abcdef" }) return@withContext
        runCatching { brainService.deleteFact(idHex) }
            .onFailure { error ->
                android.util.Log.w(
                    "guru_onboarding",
                    "profile fact archive failed for ${idHex.take(12)}…: ${error.message} (reason: $reason)"
                )
            }
        Unit
    }
}
