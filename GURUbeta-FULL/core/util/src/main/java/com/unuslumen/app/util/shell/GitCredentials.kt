// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

/**
 * How git authenticates to GitHub on this device, and why it is not a credential
 * helper.
 *
 * ============================================================================
 * THE PROBLEM, measured on device. Every helper shape fails, for four
 * independent reasons, and each one alone is fatal.
 * ============================================================================
 *
 * Issue 33 recorded the symptom `fatal: cannot exec 'f() {...}; f get': No such
 * file or directory`. The investigation found four separate blockers:
 *
 *  1. A leading `!` in a `GIT_CONFIG_VALUE_n` environment pair is STRIPPED by
 *     git's config reader, so git stores a bare function body.
 *
 *  2. In a config FILE the `!` survives, but a SEMICOLON truncates the value,
 *     because git treats `;` as a comment. Measured: `helper = !a;b` reads back
 *     as `!a`. A function body is made of semicolons, so `!f() {...}` can never
 *     survive as config, wherever it is written.
 *
 *  3. Quotes and `!` are then stripped and the REMAINDER IS EXEC'D as a program
 *     name with the operation appended:
 *       cannot exec 'printf username=... get': No such file or directory
 *
 *  4. /data is mounted noexec and Android blocks exec from app storage, so a
 *     helper script in the prefix cannot be exec'd either, and `helper = store`
 *     fails the same way because git execs `git-credential-store`:
 *       cannot exec 'git credential-store get': No such file or directory
 *
 * The termux-exec preload does not cover the credential exec either, measured
 * with the full preload environment set.
 *
 * ============================================================================
 * THE ANSWER: an HTTP authorization header, which needs no helper and no exec.
 * ============================================================================
 *
 * GitHub accepts a personal access token as HTTP Basic authentication. git's
 * `http.extraHeader` sends an arbitrary header with every request in that
 * operation, and it works at the transport layer, so nothing has to be exec'd.
 *
 *   http.extraHeader = AUTHORIZATION: basic <base64("x-access-token:<token>")>
 *
 * This is git's documented mechanism for supplying authentication without a
 * credential helper, it needs no shell, no script, no store file on disk and no
 * exec, and it is scoped to the single invocation because the config is passed
 * per call.
 *
 * The token therefore never touches disk, never enters argv, and never appears in
 * a log line. It exists only in the config value passed to one git process and
 * dies with it.
 */
object GitCredentials {

    /** Environment key holding the transient token for the git child process. */
    const val ENV_CREDENTIAL = "GURU_GIT_CREDENTIAL"

    /** The fixed GitHub username a personal access token authenticates as. */
    private const val GITHUB_USER = "x-access-token"

    /**
     * Base64 of `x-access-token:<token>`, encoded here rather than by a shell.
     *
     * Android has `android.util.Base64`, but this object is deliberately free of
     * Android imports so it can be unit tested on the JVM. java.util.Base64 is
     * available on both.
     */
    fun basicAuthorization(token: String): String {
        val raw = "$GITHUB_USER:$token"
        val encoded = java.util.Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
        return "AUTHORIZATION: basic $encoded"
    }

    /**
     * The config pair that authenticates one git invocation.
     *
     * Returned as a key/value pair rather than text so it goes through the same
     * GIT_CONFIG_* path as proxy and CA settings, and deliberately contains no
     * semicolon, no quote and no `!`, so none of the four failure modes can
     * apply to it.
     */
    fun extraHeaderPair(token: String): Pair<String, String> =
        "http.extraHeader" to basicAuthorization(token)

    /**
     * The helper value that must also be set so git does not try to run one.
     *
     * An empty `credential.helper` DISABLES the helper chain. Without it git
     * falls through to a credential helper (or a terminal prompt) when the header
     * is not enough, and either path fails here. Setting it empty is the
     * documented way to say "no helper", and an empty value has nothing for the
     * config parser to truncate.
     */
    fun noHelperPair(): Pair<String, String> = "credential.helper" to ""

    /** Whether a config value is safe to hand to git's config parser. */
    fun isSafeConfigValue(value: String): Boolean =
        !value.contains('\n') && !value.contains('\u0000')

    /**
     * Whether a value would be truncated by git's config parser.
     *
     * Kept because it documents the actual trap: a semicolon ends a value in git
     * config, and a leading `!` is special. Both are asserted by the tests so a
     * future change cannot quietly reintroduce a helper string.
     */
    fun wouldBeTruncatedByGitConfig(value: String): Boolean =
        value.contains(';') || value.startsWith("!")
}
