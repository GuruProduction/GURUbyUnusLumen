// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.gurutools.GuruToolSchemaMapper
import com.unuslumen.app.domain.model.DefineToolRequest
import com.unuslumen.app.domain.model.GuruDefinedTool
import com.unuslumen.app.domain.model.ToolImplementation
import com.unuslumen.app.domain.model.ToolStatus
import com.unuslumen.app.domain.repository.GuruToolRepository
import com.unuslumen.app.domain.repository.ValidationResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the repair path that did not exist (issue 297).
 *
 * THE DEFECT. `updateTool` rebuilt the request with `existing.parameters` every
 * time, so a tool's parameter schema was write-once. The only way to fix a wrong
 * schema was delete and redefine, which destroys the tool's id, its use count,
 * its creation time and every automation that references it by name.
 *
 * These tests hold the three things that make the repair real: the schema
 * changes, an omitted schema is left alone, and an invalid one is refused before
 * it reaches the database.
 */
class MetaToolUpdateSchemaTest {

    private val stored: JsonElement = Json.parseToJsonElement(
        """{"type":"object","properties":{"app":{"type":"string"}},"required":["app"]}""",
    )

    private fun existingTool() = GuruDefinedTool(
        id = "tool-1",
        name = "tv_control",
        displayName = "TV Control",
        description = "turns the telly over",
        parameters = stored,
        implementation = ToolImplementation.ShellCommand("echo hi", emptyList()),
        status = ToolStatus.APPROVED,
        createdAt = 1000L,
        approvedAt = 1000L,
        createdBy = "guru",
        lastUsedAt = null,
        useCount = 7,
        rationale = "because",
    )

    private fun executor(repo: GuruToolRepository) = MetaToolExecutor(repo)

    @Test
    fun updateToolAcceptsANewParameterSchema() = runBlocking {
        val repo = mockk<GuruToolRepository>(relaxed = true)
        val captured = slot<DefineToolRequest>()

        coEvery { repo.getTool("tool-1") } returns existingTool()
        coEvery { repo.validateToolDefinition(any()) } returns ValidationResult(valid = true, errors = emptyList(), warnings = emptyList())
        coEvery { repo.updateTool("tool-1", capture(captured)) } returns existingTool()

        val newSchema = """{"type":"object","properties":{"app":{"type":"string"},"volume":{"type":"integer"}},"required":["app"]}"""
        val result = executor(repo).execute(
            MetaToolDefinitions.UPDATE_TOOL,
            mapOf("toolId" to "tool-1", "parameters" to newSchema),
        )

        assertTrue("the update should succeed", result.success)
        val passed = captured.captured.parameters.jsonObject
        assertEquals(
            "the volume property must have reached the repository",
            "integer",
            passed["properties"]!!.jsonObject["volume"]!!.jsonObject["type"].toString().trim('"'),
        )
    }

    @Test
    fun anOmittedSchemaLeavesTheStoredOneAlone() = runBlocking {
        val repo = mockk<GuruToolRepository>(relaxed = true)
        val captured = slot<DefineToolRequest>()

        coEvery { repo.getTool("tool-1") } returns existingTool()
        coEvery { repo.validateToolDefinition(any()) } returns ValidationResult(valid = true, errors = emptyList(), warnings = emptyList())
        coEvery { repo.updateTool("tool-1", capture(captured)) } returns existingTool()

        val result = executor(repo).execute(
            MetaToolDefinitions.UPDATE_TOOL,
            mapOf("toolId" to "tool-1", "displayName" to "New Name"),
        )

        assertTrue(result.success)
        assertSame(
            "changing a display name must not touch the schema",
            stored,
            captured.captured.parameters,
        )
        assertEquals("New Name", captured.captured.displayName)
    }

    @Test
    fun anInvalidSchemaIsRefusedBeforeItIsSaved() = runBlocking {
        val repo = mockk<GuruToolRepository>(relaxed = true)

        coEvery { repo.getTool("tool-1") } returns existingTool()
        coEvery { repo.validateToolDefinition(any()) } returns ValidationResult(
            valid = false,
            errors = listOf("Composition must have at least one step"),
            warnings = emptyList(),
        )

        val result = executor(repo).execute(
            MetaToolDefinitions.UPDATE_TOOL,
            mapOf("toolId" to "tool-1", "parameters" to """{"a":{"type":"string"}}"""),
        )

        assertFalse("an invalid definition must not be reported as an update", result.success)
        coVerify(exactly = 0) { repo.updateTool(any(), any()) }
    }

    @Test
    fun aSchemaPassedAsAnObjectRatherThanTextIsAccepted() = runBlocking {
        // A model sometimes passes structured JSON and sometimes a string. Both
        // are reasonable and refusing one would be a trap.
        val repo = mockk<GuruToolRepository>(relaxed = true)
        val captured = slot<DefineToolRequest>()

        coEvery { repo.getTool("tool-1") } returns existingTool()
        coEvery { repo.validateToolDefinition(any()) } returns ValidationResult(valid = true, errors = emptyList(), warnings = emptyList())
        coEvery { repo.updateTool("tool-1", capture(captured)) } returns existingTool()

        val result = executor(repo).execute(
            MetaToolDefinitions.UPDATE_TOOL,
            mapOf("toolId" to "tool-1", "parameters" to Json.parseToJsonElement("""{"x":{"type":"string"}}""")),
        )

        assertTrue(result.success)
        assertTrue(captured.captured.parameters.jsonObject.containsKey("x"))
    }

    @Test
    fun theUpdatedSchemaIsReadBackAsOptionalWhereMarked() = runBlocking {
        // The whole point of the pair of issues: after an edit, an argument marked
        // optional must be offered to the model as optional, not mandatory.
        val repo = mockk<GuruToolRepository>(relaxed = true)
        val captured = slot<DefineToolRequest>()

        coEvery { repo.getTool("tool-1") } returns existingTool()
        coEvery { repo.validateToolDefinition(any()) } returns ValidationResult(valid = true, errors = emptyList(), warnings = emptyList())
        // Return what was actually saved, so the read-back is honest.
        coEvery { repo.updateTool("tool-1", capture(captured)) } answers {
            existingTool().copy(parameters = captured.captured.parameters)
        }

        val result = executor(repo).execute(
            MetaToolDefinitions.UPDATE_TOOL,
            mapOf(
                "toolId" to "tool-1",
                "parameters" to """{"type":"object","properties":{"app":{"type":"string"},"volume":{"type":"integer","required":false}},"required":["app"]}""",
            ),
        )
        assertTrue(result.success)

        val parsed = GuruToolSchemaMapper.parseParameters(captured.captured.parameters).associateBy { it.name }
        assertTrue("app is named in required", parsed.getValue("app").required)
        assertFalse("volume is marked optional", parsed.getValue("volume").required)
    }
}
