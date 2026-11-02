// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.agents.AgentRuntime
import com.unuslumen.app.database.dao.GuruAgentDao
import com.unuslumen.app.data.agents.GuruAgentRepositoryImpl
import com.unuslumen.app.database.entity.GuruAgentEntity
import com.unuslumen.app.domain.model.AgentSummary
import com.unuslumen.app.domain.model.GuruAgent
import com.unuslumen.app.domain.model.SaveAgentRequest
import com.unuslumen.app.domain.repository.AgentCatalogue
import com.unuslumen.app.domain.repository.CatalogueAgent
import com.unuslumen.app.domain.repository.GrantableTool
import com.unuslumen.app.domain.repository.GuruAgentRepository
import com.unuslumen.app.domain.repository.PublishOutcome
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue tools, and the distinction that matters most: offline is not empty.
 *
 * WHY THIS EXISTS. GURU had no way to reach the publisher at all. Its only agent tools
 * read the local table, so an agent that existed in the catalogue but was not installed
 * was invisible to the model, which meant it could not suggest one, fetch one or add one.
 * These tests pin the three verbs and, more importantly, the fact that each one reports
 * its own failure rather than collapsing into a generic one.
 */
class AgentCatalogueToolsTest {

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    private class FakeDao : GuruAgentDao {
        val rows = mutableListOf<GuruAgentEntity>()
        override suspend fun getAllAgents() = rows.toList()
        override fun getAllAgentsFlow(): Flow<List<GuruAgentEntity>> = flowOf(rows.toList())
        override suspend fun getEnabledAgents() = rows.filter { it.enabled }
        override fun getEnabledAgentsFlow(): Flow<List<GuruAgentEntity>> = flowOf(rows.filter { it.enabled })
        override suspend fun getAgentsBySource(source: String) = rows.filter { it.source == source }
        override suspend fun getAgentById(id: String) = rows.firstOrNull { it.id == id }
        override suspend fun getAgentBySlug(slug: String) = rows.firstOrNull { it.slug == slug }
        override suspend fun getAgentByName(name: String) = rows.firstOrNull { it.name == name }
        override suspend fun insertAgent(agent: GuruAgentEntity) {
            rows.removeAll { it.id == agent.id }
            rows.add(agent)
        }
        override suspend fun insertAgents(agents: List<GuruAgentEntity>) = agents.forEach { insertAgent(it) }
        override suspend fun updateAgent(agent: GuruAgentEntity) = insertAgent(agent)
        override suspend fun setEnabled(id: String, enabled: Boolean) {
            val i = rows.indexOfFirst { it.id == id }
            if (i >= 0) rows[i] = rows[i].copy(enabled = enabled)
        }
        override suspend fun deleteAgent(id: String) { rows.removeAll { it.id == id } }
        override suspend fun deleteAgentsBySource(source: String) { rows.removeAll { it.source == source } }
        override suspend fun getSyncedAgents() = rows.filter { it.source == "synced" }
        override suspend fun getCount() = rows.size
        override suspend fun getEnabledCount() = rows.count { it.enabled }
        override suspend fun getCountBySource(source: String) = rows.count { it.source == source }
    }

    /** A catalogue the test controls: published agents, or null for "could not ask". */
    private class FakeCatalogue(
        private val published: List<CatalogueAgent>?,
        private val versions: Map<String, Int>? = emptyMap(),
        private val fetchable: Map<String, CatalogueAgent> = emptyMap(),
    ) : AgentCatalogue {
        override suspend fun listCatalogue(): List<CatalogueAgent>? = published
        override suspend fun fetchAgent(slug: String): CatalogueAgent? = fetchable[slug]
        override suspend fun publishedVersions(): Map<String, Int>? = versions
        override suspend fun submitForReview(
            slug: String, name: String, roleDescription: String,
            systemPrompt: String, toolPermissions: List<String>, authorLabel: String?,
        ): PublishOutcome = PublishOutcome(true, "ok")
    }

    private fun repository(dao: GuruAgentDao): GuruAgentRepository =
        GuruAgentRepositoryImpl(dao, mockk(relaxed = true))

    private fun executor(
        dao: GuruAgentDao,
        catalogue: AgentCatalogue,
    ): AgentToolExecutor = AgentToolExecutor(
        repository(dao),
        mockk<AgentRuntime>(relaxed = true),
        catalogue,
    )

