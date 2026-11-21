// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.memory

/**
 * Who a memory belongs to, and who may see it.
 *
 * THE DEFECT THIS REPLACES. Four different facts were being carried by one field.
 * `MemoryFact.source` held the storage backend (`cerebrum`), and the agent memory
 * service looked in it for `agent=<slug>` to decide whether a fact belonged to the
 * agent asking. Production never wrote that marker into `source`, so:
 *
 *  - an own-only agent was handed nothing, because no source ever said `agent=`,
 *  - a shared reader was handed everything, because the scope filter was a switch
 *    rather than a partition,
 *  - and the agent memory test passed anyway, because its fixture supplied
 *    `source = "agent=researcher"`, a value production could not produce.
 *
 * So this separates the concepts and gives each its own name:
 *
 *  - CATEGORY is what kind of fact it is. It is the tree's domain, and it is what a
 *    category filter partitions on.
 *  - BACKEND is where it is stored. `cerebrum` for every row the brain holds. It is
 *    not an origin and nothing should filter on it.
 *  - PROVENANCE is the label written with the row: who or what wrote it.
 *  - OWNER is a stable identity derived from the provenance, empty when the row was
 *    not written by an agent.
 *  - VISIBILITY says whether a fact written by an agent is that agent's own or
 *    offered to the shared scope.
 *
 * The provenance grammar below is the one writer and reader contract. It is a
 * suffix list rather than free prose because a prefix would collide with the older
 * `guru.role=...` labels and a suffix cannot: the label before the suffix stays
 * readable on its own.
 */
object MemoryOwnership {

    /** The storage backend every brain row reports. Not an origin. */
    const val BACKEND_CEREBRUM = "cerebrum"

    /** Provenance suffix carrying the owning agent's stable id. */
    private const val OWNER_MARKER = "|agent="

    /** Provenance suffix marking a fact offered to the shared scope. */
    private const val SHARED_MARKER = "|visibility=shared"

    /**
     * Where a fact came from and who may read it.
     *
     * [owner] is the stable agent id, not a display name or a slug: a name can be
     * changed by its owner and a slug is derived and never shown, so neither can be
     * an identity that a permission decision rests on.
     */
    data class Provenance(
        /** The label with no ownership or visibility suffix, for display. */
        val label: String,
        /** The stable owning agent id, or empty when not written by an agent. */
        val owner: String,
        /** Whether the owner offered this fact to the shared scope. */
        val shared: Boolean,
    ) {
        val isAgentAuthored: Boolean get() = owner.isNotEmpty()
    }

    /**
     * Compose the provenance label a write should carry.
     *
     * [agentId] empty means a non-agent writer, which produces the plain label. The
     * suffix order is fixed here and parsed in the same order, so the two cannot drift.
     */
    fun labelFor(
        baseLabel: String,
        agentId: String,
        shared: Boolean,
    ): String = buildString {
        append(baseLabel.trim())
        if (agentId.isNotBlank()) {
            append(OWNER_MARKER).append(agentId.trim())
            if (shared) append(SHARED_MARKER)
        }
    }

    /**
     * Read a stored label back into its parts.
     *
     * A label with no ownership suffix belongs to the person, not to an agent, and
     * reports an empty owner. That is the conservative reading and the one that keeps
     * an own-only agent from claiming a fact the human wrote.
     */
    fun parse(storedLabel: String): Provenance {
        val raw = storedLabel.trim()
        val ownerIndex = raw.indexOf(OWNER_MARKER)
        if (ownerIndex < 0) {
            return Provenance(label = raw, owner = "", shared = false)
        }
        val visiblePart = raw.substring(0, ownerIndex)
        val afterOwner = raw.substring(ownerIndex + OWNER_MARKER.length)
        val sharedIndex = afterOwner.indexOf(SHARED_MARKER)
        val owner = if (sharedIndex >= 0) afterOwner.substring(0, sharedIndex) else afterOwner
        return Provenance(
            label = visiblePart,
            owner = owner.trim(),
            shared = sharedIndex >= 0,
        )
    }

    /**
     * Whether one agent may read a fact, given its scope.
     *
     * THIS IS THE PARTITION, AND IT IS ONE FUNCTION SO EVERY READ PATH AGREES.
     *
     *  - A fact the agent owns is always readable when [readOwn] is on.
     *  - A fact another agent owns is readable only when [readShared] is on AND that
     *    agent explicitly marked it shared. Sharing is opt-in by the writer, never
     *    inferred.
     *  - A fact with no agent owner was written by the person or by an automatic lane.
     *    It follows [readShared], because it belongs to the shared scope by
     *    construction and belongs to no agent's private set.
     *
     * An agent id that matches its own is a read of its own set even if the scope's
     * shared flag is on, which is why the own check comes first.
     */
    fun mayRead(
        provenance: Provenance,
        requestingAgentId: String,
        readOwn: Boolean,
        readShared: Boolean,
    ): Boolean {
        if (provenance.isAgentAuthored && provenance.owner == requestingAgentId) {
            return readOwn
        }
        if (provenance.isAgentAuthored) {
            // Another agent's fact: only ever through an explicit shared mark.
            return readShared && provenance.shared
        }
        // Unattributed: the shared scope by construction.
        return readShared
    }
}
