// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URI
import java.net.ProtocolException

/**
 * The model clients must never inherit the clearnet override.
 *
 * WHY THIS TEST EXISTS AT ALL. When the override was added, [TorEgress.selector] was changed to
 * consult the posture, and that selector is used by the AI repository to build EVERY model
 * client. So setting clearnet would have sent the owner's API key and his entire conversation
 * direct, which is the worst leak the architecture could produce, and no existing test would have
 * caught it because none of them exercise the model path.
 *
 * The fix is that the selector honours the override only when a caller asks for it. This holds
 * that default, because a safety property that lives only in a comment is a safety property a
 * later refactor deletes.
 *
 * There is no Koin container under test, so no TorManager, so no circuit: that is exactly the
 * condition these tests need. A public host must THROW rather than quietly going direct.
 */
class TorEgressSelectorTest {

    @Test
    fun aDefaultSelectorRefusesAPublicHostWithNoCircuit() {
        val selector = TorEgress.selector()
        try {
            selector.select(URI("https://api.unuslumen.com/"))
            fail(
                "A default TorEgress selector returned a route for a public host with no circuit. " +
                    "That is the leak this test exists to prevent.",
            )
        } catch (expected: ProtocolException) {
            assertTrue(
                "the refusal must name the host: ${expected.message}",
                expected.message.orEmpty().contains("api.unuslumen.com"),
            )
        }
    }

    @Test
    fun anOptedInSelectorStillRefusesAPublicHostWithNoCircuit() {
        // Clearnet is NOT set here: the default posture is AUTO, which fails closed. Opting in
        // only means "may honour the override", never "may go direct regardless".
        val selector = TorEgress.selector(honoursClearnet = true)
        try {
            selector.select(URI("https://example.com/"))
            fail("AUTO with no circuit must refuse a public host rather than route it direct.")
        } catch (expected: ProtocolException) {
            assertTrue(
                "the refusal must name the way out: ${expected.message}",
                expected.message.orEmpty().contains("/clearnet"),
            )
        }
    }

    @Test
    fun aLoopbackHostIsDirectWithNoTorAtAll() {
        // The user's own Ollama listens on loopback, and a local model is not public traffic.
        // If this ever throws, every local model endpoint stops working.
        val selector = TorEgress.selector()
        val chosen = selector.select(URI("http://127.0.0.1:11434/api/chat"))
        assertEquals(1, chosen.size)
        assertNull("loopback must be a direct connection, not a proxy", chosen[0].address())
    }

    @Test
    fun aLocalNameIsDirectWithNoTorAtAll() {
        val selector = TorEgress.selector()
        val chosen = selector.select(URI("http://printer.local/status"))
        assertEquals(1, chosen.size)
        assertNull("a .local name is a LAN device, so it must be direct", chosen[0].address())
    }
}
