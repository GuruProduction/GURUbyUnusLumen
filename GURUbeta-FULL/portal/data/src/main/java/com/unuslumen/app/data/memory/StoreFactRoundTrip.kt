// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.memory

import com.unuslumen.app.data.brain.BrainService
import com.unuslumen.app.domain.memory.MemoryFact
import com.unuslumen.app.domain.memory.MemoryRepository

/**
 * StoreFactRoundTrip — one fact persist that hands back the STORED row. The
 * brain's id for content is deterministic 64-hex (sha256 of the text), so the
 * tool lane's reported handle is REAL and its later delete/update routes act
 * on rows the brain actually holds. One search post-write does the honest
 * readback so callers learn the storage identity the engine gave the row.
 */
suspend fun persistedFactRoundTrip(
    memoryRepository: MemoryRepository,
    draft: MemoryFact
): MemoryFact {
    memoryRepository.persistFacts(listOf(draft))
    val hits = memoryRepository.searchFacts(draft.fact, draft.category)
    return hits.firstOrNull { it.fact.trim() == draft.fact.trim() && it.id.length == 64 }
        ?: hits.firstOrNull { it.fact.trim() == draft.fact.trim() }
        ?: draft
}