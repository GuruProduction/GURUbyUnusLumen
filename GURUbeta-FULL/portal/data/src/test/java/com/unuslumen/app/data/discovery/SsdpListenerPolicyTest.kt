// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.discovery

import com.unuslumen.app.data.tools.registry.ToolRead
import com.unuslumen.app.data.tools.registry.ToolReads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 332's definition of done, held against the SSDP listener's five states.
 *
 * The family's defect is a tool answering `success: true` with an empty list when the underlying
 * read or bind never happened. This pins the rule for the tool that was missed.
 */
class SsdpListenerPolicyTest {

    /**
     * The same mapping the executor uses, CALLING THE PRODUCTION FUNCTION.
     *
     * That is the whole point: the first version of this test restated the rule inline, so
     * breaking the copy proved nothing about the product. `SsdpDiscoveryManager.answered` is what
     * the executor calls, so a test that passes here is a test about the shipped behaviour.
     */
    private fun readFor(state: ListenerState, devices: List<String>): ToolRead<String> =
        ToolReads.fromShell(
            succeeded = SsdpDiscoveryManager.answered(state),
            items = devices,
            tier = "APP_UID",
            refusalReason = "The SSDP multicast socket never bound.",
            remedy = "Check that wifi is connected.",
        )

    @Test
    fun aSocketThatNeverBoundIsARefusalNotAnEmptyNetwork() {
        val read = readFor(ListenerState.BIND_FAILED, emptyList())
        assertTrue("nothing was listened for, so this is a refusal", read.refused)
        assertFalse("and it must not read as an answered question", read.answered)
    }

    @Test
    fun aScanThatNeverStartedIsARefusal() {
        val read = readFor(ListenerState.STOPPED, emptyList())
        assertTrue("a scan that was never started has heard nothing by definition", read.refused)
    }

    @Test
    fun aLiveSocketThatHeardNothingIsARealAnswer() {
        val read = readFor(ListenerState.LISTENING, emptyList())
        assertEquals(
            "a bound socket on a quiet network is a measurement about the wifi",
            ToolRead.Empty,
            read,
        )
        assertTrue("and it IS an answer", read.answered)
    }

    @Test
    fun devicesAreFound() {
        val read = readFor(ListenerState.FOUND, listOf("uuid:one", "uuid:two"))
        assertEquals(2, read.values?.size)
        assertTrue(read.found)
    }

    /**
     * PARTIAL IS THE SUBTLE ONE, and it is deliberately NOT treated as a listener that answered.
     *
     * It means datagrams arrived but none parsed into a device, which is a fault in the parse
     * rather than a fact about the network. Reporting an empty list there would be the same lie in
     * the other direction: something answered and the tool threw it away.
     */
    @Test
    fun trafficThatParsedIntoNothingIsNotAnEmptyNetwork() {
        val read = readFor(ListenerState.PARTIAL, emptyList())
        assertTrue(
            "traffic arrived and produced no device, so this is not a quiet network",
            read.refused,
        )
    }

    @Test
    fun theListenerStateVocabularyMatchesTheOtherDiscoveryTool() {
        // One set of words across the family, so a model reading two tools is not translating.
        val names = ListenerState.values().map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("STOPPED", "BIND_FAILED", "PARTIAL", "LISTENING", "FOUND")))
    }
}
