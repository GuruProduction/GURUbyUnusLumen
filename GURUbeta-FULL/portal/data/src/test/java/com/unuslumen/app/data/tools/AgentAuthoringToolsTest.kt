// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.agents.GuruAgentRepositoryImpl
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolRegistry
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.database.dao.GuruAgentDao
import com.unuslumen.app.database.entity.GuruAgentEntity
import com.unuslumen.app.domain.model.GuruAgent
import com.unuslumen.app.domain.repository.AgentCatalogue
import com.unuslumen.app.domain.repository.AgentReview
import com.unuslumen.app.domain.repository.AgentStats
import com.unuslumen.app.domain.repository.CatalogueAgent
import com.unuslumen.app.domain.repository.GrantableTool
import com.unuslumen.app.domain.repository.GuruAgentRepository
import com.unuslumen.app.domain.repository.PublishOutcome
import com.unuslumen.app.domain.repository.PublisherPage
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

/**
 * The agent authoring verbs, the capability search and listTools.
 *
 * WHY THESE EXIST. The Agents room let a person write an agent, grant it tools, switch it
 * on and publish it, and none of that was reachable by the model. GURU could call an agent
 * whose name it had been told and could install one from the catalogue, and could not make
 * one at all. These tests pin the verbs that close that, and the four refusals that matter:
 * a reserved verb may never be granted, an unknown tool name may not be stored, a catalogue
 * agent may not be edited, and a name already in use is refused rather than replaced.
 */
class AgentAuthoringToolsTest {

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

    /** A catalogue the test controls, recording what was submitted. */
    private class FakeCatalogue : AgentCatalogue {
        var submitted: CatalogueAgent? = null
        var submittedPrice: Long? = null
        var submittedCategory: String? = null
        var outcome: PublishOutcome = PublishOutcome(true, "sent for review")

        override suspend fun listCatalogue(): List<CatalogueAgent>? = emptyList()
        override suspend fun fetchAgent(slug: String): CatalogueAgent? = null
        override suspend fun publishedVersions(): Map<String, Int>? = emptyMap()
        override suspend fun submitForReview(
            slug: String, name: String, roleDescription: String,
            systemPrompt: String, toolPermissions: List<String>, authorLabel: String?,
            priceCents: Long, category: String,
        ): PublishOutcome {
            submitted = CatalogueAgent(
                slug = slug, name = name, roleDescription = roleDescription,
                systemPrompt = systemPrompt, toolPermissions = toolPermissions,
            )
            submittedPrice = priceCents
            submittedCategory = category
            return outcome
        }

        override suspend fun fetchPublisher(handle: String): PublisherPage? = null
        override suspend fun fetchStats(slug: String): AgentStats? = null
        override suspend fun fetchReviews(slug: String): List<AgentReview> = emptyList()
        override suspend fun recordInstall(slug: String): Boolean = false
        override suspend fun submitRating(slug: String, stars: Int, comment: String?): Boolean = false
        override suspend fun ownedSlugs(): Set<String> = emptySet()
    }

    /** A tool set the registry can hold, so listTools has something real to report. */
    private class FakeToolSet(
        override val definitions: List<ToolDefinition>,
    ) : ToolSetRegistration {
        override fun executorClass(): KClass<out ToolExecutor> = DummyExecutor::class
        override fun extractorClass(): KClass<out ToolResultExtractor>? = null
    }

    private class DummyExecutor : ToolExecutor {
        override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
            ToolExecutionResult.error("not used")
    }

    private fun repository(dao: GuruAgentDao): GuruAgentRepository =
        GuruAgentRepositoryImpl(dao, mockk(relaxed = true))

    /**
     * A registry holding the two real tool sets plus a couple of extras, which is what
     * the executor queries. Built rather than mocked so listTools is proved against the
     * same registry the app uses at boot.
     */
    private fun registry(): ToolRegistry {
        val registry = ToolRegistry()
        val sets = listOf(
            AgentToolDefinitions,
            AgentAuthoringToolDefinitions,
            FakeToolSet(
                listOf(
                    ToolDefinition(
                        name = "executeShellCommand",
                        description = "Run a shell command on this phone.",
                        category = "shell",
                        parameters = listOf(ToolParameter("command", com.unuslumen.app.data.tools.registry.ToolParameterType.String, true, "the command")),
                        permissions = emptyList(),
                    ),
                    ToolDefinition(
                        name = "cameraCapture",
                        description = "Take a photograph with the camera.",
                        category = "media",
                        parameters = emptyList(),
                        permissions = listOf("android.permission.CAMERA"),
                    ),
                )
            ),
        )
        val executor = DummyExecutor()
        sets.forEach { set -> set.definitions.forEach { registry.register(it, executor, null) } }
        return registry
    }

