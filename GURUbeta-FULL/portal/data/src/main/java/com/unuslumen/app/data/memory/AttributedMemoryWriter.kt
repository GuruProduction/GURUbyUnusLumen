// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.memory

import com.unuslumen.app.data.tools.registry.ToolActor
import com.unuslumen.app.domain.memory.MemoryFact

/**
 * A memory store that can record WHO asked for a write.
 *
 * WHY AN OPTIONAL INTERFACE RATHER THAN A WIDER [com.unuslumen.app.domain.memory.MemoryRepository].
 *
 * The interface has eight implementers across five modules, and six of them are
 * fakes or read-only surfaces that have no actor to supply. Widening
 * `persistFacts` would have forced every one of them to declare a parameter they
 * cannot fill, and the ones that filled it anyway would have had to invent an
 * identity for a call nobody made. That is the same churn [ToolActor]'s doc
 * explains at the dispatcher, one layer down.
 *
 * So attribution is a capability a store either has or does not. `MemoryRepositoryImpl`
 * has it, because it owns the brain connection where provenance is written. A test
 * fake that has not been taught about actors keeps implementing the plain interface
 * and keeps compiling, which is the honest outcome: an unattributed write is exactly
 * what those fakes already modelled.
 *
 * WHY THE ACTOR IS PASSED RATHER THAN LOOKED UP. There is no ambient "current agent"
 * anywhere in this app, and inventing one would be the silent-global-state mistake.
 * The actor is a value the caller holds and hands over, so a write can always be
 * traced to the code path that made it.
 */
interface AttributedMemoryWriter {

    /**
     * Persist facts, recording [actor] in the provenance of every row written.
     *
     * Identical to `MemoryRepository.persistFacts` in every other respect: the same
     * deterministic content id, the same category handling, the same returned rows.
     * The only difference is what the provenance label says, which is what makes this
     * a separate method rather than a changed one.
     */
    suspend fun persistFactsAs(
        facts: List<MemoryFact>,
        actor: ToolActor,
    ): List<MemoryFact>

    /**
     * Write one typed record, recording [actor] alongside the caller's provenance.
     *
     * `cortexRecord` takes a free provenance label from the model, so the actor is
     * APPENDED rather than substituted: the model's own account of where the memory
     * came from is worth keeping, and losing it to gain attribution would trade one
     * fact for another.
     */
    suspend fun storeRecordAs(
        content: String,
        domain: String,
        topic: String,
        subtopic: String,
        provenance: String,
        rationale: String,
        relatedTo: List<String>,
        confidence: Float,
        actor: ToolActor,
    ): MemoryFact
}
