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
 * Issue 335: the Termux prefix had no Tor proxy environment, so curl, git, pip, npm and apt
 * inside it left from the device's real address while every other tool was in the circuit.
 *
 * WHAT CAN AND CANNOT BE PROVEN HERE. Whether a download actually exits through a Tor circuit
 * needs a running Tor and a network check, which this test does not do: an emulator without a
 * circuit would make it fail for a reason that has nothing to do with the environment. What it
 * can prove is the thing that was broken and is cheap to check exactly: the variables are
 * present in the environment the prefix is launched with, they point at the SOCKS port with
 * the resolving spelling, and a real `sh` inside the prefix sees them.
 *
 * `socks5h` versus `socks5` is asserted deliberately. Plain socks5 resolves the hostname
 * LOCALLY and sends only the address, which leaks the query even though the connection is
 * tunnelled. That distinction is the difference between a proxy that protects a DNS lookup and
 * one that does not.
 */
@RunWith(AndroidJUnit4::class)
class TermuxProxyEnvironmentTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun linker(): String =
        if (System.getProperty("os.arch")?.contains("64") == true) "/system/bin/linker64"
        else "/system/bin/linker"

    /**
     * Run one command through the provider's own exec path.
     *
     * THIS IS THE LESSON FROM #290 APPLIED HERE. My first version of this helper rebuilt the
     * environment by hand, which tests the harness rather than the product: a variable missing
     * from my copy would look like a missing variable in the app, and a variable I added would
     * pass while the app still lacked it. execInTermux is what a real command takes, so its
     * environment is the only one worth asserting on.
     */
    private suspend fun runInPrefix(command: String): Triple<Int, String, String> = runBlocking {
        val provider = TermuxProvider(context)
        val result = provider.execInTermux(command)
        Triple(result.exitCode, result.stdout.trim(), result.stderr.trim())
    }

    @Test
    fun thePrefixIsExtractedFirst() = runBlocking {
        val provider = TermuxProvider(context)
        val ready = provider.initialize()
        println("PREFIX ready=$ready")
        assertTrue("initialize() did not produce a prefix", ready)
    }

    /**
     * A shell inside the prefix must see the proxy variables.
     *
     * THIS IS THE TEST THAT WOULD HAVE CAUGHT #335. The ticket's own measurement was
     * `curl -s https://check.torproject.org/api/ip` inside the prefix returning IsTor:false,
     * and the reason was that the variables were not set at all. Checking them from inside is
     * the cheapest form of the same fact.
     */
    @Test
    fun aShellInsideThePrefixSeesTheTorProxy() = runBlocking {
        TermuxProvider(context).initialize()

        /*
         * THE TRAILING SEMICOLON IS LOAD-BEARING, AND ITS ABSENCE COST TWO RUNS.
         *
         * execInTermux only routes through bash when the command contains a shell
         * metacharacter; otherwise it parses the string into argv and execs the binary
         * directly. So "echo $ALL_PROXY" was executed as `echo` with the LITERAL argument
         * "$ALL_PROXY", which printed that text and made a working environment look unset.
         * A semicolon sends it down the bash path, where the variable is expanded.
         */
        val (code, out, err) = runInPrefix("echo \$ALL_PROXY;")
        println("PROXY_ENV exit=$code out=[$out] err=[${err.take(200)}]")

        assertTrue(
            "A shell in the prefix must print the proxy it inherited. " +
                "exit=$code out=[$out] err=[${err.take(300)}]",
            code == 0 && out.isNotBlank(),
        )
        assertTrue(
            "the proxy must name the SOCKS port. Got: $out",
            out.contains(":9050"),
        )
        assertTrue(
            "it must use socks5h rather than socks5, because plain socks5 resolves the " +
                "hostname locally and leaks the query. Got: $out",
            out.contains("socks5h://"),
        )
    }

    /**
     * The resolving spelling is the whole point of the fix, so it is asserted on its own.
     *
     * A future change that "simplifies" socks5h to socks5 would keep every visible behaviour
     * and quietly start leaking every hostname a pip or npm install asked for.
     */
    @Test
    fun theProxyUsesTheResolvingSocksSchema() = runBlocking {
        TermuxProvider(context).initialize()
        val (code, out, _) = runInPrefix("echo \$ALL_PROXY;")
        println("PROXY_SCHEMA exit=$code value=[$out]")
        assertTrue("ALL_PROXY must be set", out.isNotBlank())
        assertTrue(
            "ALL_PROXY must be socks5h, not socks5. Value: [$out]",
            out.startsWith("socks5h://"),
        )
        assertTrue(
            "and it must point at the loopback socket Tor publishes. Value: [$out]",
            out.contains("127.0.0.1:9050"),
        )
    }

    /**
     * The lower-case spellings must be present too.
     *
     * curl reads either case, but plenty of tools read only the lower-case form, and the
     * difference is invisible until one of them quietly goes direct.
     */
    @Test
    fun theLowerCaseSpellingsAreAlsoPresent() = runBlocking {
        TermuxProvider(context).initialize()
        val (code, out, _) = runInPrefix("echo \$http_proxy;")
        println("PROXY_LOWER exit=$code value=[$out]")
        assertTrue(
            "http_proxy must be set for tools that read only the lower-case form. Got: [$out]",
            out.contains("127.0.0.1:9050"),
        )
    }
}
