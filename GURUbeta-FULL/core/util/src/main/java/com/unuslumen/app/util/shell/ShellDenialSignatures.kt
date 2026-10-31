// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

/**
 * The permission-denial signatures a command can print while still exiting 0,
 * which of them mean OUR command was refused, and what each one means for a
 * caller at a given [ShellTier].
 *
 * THE DEFECT THIS EXISTS FOR (issue 44). `pm dump`, `dumpsys` and anything else
 * that asks a system service for data report a refusal on STDOUT and exit 0:
 *
 *   DUMP OF SERVICE package:
 *   Permission Denial: can't dump PackageManager from from pid=19342, uid=10332
 *   due to missing android.permission.DUMP permission
 *
 * Every call site decided success from exitCode and stderr, so a total denial
 * was reported `success: true` with empty stderr, no error and exit 0. The
 * "DUMP OF SERVICE ..." headers made the denial body look structurally like a
 * real dump, so a reader could summarise six refusals as six services. And
 * DUMP is signature-level: a normal app can never hold it, at any point, on any
 * device, so there was nothing to retry and nothing to fix.
 *
 * WHY DETECTION LIVES HERE, at the executor boundary, instead of inside pmDump:
 * `dumpsys`, `settings`, netlink readers and every future binary that refuses
 * on stdout have the same shape, and one change has to fix them all.
 *
 * TWO CLASSES, DELIBERATELY. A word search alone creates the opposite lie: a
 * successful `logcat -d`, a `dmesg`, or a `grep` across a tree can contain
 * "Permission denied" thousands of times because OTHER processes logged it.
 * Marking those runs failed would be the same dishonesty as issue 44 aimed the
 * other way. The distinguishing property is not the words, it is WHO the
 * refusal is about, and the shape shows it: a service answers its caller with a
 * line that IS the refusal, and a log quotes someone else's refusal inside a
 * timestamped record.
 *
 *  - COMMAND_LEVEL refusals mean the command we just ran did not do its work,
 *    so [apply] sets `success = false`.
 *  - INCIDENTAL mentions are text the command produced, possibly about someone
 *    else, so they do NOT flip success. They are surfaced on
 *    [ShellResult.advisory] so a caller who wants to know still can.
 *
 * The rule deliberately does NOT discard the observation. Some tools grep for
 * the words "Permission denied" in another program's output, so the exit code
 * and both streams are preserved on the result and only `success` changes;
 * [ShellResult.denial] says why. Losing the text would make the honest failure
 * as useless as the false success it replaces.
 *
 * THE LIMIT, STATED PLAINLY. Text matching cannot prove whose denial it read. A
 * log that reproduces a verbatim platform denial line at the start of one of its
 * own lines would still be read as a refusal of our command. The line-start
 * shape and the log-record rejection below make that unlikely rather than
 * impossible, and the exit code and both streams remain on the result as the
 * evidence a caller can fall back to.
 *
 * Deliberately pure and free of Android imports, so the rule is JVM-testable
 * with no Robolectric and no device. [ShellTier] is a plain enum in this
 * package, and its `capabilities()` wording is reused rather than reinvented so
 * a denial cannot be described in terms the tier does not actually promise.
 */
object ShellDenialSignatures {

    /** Which question a signature answers. */
    enum class DenialKind {
        /** The command we just ran was itself refused, so the run failed. */
        COMMAND_LEVEL,
        /** A refusal appears in the text the command produced, not necessarily ours. */
        INCIDENTAL,
    }

    /**
     * One signature, its class, and what it means in the words a caller reads
     * when it matches.
     */
    data class DenialSignature(
        /** The literal text searched for, matched case-insensitively. */
        val text: String,
        /** Whether this refusal is about our own command. */
        val kind: DenialKind,
        /** The clause that follows the signature in a caller-facing explanation. */
        val meaning: String,
    )

    /** The dumpsys and pm-dump form, matched by shape rather than by prefix alone. */
    private const val PERMISSION_DENIAL = "Permission Denial:"

    private const val NETLINK_REFUSAL = "Cannot bind netlink socket"

    /** The signature-level permission `dumpsys` and `pm dump` demand, and no app can hold. */
    private const val DUMP_PERMISSION = "android.permission.DUMP"

