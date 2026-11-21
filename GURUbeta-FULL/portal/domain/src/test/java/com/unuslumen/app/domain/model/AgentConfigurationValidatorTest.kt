// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The configuration validator, and the promises it exists to keep.
 *
 * Every test here guards a way the product could lie to its owner: an agent that says it will do
 * something it cannot, a setting that is accepted and then quietly ignored, or a refusal that
 * happens without saying why. Those are the failure modes the specification's section 6.2 forbids
 * by name, so they get tests rather than good intentions.
 */
class AgentConfigurationValidatorTest {

    private fun agent(
        tools: List<String> = emptyList(),
        manifest: AgentCapabilityManifest = AgentCapabilityManifest.EMPTY,
        autonomy: AgentAutonomy = AgentAutonomy.DEFAULT,
        modelPreference: AgentModelPreference = AgentModelPreference.INHERIT,
        skills: List<String> = emptyList(),
    ) = GuruAgent(
        id = "agent-1",
        slug = "tester",
        name = "Tester",
        description = "does tests",
        systemPrompt = "you test",
        toolPermissions = tools,
        source = GuruAgent.SOURCE_USER,
        createdAt = 0L,
        updatedAt = 0L,
        manifest = manifest,
        autonomy = autonomy,
        modelPreference = modelPreference,
        skillIds = skills,
    )

    private fun capabilities(
        tools: Set<String> = setOf("captureScreen", "phoneCall"),
        skills: Set<String> = setOf("research"),
        integrations: Set<String> = setOf("github"),
        reasoning: Boolean = true,
        toolCalling: Boolean = true,
        streaming: Boolean = true,
        schemaVersion: Int = 37,
        modelUnavailable: String? = null,
    ) = AgentConfigurationValidator.RuntimeCapabilities(
        availableTools = tools,
        availableSkills = skills,
        availableIntegrations = integrations,
        supportsReasoningChannel = reasoning,
        supportsToolCalling = toolCalling,
        supportsStreaming = streaming,
        runtimeSchemaVersion = schemaVersion,
        modelUnavailableReason = modelUnavailable,
    )

    @Test
    fun anAgentThatAsksForNothingValidatesCleanly() {
        val verdict = AgentConfigurationValidator.validate(agent(), capabilities())
        assertTrue("an empty manifest asks for nothing and must always run", verdict.canRun)
        assertTrue(
            "there is nothing to warn about, so a clean run must produce no findings at all",
            verdict.findings.isEmpty(),
        )
        assertNull(verdict.headline())
    }

    @Test
    fun aRequiredToolThatWasNotGrantedIsAnErrorNotAWarning() {
        /*
         * THIS IS THE LIE THE MANIFEST EXISTS TO PREVENT. An agent whose manifest says it needs
         * phoneCall, created without phoneCall granted, would refuse that call at runtime and
         * report a failure the owner would read as the tool being broken. Refusing to run is the
         * honest answer: the agent was configured in a way that cannot work.
         */
        val verdict = AgentConfigurationValidator.validate(
            agent(
                tools = listOf("captureScreen"),
                manifest = AgentCapabilityManifest(requiredTools = listOf("phoneCall")),
            ),
            capabilities(),
        )
        assertFalse(verdict.canRun)
        assertEquals(1, verdict.errors.size)
        assertTrue(verdict.errors.first().message.contains("phoneCall"))
        assertTrue(verdict.errors.first().message.contains("was not granted"))
    }

    @Test
    fun aRequiredToolThatDoesNotExistOnThisDeviceIsAnError() {
        val verdict = AgentConfigurationValidator.validate(
            agent(
                tools = listOf("ghostTool"),
                manifest = AgentCapabilityManifest(requiredTools = listOf("ghostTool")),
            ),
            capabilities(),
        )
        assertFalse(verdict.canRun)
        assertTrue(verdict.errors.any { it.message.contains("no such tool exists") })
    }

    @Test
    fun aGrantedAndExistingRequiredToolPasses() {
        val verdict = AgentConfigurationValidator.validate(
            agent(
                tools = listOf("captureScreen"),
                manifest = AgentCapabilityManifest(requiredTools = listOf("captureScreen")),
            ),
            capabilities(),
        )
        assertTrue(verdict.canRun)
        assertTrue(verdict.errors.isEmpty())
    }

