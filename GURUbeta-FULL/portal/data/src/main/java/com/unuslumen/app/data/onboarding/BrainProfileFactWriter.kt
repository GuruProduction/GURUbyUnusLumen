// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.onboarding

import com.unuslumen.app.data.brain.BrainService
import com.unuslumen.app.data.brain.cerebrum.CerebrumHost
import com.unuslumen.app.domain.memory.MemoryFact
import com.unuslumen.app.onboarding.domain.OnboardingQuestion
import com.unuslumen.app.onboarding.domain.ProfileFactWriter
import org.koin.core.annotation.Single

/**
 * BrainProfileFactWriter — the onboarding answers' route into Cerebrum.
 *
 * Lives in portal:data because it wraps the one BrainService and must obey the
 * same write gate, the same deterministic-id rule and the same engine side
 * effects as every other memory writer. Putting it anywhere else would create
 * a second write path into the vault, which is exactly the bug the one-store
 * boundary reset removed.
 *
 * Two behaviours that matter:
 *  - returns the REAL brain id, so the profile can archive a superseded
 *    answer later instead of leaving a ghost
 *  - returns null (never throws) when the brain is not up yet, so the caller
 *    can record the answer as pending and retry at boot
 */
@Single
class BrainProfileFactWriter(
    private val brainService: BrainService
) : ProfileFactWriter {

    private val tag = "guru_onboarding"

    override suspend fun writeFact(key: String, factText: String, category: String): String? {
        if (!CerebrumHost.isRunning()) {
            android.util.Log.d(tag, "brain not up yet; '$key' queued by caller for boot retry")
            return null
        }
        if (factText.isBlank()) return null

        return try {
            val fact = MemoryFact(
                id = key,
                category = category,
                fact = factText,
                confidence = 1.0f,
                extractedDate = System.currentTimeMillis(),
                topic = OnboardingQuestion.PROFILE_TOPIC,
                subtopic = key,
                domain = OnboardingQuestion.PROFILE_DOMAIN
            )
            val stored = brainService.storeFact(
                fact = fact,
                role = "onboarding",
                beforeContext = "onboarding bootstrap",
                afterContext = "profile answer"
            )
            stored.id.takeIf { it.length == 64 }
        } catch (e: Exception) {
            android.util.Log.w(tag, "profile fact write failed for '$key': ${e.message}")
            null
        }
    }

    override suspend fun archiveFact(idHex: String, reason: String) {
        if (!CerebrumHost.isRunning()) return
        runCatching { brainService.deleteFact(idHex) }
            .onFailure { android.util.Log.w(tag, "profile archive failed for $idHex: ${it.message}") }
    }
}
