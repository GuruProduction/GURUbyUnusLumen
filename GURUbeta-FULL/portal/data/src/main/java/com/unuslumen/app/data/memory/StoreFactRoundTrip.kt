// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.memory

import com.unuslumen.app.domain.memory.MemoryFact
import com.unuslumen.app.domain.memory.MemoryRepository

/**
 * StoreFactRoundTrip — one fact persist that hands back the STORED row.
 *
 * The brain's id for content is deterministic 64-hex (sha256 of the text), and
 * `persistFacts` now returns the row carrying it, so there is nothing to
 * recover afterwards. This function is deliberately thin for that reason.
 *
 * What it replaces was the defect: it persisted, then searched for the fact to
 * read the id back, then returned the CALLER'S DRAFT when that search missed.
 * The draft carried the random uuid `MemoryToolExecutor` had invented, the brain
 * cannot resolve a uuid, and so `addMemoryFact` returned an id that
 * `deleteMemoryFact` rejected every single time. A readback is also a race with
 * anything that rewrites the row in between, and the id was knowable before the
 * write ever dispatched.
 *
 * A persist that returns no row is now a hard failure rather than a silent
 * fallback, because a silent fallback here is exactly how a fact becomes
 * impossible to remove.
 */
suspend fun persistedFactRoundTrip(
    memoryRepository: MemoryRepository,
    draft: MemoryFact
): MemoryFact {
    val storedRows = memoryRepository.persistFacts(listOf(draft))
    val stored = storedRows.firstOrNull()
        ?: error("brain persist returned no row for: ${draft.fact.take(80)}")
    if (stored.id.isBlank()) {
        error("brain persist returned a blank id for: ${draft.fact.take(80)}")
    }
    return stored
}
