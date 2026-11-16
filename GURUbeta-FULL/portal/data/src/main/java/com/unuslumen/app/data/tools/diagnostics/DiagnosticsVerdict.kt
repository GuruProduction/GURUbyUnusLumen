// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.diagnostics

/**
 * What a vendor diagnostic command actually did, decided from its output rather than its exit code.
 *
 * WHY THIS IS A PURE OBJECT AND NOT A FEW IF STATEMENTS IN THE TOOL. Vendor diagnostic and
 * factory-test commands are the least standardised thing on Android. On a retail phone the
 * binary usually is not there, sometimes it is there and refuses, and on a vendor build it runs
 * and prints. Those three look identical to a caller that trusts an exit code, and two of them
 * are not the capability existing.
 *
 * So the decision is one function over plain values, provable on the JVM. On a device, a
 * mistaken verdict reads as the tool working when nothing happened, which is the exact class of
 * fault issue 44 shipped.
 *
 * THE THREE ANSWERS, AND WHY NONE OF THEM IS A FAILURE IN THE TOOL:
 *
 *   RAN            the command produced output and no refusal signature. Real data.
 *   ABSENT         the device does not carry the binary or the service at all. No grant and no
 *                  setup changes this, and the honest answer is about the device.
 *   REFUSED        the command exists and the platform declined to run it. That is a privilege
 *                  the build does not hold, and it is a different sentence from ABSENT.
 */
object DiagnosticsVerdict {

    /** How a diagnostic command's outcome is classified. */
    enum class Outcome {
        RAN,
        ABSENT,
        REFUSED,
    }

    /**
     * The lines that mean the platform refused, taken from what these commands actually print.
     *
     * Kept together and lower-cased at the comparison rather than duplicated per tool, because
     * one list drifting from another is how two tools start giving different answers about the
     * same refusal.
     */
    private val refusalSignatures = listOf(
        "permission denial",
        "permission denied",
        "not permitted",
        "requires permission",
        "requires the",
        "no permission",
        "operation not permitted",
        "securityexception",
    )

    /**
     * The lines that mean the thing is not on this device at all.
     *
     * "not found" and "inaccessible or not found" are what a shell prints for a binary that does
     * not exist, and "unknown option" or "usage:" means the binary is something else entirely,
     * such as a stub or the wrong tool with a similar name.
     */
    private val absenceSignatures = listOf(
        "not found",
        "no such file",
        "inaccessible or not found",
        "command not found",
        "unknown command",
        "unknown option",
        "does not exist",
    )

    /** How many of the command's output lines are worth keeping in a result. */
    const val OUTPUT_LINE_LIMIT = 60

    /**
     * Classify one command's outcome.
     *
     * [exitCode] is deliberately not the deciding input. These commands routinely exit zero
     * while printing a denial, and a shell prints 127 for a missing binary which is the same
     * number as some refusals, so the output is what carries the meaning. The exit code is
     * still reported because it is evidence a person may want.
     */
    fun classify(exitCode: Int, stdout: String, stderr: String): Verdict {
        val allOutput = (stdout + "\n" + stderr).trim()
        val lines = allOutput.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val refusal = lines.firstOrNull { line ->
            val lower = line.lowercase()
            refusalSignatures.any { lower.contains(it) }
        }
        if (refusal != null) {
            return Verdict(Outcome.REFUSED, lines, refusal)
        }

        val absence = lines.firstOrNull { line ->
            val lower = line.lowercase()
            absenceSignatures.any { lower.contains(it) }
        }
        if (absence != null) {
            return Verdict(Outcome.ABSENT, lines, absence)
        }

        // A missing binary usually prints nothing at all and exits 127. With no output there is
        // no signature to read, so the exit code is the only evidence left, and 127 is the shell
        // saying it could not find the command rather than that the command refused.
        if (lines.isEmpty() && exitCode == 127) {
            return Verdict(Outcome.ABSENT, emptyList(), null)
        }

        if (lines.isEmpty()) {
            // No output and no recognisable code: the command ran and said nothing, which is
            // not the same as the capability being present. Reported as refused rather than RAN,
            // because RAN would claim data that is not there.
            return Verdict(Outcome.REFUSED, emptyList(), null)
        }

        return Verdict(Outcome.RAN, lines, null)
    }

    /** The classification plus the evidence it was drawn from. */
    data class Verdict(
        val outcome: Outcome,
        val lines: List<String>,
        /** The line that decided it, when one did. */
        val decidingLine: String?,
        /**
         * A sentence for the person, written once here so every diagnostic tool phrases an
         * absence the same way instead of inventing its own.
         */
        val sentence: String? = null,
    )

    /**
     * The sentence for an absent or refused outcome, built once for every diagnostic tool.
     *
     * [what] is the tool's own subject, such as "the vendor diagnostic service". The sentence
     * has to make clear that on this device the answer is a fact about the device, not a step
     * the person has failed to take.
     */
    fun absenceSentence(what: String, outcome: Outcome, detail: String? = null): String = when (outcome) {
        Outcome.ABSENT -> buildString {
            append("This device does not carry ")
            append(what)
            append(", so there is nothing here to run. That is the device rather than a missing ")
            append("grant or a step you have not taken, and no setup changes it")
            append(detail?.let { ": $it" } ?: ".")
        }
        Outcome.REFUSED -> buildString {
            append("This device carries ")
            append(what)
            append(" and the platform declined to run it, which is a privilege this build does not ")
            append("hold rather than something you can turn on")
            append(detail?.let { ": $it" } ?: ".")
        }
        Outcome.RAN -> ""
    }
}
