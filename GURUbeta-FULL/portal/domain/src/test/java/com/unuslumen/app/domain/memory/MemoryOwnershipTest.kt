// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agent memory partition, proved without a device.
 *
 * THE BUG THIS HOLDS SHUT. `AgentMemoryService` decided ownership by searching a
 * `source` field for `agent=<slug>`. Production always set that field to `cerebrum`,
 * the storage backend, so:
 *
 *  - an own-only agent received nothing at all,
 *  - a shared reader received every fact in the vault,
 *  - and `AgentMemoryServiceTest` passed because its fixture supplied
 *       `source = "agent=researcher"`,
 *
 * a value production cannot emit. A test whose fixture fabricates the value under
 * test proves the fixture, not the code.
 *
 * These cases use labels produced by [MemoryOwnership.labelFor], which is the exact
 * function the write path calls. There is no hand-written provenance string anywhere
 * below, so a fixture cannot invent a shape the writer would never produce.
 */
class MemoryOwnershipTest {

    @Test
    fun `a label written for no agent carries no owner`() {
        val label = MemoryOwnership.labelFor("hive extraction", agentId = "", shared = false)
        val parsed = MemoryOwnership.parse(label)
        assertEquals("hive extraction", parsed.label)
        assertEquals("", parsed.owner)
        assertFalse(parsed.isAgentAuthored)
        assertFalse(parsed.shared)
    }

    @Test
    fun `an agent label round trips its owner and sharing`() {
        val own = MemoryOwnership.parse(
            MemoryOwnership.labelFor("researcher run", agentId = "agent-uuid-1", shared = false),
        )
        assertEquals("researcher run", own.label)
        assertEquals("agent-uuid-1", own.owner)
        assertFalse(own.shared)

        val shared = MemoryOwnership.parse(
            MemoryOwnership.labelFor("researcher run", agentId = "agent-uuid-1", shared = true),
        )
        assertEquals("agent-uuid-1", shared.owner)
        assertTrue(shared.shared)
    }

    @Test
    fun `an own-only agent reads its own facts`() {
        val mine = MemoryOwnership.parse(
            MemoryOwnership.labelFor("note", agentId = "agent-a", shared = false),
        )
        assertTrue(
            "own-only must include what the agent wrote itself",
            MemoryOwnership.mayRead(mine, "agent-a", readOwn = true, readShared = false),
        )
    }

    @Test
    fun `an own-only agent never reads another agent's private fact`() {
        val theirs = MemoryOwnership.parse(
            MemoryOwnership.labelFor("note", agentId = "agent-b", shared = false),
        )
        assertFalse(
            "another agent's private fact must not be visible, which is what the old " +
                "source check got wrong in both directions",
            MemoryOwnership.mayRead(theirs, "agent-a", readOwn = true, readShared = false),
        )
    }

    @Test
    fun `shared reads see an explicitly shared fact from another agent`() {
        val offered = MemoryOwnership.parse(
            MemoryOwnership.labelFor("note", agentId = "agent-b", shared = true),
        )
        assertTrue(
            MemoryOwnership.mayRead(offered, "agent-a", readOwn = false, readShared = true),
        )
    }

    @Test
    fun `shared reads do not see another agent's private fact`() {
        val private = MemoryOwnership.parse(
            MemoryOwnership.labelFor("note", agentId = "agent-b", shared = false),
        )
        assertFalse(
            "the old behaviour handed a shared reader every fact in the vault; sharing is " +
                "opt-in by the writer",
            MemoryOwnership.mayRead(private, "agent-a", readOwn = false, readShared = true),
        )
    }

    @Test
    fun `a fact the person wrote belongs to the shared scope and to no agent`() {
        val humanFact = MemoryOwnership.parse(
            MemoryOwnership.labelFor("hive extraction", agentId = "", shared = false),
        )
        assertFalse(
            "an unattributed fact is not an agent's private property",
            MemoryOwnership.mayRead(humanFact, "agent-a", readOwn = true, readShared = false),
        )
        assertTrue(
            "it is in the shared scope by construction",
            MemoryOwnership.mayRead(humanFact, "agent-a", readOwn = false, readShared = true),
        )
    }

    @Test
    fun `a scope with every flag off reads nothing`() {
        val mine = MemoryOwnership.parse(
            MemoryOwnership.labelFor("note", agentId = "agent-a", shared = true),
        )
        assertFalse(
            MemoryOwnership.mayRead(mine, "agent-a", readOwn = false, readShared = false),
        )
    }

    @Test
    fun `two agents with different ids cannot read each other's private facts`() {
        // The concrete two-agent case the requirements name, rather than one agent in
        // isolation: agent-a writes privately, agent-b writes privately, and neither
        // own-only reader sees the other while each sees its own.
        val fromA = MemoryOwnership.parse(
            MemoryOwnership.labelFor("a's note", agentId = "agent-a", shared = false),
        )
        val fromB = MemoryOwnership.parse(
            MemoryOwnership.labelFor("b's note", agentId = "agent-b", shared = false),
        )

        val aOwnOnly = listOf(fromA, fromB).filter {
            MemoryOwnership.mayRead(it, "agent-a", readOwn = true, readShared = false)
        }
        val bOwnOnly = listOf(fromA, fromB).filter {
            MemoryOwnership.mayRead(it, "agent-b", readOwn = true, readShared = false)
        }

        assertEquals(listOf("a's note"), aOwnOnly.map { it.label })
        assertEquals(listOf("b's note"), bOwnOnly.map { it.label })
    }

    @Test
    fun `a shared reader receives exactly the shared set and not the private one`() {
        val fromA = MemoryOwnership.parse(
            MemoryOwnership.labelFor("a's note", agentId = "agent-a", shared = false),
        )
        val fromBShared = MemoryOwnership.parse(
            MemoryOwnership.labelFor("b's offered note", agentId = "agent-b", shared = true),
        )
        val humanFact = MemoryOwnership.parse(
            MemoryOwnership.labelFor("hive extraction", agentId = "", shared = false),
        )

        val visible = listOf(fromA, fromBShared, humanFact).filter {
            MemoryOwnership.mayRead(it, "agent-a", readOwn = false, readShared = true)
        }

        assertEquals(
            "shared must hand back the offered fact and the shared human fact, and the " +
                "private fact of another agent must not appear",
            listOf("b's offered note", "hive extraction"),
            visible.map { it.label },
        )
    }
}
