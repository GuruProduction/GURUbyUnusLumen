// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import com.unuslumen.app.database.dao.GuruAgentDao
import com.unuslumen.app.database.entity.GuruAgentEntity
import com.unuslumen.app.domain.model.SaveAgentRequest
import com.unuslumen.app.domain.repository.GuruAgentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The create path must give an agent a real slug. Issue #233.
 *
 * WHY THIS TEST EXISTS WHEN AgentSlugTest ALREADY PASSES. That one proves the RULE. This
 * one proves the repository actually CALLS it, which is where the defect was: the rule
 * could be perfect and the save path still wrote an empty string, and the two would look
 * identical from the outside. A fake DAO in front of the real repository closes that gap.
 */
class AgentCreateSlugTest {

    /** An in-memory stand-in for the Room DAO, holding rows in a list. */
    private class FakeDao : GuruAgentDao {
        val rows = mutableListOf<GuruAgentEntity>()

        override suspend fun getAllAgents(): List<GuruAgentEntity> = rows.toList()
        override fun getAllAgentsFlow(): Flow<List<GuruAgentEntity>> = flowOf(rows.toList())
        override suspend fun getEnabledAgents(): List<GuruAgentEntity> = rows.filter { it.enabled }
        override fun getEnabledAgentsFlow(): Flow<List<GuruAgentEntity>> = flowOf(rows.filter { it.enabled })
        override suspend fun getAgentsBySource(source: String): List<GuruAgentEntity> =
            rows.filter { it.source == source }

        override suspend fun getAgentById(id: String): GuruAgentEntity? = rows.firstOrNull { it.id == id }
        override suspend fun getAgentBySlug(slug: String): GuruAgentEntity? =
            rows.firstOrNull { it.slug == slug }

        override suspend fun getAgentByName(name: String): GuruAgentEntity? =
            rows.firstOrNull { it.name == name }

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

        override suspend fun deleteAgent(id: String) {
            rows.removeAll { it.id == id }
        }

        override suspend fun deleteAgentsBySource(source: String) {
            rows.removeAll { it.source == source }
        }

        override suspend fun getSyncedAgents(): List<GuruAgentEntity> =
            rows.filter { it.source == "synced" }

        override suspend fun getCount(): Int = rows.size
        override suspend fun getEnabledCount(): Int = rows.count { it.enabled }
        override suspend fun getCountBySource(source: String): Int = rows.count { it.source == source }
    }

    /**
     * The tool registry is only read by grantableTools, which these tests do not call.
     *
     * A relaxed mock rather than a real registry, because building one means registering
     * every tool domain just to create an agent, and a mock that answers nothing is a
     * clearer statement of "this test does not touch the picker".
     */
    private fun repository(dao: GuruAgentDao): GuruAgentRepository =
        GuruAgentRepositoryImpl(dao, io.mockk.mockk(relaxed = true))

    private fun request(name: String) = SaveAgentRequest(
        name = name,
        description = "this is for a test",
        systemPrompt = "You are a coding helper.",
        toolPermissions = emptyList(),
    )

    /**
     * The reported case: an agent named "coding agent" is saved with a real slug.
     *
     * The defect was `slug = ""` written directly by createAgent. Nothing else in the
     * store noticed, because spawn falls back to matching the name.
     */
    @Test
    fun creatingAnAgentStoresADerivedSlug() = runBlocking {
        val dao = FakeDao()
        val created = repository(dao).createAgent(request("coding agent"))

        assertEquals(
            "the reported defect was an empty slug; a created agent must carry a real one",
            "coding-agent",
            created.slug,
        )
        assertNotEquals("an empty slug is the defect", "", created.slug)
        assertEquals("the slug must be what was actually stored", "coding-agent", dao.rows.single().slug)
    }

    /** The stored slug is the catalogue's shape, not merely non-empty. */
    @Test
    fun theStoredSlugIsValidForPublishing() = runBlocking {
        val dao = FakeDao()
        val created = repository(dao).createAgent(request("  My   Coding Agent!!!  "))

        assertTrue(
            "'${created.slug}' would be refused by the catalogue, which accepts only lowercase " +
                "ascii words joined by single dashes",
            Regex("^[a-z0-9]+(-[a-z0-9]+)*$").matches(created.slug),
        )
    }

    /** Two agents with the same name must not collide on the shared key. */
    @Test
    fun twoAgentsWithTheSameNameGetDistinctSlugs() = runBlocking {
        val dao = FakeDao()
        val repo = repository(dao)

        val first = repo.createAgent(request("coding agent"))
        val second = repo.createAgent(request("coding agent"))

        assertEquals("coding-agent", first.slug)
        assertEquals("coding-agent-2", second.slug)
        assertNotEquals(
            "an empty or shared slug is the defect: two agents must not resolve to one key",
            first.slug,
            second.slug,
        )
    }

    /** A created agent resolves by its slug, which is what a stored reference needs. */
    @Test
    fun aCreatedAgentResolvesByItsSlug() = runBlocking {
        val dao = FakeDao()
        val repo = repository(dao)
        repo.createAgent(request("coding agent"))

        val bySlug = repo.resolveAgent("coding-agent")
        assertEquals(
            "anything holding a slug must be able to find the agent again",
            "coding agent",
            bySlug?.name,
        )
    }
}
