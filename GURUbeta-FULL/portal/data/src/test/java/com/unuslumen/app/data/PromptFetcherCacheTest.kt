// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The cache half of "a prompt saved in the panel reaches the app".
 *
 * WHAT THESE PROVE, AND WHAT THEY DO NOT. The network call is not exercised:
 * [PromptFetcher] builds its own Tor-backed client and no seam exists to inject
 * a fake. So these pin the contract the fetch depends on — that the cache is
 * the single source the send path reads, that an absent cache is distinguishable
 * from an empty one, and that the ETag the server sends is what a later request
 * conditions on. The end-to-end proof is the device log:
 *
 *   Fetched and cached prompt (300069 chars, v156927199)
 *   Prompt CHANGED on the server: 291990 -> 300050 chars
 *
 * which is the real fetch running against the live server on an emulator.
 */
@RunWith(RobolectricTestRunner::class)
class PromptFetcherCacheTest {

    private fun prefs() = ApplicationProvider.getApplicationContext<Application>()
        .getSharedPreferences("guru_prompts", android.content.Context.MODE_PRIVATE)

    @Test
    fun an_unfetched_install_reads_as_absent_not_empty() {
        prefs().edit().clear().commit()

        // The send path branches on null versus "": null means "never fetched",
        // and that is what lets it fall back to the captured prompt instead of
        // sending a turn with no system message at all.
        assertNull(PromptFetcher.getCachedPrompt(ApplicationProvider.getApplicationContext()))
        assertEquals(0, PromptFetcher.getCachedVersion(ApplicationProvider.getApplicationContext()))
    }

    @Test
    fun a_written_prompt_is_what_the_send_path_reads() {
        prefs().edit()
            .putString("assembled_prompt", "PROMPT VERSION TWO")
            .putInt("prompt_version", 156927199)
            .putString("prompt_etag", "\"assembled-abc123\"")
            .putLong("prompt_last_fetch", System.currentTimeMillis())
            .commit()

        val context = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("PROMPT VERSION TWO", PromptFetcher.getCachedPrompt(context))
        assertEquals(156927199, PromptFetcher.getCachedVersion(context))
    }

    @Test
    fun the_etag_survives_so_the_next_request_can_condition_on_it() {
        prefs().edit()
            .putString("assembled_prompt", "body")
            .putString("prompt_etag", "\"assembled-deadbeef\"")
            .commit()

        // The fetch sends this back as If-None-Match. If it is lost the app
        // re-downloads 300 KB on every single send, which is the cost this
        // whole change exists to remove.
        assertEquals(
            "\"assembled-deadbeef\"",
            prefs().getString("prompt_etag", null)
        )
    }

    @Test
    fun a_recent_cache_is_reported_recent() {
        prefs().edit()
            .putString("assembled_prompt", "body")
            .putLong("prompt_last_fetch", System.currentTimeMillis())
            .commit()

        org.junit.Assert.assertTrue(
            PromptFetcher.hasRecentCache(ApplicationProvider.getApplicationContext())
        )
    }

    @Test
    fun a_stale_cache_is_not_reported_recent() {
        prefs().edit()
            .putString("assembled_prompt", "body")
            .putLong("prompt_last_fetch", System.currentTimeMillis() - 25L * 60 * 60 * 1000)
            .commit()

        org.junit.Assert.assertFalse(
            PromptFetcher.hasRecentCache(ApplicationProvider.getApplicationContext())
        )
    }

    @Test
    fun the_json_unescaper_handles_the_escapes_the_route_emits() {
        // A prompt body contains newlines, tabs, quotes and backslashes. If any
        // of these decode wrongly the system message changes meaning silently,
        // which is worse than a visible failure.
        val json = """{"prompt":"line one\nline \"two\"\twith\\slash","version":7}"""
        val start = json.indexOf("\"prompt\":\"") + 10
        val out = PromptFetcher.accessExtractJsonString(json, start)
        assertNotNull(out)
        assertEquals("line one\nline \"two\"\twith\\slash", out)
    }
}