    @Test
    fun aMissingReasoningChannelWarnsAndStillRuns() {
        /*
         * THE DISTINCTION THAT MATTERS MOST IN THIS FILE. A model without a reasoning channel does
         * not stop an agent working; it means the reasoning area will be empty. Refusing to run
         * would be wrong, and running silently would be the silent-preference failure. A warning
         * carries the difference to the owner and the run proceeds.
         */
        val verdict = AgentConfigurationValidator.validate(
            agent(manifest = AgentCapabilityManifest(requiresReasoningChannel = true)),
            capabilities(reasoning = false),
        )
        assertTrue("an unavailable reasoning channel must not block the work", verdict.canRun)
        assertEquals(1, verdict.warnings.size)
        assertTrue(verdict.warnings.first().message.contains("reasoning"))
        assertTrue(
            "the warning must promise the honest report, not merely note the absence",
            verdict.warnings.first().message.contains("will say so"),
        )
    }

    @Test
    fun missingToolCallingIsAnErrorBecauseTheAgentCouldNotWork() {
        val verdict = AgentConfigurationValidator.validate(
            agent(manifest = AgentCapabilityManifest(requiresToolCalling = true)),
            capabilities(toolCalling = false),
        )
        assertFalse(verdict.canRun)
        assertTrue(verdict.errors.any { it.message.contains("tool calling") })
    }

    @Test
    fun aModelThatIsNotConnectedRefusesTheRunWithTheReason() {
        val verdict = AgentConfigurationValidator.validate(
            agent(),
            capabilities(modelUnavailable = "no provider selected"),
        )
        assertFalse(verdict.canRun)
        assertTrue(verdict.errors.first().message.contains("no provider selected"))
    }

    @Test
    fun aManifestForANewerRuntimeIsRefusedRatherThanAttempted() {
        val verdict = AgentConfigurationValidator.validate(
            agent(manifest = AgentCapabilityManifest(minimumRuntimeSchemaVersion = 99)),
            capabilities(schemaVersion = 37),
        )
        assertFalse(verdict.canRun)
        assertTrue(verdict.errors.first().message.contains("99"))
        assertTrue(verdict.errors.first().message.contains("37"))
    }

    @Test
    fun anAbsentSkillWarnsAndNamesIt() {
        val verdict = AgentConfigurationValidator.validate(
            agent(skills = listOf("research", "not-installed")),
            capabilities(skills = setOf("research")),
        )
        assertTrue(verdict.canRun)
        assertEquals(1, verdict.warnings.size)
        assertTrue(verdict.warnings.first().message.contains("not-installed"))
    }

    @Test
    fun aDelegationSettingTheRuntimeCannotYetHonourIsReported() {
        /*
         * The runtime that enforces delegation lands in Phase 8. Until then the setting is
         * accepted because the configuration model must be complete, and the validator says
         * plainly that the request will be refused rather than silently ignored. That sentence is
         * the whole difference between an honest gap and a false control.
         */
        val verdict = AgentConfigurationValidator.validate(
            agent(autonomy = AgentAutonomy(requestDelegation = true)),
            capabilities(),
        )
        assertTrue(verdict.canRun)
        assertTrue(verdict.notes.any { it.message.contains("refused rather than silently ignored") })
    }

    @Test
    fun anUnconnectedIntegrationIsANoteAndNeverAnError() {
        val verdict = AgentConfigurationValidator.validate(
            agent(manifest = AgentCapabilityManifest(requiredIntegrations = listOf("slack"))),
            capabilities(integrations = setOf("github")),
        )
        assertTrue(verdict.canRun)
        assertEquals(1, verdict.notes.size)
        assertTrue(verdict.notes.first().message.contains("slack"))
    }

    @Test
    fun headlineReportsTheMostSevereFindingFirst() {
        val verdict = AgentConfigurationValidator.validate(
            agent(
                tools = emptyList(),
                manifest = AgentCapabilityManifest(
                    requiredTools = listOf("phoneCall"),
                    requiredIntegrations = listOf("slack"),
                ),
            ),
            capabilities(),
        )
        assertEquals(verdict.errors.first().message, verdict.headline())
    }

    @Test
    fun quietHoursHandleAWindowThatCrossesMidnight() {
        val notifications = AgentNotifications(quietFromMinute = 22 * 60, quietToMinute = 7 * 60)
        assertTrue("23:00 is inside 22:00 to 07:00", notifications.isQuietAt(23 * 60))
        assertTrue("02:00 is inside the window across midnight", notifications.isQuietAt(2 * 60))
        assertFalse("noon is outside it", notifications.isQuietAt(12 * 60))
        assertFalse("the window is half-open, so 07:00 is not quiet", notifications.isQuietAt(7 * 60))
    }

    @Test
    fun quietHoursThatWereNeverSetAreNeverQuiet() {
        assertFalse(AgentNotifications().isQuietAt(3 * 60))
    }

