// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.data.agents.AgentRunLedger
import com.unuslumen.app.database.entity.AgentRunEntity
import com.unuslumen.app.database.entity.AgentRunEventEntity
import com.unuslumen.app.database.entity.ConversationEntity
import com.unuslumen.app.database.entity.ToolObservationEntity
import com.unuslumen.app.database.guruDatabase
import com.unuslumen.app.domain.agents.AgentRunStatuses
import com.unuslumen.app.domain.security.DataProtector
import com.unuslumen.app.domain.security.ProtectedRead
import com.unuslumen.app.domain.security.ProtectedWrite
import com.unuslumen.app.domain.security.ProtectionContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reading a spawned agent's tool calls, which is the other half of D8 and D9.
 *
 * THE DEFECT IN ONE SENTENCE. `getToolResult` searched `tool_results`, which only the main chat ever
 * writes, so an agent's calls were invisible and the absence was reported as an answer. It searched
 * the right column of the wrong table.
 *
 * THE TWO CLAIMS THAT MATTER:
 *
 *  - a run's call is found by run id, with the output the tool actually returned;
 *  - a call whose output was never recorded says exactly that, rather than presenting the completion
 *    event as its result.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolResultRunLookupTest {

    private object PlainText : DataProtector {
        override fun seal(context: ProtectionContext, plaintext: String): ProtectedWrite =
            ProtectedWrite.Sealed(plaintext)
        override fun open(context: ProtectionContext, stored: String?): ProtectedRead =
            if (stored == null) ProtectedRead.Absent("nothing") else ProtectedRead.Value(stored)
        override fun isEnvelope(stored: String?): Boolean = false
    }

    private lateinit var db: guruDatabase
    private lateinit var ledger: AgentRunLedger
    private lateinit var executor: ToolResultToolExecutor

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            guruDatabase::class.java,
        ).allowMainThreadQueries().build()
        ledger = AgentRunLedger(
            database = db,
            runDao = db.agentRunDao(),
            eventDao = db.agentRunEventDao(),
            checkpointDao = db.agentRunCheckpointDao(),
            protector = PlainText,
        )
        executor = ToolResultToolExecutor(
            toolResultDao = db.toolResultDao(),
            runDao = db.agentRunDao(),
            observations = db.toolObservationDao(),
            ledger = ledger,
        )
        db.conversationDao().upsertConversation(
            ConversationEntity(id = "conv-1", title = "t", createdDate = 1, updatedDate = 1, messageCount = 0)
        )
        ledger.createRun(
            AgentRunEntity(
                runId = "run-1",
                conversationId = "conv-1",
                agentId = "agent-1",
                agentSlug = "researcher",
                agentName = "Researcher",
                assignment = "look into it",
                status = AgentRunStatuses.RUNNING,
                agentDefinitionSnapshotJson = """{"schemaVersion":1}""",
                resolvedSystemPrompt = "p",
                toolManifestSnapshotJson = """{"schemaVersion":1}""",
                modelProvider = "UnusLumen",
                modelName = "a-model",
                createdAt = 1_000,
                updatedAt = 1_000,
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Append a start event, with the arguments properly encoded.
     *
     * BUILT WITH THE SERIALIZER, NOT BY STRING CONCATENATION. A hand-built payload with a JSON
     * argument in it produces `"arguments":"{"query":"bristol"}"`, which is not JSON at all. The
     * runtime never does that — it encodes through the ledger's own serializer — so a test that did
     * would be testing a shape production cannot emit.
     */
    private suspend fun startCall(invocationId: String, tool: String, arguments: String = "{}") {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("invocationId", kotlinx.serialization.json.JsonPrimitive(invocationId))
            put("tool", kotlinx.serialization.json.JsonPrimitive(tool))
            put("arguments", kotlinx.serialization.json.JsonPrimitive(arguments))
        }
        ledger.append(
            "run-1",
            AgentRunLedger.EventDraft(
                eventType = AgentRunEventEntity.TOOL_CALL_STARTED,
                actorType = AgentRunEventEntity.ACTOR_RUNTIME,
                payload = payload.toString(),
            ),
        )
    }

    private fun json(result: com.unuslumen.app.data.tools.registry.ToolExecutionResult): JsonObject =
        Json.parseToJsonElement(result.rawJson).jsonObject

    /**
     * A run's call is found by its run id, with the output the tool actually returned.
     *
     * THE REPORTED DEFECT. This returned an empty result because it searched the chat's table.
     */
    @Test
    fun aRunsCallIsFoundByRunIdWithItsRealOutput() = runTest {
        startCall("inv-1", "webSearch", """{"query":"bristol"}""")
        db.toolObservationDao().insert(
            ToolObservationEntity(
                id = "obs-1",
                packageName = "",
                toolName = "webSearch",
                agentRunId = "run-1",
                agentInvocationId = "inv-1",
                resultSummary = "three results about Bristol",
                success = 1,
                durationMs = 120,
                timestamp = 2_000,
            )
        )

        val body = json(
            executor.execute(
                ToolResultToolDefinitions.GET_TOOL_RESULT,
                mapOf("runId" to "run-1"),
            )
        )

        val results = body["results"]!!.jsonArray
        assertEquals("the call is found", 1, results.size)
        val entry = results[0].jsonObject
        assertEquals("webSearch", entry["toolName"]!!.jsonPrimitive.content)
        assertEquals(
            "and its output is the observation, not the completion event",
            "three results about Bristol",
            entry["result"]!!.jsonPrimitive.content,
        )
        assertEquals("""{"query":"bristol"}""", entry["parameters"]!!.jsonPrimitive.content)
    }

    /**
     * A call whose output was never recorded says so.
     *
     * THE HONESTY CASE. Reporting this as an empty result would claim the tool returned nothing, which
     * is a different fact from nobody having observed what it returned.
     */
    @Test
    fun aCallWithNoObservationReportsThatNoOutputWasRecorded() = runTest {
        startCall("inv-2", "captureScreen")

        val body = json(
            executor.execute(
                ToolResultToolDefinitions.GET_TOOL_RESULT,
                mapOf("runId" to "run-1"),
            )
        )

        val entry = body["results"]!!.jsonArray[0].jsonObject
        assertTrue(
            "the absence is named rather than an empty string: ${entry["result"]}",
            entry["result"]!!.jsonPrimitive.content.contains("no output was recorded"),
        )
    }

    /** Narrowing by tool name still works, because sometimes the question is about one tool. */
    @Test
    fun aRunOwnedLookupCanBeNarrowedToOneTool() = runTest {
        startCall("inv-1", "webSearch")
        startCall("inv-2", "captureScreen")

        val body = json(
            executor.execute(
                ToolResultToolDefinitions.GET_TOOL_RESULT,
                mapOf("runId" to "run-1", "toolName" to "captureScreen"),
            )
        )

        val results = body["results"]!!.jsonArray
        assertEquals("only the named tool", 1, results.size)
        assertEquals("captureScreen", results[0].jsonObject["toolName"]!!.jsonPrimitive.content)
    }

    /** A run id that does not exist is refused with a pointer at the verb that lists them. */
    @Test
    fun anUnknownRunIdIsRefusedAndPointsAtListAgentRuns() = runTest {
        val result = executor.execute(
            ToolResultToolDefinitions.GET_TOOL_RESULT,
            mapOf("runId" to "no-such-run"),
        )

        assertFalse("the call did not succeed", result.success)
        assertTrue(
            // THE MESSAGE LIVES IN `error`, NOT `rawJson`. `ToolExecutionResult.error` carries the
            // sentence and leaves the payload empty, because a failed call has no payload to carry.
            "and it says where to find a real one: ${result.error}",
            result.error.orEmpty().contains("listAgentRuns"),
        )
    }

    /** A request with neither a run id nor a tool name is a broken call, not an empty answer. */
    @Test
    fun aCallWithNeitherRunNorToolNameIsAnArgumentError() = runTest {
        val result = executor.execute(ToolResultToolDefinitions.GET_TOOL_RESULT, emptyMap())

        assertFalse("this is a broken call", result.success)
        assertTrue(
            "and it names both routes: ${result.error}",
            result.error.orEmpty().contains("runId") && result.error.orEmpty().contains("toolName"),
        )
    }

    /** The main chat's history still answers when no run is named, exactly as before. */
    @Test
    fun theChatHistoryStillAnswersWhenNoRunIsNamed() = runTest {
        db.toolResultDao().insertToolResult(
            com.unuslumen.app.database.entity.ToolResultEntity(
                id = "tr-1",
                toolName = "weatherCurrent",
                parameters = "{}",
                result = """{"temperature":12}""",
                resultText = "temperature 12",
                timestamp = System.currentTimeMillis(),
                conversationId = "conv-1",
                success = true,
            )
        )

        val body = json(
            executor.execute(
                ToolResultToolDefinitions.GET_TOOL_RESULT,
                mapOf("toolName" to "weatherCurrent"),
            )
        )

        val results = body["results"]!!.jsonArray
        assertEquals("the chat's own record is unchanged", 1, results.size)
    }
}
