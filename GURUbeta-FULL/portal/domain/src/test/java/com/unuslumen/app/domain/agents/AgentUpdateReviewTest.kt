// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An update must not silently grant new permissions.
 *
 * THE SPECIFICATION'S SECTION 12.2, AS A TEST. An agent that arrives from a catalogue holds real
 * tools on a real phone, and a publisher adding a capability between versions is the exact channel
 * by which somebody ends up with an agent that can do something they never agreed to. Each test
 * below guards one step of that: the diff is computed correctly, a new tool stops the update, a
 * removed tool does not, and a number cannot be used to smuggle a grant past the check.
 */
class AgentUpdateReviewTest {

    private fun review(
        installedTools: Set<String> = setOf("captureScreen"),
        installedPrompt: String = "you research",
        installedDescription: String = "reads things",
        installedName: String = "Researcher",
        installedVersion: Int = 3,
        publishedTools: Set<String> = setOf("captureScreen"),
        publishedPrompt: String = "you research",
        publishedDescription: String = "reads things",
        publishedName: String = "Researcher",
        publishedVersion: Int = 4,
    ) = AgentUpdateReview.between(
        installedTools = installedTools,
        installedPrompt = installedPrompt,
        installedDescription = installedDescription,
        installedName = installedName,
        installedVersion = installedVersion,
        publishedTools = publishedTools,
        publishedPrompt = publishedPrompt,
        publishedDescription = publishedDescription,
        publishedName = publishedName,
        publishedVersion = publishedVersion,
        slug = "researcher",
    )

    @Test
    fun anIdenticalUpdateSaysNothing() {
        val unchanged = review(publishedVersion = 3)
        assertFalse(unchanged.hasChanges)
        assertNull("a silent update must not render a box", unchanged.headline())
        assertTrue(
            "nothing changed, so it applies without asking",
            AgentUpdateReview.dispositionFor(unchanged) is Disposition.Silent,
        )
    }

    @Test
    fun aNewToolStopsTheUpdateForApproval() {
        /*
         * THE WHOLE POINT. Version 4 of this agent asks for sendSms, which version 3 did not. That
         * is a new permission on the person's phone and it must be a yes, not a download.
         */
        val grown = review(
            installedTools = setOf("captureScreen"),
            publishedTools = setOf("captureScreen", "sendSms"),
        )

        assertTrue(grown.hasChanges)
        assertEquals(setOf("sendSms"), grown.addedTools)
        assertTrue(grown.needsApproval)

        val disposition = AgentUpdateReview.dispositionFor(grown)
        assertTrue(disposition is Disposition.Ask)
        assertEquals(grown, (disposition as Disposition.Ask).review)
    }

    @Test
    fun theHeadlineNamesTheNewPermissionsExactly() {
        val grown = review(
            installedTools = setOf("captureScreen"),
            publishedTools = setOf("captureScreen", "sendSms", "phoneCall"),
        )
        val headline = grown.headline()!!
        assertTrue("it says how many: $headline", headline.contains("2 new permissions"))
        assertTrue("and names them", headline.contains("phoneCall"))
        assertTrue(headline.contains("sendSms"))
    }

    @Test
    fun aRemovedToolDoesNotNeedApproval() {
        /*
         * REMOVING A TOOL MAKES AN AGENT WEAKER, which is never a surprise worth stopping for. If
         * every narrowing update required a yes, people would learn to tap through the prompt that
         * exists for the dangerous direction.
         */
        val narrowed = review(
            installedTools = setOf("captureScreen", "sendSms"),
            publishedTools = setOf("captureScreen"),
        )

        assertTrue(narrowed.hasChanges)
        assertEquals(setOf("sendSms"), narrowed.removedTools)
        assertFalse(narrowed.needsApproval)
        assertTrue(
            AgentUpdateReview.dispositionFor(narrowed) is Disposition.Silent,
        )
        assertNotNull("but it is still visible if somebody looks", narrowed.headline())
    }