    /**
     * Parse a result with kotlinx rather than android.util.JSONObject.
     *
     * THE REASON THIS IS NOT JSONObject. That class is part of the Android framework and a
     * plain JVM unit test gets a stub that constructs nothing, so `JSONObject(raw)` returns
     * null and every assertion reads as a failure against a correct result. The tool itself
     * produces kotlinx JSON, so the test reads it the same way.
     */
    private fun json(result: com.unuslumen.app.data.tools.registry.ToolExecutionResult): JsonObject {
        // A tool call that never produced a payload is a real failure, reported as one rather
        // than as a null dereference three lines later.
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(result.rawJson)
        return parsed as? JsonObject
            ?: error("the result was not a JSON object: ${result.rawJson.take(200)}")
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: error("'$key' missing from undefined")

    private fun JsonObject.flag(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: error("'$key' missing from undefined")

    private fun JsonObject.num(key: String): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: error("'$key' missing from undefined")

    private fun JsonObject.arr(key: String): JsonArray =
        (this[key] as? JsonArray) ?: error("'$key' missing from undefined")

    // ------------------------------------------------------------------
    // browseAgentCatalogue
    // ------------------------------------------------------------------

    /**
     * An offline catalogue is NOT an empty catalogue.
     *
     * This is the whole point of the tool. Reporting "I could not ask" as "nothing exists"
     * would tell a person their catalogue is empty when their connection is down, which is
     * the same fault class as an empty ARP scan read as an empty network.
     */
    @Test
    fun anUnreachableCatalogueIsReportedAsUnreachableNotEmpty() = runBlocking {
        val result = executor(FakeDao(), FakeCatalogue(published = null))
            .execute(AgentToolDefinitions.BROWSE_AGENT_CATALOGUE, emptyMap())
        val body = json(result)

        assertFalse("an unreachable catalogue is not a success", body.flag("success"))
        assertFalse("and it must say it could not ask", body.flag("reachable"))
        assertEquals("there is no list to report", 0, body.num("total"))
        assertTrue(
            "the message must not read as an answer about what exists: ${body.str("error")}",
            body.str("error").contains("could not be reached"),
        )
    }

    /** A catalogue that genuinely has nothing published is a different answer. */
    @Test
    fun anEmptyCatalogueIsReachableAndSaysSo() = runBlocking {
        val result = executor(FakeDao(), FakeCatalogue(published = emptyList()))
            .execute(AgentToolDefinitions.BROWSE_AGENT_CATALOGUE, emptyMap())
        val body = json(result)

        assertTrue("the call succeeded", body.flag("success"))
        assertTrue("and the catalogue was reachable", body.flag("reachable"))
        assertEquals(0, body.num("total"))
    }

    /** Published agents appear, including ones this phone does not have. */
    @Test
    fun everyPublishedAgentIsListedWithItsInstallState() = runBlocking {
        val dao = FakeDao()
        val catalogue = FakeCatalogue(
            published = listOf(
                CatalogueAgent(slug = "coding-agent", name = "Coding Agent", roleDescription = "writes code"),
                CatalogueAgent(slug = "researcher", name = "Researcher", roleDescription = "reads things"),
            ),
            versions = mapOf("coding-agent" to 3, "researcher" to 1),
        )

        val body = json(
            executor(dao, catalogue).execute(AgentToolDefinitions.BROWSE_AGENT_CATALOGUE, emptyMap()),
        )

        assertEquals("both published agents are listed", 2, body.num("total"))
        assertEquals("and nothing is installed yet", false, body.arr("agents")[0].jsonObject.flag("installed"))
        assertEquals(
            "the published version comes from the manifest",
            3,
            body.arr("agents")[0].jsonObject.num("publishedVersion"),
        )
    }

    // ------------------------------------------------------------------
    // fetchCatalogueAgent
    // ------------------------------------------------------------------

    /** One agent in full, without installing it. */
    @Test
    fun fetchingAnAgentReturnsItsInstructionsAndTools() = runBlocking {
        val agent = CatalogueAgent(
            slug = "coding-agent",
            name = "Coding Agent",
            roleDescription = "writes code",
            systemPrompt = "You write careful code.",
            toolPermissions = listOf("readFile", "writeFile"),
        )
        val result = executor(FakeDao(), FakeCatalogue(published = listOf(agent), fetchable = mapOf("coding-agent" to agent)))
            .execute(AgentToolDefinitions.FETCH_CATALOGUE_AGENT, mapOf("slug" to "coding-agent"))
        val body = json(result)

        assertTrue(body.flag("success"))
        assertEquals("coding-agent", body.str("slug"))
        assertEquals("You write careful code.", body.str("systemPrompt"))
        assertEquals(2, body.arr("tools").size)
        assertFalse("fetching does not install", body.flag("installed"))
    }

    /** A slug that does not exist is a failure that names the way to find the real one. */
    @Test
    fun anUnknownSlugIsAFailureThatPointsAtBrowsing() = runBlocking {
        val body = json(
            executor(FakeDao(), FakeCatalogue(published = emptyList()))
                .execute(AgentToolDefinitions.FETCH_CATALOGUE_AGENT, mapOf("slug" to "nope")),
        )
        assertFalse(body.flag("success"))
        assertTrue(
            "the refusal must tell a caller how to find a real slug: ${body.str("error")}",
            body.str("error").contains("browseAgentCatalogue"),
        )
    }

    /** A missing slug is a broken call, not a catalogue answer. */
    @Test
    fun aMissingSlugIsAnArgumentError() = runBlocking {
        val result = executor(FakeDao(), FakeCatalogue(published = emptyList()))
            .execute(AgentToolDefinitions.FETCH_CATALOGUE_AGENT, emptyMap())
        assertFalse("no slug is an argument error", result.success)
    }

    // ------------------------------------------------------------------
    // installCatalogueAgent
    // ------------------------------------------------------------------

    /** Installing lands the agent in the synced lane with exactly its shipped tools. */
    @Test
    fun installingLandsTheAgentWithTheToolsItShippedWith() = runBlocking {
        val dao = FakeDao()
        val agent = CatalogueAgent(
            slug = "coding-agent",
            name = "Coding Agent",
            roleDescription = "writes code",
            systemPrompt = "You write careful code.",
            toolPermissions = listOf("readFile", "writeFile", "clearAppData"),
        )
        val catalogue = FakeCatalogue(
            published = listOf(agent),
            versions = mapOf("coding-agent" to 4),
            fetchable = mapOf("coding-agent" to agent),
        )

        val body = json(
            executor(dao, catalogue)
                .execute(AgentToolDefinitions.INSTALL_CATALOGUE_AGENT, mapOf("slug" to "coding-agent")),
        )

        assertTrue(body.flag("success"))
        assertEquals(4, body.num("version"))
        assertFalse("nothing was installed before, so nothing was replaced", body.flag("replaced"))

        val stored = dao.rows.single()
        assertEquals("it lands as a catalogue agent, not a user one", GuruAgent.SOURCE_SYNCED, stored.source)
        assertEquals("coding-agent", stored.slug)
        assertTrue(
            "the shipped tools are not filtered: ${stored.toolPermissions}",
            stored.toolPermissions.contains("clearAppData"),
        )
    }

    /** Installing over an existing copy replaces it rather than duplicating it. */
    @Test
    fun reinstallingReplacesRatherThanDuplicates() = runBlocking {
        val dao = FakeDao()
        dao.rows.add(
            GuruAgentEntity(
                id = "existing", slug = "coding-agent", name = "Coding Agent",
                systemPrompt = "old", toolPermissions = "readFile",
                source = GuruAgent.SOURCE_SYNCED, version = 1,
            ),
        )
        val agent = CatalogueAgent(slug = "coding-agent", name = "Coding Agent", systemPrompt = "new")
        val catalogue = FakeCatalogue(
            published = listOf(agent), versions = mapOf("coding-agent" to 2),
            fetchable = mapOf("coding-agent" to agent),
        )

        val body = json(
            executor(dao, catalogue)
                .execute(AgentToolDefinitions.INSTALL_CATALOGUE_AGENT, mapOf("slug" to "coding-agent")),
        )

        assertTrue(body.flag("success"))
        assertTrue("it replaced an installed copy", body.flag("replaced"))
        assertEquals("one live agent, not two", 1, dao.rows.count { it.enabled })
    }

    /** An unreachable catalogue installs nothing and says nothing changed. */
    @Test
    fun anUnreachableCatalogueInstallsNothing() = runBlocking {
        val dao = FakeDao()
        val body = json(
            executor(dao, FakeCatalogue(published = null))
                .execute(AgentToolDefinitions.INSTALL_CATALOGUE_AGENT, mapOf("slug" to "coding-agent")),
        )

        assertFalse(body.flag("success"))
        assertTrue("nothing may land on the phone", dao.rows.isEmpty())
        assertTrue(
            "the message must say nothing changed: ${body.str("error")}",
            body.str("error").contains("Nothing was installed"),
        )
    }

    // ------------------------------------------------------------------
    // The tools are reachable at all
    // ------------------------------------------------------------------

    /**
     * The three verbs exist in the tool set. That is the defect this closes.
     *
     * GURU had no way to reach the publisher: `listAgents` and `spawnAgent` both read the
     * local table, so an agent in the catalogue but not on the phone was invisible to it.
     */
    @Test
    fun theCatalogueVerbsAreDeclared() {
        val names = AgentToolDefinitions.definitions.map { it.name }.toSet()
        assertTrue(names.contains(AgentToolDefinitions.BROWSE_AGENT_CATALOGUE))
        assertTrue(names.contains(AgentToolDefinitions.FETCH_CATALOGUE_AGENT))
        assertTrue(names.contains(AgentToolDefinitions.INSTALL_CATALOGUE_AGENT))
    }

    /** Each definition describes what it does, because the model reasons from that text. */
    @Test
    fun everyCatalogueVerbIsDescribed() {
        for (name in listOf(
            AgentToolDefinitions.BROWSE_AGENT_CATALOGUE,
            AgentToolDefinitions.FETCH_CATALOGUE_AGENT,
            AgentToolDefinitions.INSTALL_CATALOGUE_AGENT,
        )) {
            val definition = AgentToolDefinitions.definitions.first { it.name == name }
            assertTrue("'$name' has no description", definition.description.isNotBlank())
            assertEquals("'$name' belongs in the agents category", "agents", definition.category)
        }
    }
}
