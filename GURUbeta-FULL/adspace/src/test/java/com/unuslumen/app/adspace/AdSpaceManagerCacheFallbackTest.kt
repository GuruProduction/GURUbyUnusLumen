// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.adspace

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The ad pack client is documented as failing closed: when Tor is not ready the
 * request is refused and the cache keeps the ad space showing content. That only
 * holds if the cache survives boot, so these tests pin the boot sequence to
 * "read the pack on disk, show it, then fetch and replace it on success".
 *
 * Issue 38: `initialize()` cleared the cache before it fetched, so every cold
 * boot destroyed the fallback it was meant to fall back to.
 */
class AdSpaceManagerCacheFallbackTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val cachedPack = AdPack(
        id = "cached-pack",
        version = 1,
        entries = listOf(AdEntry(id = "cached-entry", lottieData = "")),
    )

    private val freshPackJson = """
        {
          "id": "fresh-pack",
          "version": 2,
          "entries": [ { "id": "fresh-entry", "slogan": "hello" } ]
        }
    """.trimIndent()

    private fun newClient(engine: MockEngine): HttpClient = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private fun newCache(): AdPackCache = AdPackCache(File(temp.newFolder(), "adspace"))

    /** Runs the real boot sequence and returns the pack the ad space would render. */
    private fun boot(cache: AdPackCache, engine: MockEngine): AdPack? = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = AdSpaceManager(
            cache = cache,
            httpClient = newClient(engine),
            scope = scope,
        )
        try {
            manager.initialize().join()
            manager.pack
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a refused fetch keeps the cached pack instead of blanking the ad space`() {
        val cache = newCache()
        cache.savePack(cachedPack)

        val engine = MockEngine { throw IOException("Tor is not ready, refusing clearnet") }

        val pack = boot(cache, engine)

        assertEquals("cached-pack", pack?.id)
        assertTrue("a failed fetch must never delete the pack it falls back to", cache.hasCache())
    }

    @Test
    fun `a non-success response keeps the cached pack`() {
        val cache = newCache()
        cache.savePack(cachedPack)

        val engine = MockEngine { respond("upstream is unhappy", HttpStatusCode.InternalServerError) }

        val pack = boot(cache, engine)

        assertEquals("cached-pack", pack?.id)
        assertTrue(cache.hasCache())
    }

    @Test
    fun `a successful fetch replaces the cached pack in memory and on disk`() {
        val cache = newCache()
        cache.savePack(cachedPack)

        val engine = MockEngine { request ->
            if (request.url.encodedPath.startsWith("/api/adpack/entry-media")) {
                respond("", HttpStatusCode.OK)
            } else {
                respond(
                    freshPackJson,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }

        val pack = boot(cache, engine)

        assertEquals("fresh-pack", pack?.id)
        assertEquals("fresh-pack", cache.loadPack()?.id)
    }

    @Test
    fun `a first boot with no cache and no network stays empty without throwing`() {
        val cache = newCache()

        val engine = MockEngine { throw IOException("Tor is not ready, refusing clearnet") }

        assertNull(boot(cache, engine))
    }

    @Test
    fun `pruning keeps every file the new pack still references and drops the rest`() {
        val cache = newCache()
        val oldPack = AdPack(
            id = "old",
            version = 1,
            entries = listOf(
                AdEntry(
                    id = "old-entry",
                    lottieData = "",
                    elements = listOf(
                        AdElement(id = "old-element", type = "image", imageFileName = "a.gif"),
                    ),
                ),
            ),
        )
        cache.savePack(oldPack)
        cache.saveEntryLottie("old-entry", "{}".toByteArray())
        cache.saveElementLottie("old-element", "{}".toByteArray())
        cache.saveElementImage("old-element", byteArrayOf(1))
        cache.saveElementVideo("old-element", byteArrayOf(1))

        val newPack = AdPack(
            id = "new",
            version = 2,
            entries = listOf(
                AdEntry(
                    id = "new-entry",
                    lottieData = "",
                    elements = listOf(
                        AdElement(id = "new-element", type = "image", imageFileName = "b.gif"),
                    ),
                ),
            ),
        )
        cache.saveEntryLottie("new-entry", "{}".toByteArray())
        cache.saveElementImage("new-element", byteArrayOf(1))

        val pruned = cache.pruneUnreferencedMedia(newPack)

        assertEquals(
            "old entry lottie, old element lottie, old element image and old element video",
            4,
            pruned,
        )
        assertTrue("the new entry's lottie must survive", cache.hasEntryLottie("new-entry"))
        assertTrue("the new element's image must survive", cache.hasElementImage("new-element"))
        assertTrue("the old entry is unreferenced, so its lottie goes", !cache.hasEntryLottie("old-entry"))
        assertTrue(!cache.hasElementLottie("old-element"))
        assertTrue(!cache.hasElementImage("old-element"))
        assertTrue(!cache.hasElementVideo("old-element"))
    }

    @Test
    fun `a cached pack is on screen before the fetch is even attempted`() {
        val cache = newCache()
        cache.savePack(cachedPack)

        // A fetch that would fail proves the pack was shown from disk first:
        // the manager never had a successful fetch to learn the pack from.
        val engine = MockEngine { throw IOException("Tor is not ready, refusing clearnet") }

        val pack = boot(cache, engine)

        assertEquals(listOf("cached-entry"), pack?.entries?.map { it.id })
    }
}
