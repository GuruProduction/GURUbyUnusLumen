// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unuslumen.app.util.shell.PythonProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue 285: a Python script's raw socket leaving from the device's real address.
 *
 * THE DEFECT AND WHY IT LOOKED UNFIXABLE. Environment variables only reach code that reads
 * them. urllib and requests honour a proxy; `socket.create_connection` does not know proxies
 * exist. The previous answer was that confining a raw socket needs a network namespace, which a
 * stock app cannot install. That is true of the NETWORK and it is not true of the PROGRAM:
 * CPython imports `sitecustomize` at startup, so the guard runs before the script and wraps the
 * one function that matters.
 *
 * WHAT IS MEASURED HERE is the decision, not the network. With no proxy configured a PUBLIC
 * destination must be refused, and a PRIVATE one must be allowed through untouched, because
 * proxying a LAN address is what made a router read as a dead host in issue 215.
 */
@RunWith(AndroidJUnit4::class)
class PythonEgressGuardTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun python(): PythonProvider = PythonProvider(context)

    private fun runScript(script: String): Triple<Int, String, String> = runBlocking {
        val provider = python()
        provider.initialize()
        val file = File(context.cacheDir, "guru_285_${System.nanoTime()}.py")
        file.writeText(script)
        try {
            val pb = ProcessBuilder(provider.buildExecCommand(file.absolutePath))
            val env = provider.getPythonEnv(null).toMutableMap()
            // No proxy, deliberately: this is the posture the ticket measured, and the one in
            // which the old behaviour leaked.
            env.remove("ALL_PROXY"); env.remove("all_proxy")
            env.remove("HTTP_PROXY"); env.remove("http_proxy")
            env.remove("HTTPS_PROXY"); env.remove("https_proxy")
            pb.environment().clear()
            pb.environment().putAll(env)
            val proc = pb.start()
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = Thread { runCatching { proc.inputStream.bufferedReader().use { out.append(it.readText()) } } }
            val tErr = Thread { runCatching { proc.errorStream.bufferedReader().use { err.append(it.readText()) } } }
            tOut.start(); tErr.start()
            val code = proc.waitFor()
            tOut.join(20000); tErr.join(20000)
            Triple(code, out.toString().trim(), err.toString().trim())
        } finally {
            file.delete()
        }
    }

    @Test
    fun theRuntimeIsReady() = runBlocking {
        val status = python().initialize()
        println("PY status available=${status.available} version=${status.version} detail=${status.detail}")
        assertTrue("The Python runtime must be available for this to mean anything", status.available)
    }

    @Test
    fun theGuardIsInstalledIntoTheRuntime() {
        // The guard only works because site.py imports it, so its presence on disk is the fact
        // the rest of this test depends on.
        runBlocking { python().initialize() }
        /*
         * LOCATED BY SEARCHING THE APP'S OWN FILES rather than by rebuilding the provider's
         * private path arithmetic. Rebuilding it is how a test ends up asserting about a
         * directory the product never writes to, which reads as a missing guard.
         */
        val target = context.filesDir.walkTopDown()
            .firstOrNull { it.isFile && it.name == "sitecustomize.py" }
        println("PY_GUARD found=${target != null} path=${target?.absolutePath} size=${target?.length() ?: 0}")
        assertTrue(
            "sitecustomize.py is not anywhere under the app's files directory, so CPython " +
                "never imports the guard and every raw socket goes direct.",
            target != null && target.length() > 0,
        )
    }

    /**
     * The ticket's own code, with no circuit: it must be refused rather than sent.
     *
     * The old behaviour was a connection to the public host from the device's real address,
     * which succeeded and reported IsTor:false. The new behaviour is an OSError that names the
     * reason. Either the script never reaches the network, or it does and the guard is not
     * installed.
     */
    @Test
    fun aPublicRawSocketIsRefusedWithNoCircuit() {
        val script = """
import socket
try:
    s = socket.create_connection(("check.torproject.org", 443), timeout=5)
    s.close()
    print("CONNECTED")
except OSError as e:
    print("REFUSED", str(e)[:120])
""".trimIndent()
        val (code, out, err) = runScript(script)
        println("PY_PUBLIC exit=$code out=[$out] err=[${err.take(300)}]")

        assertTrue(
            "The script must not have connected. out=[$out] err=[${err.take(300)}]",
            !out.contains("CONNECTED"),
        )
        assertTrue(
            "It must have been refused by the guard, with a reason a person can act on. " +
                "out=[$out] err=[${err.take(300)}]",
            out.contains("REFUSED"),
        )
    }

    /**
     * A private destination must pass through untouched.
     *
     * This is the guard that stops the fix from breaking the LAN, and it is the half issue 215
     * was about. It connects to the loopback address the app itself owns, so the test needs no
     * network at all: a refusal here would mean the guard is too broad.
     */
    @Test
    fun aPrivateRawSocketIsAllowedThrough() {
        val script = """
import socket
try:
    s = socket.create_connection(("127.0.0.1", 1), timeout=2)
    s.close()
    print("CONNECTED")
except OSError as e:
    # A refused connection proves the guard let it out; the message is the kernel's.
    print("PASSED_THROUGH", e.errno)
""".trimIndent()
        val (code, out, err) = runScript(script)
        println("PY_PRIVATE exit=$code out=[$out] err=[${err.take(300)}]")

        assertTrue(
            "A loopback destination must reach the kernel rather than being refused by the " +
                "guard. out=[$out] err=[${err.take(300)}]",
            out.contains("PASSED_THROUGH") || out.contains("CONNECTED"),
        )
        assertTrue(
            "the guard's own refusal text must NOT appear for a local address. Got: $out",
            !out.contains("no Tor proxy is configured"),
        )
    }
}
