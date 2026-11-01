// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

/**
 * Proving an endpoint actually said something, before anything is claimed about it.
 * Requirement 7 of the network control spec, from Steven's own live test.
 *
 * THE DEFECT THIS EXISTS TO REMOVE, found on his network on 2026-11-01: the Virgin TV
 * boxes at 192.168.0.50 and 192.168.0.154 answer HTTP 200 with the body "status=ok" to
 * EVERY path asked of them, including invented gibberish like /zzzznotreal. So a
 * capability check that treats a 200 as proof would report control of a device that
 * answers everything and does nothing.
 *
 * THE METHOD. Ask a nonsense path first and keep the answer as a baseline. Then ask the
 * real path and only trust the answer if it differs from that baseline. A device that
 * answers nonsense identically is a device whose replies carry no information, and that
 * is reported as exactly that rather than worked around.
 *
 * WHY THIS IS PURE AND SEPARATE. It is a comparison of two responses, so a JVM test can
 * prove the rule against the real strings his boxes returned, without a network and
 * without a device.
 */
object EndpointFingerprint {

    /**
     * A path no real device implements.
     *
     * Random enough that no firmware would have it, and stable so a baseline is
     * comparable between calls.
     */
    const val NONSENSE_PATH = "/zzzz-guru-probe-not-a-real-path-7f3a"

    /** What one endpoint answered, for comparing against the baseline. */
    data class Response(
        val statusCode: Int,
        /** The body, trimmed and capped, because only its shape matters. */
        val body: String,
        val contentType: String? = null,
    ) {
        /** A comparable form: status plus body, ignoring incidental whitespace. */
        fun signature(): String = "$statusCode|${body.trim()}"
    }

    /**
     * Whether a real path's answer is trustworthy, given what nonsense returned.
     *
     * Returns a [Verdict] that says either "this answer is real" or "this device answers
     * everything, so its replies prove nothing", with the evidence attached.
     */
    fun judge(realPath: String, real: Response, nonsense: Response): Verdict {
        val realSignature = real.signature()
        val nonsenseSignature = nonsense.signature()

        if (nonsense.statusCode == 0) {
            // The nonsense probe never answered, which means the device refuses unknown
            // paths at the socket level. That is the best case: every real answer is
            // informative because the device discriminates.
            return Verdict.Trustworthy(
                "The device refused $NONSENSE_PATH outright, so it discriminates between " +
                    "paths and its answer to $realPath is real.",
            )
        }

        if (realSignature == nonsenseSignature) {
            return Verdict.Uninformative(
                "The device answered $realPath with exactly what it answered the nonsense " +
                    "path $NONSENSE_PATH (HTTP ${real.statusCode}, body " +
                    "'${real.body.take(60)}'), so its replies carry no information. This is the " +
                    "fake-200 behaviour a Virgin TV box shows, and nothing about this device can " +
                    "be trusted from an HTTP status alone.",
            )
        }

        return Verdict.Trustworthy(
            "The device answered $realPath differently from the nonsense path " +
                "(HTTP ${real.statusCode} against ${nonsense.statusCode}), so the answer is real.",
        )
    }

    /** The outcome of a trust decision. */
    sealed interface Verdict {
        val sentence: String

        /** The answer differs from the baseline, so it is real evidence. */
        data class Trustworthy(override val sentence: String) : Verdict

        /**
         * The answer matches the nonsense baseline, so the device tells us nothing.
         *
         * This is NOT a failure of the probe: it is a true fact about the device, and it
         * is why a capability must never be inferred from a 200 alone.
         */
        data class Uninformative(override val sentence: String) : Verdict

        val trustworthy: Boolean get() = this is Trustworthy
    }
}
