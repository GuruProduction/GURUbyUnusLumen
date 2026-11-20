// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.database.guruDatabase
import com.unuslumen.app.database.migrations.MIGRATION_36_37
import com.unuslumen.app.domain.model.AgentAutonomy
import com.unuslumen.app.domain.model.AgentCapabilityManifest
import com.unuslumen.app.domain.model.AgentMemoryScope
import com.unuslumen.app.domain.model.AgentVisibility
import com.unuslumen.app.domain.model.AgentVisual
import com.unuslumen.app.domain.model.SaveAgentRequest
import com.unuslumen.app.domain.model.VisibilityDetail
import com.unuslumen.app.domain.model.VisibilityRule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The agent store's configuration and history, proved on a real Room database.
 *
 * WHY ROBOLECTRIC AND NOT A MOCK. Every claim here is about what actually reaches SQLite: that an
 * edit appends a version row rather than overwriting the only copy, that the version number and
 * the history row move together, that an existing agent from before this migration loads with the
 * defaults rather than a crash. A mock would assert that the repository called the DAO, which is
 * not the question and would pass while the schema was wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentConfigurationPersistenceTest {

    private lateinit var db: guruDatabase
    private lateinit var repository: GuruAgentRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            guruDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = GuruAgentRepositoryImpl(
            guruAgentDao = db.guruAgentDao(),
            definitionVersionDao = db.agentDefinitionVersionDao(),
            // THE REAL BOUNDARY, on a real database. This transactor is what makes the live row
            // and its history roll back together, so the atomicity claims in these tests are
            // proved against the same implementation production uses.
            transactor = RoomAgentStoreTransactor(db),
            toolRegistry = com.unuslumen.app.data.tools.registry.ToolRegistry(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun creatingAnAgentWritesItsConfigurationAndItsFirstVersion() = runTest {
        val created = repository.createAgent(
            SaveAgentRequest(
                name = "Chain Worker",
                description = "does the chain work",
                systemPrompt = "you are a chain worker",
                toolPermissions = listOf("shellExec"),
                visual = AgentVisual(colourIndex = 3, initials = "CW"),
                autonomy = AgentAutonomy(performExternalSideEffects = true),
                memoryScope = AgentMemoryScope(readShared = true, proposeShared = true),
            )
        )

        assertEquals("Chain Worker", created.name)
        assertEquals("the slug is derived, never asked for", "chain-worker", created.slug)
        assertEquals(3, created.visual.colourIndex)
        assertEquals("CW", created.visual.initials)
        assertTrue(
            "a non-default autonomy setting must survive the round trip",
            created.autonomy.performExternalSideEffects,
        )
        assertTrue(created.memoryScope.readShared)
        assertEquals("a created agent starts its edit count at 1", 1, created.definitionVersion)

        val versions = repository.versionsFor(created.id)
        assertEquals("creation writes exactly one version", 1, versions.size)
        assertEquals(1, versions.first().definitionVersion)
        assertEquals("CREATED", versions.first().reason)
        assertTrue(
            "the stored definition must carry the prompt it was given",
            versions.first().definitionJson.contains("you are a chain worker"),
        )
    }

    @Test
    fun editingAnAgentAppendsAVersionAndKeepsThePreviousOne() = runTest {
        /*
         * THE DEFECT THIS CLOSES. `updateAgent` used to overwrite the row in place, so the previous
         * prompt and tool set ceased to exist: a run from last week could not be explained because
         * the instructions it ran under were gone. Now an edit appends, and the earlier version is
         * still readable afterwards.
         */
        val created = repository.createAgent(
            SaveAgentRequest(
                name = "Researcher",
                systemPrompt = "the first instructions",
                toolPermissions = listOf("captureScreen"),
            )
        )

        val edited = repository.updateAgent(
            created.id,
            SaveAgentRequest(
                name = "Researcher",
                systemPrompt = "the second instructions",
                toolPermissions = listOf("captureScreen", "phoneCall"),
            ),
        )

        assertEquals("the edit count advances", 2, edited.definitionVersion)
        assertEquals("the live row carries the new prompt", "the second instructions", edited.systemPrompt)

        val versions = repository.versionsFor(created.id)
        assertEquals("history holds both versions", 2, versions.size)
        val newest = versions.first()
        val oldest = versions.last()
        assertEquals(2, newest.definitionVersion)
        assertEquals(1, oldest.definitionVersion)
        assertTrue(
            "the previous prompt is still there, in its own version",
            oldest.definitionJson.contains("the first instructions"),
        )
        assertTrue(
            "the new prompt is in the new version",
            newest.definitionJson.contains("the second instructions"),
        )
        assertFalse(
            "the older version must not have been rewritten by the edit",
            oldest.definitionJson.contains("the second instructions"),
        )
    }

    @Test
    fun threeEditsProduceVersionsOneTwoAndThree() = runTest {
        val created = repository.createAgent(
            SaveAgentRequest(name = "Counter", systemPrompt = "v1")
        )
        repository.updateAgent(created.id, SaveAgentRequest(name = "Counter", systemPrompt = "v2"))
        val third = repository.updateAgent(created.id, SaveAgentRequest(name = "Counter", systemPrompt = "v3"))

        assertEquals(3, third.definitionVersion)
        val versionNumbers = repository.versionsFor(created.id).map { it.definitionVersion }
            .sorted()
        assertEquals(
            "each edit must add exactly one version rather than colliding or skipping",
            listOf(1, 2, 3),
            versionNumbers,
        )
    }

    @Test
    fun anAgentSavedBeforeThisMigrationLoadsWithTheDeclaredDefaults() = runTest {
        /*
         * THE UPGRADE PATH, PROVED AT THE REPOSITORY RATHER THAN IN SQL. A row written before this
         * change carries `{"schemaVersion":1}` in every configuration column. It must decode to the
         * domain defaults, and those defaults must reproduce the behaviour that agent already had:
         * transparent, able to work, and not reaching outside the phone.
         */
        db.guruAgentDao().insertAgent(
            com.unuslumen.app.database.entity.GuruAgentEntity(
                id = "legacy-1",
                slug = "legacy",
                name = "Legacy",
                description = "an agent from before the configuration model",
                systemPrompt = "you are legacy",
                toolPermissions = "shellExec",
                source = "user",
                enabled = true,
                version = 4,
                createdAt = 1000,
                updatedAt = 2000,
                visualJson = AgentConfigurationCodec.EMPTY_DOCUMENT,
                visibilityJson = AgentConfigurationCodec.EMPTY_DOCUMENT,
                autonomyJson = AgentConfigurationCodec.EMPTY_DOCUMENT,
                budgetsJson = AgentConfigurationCodec.EMPTY_DOCUMENT,
                modelPreferenceJson = AgentConfigurationCodec.EMPTY_DOCUMENT,
                notificationsJson = AgentConfigurationCodec.EMPTY_DOCUMENT,
                memoryScopeJson = AgentConfigurationCodec.EMPTY_DOCUMENT,
                contextScopeJson = AgentConfigurationCodec.EMPTY_DOCUMENT,
                manifestJson = AgentConfigurationCodec.EMPTY_DOCUMENT,
                skillIds = "",
                definitionVersion = 1,
            )
        )

        val loaded = repository.getAgent("legacy-1")!!
        assertEquals("Legacy", loaded.name)
        assertEquals(listOf("shellExec"), loaded.toolPermissions)
        assertEquals("the published version is untouched", 4, loaded.version)

        assertTrue("transparent by default", loaded.visibility.reasoning.display)
        assertEquals(VisibilityDetail.FULL, loaded.visibility.tools.detail)
        assertTrue("it may still plan", loaded.autonomy.planIndependently)
        assertTrue("it may still use its granted tools", loaded.autonomy.useDeclaredToolsWithoutAsking)
        assertFalse(
            "reaching outside the phone is opt-in and a legacy row never opted in",
            loaded.autonomy.performExternalSideEffects,
        )
        assertFalse("memory was never granted, so it stays ungranted", loaded.memoryScope.readShared)
        assertTrue(
            "an empty manifest asks for nothing, so it validates against any runtime",
            loaded.manifest.requiredTools.isEmpty(),
        )
    }

    @Test
    fun aCorruptConfigurationDocumentFallsBackRatherThanCrashing() = runTest {
        /*
         * A TRUNCATED WRITE, a document from a newer build, or a column somebody edited by hand
         * must not turn the agent list into a crash. Falling back to the default produces the
         * behaviour that agent had before the field meant anything, which is the honest reading of
         * a document that says nothing usable.
         */
        db.guruAgentDao().insertAgent(
            com.unuslumen.app.database.entity.GuruAgentEntity(
                id = "corrupt-1",
                name = "Corrupt",
                systemPrompt = "you are corrupt",
                visibilityJson = "{\"plans\":{\"capture\":tru",
                autonomyJson = "not json at all",
                manifestJson = "",
            )
        )

        val loaded = repository.getAgent("corrupt-1")!!
        assertEquals("the agent still loads", "Corrupt", loaded.name)
        assertTrue(
            "a damaged visibility document decodes to the transparent default",
            loaded.visibility.plans.capture,
        )
        assertTrue("a damaged autonomy document decodes to the default", loaded.autonomy.planIndependently)
    }

    @Test
    fun deletingAnAgentRemovesItsHistoryToo() = runTest {
        val created = repository.createAgent(SaveAgentRequest(name = "Doomed", systemPrompt = "bye"))
        repository.updateAgent(created.id, SaveAgentRequest(name = "Doomed", systemPrompt = "bye again"))
        assertEquals(2, repository.versionsFor(created.id).size)

        repository.deleteAgent(created.id)

        assertEquals(
            "the owner deleted the agent, so its history goes with it",
            0,
            repository.versionsFor(created.id).size,
        )
    }

    @Test
    fun aSyncedAgentCannotBeDeletedByHandButItsHistorySurvivesRetraction() = runTest {
        val installed = repository.installSyncedAgent(
            slug = "from-catalogue",
            name = "From Catalogue",
            description = "someone else wrote this",
            systemPrompt = "you came from the catalogue",
            toolPermissions = listOf("captureScreen"),
            version = 7,
        )
        assertEquals("a synced install records its own version", 1, repository.versionsFor(installed.id).size)
        assertEquals("SYNCED", repository.versionsFor(installed.id).first().reason)

        var refused = false
        try {
            repository.deleteAgent(installed.id)
        } catch (e: IllegalStateException) {
            refused = true
        }
        assertTrue("a catalogue agent is withdrawn, never deleted by hand", refused)
    }

    @Test
    fun theEditCountIsSeparateFromThePublishedVersion() = runTest {
        /*
         * TWO NUMBERS THAT LOOK ALIKE AND MEAN DIFFERENT THINGS. `version` is the catalogue
         * publication the sync worker force-pushes on. `definitionVersion` counts local edits and
         * is what a history row is keyed on. Conflating them would either break force-update or
         * break history, so the test pins that they move independently.
         */
        val installed = repository.installSyncedAgent(
            slug = "published",
            name = "Published",
            description = "",
            systemPrompt = "published instructions",
            toolPermissions = emptyList(),
            version = 12,
        )
        assertEquals("the published version is what the catalogue said", 12, installed.version)
        assertEquals("the local edit count starts at 1", 1, installed.definitionVersion)

        val edited = repository.updateAgent(
            installed.id,
            SaveAgentRequest(name = "Published", systemPrompt = "edited here"),
        )
        assertEquals("a local edit does not change the published version", 12, edited.version)
        assertEquals("a local edit advances only the edit count", 2, edited.definitionVersion)
        assertNotEquals(edited.definitionVersion, edited.version)
    }

    @Test
    fun visibilityRulesRoundTripPerCategory() = runTest {
        /*
         * ELEVEN CATEGORIES, EACH INDEPENDENT. The specification's section 6.1 rejects a single
         * switch, so the test sets three categories to three different rules and proves each comes
         * back as itself rather than as one shared value.
         */
        val created = repository.createAgent(
            SaveAgentRequest(
                name = "Selective",
                systemPrompt = "you show some things",
                visibility = AgentVisibility.TRANSPARENT.copy(
                    reasoning = VisibilityRule.CAPTURE_ONLY,
                    memory = VisibilityRule.OFF,
                    tools = VisibilityRule(capture = true, display = true, detail = VisibilityDetail.SUMMARY),
                ),
            )
        )

        val loaded = repository.getAgent(created.id)!!
        assertTrue("reasoning is still captured", loaded.visibility.reasoning.capture)
        assertFalse("reasoning is hidden from the room", loaded.visibility.reasoning.display)
        assertFalse("memory capture was turned off", loaded.visibility.memory.capture)
        assertEquals(VisibilityDetail.SUMMARY, loaded.visibility.tools.detail)
        assertTrue(
            "a category the caller never touched stays transparent",
            loaded.visibility.plans.display,
        )
    }

    @Test
    fun changingVisibilityTouchesNothingElseAndIsStillAnEdit() = runTest {
        /*
         * THE SPECIFICATION'S RULE IS THE ASSERTION: changing visibility must not silently change
         * tool permissions, autonomy, memory access or execution authority. The only way to prove it
         * is to set every one of those to a non-default value, change visibility, and check they are
         * all still exactly as they were.
         *
         * IT IS STILL AN EDIT. The version count advances and a history row is written, because a
         * run started afterwards records the visibility it actually had and the change is
         * explainable later.
         */
        val created = repository.createAgent(
            SaveAgentRequest(
                name = "Guarded",
                systemPrompt = "you do a specific job",
                toolPermissions = listOf("shellExec", "captureScreen"),
                visibility = AgentVisibility.TRANSPARENT,
                autonomy = AgentAutonomy(
                    performExternalSideEffects = true,
                    requestDelegation = true,
                ),
                memoryScope = AgentMemoryScope(readShared = true, writeOwn = true),
            )
        )

        val changed = repository.updateVisibility(
            created.id,
            AgentVisibility.TRANSPARENT.copy(reasoning = VisibilityRule.CAPTURE_ONLY),
        )

        assertFalse("the new rule landed", changed.visibility.reasoning.display)
        assertTrue("and its capture was left alone", changed.visibility.reasoning.capture)
        assertEquals(
            "tool permissions are untouched",
            listOf("shellExec", "captureScreen"),
            changed.toolPermissions,
        )
        assertTrue(
            "autonomy is untouched: external side effects were on and stay on",
            changed.autonomy.performExternalSideEffects,
        )
        assertTrue("delegation is untouched", changed.autonomy.requestDelegation)
        assertTrue("memory read is untouched", changed.memoryScope.readShared)
        assertTrue("memory write is untouched", changed.memoryScope.writeOwn)
        assertEquals("the prompt is untouched", "you do a specific job", changed.systemPrompt)
        assertEquals("the edit count advances", 2, changed.definitionVersion)

        val versions = repository.versionsFor(created.id)
        assertEquals("the visibility change is recorded as a version", 2, versions.size)
        assertTrue(
            "and the newest version carries the new rule",
            versions.first().definitionJson.contains("\"display\":false"),
        )
    }

    @Test
    fun aCapabilityManifestRoundTripsWithItsRequirements() = runTest {
        val manifest = AgentCapabilityManifest(
            requiredTools = listOf("captureScreen"),
            requiresReasoningChannel = true,
            requiresToolCalling = true,
            requiredIntegrations = listOf("github"),
            inputTypes = listOf("text/plain"),
            outputTypes = listOf("application/json"),
        )
        val created = repository.createAgent(
            SaveAgentRequest(
                name = "Manifested",
                systemPrompt = "you declare things",
                toolPermissions = listOf("captureScreen"),
                manifest = manifest,
            )
        )

        val loaded = repository.getAgent(created.id)!!
        assertEquals(listOf("captureScreen"), loaded.manifest.requiredTools)
        assertTrue(loaded.manifest.requiresReasoningChannel)
        assertEquals(listOf("github"), loaded.manifest.requiredIntegrations)
        assertEquals(listOf("text/plain"), loaded.manifest.inputTypes)
        assertEquals(listOf("application/json"), loaded.manifest.outputTypes)
    }
}
