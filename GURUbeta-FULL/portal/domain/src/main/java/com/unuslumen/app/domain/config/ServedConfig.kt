// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.config

/**
 * Whether a response body from a config endpoint may be cached (issue 519).
 *
 * THE DEFECT, in two halves that combined into silence.
 *
 * The server's `/api/llm-config` answered `503 {"error":"llm_config not seeded"}`
 * because the seeder had never written the row. The app's fetcher read the body,
 * never looked at the status, and cached it. `LlmConfig` has a default on every
 * field and the JSON parser ignores unknown keys, so the error page decoded
 * successfully into a config whose `baseModel` was `""`.
 *
 * The consequence surfaced a long way from the cause: `Init: Server returned empty
 * model name!` on every start, `llmModel` never built, every send refused. That
 * reads like a server data problem rather than a client that stored an error page,
 * which is why it took a fresh set of eyes to find.
 *
 * THE CODEBASE ALREADY WARNS ABOUT THIS SHAPE. A type whose every field defaults
 * will decode from any valid document, so the decoder silently accepts the wrong
 * one. The fix is not a stricter decoder: it is to check the thing that actually
 * carries the answer, which is the HTTP status, and to refuse a payload that has no
 * model in it whatever the status says.
 */
object ServedConfig {

    /** What to do with a response. */
    sealed interface Verdict {
        /** Cache this body. */
        data class Accept(val body: String) : Verdict

        /**
         * Do not cache this body, and keep whatever was cached before.
         *
         * `reason` is logged and surfaced, because a config that was refused is a
         * fact about the machine the owner may need.
         */
        data class Refuse(val reason: String) : Verdict
    }

    /**
     * Decide from the status and body alone.
     *
     * `previousCachedBody` is used only to say, in the log, whether a good config was
     * kept. It never turns a refusal into an acceptance: a 503 is a 503 whether or
     * not this device happens to hold an older working config.
     */
    fun verdict(statusCode: Int, body: String?): Verdict {
        if (statusCode !in 200..299) {
            return Verdict.Refuse(
                "the endpoint answered HTTP $statusCode, so its body is not config. " +
                    "The previously cached config is kept.",
            )
        }
        val trimmed = body?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return Verdict.Refuse(
                "the endpoint answered HTTP $statusCode with an empty body. " +
                    "The previously cached config is kept.",
            )
        }
        return Verdict.Accept(trimmed)
    }

    /**
     * Whether a decoded config is usable at all.
     *
     * A CONFIG WITH NO MODEL IS NOT A CONFIG. Every field on the type has a default,
     * so a body carrying no `base_model` decodes into a perfectly well-formed object
     * that cannot start a conversation. Refusing it here means the empty-model
     * failure is caught where it happened rather than four layers away at send time.
     *
     * The model name is checked for content, not merely for absence: the live
     * endpoint once carried `"base_model": ""`, and a whitespace-only name is the
     * same nothing wearing a value.
     */
    fun usableModelName(baseModel: String?): Boolean {
        val name = baseModel?.trim().orEmpty()
        return name.isNotEmpty()
    }
}