    private fun executor(dao: FakeDao, catalogue: AgentCatalogue = FakeCatalogue()): AgentAuthoringToolExecutor =
        AgentAuthoringToolExecutor(repository(dao), catalogue, registry())

    private fun json(result: ToolExecutionResult): JsonObject =
        Json.parseToJsonElement(result.rawJson) as? JsonObject
            ?: error("the result was not a JSON object: ${result.rawJson.take(200)}")

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: error("'$key' missing")

    private fun JsonObject.flag(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: error("'$key' missing")

    private fun JsonObject.num(key: String): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: error("'$key' missing")

    private fun JsonObject.arr(key: String): JsonArray =
        (this[key] as? JsonArray) ?: error("'$key' missing")

    private fun stubAgent(
        id: String = "a1",
        slug: String = "coding-agent",
        name: String = "Coding Agent",
        source: String = GuruAgent.SOURCE_USER,
        tools: String = "readFile,writeFile",
    ) = GuruAgentEntity(
        id = id, slug = slug, name = name,
        systemPrompt = "Write careful code.",
        toolPermissions = tools,
        source = source,
    )

    // ------------------------------------------------------------------
    // createAgent
    // ------------------------------------------------------------------

    @Test
    fun creatingAnAgentStoresItWithTheNameAndInstructions() = runBlocking {
        val dao = FakeDao()
        val body = json(
            executor(dao).execute(
                AgentAuthoringToolDefinitions.CREATE_AGENT,
                mapOf("name" to "Note Taker", "instructions" to "Keep the notes tidy."),
            ),
        )

        assertTrue(body.flag("success"))
        assertTrue("a create says it created", body.flag("created"))
        assertEquals(1, dao.rows.size)
        assertEquals("Note Taker", dao.rows.single().name)
        assertEquals("Keep the notes tidy.", dao.rows.single().systemPrompt)
        assertEquals("the slug is derived, never typed by a person", "note-taker", dao.rows.single().slug)
    }

    /**
     * The instructions are the agent. An agent with none is refused by name.
     *
     * A blank argument is a broken call rather than a store refusing, so it comes back
     * through the framework's error channel with no payload, which is how every other
     * argument check in this module behaves. The assertion is on the sentence because
     * that is what the model reads and acts on.
     */
    @Test
    fun anAgentWithNoInstructionsIsRefused() = runBlocking {
        val dao = FakeDao()
        val result = executor(dao).execute(
            AgentAuthoringToolDefinitions.CREATE_AGENT,
            mapOf("name" to "Empty", "instructions" to "  "),
        )
        assertFalse("a blank brief is not a create", result.success)
        assertTrue("nothing may be stored", dao.rows.isEmpty())
        assertTrue(
            "the refusal must say instructions are the point: ${result.error}",
            result.error.orEmpty().contains("instructions", ignoreCase = true),
        )
    }

    /** A name already in use is refused with that reason, not silently replaced. */
    @Test
    fun aTakenNameIsRefusedAndNothingIsOverwritten() = runBlocking {
        val dao = FakeDao()
        dao.rows.add(stubAgent(name = "Coding Agent"))
        val body = json(
            executor(dao).execute(
                AgentAuthoringToolDefinitions.CREATE_AGENT,
                mapOf("name" to "Coding Agent", "instructions" to "different work"),
            ),
        )

        assertFalse(body.flag("success"))
        assertEquals("the existing agent is untouched", 1, dao.rows.size)
        assertEquals("Write careful code.", dao.rows.single().systemPrompt)
        assertTrue(
            "the refusal names the collision: ${body.str("error")}",
            body.str("error").contains("already an agent"),
        )
    }

    /**
     * A reserved verb may never be granted, even when the caller asks for it by name.
     *
     * An agent that can spawn or create another agent is a recursion nobody asked for, and
     * one that can grant itself tools has escaped its own permission set. This is the rule
     * the creator's picker already enforces, and it has to be enforced here too because a
     * tool name arriving as an argument is otherwise just a string.
     */
    @Test
    fun anAgentMayNotBeGrantedTheAbilityToSpawnAnother() = runBlocking {
        val dao = FakeDao()
        val body = json(
            executor(dao).execute(
                AgentAuthoringToolDefinitions.CREATE_AGENT,
                mapOf(
                    "name" to "Recursive",
                    "instructions" to "Make more of yourself.",
                    "tools" to "[\"spawnAgent\",\"createAgent\"]",
                ),
            ),
        )

        assertFalse(body.flag("success"))
        assertTrue("nothing is stored", dao.rows.isEmpty())
        assertTrue(
            "the refusal names the verbs: ${body.str("error")}",
            body.str("error").contains("spawnAgent") && body.str("error").contains("createAgent"),
        )
    }

    /** A tool name that does not exist is refused rather than stored as a dead grant. */
    @Test
    fun anUnknownToolNameIsRefusedRatherThanStored() = runBlocking {
        val dao = FakeDao()
        val body = json(
            executor(dao).execute(
                AgentAuthoringToolDefinitions.CREATE_AGENT,
                mapOf(
                    "name" to "Ghost",
                    "instructions" to "Do something.",
                    "tools" to "[\"teleport\"]",
                ),
            ),
        )

        assertFalse(body.flag("success"))
        assertTrue(dao.rows.isEmpty())
        assertTrue(
            "the refusal names the missing tool and points at listTools: ${body.str("error")}",
            body.str("error").contains("teleport") && body.str("error").contains("listTools"),
        )
    }

    /** A real grant lands, so the refusal above is not refusing everything. */
    @Test
    fun aRealToolIsGrantedAndStored() = runBlocking {
        val dao = FakeDao()
        val body = json(
            executor(dao).execute(
                AgentAuthoringToolDefinitions.CREATE_AGENT,
                mapOf(
                    "name" to "Shell Hand",
                    "instructions" to "Run the command you were given.",
                    "tools" to "[\"executeShellCommand\"]",
                ),
            ),
        )

        assertTrue(body.flag("success"))
        assertEquals(
            "the one real grant lands",
            listOf("executeShellCommand"),
            dao.rows.single().toolPermissions.split(",").map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    // ------------------------------------------------------------------
    // editAgent
    // ------------------------------------------------------------------

    @Test
    fun editingKeepsEveryFieldItWasNotGiven() = runBlocking {
        val dao = FakeDao()
        dao.rows.add(stubAgent(name = "Coding Agent", tools = "readFile,writeFile"))
        val body = json(
            executor(dao).execute(
                AgentAuthoringToolDefinitions.EDIT_AGENT,
                mapOf("agent" to "coding-agent", "instructions" to "Write very careful code."),
            ),
        )

        assertTrue(body.flag("success"))
        val stored = dao.rows.single()
        assertEquals("Write very careful code.", stored.systemPrompt)
        assertEquals("the name is untouched", "Coding Agent", stored.name)
        // The store normalises the grant list to "a, b", so the assertion is on the
        // names rather than on the separator.
        assertEquals(
            "the grants are untouched",
            setOf("readFile", "writeFile"),
            stored.toolPermissions.split(",").map { it.trim() }.toSet(),
        )
    }

    /** A catalogue agent cannot be rewritten, and the refusal says why. */
    @Test
    fun aCatalogueAgentCannotBeEdited() = runBlocking {
        val dao = FakeDao()
        dao.rows.add(stubAgent(slug = "archivist", name = "Archivist", source = GuruAgent.SOURCE_SYNCED))
        val body = json(
            executor(dao).execute(
                AgentAuthoringToolDefinitions.EDIT_AGENT,
                mapOf("agent" to "archivist", "instructions" to "Something else entirely."),
            ),
        )

        assertFalse(body.flag("success"))
        assertEquals("Write careful code.", dao.rows.single().systemPrompt)
        assertTrue(
            "the refusal must make sense to a person: ${body.str("error")}",
            body.str("error").contains("catalogue"),
        )
    }

    // ------------------------------------------------------------------
    // setAgentEnabled and deleteAgent
    // ------------------------------------------------------------------

    @Test
    fun switchingAnAgentOffStoresTheNewState() = runBlocking {
        val dao = FakeDao()
        dao.rows.add(stubAgent())
        val body = json(
            executor(dao).execute(
                AgentAuthoringToolDefinitions.SET_AGENT_ENABLED,
                mapOf("agent" to "coding-agent", "enabled" to false),
            ),
        )

        assertTrue(body.flag("success"))
        assertFalse("the result reports the stored state", body.flag("enabled"))
        assertFalse(dao.rows.single().enabled)
    }

    @Test
    fun deletingAnAgentYouMadeRemovesIt() = runBlocking {
        val dao = FakeDao()
        dao.rows.add(stubAgent())
        val body = json(
            executor(dao).execute(
                AgentAuthoringToolDefinitions.DELETE_AGENT,
                mapOf("agent" to "coding-agent"),
            ),
        )

        assertTrue(body.flag("success"))
        assertTrue(body.flag("deleted"))
        assertTrue("the row is gone", dao.rows.isEmpty())
    }

    // ------------------------------------------------------------------
    // publishAgent
    // ------------------------------------------------------------------

    /** Publishing carries a price and a category, and uses the agent's own slug. */
    @Test
    fun publishingSendsTheStoredAgentWithItsPriceAndCategory() = runBlocking {
        val dao = FakeDao()
        val catalogue = FakeCatalogue()
        dao.rows.add(stubAgent(slug = "coding-agent", name = "Coding Agent"))

        val body = json(
            executor(dao, catalogue).execute(
                AgentAuthoringToolDefinitions.PUBLISH_AGENT,
                mapOf("agent" to "coding-agent", "price" to "2.50", "category" to "code"),
            ),
        )

        assertTrue(body.flag("success"))
        assertEquals(250L, catalogue.submittedPrice)
        assertEquals("code", catalogue.submittedCategory)
        assertEquals("the stored slug travels, not a fresh one", "coding-agent", catalogue.submitted?.slug)
        assertEquals("Write careful code.", catalogue.submitted?.systemPrompt)
    }

    /** A price that is not a number refuses to publish rather than guessing. */
    @Test
    fun anUnreadablePriceRefusesToPublish() = runBlocking {
        val dao = FakeDao()
        val catalogue = FakeCatalogue()
        dao.rows.add(stubAgent())
        val body = json(
            executor(dao, catalogue).execute(
                AgentAuthoringToolDefinitions.PUBLISH_AGENT,
                mapOf("agent" to "coding-agent", "price" to "cheap"),
            ),
        )

        assertFalse(body.flag("success"))
        assertEquals("nothing was submitted", null, catalogue.submitted)
        assertTrue(
            "the refusal shows the shape that works: ${body.str("error")}",
            body.str("error").contains("2.50"),
        )
    }

    /** A catalogue agent is already somebody else's listing. */
    @Test
    fun aCatalogueAgentCannotBePublishedAsYourOwn() = runBlocking {
        val dao = FakeDao()
        val catalogue = FakeCatalogue()
        dao.rows.add(stubAgent(slug = "archivist", name = "Archivist", source = GuruAgent.SOURCE_SYNCED))
        val body = json(
            executor(dao, catalogue).execute(
                AgentAuthoringToolDefinitions.PUBLISH_AGENT,
                mapOf("agent" to "archivist"),
            ),
        )

        assertFalse(body.flag("success"))
        assertEquals("nothing was submitted", null, catalogue.submitted)
    }

    // ------------------------------------------------------------------
    // searchAgents
    // ------------------------------------------------------------------

    /**
     * A match on the tools an agent holds outranks a match on its name.
     *
     * This is the routing signal. A name is a label somebody chose; a tool it already holds
     * is evidence it can do the thing.
     */
    @Test
    fun aToolMatchOutranksANameMatch() = runBlocking {
        val dao = FakeDao()
        // Two separate adds. MutableList.add has a two-argument overload that takes an
        // index, so a two-element add reads as "insert at ..." rather than two rows.
        dao.rows.add(stubAgent(id = "a1", slug = "named-shell", name = "Shell Runner", tools = "readFile"))
        dao.rows.add(
            stubAgent(id = "a2", slug = "quiet-hand", name = "Quiet Hand", tools = "executeShellCommand"),
        )

        val body = json(
            executor(dao).execute(
                AgentAuthoringToolDefinitions.SEARCH_AGENTS,
                mapOf("query" to "shell"),
            ),
        )

        val matches = body.arr("matches").map { it.jsonObject }
        assertEquals(2, matches.size)
        assertEquals("the agent that holds the shell tool leads", "quiet-hand", matches[0].str("slug"))
        assertTrue(
            "and the reason says so: ${matches[0].str("why")}",
            matches[0].str("why").contains("tool"),
        )
    }

    @Test
    fun aQueryThatMatchesNothingSaysSoAndPointsAtTheList() = runBlocking {
        val dao = FakeDao()
        dao.rows.add(stubAgent())
        val body = json(
            executor(dao).execute(
                AgentAuthoringToolDefinitions.SEARCH_AGENTS,
                mapOf("query" to "astrophysics"),
            ),
        )

        assertEquals(0, body.num("total"))
        assertTrue(
            "the empty answer names the way out: ${body.str("error")}",
            body.str("error").contains("listAgents"),
        )
    }

    @Test
    fun anEmptyQueryListsEveryAgent() = runBlocking {
        val dao = FakeDao()
        dao.rows.add(stubAgent(id = "a1", slug = "one"))
        dao.rows.add(stubAgent(id = "a2", slug = "two", name = "Second Agent"))
        val body = json(
            executor(dao).execute(AgentAuthoringToolDefinitions.SEARCH_AGENTS, mapOf("query" to "")),
        )
        assertEquals(2, body.num("total"))
    }

    // ------------------------------------------------------------------
    // listTools
    // ------------------------------------------------------------------

    /**
     * listTools reports the registry, not the model's own definitions.
     *
     * getMyTools answers "what have I made", which cannot answer "what do I have". This is
     * the list an agent's grants are chosen from and the list a permission audit needs.
     */
    @Test
    fun listToolsReportsWhatTheRegistryActuallyHolds() = runBlocking {
        val body = json(
            executor(FakeDao()).execute(AgentAuthoringToolDefinitions.LIST_TOOLS, emptyMap()),
        )

        val names = body.arr("tools").map { it.jsonObject.str("name") }.toSet()
        assertTrue("a real tool is reported: $names", names.contains("executeShellCommand"))
        assertTrue("and the authoring verbs themselves", names.contains("createAgent"))
        assertFalse("a filter was not applied", body.flag("filtered"))
    }

    @Test
    fun listToolsFiltersByCategoryAndSaysItFiltered() = runBlocking {
        val body = json(
            executor(FakeDao()).execute(
                AgentAuthoringToolDefinitions.LIST_TOOLS,
                mapOf("category" to "shell"),
            ),
        )

        assertTrue("a filter was applied", body.flag("filtered"))
        val names = body.arr("tools").map { it.jsonObject.str("name") }.toSet()
        assertEquals(setOf("executeShellCommand"), names)
    }

    @Test
    fun listToolsReportsThePermissionAToolNeeds() = runBlocking {
        val body = json(
            executor(FakeDao()).execute(
                AgentAuthoringToolDefinitions.LIST_TOOLS,
                mapOf("permission" to "CAMERA"),
            ),
        )

        val names = body.arr("tools").map { it.jsonObject.str("name") }.toSet()
        assertEquals(setOf("cameraCapture"), names)
    }

    // ------------------------------------------------------------------
    // The set itself
    // ------------------------------------------------------------------

    @Test
    fun everyAuthoringVerbIsDeclaredDescribedAndInTheAgentsCategory() {
        val names = AgentAuthoringToolDefinitions.definitions.map { it.name }.toSet()
        assertEquals(
            setOf(
                "createAgent", "editAgent", "deleteAgent", "setAgentEnabled",
                "publishAgent", "searchAgents", "listTools",
            ),
            names,
        )
        for (definition in AgentAuthoringToolDefinitions.definitions) {
            assertTrue("'${definition.name}' has no description", definition.description.isNotBlank())
            assertEquals("'${definition.name}' belongs in agents", "agents", definition.category)
        }
    }
}