    @Test
    fun aPromptChangeIsRecordedButNotGated() {
        val reworded = review(publishedPrompt = "you research very carefully")
        assertTrue(reworded.promptChanged)
        assertFalse("a reworded prompt is not a new permission", reworded.needsApproval)
        assertTrue(reworded.headline()!!.contains("instructions"))
    }

    @Test
    fun aDescriptionChangeIsRecorded() {
        val described = review(publishedDescription = "reads things carefully")
        assertTrue(described.descriptionChanged)
        assertFalse(described.needsApproval)
    }

    @Test
    fun aRenameIsItsOwnKindOfChange() {
        /*
         * A RENAME IS HOW A PUBLISHER IMPERSONATES ANOTHER AGENT. It does not grant a permission, so
         * it does not gate, but it is named separately because "Coding Agent" becoming "Archivist"
         * is a different act from a description tweak.
         */
        val renamed = review(publishedName = "Archivist")
        assertTrue(renamed.identityChanged)
        assertTrue(renamed.hasChanges)
        assertTrue(
            "the headline says what it was called and what it is called now: ${renamed.headline()}",
            renamed.headline()!!.contains("Researcher") && renamed.headline()!!.contains("Archivist"),
        )
    }

    @Test
    fun aWhitespaceOnlyPromptEditStillCounts() {
        /*
         * COMPARED EXACTLY. A false positive here costs a line of text; a false negative hides a
         * behaviour change. Erring toward telling the person is the right direction for a prompt
         * that governs what an agent does with real tools.
         */
        val nudged = review(publishedPrompt = "you research ")
        assertTrue(nudged.promptChanged)
    }

    @Test
    fun aVersionNumberCannotSmuggleAGrantPastTheCheck() {
        /*
         * THE ATTACK THE DESIGN EXISTS TO STOP. A "rollback" to a lower version that carries a new
         * tool is not a rollback. The disposition looks at the tool set, not the numbers, so the
         * older label buys nothing.
         */
        val disguised = review(
            installedTools = setOf("captureScreen"),
            installedVersion = 9,
            publishedTools = setOf("captureScreen", "clearAppData"),
            publishedVersion = 2,
        )

        assertTrue(disguised.needsApproval)
        assertTrue(
            "a lower version with more tools still asks",
            AgentUpdateReview.dispositionFor(disguised) is Disposition.Ask,
        )
    }

    @Test
    fun theReviewCarriesBothVersionsAndTheSlug() {
        val update = review(installedVersion = 3, publishedVersion = 7)
        assertEquals("researcher", update.slug)
        assertEquals(3, update.installedVersion)
        assertEquals(7, update.publishedVersion)
    }

    @Test
    fun anAddedAndRemovedPairIsReportedAsBoth() {
        /*
         * A SWAP IS NOT A REMOVAL. If an agent stops using shellExec and starts using phoneCall, the
         * person has lost one capability and gained another, and the added half still gates.
         */
        val swapped = review(
            installedTools = setOf("shellExec"),
            publishedTools = setOf("phoneCall"),
        )
        assertEquals(setOf("phoneCall"), swapped.addedTools)
        assertEquals(setOf("shellExec"), swapped.removedTools)
        assertTrue(swapped.needsApproval)
    }

    @Test
    fun goingFromNoToolsToSomeToolsIsGated() {
        val firstGrant = review(installedTools = emptySet(), publishedTools = setOf("captureScreen"))
        assertTrue(firstGrant.needsApproval)
        assertTrue(firstGrant.headline()!!.contains("1 new permission"))
    }

    @Test
    fun theSingularAndPluralReadCorrectly() {
        val one = review(installedTools = emptySet(), publishedTools = setOf("captureScreen"))
        assertTrue(
            "one new permission, not permissions: ${one.headline()}",
            one.headline()!!.contains("1 new permission:") ||
                one.headline()!!.contains("1 new permission"),
        )
        assertFalse(one.headline()!!.contains("1 new permissions"))
    }
}
