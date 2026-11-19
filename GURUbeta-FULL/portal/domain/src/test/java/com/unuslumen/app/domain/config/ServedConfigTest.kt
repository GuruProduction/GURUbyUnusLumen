// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What may be cached as served config (issue 519).
 *
 * THE DEFECT. The endpoint answered `503 {"error":"llm_config not seeded"}` because
 * the seeder had never written the row. The fetcher read the body, never looked at
 * the status, and cached it. Every field of `LlmConfig` has a default and the parser
 * ignores unknown keys, so the error page decoded cleanly into a config with an
 * empty model. The failure then appeared four layers away as "Server returned empty
 * model name", which reads like a server problem rather than a client that stored an
 * error page.
 */
class ServedConfigTest {

    @Test
    fun a_503_is_refused_whatever_it_says() {
        val verdict = ServedConfig.verdict(
            statusCode = 503,
            body = """{"error":"llm_config not seeded"}""",
        )
        assertTrue("a 503 must never become stored config: $verdict", verdict is ServedConfig.Verdict.Refuse)
        assertTrue(
            "the reason must name the status: $verdict",
            (verdict as ServedConfig.Verdict.Refuse).reason.contains("503"),
        )
    }

    @Test
    fun every_non_2xx_status_is_refused() {
        listOf(301, 400, 401, 403, 404, 418, 500, 502, 504).forEach { code ->
            assertTrue(
                "HTTP $code must be refused",
                ServedConfig.verdict(code, """{"base_model":"x"}""") is ServedConfig.Verdict.Refuse,
            )
        }
    }

    @Test
    fun a_2xx_with_a_real_body_is_accepted() {
        val verdict = ServedConfig.verdict(
            statusCode = 200,
            body = """{"base_model":"glm-5.3-flash:cloud","context_window":1000000}""",
        )
        assertTrue(verdict is ServedConfig.Verdict.Accept)
    }

    @Test
    fun a_2xx_with_an_empty_body_is_refused() {
        // A 200 carrying nothing is still nothing, and caching it would overwrite a
        // working config with an empty string.
        assertTrue(ServedConfig.verdict(200, "") is ServedConfig.Verdict.Refuse)
        assertTrue(ServedConfig.verdict(200, "   ") is ServedConfig.Verdict.Refuse)
        assertTrue(ServedConfig.verdict(200, null) is ServedConfig.Verdict.Refuse)
    }

    @Test
    fun a_config_with_no_model_is_not_usable() {
        // THE SECOND HALF OF THE SAME FAILURE. A payload that parses but names no
        // model is a config that cannot start a conversation. Refusing it here catches
        // the empty-model failure where it happened rather than at send time.
        assertFalse(ServedConfig.usableModelName(""))
        assertFalse(ServedConfig.usableModelName("   "))
        assertFalse(ServedConfig.usableModelName(null))
        assertTrue(ServedConfig.usableModelName("glm-5.3-flash:cloud"))
        assertTrue(
            "trailing whitespace must not hide a real model",
            ServedConfig.usableModelName("  glm-5.3-flash:cloud  "),
        )
    }

    @Test
    fun the_accepted_body_is_trimmed_so_a_stray_newline_is_not_cached() {
        val verdict = ServedConfig.verdict(200, "\n  {\"base_model\":\"x\"}  \n") as ServedConfig.Verdict.Accept
        assertTrue(verdict.body.startsWith("{"))
        assertTrue(verdict.body.endsWith("}"))
    }
}
