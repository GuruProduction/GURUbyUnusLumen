// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unuslumen.app.util.shell.GitProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves the git runtime works in the APP's SELinux domain, not adb's.
 *
 * WHY THIS EXISTS. Every shell-level test of the git tooling passed while the
 * tool itself failed, because `adb shell` runs as `u:r:shell:s0` and `run-as`
 * runs as `u:r:runas_app:s0`, while the app runs as
 * `u:r:untrusted_app_34:s0`. Android's W^X rule (targetSdk 29+) stops a process
 * exec'ing a binary from its own data directory, and only the app domain
 * carries that restriction. So the shell was testing a world where the problem
 * does not exist.
 *
 * An instrumentation test runs inside the app's uid and therefore its domain.
 * That is the only place the real failure can be reproduced without a rooted
 * device.
 *
 * The test is self-validating: it asserts its own domain first, and discards
 * everything if the domain is not the app's, because a green result from the
 * wrong domain is the exact trap that produced several false conclusions
 * earlier.
 */
@RunWith(AndroidJUnit4::class)
class GitRuntimeDomainTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The SELinux domain this process is actually in. */
    private fun domain(): String =
        runCatching { File("/proc/self/attr/current").readText().trim().trim('\u0000') }
            .getOrDefault("unknown")

    @Test
    fun runsInTheAppDomainNotTheShellDomain() {
        val d = domain()
        println("SELinux domain under test: $d")
        assertTrue(
            "This test must run in the app domain to be meaningful, but ran as '$d'. " +
                "A result from the shell or runas domain proves nothing about the app.",
            d.startsWith("u:r:untrusted_app"),
        )
    }

    @Test
    fun gitVersionRunsInTheAppDomain() = runBlocking {
        val provider = GitProvider(context)
        val result = provider.runGit(args = listOf("--version"), workingDir = null)

        println(
            "git --version in app domain :: exit=${result.exitCode} " +
                "stdout=${result.stdout} stderr=${result.stderr.take(300)}"
        )
        assertTrue(
            "git did not run. exit=${result.exitCode} stderr=${result.stderr.take(400)}",
            result.success && result.stdout.contains("git version"),
        )
    }

    /**
     * The real reproduction. A network verb spawns git-remote-https, which is a
     * binary in app storage, and that exec is what W^X refuses. This is the test
     * that failed with "cannot exec 'remote-https': Permission denied" before the
     * termux-exec inputs were supplied.
     */
    @Test
    fun lsRemoteReachesGithubThroughTorInTheAppDomain() = runBlocking {
        val provider = GitProvider(context)
        val result = provider.runGit(
            args = listOf("ls-remote", "https://github.com/octocat/Hello-World.git"),
            workingDir = null,
            requireTor = true,
            torProxyUrl = "socks5h://127.0.0.1:9050",
            timeoutMs = 240_000L,
        )

        println(
            "ls-remote in app domain :: exit=${result.exitCode} " +
                "stdout=${result.stdout.take(300)} stderr=${result.stderr.take(400)}"
        )
        assertTrue(
            "ls-remote failed in the app domain. exit=${result.exitCode}\n" +
                "stderr: ${result.stderr.take(600)}",
            result.success && result.stdout.contains("refs/heads/"),
        )
    }

    /** A full clone, which spawns more children than ls-remote: index-pack among them. */
    @Test
    fun cloneSucceedsInTheAppDomainAndLandsOnDisk() = runBlocking {
        val provider = GitProvider(context)
        val target = provider.cloneTargetPath("octocat/Hello-World")
            ?: error("cloneTargetPath refused the repository name")
        runCatching { target.deleteRecursively() }

        val result = provider.runGit(
            args = listOf(
                "clone", "--depth", "1",
                "https://github.com/octocat/Hello-World.git",
                target.absolutePath,
            ),
            workingDir = provider.reposDir,
            requireTor = true,
            torProxyUrl = "socks5h://127.0.0.1:9050",
            timeoutMs = 300_000L,
        )

        println(
            "clone in app domain :: exit=${result.exitCode} " +
                "stderr=${result.stderr.take(400)}"
        )

        // Two assertions, because the earlier defect was a tool reporting success
        // for a clone that was not on disk. Exiting 0 is not the claim; a real
        // repository at the path is.
        assertTrue(
            "clone failed in the app domain. exit=${result.exitCode}\n" +
                "stderr: ${result.stderr.take(600)}",
            result.success,
        )
        assertEquals(
            "clone exited 0 but no repository exists at ${target.absolutePath}",
            true,
            File(target, ".git/HEAD").exists(),
        )
    }
}
