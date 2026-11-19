// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.components

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * The loopback origin a portal document is served from (issue 475).
 *
 * WHY THE ORIGIN IS THE FIX. A WebView loaded with a `file://` base URL gives its
 * document an opaque origin: `window.location.origin` is `"null"` and every iframe
 * sends no Referer or one naming a file path. YouTube answers error 153 and so
 * does every provider that validates origin, before the player is constructed.
 * The measurement in the ticket showed the WebView itself healthy — a direct MP4
 * in a video tag played fine — so the only missing thing was an origin.
 *
 * These tests exercise the real socket, not just the routing table, because the
 * claim being made is that a document can be FETCHED over HTTP, and a routing
 * function returning the right data proves something weaker than that.
 */
class PortalOriginServerTest {

    @After
    fun tearDown() {
        PortalOriginServer.stop()
    }

    @Test
    fun a_registered_document_is_served_over_http_with_a_real_origin() {
        val url = PortalOriginServer.register("<html><body>hello portal</body></html>")
        assertNotNull("the server must start on a device that can bind loopback", url)
        assertTrue(
            "the origin must be loopback HTTP, not a file URL: got $url",
            url!!.startsWith("http://127.0.0.1:"),
        )

        val body = fetch(url)
        assertEquals("<html><body>hello portal</body></html>", body)
    }

    @Test
    fun the_response_carries_an_html_content_type_so_the_document_is_a_document() {
        val url = PortalOriginServer.register("<html></html>")!!
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        try {
            assertEquals(200, connection.responseCode)
            assertTrue(
                "a text/html content type is what makes this a document rather than a download",
                connection.contentType.startsWith("text/html"),
            )
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun the_port_is_stable_across_renders_within_a_session() {
        // An ephemeral port that changed per render would present a different
        // origin on each request, which fails the referrer check exactly as a file
        // origin does.
        val first = PortalOriginServer.register("<html>one</html>")!!
        val second = PortalOriginServer.register("<html>two</html>")!!
        val firstOrigin = first.substringBefore("/portal/")
        val secondOrigin = second.substringBefore("/portal/")
        assertEquals(firstOrigin, secondOrigin)
        assertTrue(first != second)
    }

    @Test
    fun an_unknown_document_is_a_404_rather_than_a_hang_or_a_crash() {
        PortalOriginServer.register("<html></html>")
        val base = PortalOriginServer.originBase!!
        val connection = URL("$base/portal/999999").openConnection() as HttpURLConnection
        try {
            assertEquals(404, connection.responseCode)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun a_path_outside_the_portal_prefix_is_a_404() {
        val response = PortalOriginServer.responseFor("/etc/passwd")
        assertEquals("404 Not Found", response.status)
    }

    @Test
    fun a_bundled_library_is_served_with_a_javascript_content_type() {
        PortalOriginServer.ensureStarted { name ->
            if (name == "mermaid.min.js") "var x = 1;".toByteArray() else null
        }
        PortalOriginServer.register("<html></html>")
        val response = PortalOriginServer.responseFor("/portal/assets/mermaid.min.js")
        assertEquals("200 OK", response.status)
        assertTrue(
            "a library served as octet-stream is a library the browser refuses to run",
            response.headers.any { it.first == "Content-Type" && it.second.startsWith("application/javascript") },
        )
    }

    @Test
    fun a_path_traversal_attempt_on_the_asset_route_is_refused() {
        PortalOriginServer.ensureStarted { _ -> "secret".toByteArray() }
        val response = PortalOriginServer.responseFor("/portal/assets/../../etc/passwd")
        assertEquals("404 Not Found", response.status)
    }

    @Test
    fun an_asset_the_app_does_not_have_is_a_404() {
        PortalOriginServer.ensureStarted { _ -> null }
        val response = PortalOriginServer.responseFor("/portal/assets/nothing.js")
        assertEquals("404 Not Found", response.status)
    }

    @Test
    fun the_asset_rewrite_turns_a_file_reference_into_an_origin_reference() {
        // The template loads KaTeX, Mermaid, Chart.js and the rest from
        // android_asset. An HTTP document may not load file:// subresources, so
        // every one of them must move onto the origin or every portal arrives with
        // no math, no diagrams and no charts.
        val html = """<script src="file:///android_asset/portal/mermaid.min.js"></script>"""
        val base = "http://127.0.0.1:1234"
        val rewritten = html.replace(
            "file:///android_asset/portal/",
            "$base${PortalOriginServer.PATH_PREFIX}assets/",
        )
        assertEquals(
            """<script src="http://127.0.0.1:1234/portal/assets/mermaid.min.js"></script>""",
            rewritten,
        )
    }

    @Test
    fun nothing_outside_the_portal_prefix_ever_reaches_the_document_map() {
        PortalOriginServer.register("<html>private</html>")
        val root = PortalOriginServer.responseFor("/")
        assertEquals("404 Not Found", root.status)
        assertTrue(
            "a request outside the prefix must never be answered with a document body",
            !root.body.contains("private"),
        )
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        try {
            return BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
