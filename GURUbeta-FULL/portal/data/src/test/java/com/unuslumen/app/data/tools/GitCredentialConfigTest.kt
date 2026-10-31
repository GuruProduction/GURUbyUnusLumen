// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.util.shell.GitCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Locks the authentication contract, because four different mechanisms all
 * failed before this one and each failure looked like something else.
 *
 * WHY THIS EXISTS. Issue 33: `gitPush` died with
 * `cannot exec 'f() {...}; f get': No such file or directory`. Measuring it on
 * device found four independent blockers, and these tests hold the answer still
 * so nobody can reintroduce any of them:
 *
 *  1. git strips a leading `!` from a GIT_CONFIG_VALUE environment pair.
 *  2. git truncates a config value at `;`, so `!a;b` reads back as `!a`, which
 *     means no function-body helper can ever survive as config.
 *  3. whatever survives is exec'd as a program name, so multi-word values fail.
 *  4. /data is noexec, so helper scripts and `git-credential-store` cannot be
 *     exec'd either.
 */
class GitCredentialConfigTest {

    private val token = "ghp_exampletokenvalue"

    @Test
    fun theHeaderIsWellFormedBasicAuth() {
        val header = GitCredentials.basicAuthorization(token)
        assertTrue("must be an AUTHORIZATION header", header.startsWith("AUTHORIZATION: basic "))

        val encoded = header.removePrefix("AUTHORIZATION: basic ")
        val decoded = String(Base64.getDecoder().decode(encoded))
        assertEquals(
            "GitHub expects the fixed token user, not the account name",
            "x-access-token:$token",
            decoded,
        )
    }

    @Test
    fun theHeaderCarriesTheTokenAndNothingElse() {
        val header = GitCredentials.basicAuthorization(token)
        assertFalse(
            "a raw token must never appear in the header; it must be base64 encoded",
            header.contains(token),
        )
        // And the encoding must be the reversible, standard form.
        val decoded = String(Base64.getDecoder().decode(header.removePrefix("AUTHORIZATION: basic ")))
        assertTrue(decoded.endsWith(token))
    }

    @Test
    fun noHelperIsDisabledExplicitly() {
        val (key, value) = GitCredentials.noHelperPair()
        assertEquals("credential.helper", key)
        assertEquals(
            "an empty value is git's documented way to disable the helper chain",
            "",
            value,
        )
    }

    @Test
    fun theAuthConfigAvoidsEveryGitConfigLandmine() {
        val pairs = listOf(GitCredentials.extraHeaderPair(token), GitCredentials.noHelperPair())
        for ((key, value) in pairs) {
            assertFalse("$key would be truncated at a semicolon", value.contains(';'))
            assertFalse("$key would lose a leading bang", value.startsWith("!"))
            assertTrue("$key must be a single safe config value", GitCredentials.isSafeConfigValue(value))
        }
    }

    @Test
    fun theKnownTrapsAreDocumentedAccurately() {
        // These assertions are the record of what was measured, not opinion.
        assertTrue("a semicolon truncates a git config value", GitCredentials.wouldBeTruncatedByGitConfig("!a;b"))
        assertTrue("a leading bang is special in git config", GitCredentials.wouldBeTruncatedByGitConfig("!f()"))
        assertFalse(
            "a plain header value is safe",
            GitCredentials.wouldBeTruncatedByGitConfig(GitCredentials.basicAuthorization(token)),
        )
    }
}
