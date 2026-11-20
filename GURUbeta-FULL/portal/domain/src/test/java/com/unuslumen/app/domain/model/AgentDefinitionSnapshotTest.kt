// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot a run keeps of the definition it was given.
 *
 * SECTION 10.3 LISTS WHAT THAT SNAPSHOT MUST CARRY, and the first implementation hand-wrote eleven
 * fields of it into JSON. The omissions were the ones that decide what a run could actually do —
 * scopes, autonomy, budgets, visibility — which are exactly the facts that explain an old run. The
 * fix was to serialise the typed snapshot instead, so this test asserts against the type on the
 * exact fields that were being lost rather than against a hand-written document.
 */
class AgentDefinitionSnapshotTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun agent(
        memoryScope: AgentMemoryScope = AgentMemoryScope.NONE,
        autonomy: AgentAutonomy = AgentAutonomy.DEFAULT,
        toolNarrowing: Set<String> = emptySet(),
        budgets: AgentBudgets = AgentBudgets.DEFAULT,
    ) = GuruAgent(
        id = "a1",
        slug = "scribe",
        name = "Scribe",
        description = "writes things",
        systemPrompt = "you write",
        toolPermissions = listOf("captureScreen", "sendSms"),
        source = GuruAgent.SOURCE_USER,
        createdAt = 1,
        updatedAt = 2,
        memoryScope = memoryScope,
        autonomy = autonomy,
        toolNarrowing = toolNarrowing,
        budgets = budgets,
    )

    private fun encoded(agent: GuruAgent): JsonObject =
        json.parseToJsonElement(
            json.encodeToString(
                AgentDefinitionSnapshot.serializer(),
                agent.definitionSnapshot(),
            )
        ).jsonObject

    @Test
    fun aRunRecordsTheMemoryScopeItRanUnder() {
        /*
         * THE FIELD THAT WAS LOST. A run that could read shared memory and write its own must say so
         * in its snapshot, or a reader cannot tell why it remembered something the agent beside it did
         * not.
         */
        val snap = encoded(
            agent(memoryScope = AgentMemoryScope(readShared = true, writeOwn = true))
        )
        val scope = snap["memoryScope"]!!.jsonObject
        assertTrue(scope["readShared"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(scope["writeOwn"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(
            "and own reads stayed on, which is the default rather than an omission",
            scope["readOwn"]!!.jsonPrimitive.content.toBoolean(),
        )
    }

    @Test
    fun aRunRecordsTheAutonomyItWasGranted() {
        /*
         * AUTONOMY IS WHAT AUTHORISED THE WORK. A run that performed external side effects must have a
         * snapshot saying that was permitted, because the alternative is a reader assuming it was not
         * and concluding the agent behaved badly.
         */
        val snap = encoded(
            agent(autonomy = AgentAutonomy.DEFAULT.copy(performExternalSideEffects = true))
        )
        val autonomy = snap["autonomy"]!!.jsonObject
        assertTrue(autonomy["performExternalSideEffects"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(
            "and the dimensions it was not granted say so",
            autonomy["requestDelegation"]!!.jsonPrimitive.content.toBoolean(),
        )
    }

    @Test
    fun aRunRecordsTheBudgetThatCouldStopIt() {
        val snap = encoded(agent(budgets = AgentBudgets(maxExchanges = 3, maxDurationMillis = 60_000L)))
        val budgets = snap["budgets"]!!.jsonObject
        assertEquals("3", budgets["maxExchanges"]!!.jsonPrimitive.content)
        assertEquals("60000", budgets["maxDurationMillis"]!!.jsonPrimitive.content)
    }

    @Test
    fun aRunRecordsTheLocalRestrictionOnItsTools() {
        /*
         * AN IMPORTED AGENT HELD TO ONE TOOL MUST NOT HAVE A SNAPSHOT SHOWING THE PUBLISHER'S FULL
         * GRANT, or the history would suggest it had access it was never given.
         */
        val snap = encoded(agent(toolNarrowing = setOf("captureScreen")))
        assertEquals(
            listOf("captureScreen"),
            snap["toolNarrowing"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            "and the published grant is still recorded beside it",
            listOf("captureScreen", "sendSms"),
            snap["toolPermissions"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun theSnapshotTypeCarriesEveryFieldTheSpecificationNames() {
        /*
         * THE TYPE IS THE CONTRACT, so a field dropped from it fails here rather than showing up as a
         * history that cannot answer a question years later.
         */
        val snapshot = agent().definitionSnapshot()
        assertEquals("a1", snapshot.agentId)
        assertEquals("scribe", snapshot.slug)
        assertEquals(1L.let { 1 }, snapshot.definitionVersion)
        assertTrue(snapshot.visual.initials.isEmpty())
        assertTrue("transparent by default", snapshot.visibility.plans.display)
        assertTrue("and it may plan", snapshot.autonomy.planIndependently)
        assertTrue(snapshot.notifications.enabled)
        assertTrue(snapshot.manifest.requiredTools.isEmpty())
        assertEquals(AgentContextScope.ASSIGNMENT_ONLY, snapshot.contextScope)
    }
}