    /**
     * A log record's own prefix, e.g. `10-31 06:31:12.345`. A line starting with
     * one is a quoted refusal from some other process, not an answer to us.
     */
    private val LOG_RECORD_PREFIX = Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""")

    /**
     * Every signature, command-level first so a document containing both is
     * reported by the one that matters.
     *
     * "Permission Denial:" and "Cannot bind netlink socket" are command-level
     * because a platform service emits the former only about its caller and the
     * kernel emits the latter only to the process that asked for the socket;
     * both are matched only where they BEGIN a line, which is the shape of an
     * answer to us rather than a mention inside one.
     *
     * Everything else is incidental: those strings are equally at home in
     * another process's log line, so alone they cannot justify calling our run a
     * failure. "requires android.permission." is the tail of the platform's own
     * denial line, so it is covered by the "Permission Denial:" case above and
     * is listed here only so a caller scanning [detect] sees it.
     */
    val SIGNATURES: List<DenialSignature> = listOf(
        DenialSignature(
            text = PERMISSION_DENIAL,
            kind = DenialKind.COMMAND_LEVEL,
            meaning = "means a system service refused the dump because the caller lacks the " +
                "signature permission it demands (DUMP for dumpsys and pm dump), and no " +
                "normal app can ever hold that",
        ),
        DenialSignature(
            text = NETLINK_REFUSAL,
            kind = DenialKind.COMMAND_LEVEL,
            meaning = "means the kernel will not give this process a netlink socket, so the " +
                "network state the command wanted is unreachable here",
        ),
        DenialSignature(
            text = "Permission denied",
            kind = DenialKind.INCIDENTAL,
            meaning = "means access to some file or resource was refused, though not " +
                "necessarily for this command",
        ),
        DenialSignature(
            text = "java.lang.SecurityException",
            kind = DenialKind.INCIDENTAL,
            meaning = "means a SecurityException was thrown by or reported to some process, " +
                "though not necessarily for this command",
        ),
        DenialSignature(
            text = "requires android.permission.",
            kind = DenialKind.INCIDENTAL,
            meaning = "means a platform permission was named as required, though not " +
                "necessarily by this command's own caller",
        ),
        DenialSignature(
            text = "avc: denied",
            kind = DenialKind.INCIDENTAL,
            meaning = "means SELinux policy refused an operation for some process domain, " +
                "though not necessarily for this command",
        ),
        DenialSignature(
            text = "Operation not permitted",
            kind = DenialKind.INCIDENTAL,
            meaning = "means a syscall answered EPERM for some process, though not " +
                "necessarily for this command",
        ),
    )

    /**
     * The first denial signature found in [stdout] or [stderr], or null when
     * neither stream contains one.
     *
     * Answers the broad question — "does this output mention a refusal at all" —
     * and is what a caller wanting full visibility should use. Success is
     * decided by [detectCommandDenial] instead, because a mention is not a
     * refusal of our command.
     *
     * Both streams are searched because the same refusal lands on stdout for a
     * service dump and on stderr for a failed file operation, and issue 44 is
     * the stdout case. Priority is [SIGNATURES] order across both streams, so a
     * command-level refusal on either stream wins over an incidental mention on
     * the other.
     */
    fun detect(stdout: String, stderr: String): String? =
        search(stdout, stderr, DenialKind.COMMAND_LEVEL) ?: search(stdout, stderr, DenialKind.INCIDENTAL)

    /**
     * The first COMMAND_LEVEL refusal found, or null when our command was not
     * itself refused.
     *
     * This is the only question that may change `success`. A run whose output
     * merely quotes someone else's "Permission denied" produced that text
     * successfully and must stay a success.
     */
    fun detectCommandDenial(stdout: String, stderr: String): String? =
        search(stdout, stderr, DenialKind.COMMAND_LEVEL)

    /**
     * The first INCIDENTAL mention found, or null when the output quotes none.
     *
     * Surfaced on [ShellResult.advisory] so a caller can see the noise without
     * the tool claiming its own run failed.
     */
    fun detectIncidental(stdout: String, stderr: String): String? =
        search(stdout, stderr, DenialKind.INCIDENTAL)

    /**
     * Whether the output names the signature-level DUMP permission in a
     * platform refusal line.
     *
     * Separate from [detect] because the caller's answer is different: a DUMP
     * refusal at app UID is not merely "this failed", it is "this tool can never
     * work here and must be replaced by something that does not need DUMP".
     * pmDump uses this to decide whether to explain the wall or to report the
     * dump.
     */
    fun isDumpPermissionDenial(stdout: String, stderr: String): Boolean {
        val lines = denialLines(stdout, PERMISSION_DENIAL) + denialLines(stderr, PERMISSION_DENIAL)
        return lines.any { it.contains(DUMP_PERMISSION, ignoreCase = true) }
    }

    /**
     * One actionable sentence a caller can act on, beginning with the signature
     * that matched.
     *
     * The second half always states what this run can conclude at [tier], in the
     * words [ShellTier.capabilities] already uses. Saying "permanent" only where
     * it is true is the whole point: at app UID the app cannot raise its own UID
     * or be granted a signature permission, so repeating the same command will
     * never succeed; at ADB and ROOT the UID already holds what an app lacks, so
     * a refusal there is a fact about the target and not a wall the tier built.
     */
    fun explain(signature: String, tier: ShellTier): String =
        "The output contains \"$signature\", which ${meaningOf(signature)}; " +
            "${tierGuidance(tier)}."

    /**
     * Whether repeating the same command at the same tier can ever succeed.
     *
     * Permanent at [ShellTier.APP_UID] for a permission denial, because the
     * denial is a property of the UID that ran the command: an app cannot grant
     * itself a signature permission, cannot become UID 2000 and cannot become
     * UID 0. Never claimed permanent at ADB or ROOT, where the caller already
     * holds the privileges an app lacks and a refusal says something about a
     * locked-down target rather than about the tier.
     */
    fun isPermanent(signature: String?, tier: ShellTier): Boolean =
        signature != null && tier == ShellTier.APP_UID

    /**
     * Apply the rule to a freshly built [ShellResult] stamped with [tier].
     *
     * Only a [DenialKind.COMMAND_LEVEL] refusal flips `success` to false, and
     * only then: that is the class where the refused thing is our own call, and
     * a refusal printed with exit 0 is exactly issue 44. An incidental mention
     * is recorded on [ShellResult.advisory] and leaves success alone, so a
     * `logcat -d` full of other processes' refusals is not reported as our
     * failure.
     *
     * The exit code, stdout and stderr are copied through untouched in both
     * cases: they are the evidence, and a caller that genuinely wanted to search
     * the text still can.
     */
    fun apply(result: ShellResult, tier: ShellTier): ShellResult {
        val commandDenial = detectCommandDenial(result.stdout, result.stderr)
        val advisory = detectIncidental(result.stdout, result.stderr)
        if (commandDenial == null && advisory == null) return result
        return result.copy(
            success = if (commandDenial != null) false else result.success,
            denial = commandDenial?.let { explain(it, tier) },
            advisory = advisory?.let { explain(it, tier) },
        )
    }

    /**
     * Find a signature of one class in either stream.
     *
     * A COMMAND_LEVEL signature only counts on a line that is not a log record,
     * because a timestamped line is a log entry some other process wrote rather
     * than an answer to this command. An INCIDENTAL signature counts anywhere,
     * since it only ever produces the advisory.
     */
    private fun search(stdout: String, stderr: String, kind: DenialKind): String? {
        for (signature in SIGNATURES) {
            if (signature.kind != kind) continue
            val matched = when (kind) {
                DenialKind.COMMAND_LEVEL ->
                    denialLines(stdout, signature.text).isNotEmpty() ||
                        denialLines(stderr, signature.text).isNotEmpty()
                DenialKind.INCIDENTAL ->
                    stdout.contains(signature.text, ignoreCase = true) ||
                        stderr.contains(signature.text, ignoreCase = true)
            }
            if (matched) return signature.text
        }
        return null
    }

    /**
     * The lines of [text] where [signature] IS the message, i.e. where the line
     * begins with it.
     *
     * That is the shape of a platform refusal to the caller:
     *
     *   Permission Denial: can't dump PackageManager from from pid=…, uid=…
     *   Permission Denial: can't dump AccessibilityManagerService
     *
     * The second form is real and carries no pid or uid, which is why requiring
     * those fields would miss issue 44's own accessibility case. What the shape
     * does exclude is a mention in the middle of a line — "grep: /x: Permission
     * denied" from another program, or any sentence that merely names the words.
     *
     * Lines beginning with a log-record timestamp are excluded as well, because
     * logcat writes another process's refusal that way, so such a line describes
     * the logger and not our caller.
     */
    private fun denialLines(text: String, signature: String): List<String> {
        if (!text.contains(signature, ignoreCase = true)) return emptyList()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(signature, ignoreCase = true) }
            .filterNot { LOG_RECORD_PREFIX.containsMatchIn(it) }
            .toList()
    }

    private fun meaningOf(signature: String): String =
        SIGNATURES.firstOrNull { it.text.equals(signature, ignoreCase = true) }?.meaning
            ?: "means the command was refused a permission it needs"

    private fun tierGuidance(tier: ShellTier): String = when (tier) {
        ShellTier.APP_UID ->
            "at app UID this denial is permanent and must not be retried, so use ADB shell " +
                "(UID 2000, which has most system reads and writes) or root (unrestricted " +
                "shell access)"
        ShellTier.ADB ->
            "this ran at ADB shell (UID 2000), so the refusal is the service's own answer " +
                "rather than a tier limit, and repeating it at this tier returns the same answer"
        ShellTier.ROOT ->
            "this ran as root, so no higher privilege exists to grant it and the refusal is " +
                "the device's own answer"
        ShellTier.NONE ->
            "nothing ran, so this denial did not come from an executed command"
    }
}
