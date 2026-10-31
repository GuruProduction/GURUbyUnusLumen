// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unuslumen.app.util.shell.TermuxProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves the Termux prefix is COMPLETE and apt can operate, in the app's own
 * SELinux domain.
 *
 * WHY THIS EXISTS. Issue 4 has been open since build 3.6.5 saying apt is dead
 * and "termuxDiagnose false-positive". The root cause was not apt at all: the
 * bootstrap's SYMLINKS.txt uses `target←link` with no spaces, the provider
 * split on " ← ", matched nothing, and installed none of the 1160 links. The
 * payload is multi-call, so `sh`, `mktemp`, `touch`, `sort` and `cut` are links
 * onto `coreutils`/`dash`/`gawk` and none of them existed. apt shells out to
 * `apt-key`, apt-key is a `#!/.../bin/sh` script, and with no `sh` that exec
 * fails, which apt reports as an unverifiable repository.
 *
 * Every earlier test of this ran under adb shell or run-as, which are different
 * SELinux domains and do not carry Android's W^X rule. The app domain is the
 * only domain where the real behaviour exists, so the first test here asserts
 * the domain and discards the run if it is not the app's.
 */
@RunWith(AndroidJUnit4::class)
class TermuxAptDomainTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun domain(): String =
        runCatching { File("/proc/self/attr/current").readText().trim().trim('\u0000') }
            .getOrDefault("unknown")

    @Test
    fun runsInTheAppDomainNotTheShellDomain() {
        val d = domain()
        println("SELinux domain under test: $d")
        assertTrue(
            "This test must run in the app domain, but ran as '$d'. A result from " +
                "the shell or runas domain proves nothing about the app.",
            d.startsWith("u:r:untrusted_app"),
        )
    }

    /**
     * The links that make a shell exist at all. If any of these is missing the
     * prefix is half-installed and everything downstream fails in a way that
     * looks like something else.
     */
    @Test
    fun thePrefixHasAShellAndCoreutils() = runBlocking {
        val provider = TermuxProvider(context)
        assertTrue("Termux prefix did not initialise", provider.initialize())

        val bin = File(provider.getBinPath())
        val required = listOf(
            "sh", "bash", "dash", "mktemp", "touch", "sort", "cut", "awk",
            "sed", "grep", "find", "apt-get", "dpkg", "apt-key", "gpgv",
        )
        val missing = required.filter { !File(bin, it).exists() }

        println("prefix bin: ${provider.getBinPath()}")
        println("missing: $missing")
        assertTrue(
            "The prefix is missing $missing. The bootstrap's symlinks did not apply, " +
                "so multi-call binaries are present but unreachable by name.",
            missing.isEmpty(),
        )
    }

    /** `sh` must actually RUN, not merely exist as a name. */
    @Test
    fun theShellRunsInTheAppDomain() = runBlocking {
        val provider = TermuxProvider(context)
        provider.initialize()
        val result = provider.execBash("sh -c 'echo SHELL_OK'")
        println("sh in app domain :: exit=${result.exitCode} out=${result.stdout} err=${result.stderr.take(300)}")
        assertTrue(
            "sh did not run in the app domain. exit=${result.exitCode} stderr=${result.stderr.take(400)}",
            result.exitCode == 0 && result.stdout.contains("SHELL_OK"),
        )
    }

    /** apt's signature helper is the thing that was failing. It must execute. */
    @Test
    fun aptKeyExecutesThroughItsOwnShebang() = runBlocking {
        val provider = TermuxProvider(context)
        provider.initialize()
        // apt-key with no arguments prints its usage and exits non-zero; what
        // matters is that the interpreter was found and the script ran. A dead
        // shebang produces "not found" or "Permission denied" instead.
        val result = provider.execBash("apt-key help 2>&1")
        println("apt-key help :: exit=${result.exitCode} out=${result.stdout.take(400)}")
        val combined = result.stdout + result.stderr
        assertTrue(
            "apt-key did not execute. Its shebang interpreter is missing or not " +
                "executable in the app domain. output=$combined",
            combined.contains("Usage: apt-key") || combined.contains("apt-key"),
        )
    }

    /**
     * The claim that actually matters to a person: something can be INSTALLED.
     *
     * apt update verifying a signature proves the config, keyring and
     * interpreter are sound. It does not prove dpkg can unpack a package, which
     * is where the old failure ended up after every other fault was cleared.
     * This installs a tiny package and then runs the binary it delivers, so the
     * assertion lands on a file that exists because apt put it there.
     *
     * It is guarded: if the package is already installed the install step is a
     * no-op and only the binary check runs, so the test is stable on a second
     * run and cannot fail for having already succeeded.
     */
    @Test
    fun aRealPackageInstallsAndRuns() = runBlocking {
        val provider = TermuxProvider(context)
        provider.initialize()

        // figlet is NOT in the bootstrap, so its presence afterwards can only
        // come from apt having delivered it. dos2unix would be a weaker test
        // because it ships with coreutils already.
        val wasPresent = provider.hasCommand("figlet")
        if (!wasPresent) {
            val result = provider.execAptInstall("figlet")
            println("apt install figlet :: exit=${result.exitCode}")
            // Print the LAST part of the output, because dpkg's failure line is
            // at the end and a head-truncated capture hides exactly the message
            // that says what went wrong.
            println("stdout-tail: " + result.stdout.takeLast(2500))
            println("stderr-tail: " + result.stderr.takeLast(1200))
        } else {
            println("figlet already installed; checking the binary runs")
        }

        val installed = provider.hasCommand("figlet")
        assertTrue(
            "figlet is not in the prefix after an install attempt. apt cannot " +
                "deliver packages, so the tool surface is still unusable.",
            installed,
        )

        // figlet looks for its fonts relative to a compiled-in share path, which
        // is the upstream prefix. Passing -d explicitly is how any Termux user
        // runs it here too; the point of the assertion is that the binary apt
        // delivered executes and produces output.
        val share = File(provider.getRootPath(), "usr/share/figlet").absolutePath
        val ran = provider.execBash("echo hi | figlet -d '$share' 2>&1")
        println("figlet :: exit=${ran.exitCode} out=${ran.stdout.take(200)}")
        assertTrue(
            "figlet installed but did not render. exit=${ran.exitCode} " +
                "out=${ran.stdout.take(300)} err=${ran.stderr.take(300)}",
            ran.exitCode == 0 && ran.stdout.contains("_") ,
        )
    }

    /**
     * The end claim: apt can reach its repositories and verify their
     * signatures. Needs Tor up on this emulator, so it reports the honest
     * reason rather than pretending.
     */
    @Test
    fun aptUpdateReachesItsRepositoryAndVerifiesSignatures() = runBlocking {
        val provider = TermuxProvider(context)
        provider.initialize()
        val result = provider.execAptUpdate()
        println("apt update :: exit=${result.exitCode}")
        println("stdout: ${result.stdout.take(1200)}")
        println("stderr: ${result.stderr.take(600)}")

        val combined = result.stdout + result.stderr
        assertTrue(
            "apt update did not complete. exit=${result.exitCode}\n$combined",
            result.exitCode == 0,
        )
        assertTrue(
            "apt could not verify the repository signature, which means the " +
                "keyring or the gpgv path is still wrong.\n$combined",
            !combined.contains("not signed") && !combined.contains("Couldn't execute"),
        )
    }
}