    @Test
    fun autonomyDefaultsPermitWorkAndRefuseTheOutsideWorld() {
        /*
         * THE DEFAULTS ARE A PRODUCT DECISION AND THEY GET A TEST. Planning, using granted tools,
         * and making artefacts are what an agent is created to do. Reaching outside the phone,
         * operating on a schedule and delegating are not, because those are the ones a person did
         * not ask for and would not expect.
         */
        val defaults = AgentAutonomy.DEFAULT
        assertTrue(defaults.planIndependently)
        assertTrue(defaults.useDeclaredToolsWithoutAsking)
        assertTrue(defaults.createOrModifyArtefacts)
        assertTrue(defaults.continueAfterRoomClosed)
        assertFalse("the outside world is opt-in", defaults.performExternalSideEffects)
        assertFalse("running unattended is opt-in", defaults.operateOnSchedules)
        assertFalse(defaults.requestDelegation)
    }

    @Test
    fun readOnlyAutonomyPermitsNothingThatChangesAnything() {
        val readOnly = AgentAutonomy.READ_ONLY
        assertFalse(readOnly.useDeclaredToolsWithoutAsking)
        assertFalse(readOnly.createOrModifyArtefacts)
        assertFalse(readOnly.proposeMemoryChanges)
        assertFalse(readOnly.performExternalSideEffects)
        assertFalse(readOnly.operateOnSchedules)
    }

    @Test
    fun visibilityDefaultsCaptureAndDisplayEveryCategory() {
        /*
         * THE TRANSPARENT DEFAULT, PROVED RATHER THAN ASSERTED IN A COMMENT. Every one of the
         * eleven categories must be captured and shown on a new agent, because an agent nobody can
         * see is an agent nobody can check, and the person who wants to hide something is the
         * exception rather than the rule.
         */
        val visibility = AgentVisibility.TRANSPARENT
        assertEquals("the specification names eleven categories", 11, visibility.asMap().size)
        VisibilityCategory.entries.forEach { category ->
            val rule = visibility.ruleFor(category)
            assertTrue("${category.name} must be captured by default", rule.capture)
            assertTrue("${category.name} must be displayed by default", rule.display)
            assertEquals(VisibilityDetail.FULL, rule.detail)
        }
    }

    @Test
    fun captureOnlyKeepsTheRecordWhileHidingIt() {
        val rule = VisibilityRule.CAPTURE_ONLY
        assertTrue("the record survives a display preference", rule.capture)
        assertFalse(rule.display)
    }

    @Test
    fun offIsTheOnlySettingThatLosesInformationAndItSaysSo() {
        val rule = VisibilityRule.OFF
        assertFalse(rule.capture)
        assertFalse(rule.display)
        assertEquals(VisibilityDetail.SUMMARY, rule.detail)
    }

    @Test
    fun aDefinitionSnapshotCarriesTheValuesRatherThanAVersionNumber() {
        /*
         * THE SNAPSHOT IS WHAT MAKES A HISTORICAL RUN EXPLAINABLE. If it carried only a version,
         * reading it would depend on the history table still holding that row. Carrying the values
         * means an old run can always say what it was given.
         */
        val original = agent(
            tools = listOf("captureScreen"),
            manifest = AgentCapabilityManifest(requiredTools = listOf("captureScreen")),
            autonomy = AgentAutonomy(planIndependently = false),
        )
        val snapshot = original.definitionSnapshot()

        assertEquals(original.systemPrompt, snapshot.systemPrompt)
        assertEquals(original.toolPermissions, snapshot.toolPermissions)
        assertFalse(snapshot.autonomy.planIndependently)

        // Editing the agent afterwards must not reach back into the snapshot already taken.
        val edited = original.copy(
            systemPrompt = "different instructions",
            toolPermissions = listOf("phoneCall"),
            autonomy = AgentAutonomy.DEFAULT,
        )
        assertEquals("the snapshot is a value, not a live view", "you test", snapshot.systemPrompt)
        assertEquals(listOf("captureScreen"), snapshot.toolPermissions)
        assertFalse(snapshot.autonomy.planIndependently)
        assertEquals("different instructions", edited.systemPrompt)
    }

    @Test
    fun derivedManifestAsksForToolCallingOnlyWhenToolsExist() {
        val noTools = AgentConfigurationValidator.deriveManifest(agent(tools = emptyList()))
        assertFalse("an agent with no tools does not need tool calling", noTools.requiresToolCalling)

        val withTools = AgentConfigurationValidator.deriveManifest(agent(tools = listOf("captureScreen")))
        assertTrue(withTools.requiresToolCalling)

        assertTrue(
            "a derived manifest must not invent requirements the agent never declared",
            noTools.requiredTools.isEmpty(),
        )
    }
}
