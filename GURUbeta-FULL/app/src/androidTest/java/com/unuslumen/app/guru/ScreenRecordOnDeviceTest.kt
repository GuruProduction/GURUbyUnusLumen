// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unuslumen.app.data.tools.ScreenToolDefinitions
import com.unuslumen.app.data.tools.ScreenShotResult
import com.unuslumen.app.data.tools.ScreenToolExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Drives the real recorder and reads the real result.
 *
 * WHY THIS EXISTS. Issue 43 was a tool reporting success over a zero-byte file.
 * A unit test proves the decision rule; only the device proves the tool uses it,
 * because the false success lived in the wiring, not the arithmetic.
 *
 * This runs the actual tool through the actual executor and asserts on the
 * serialised result a caller would receive.
 */
@RunWith(AndroidJUnit4::class)
class ScreenRecordOnDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(jsonText: String): ScreenShotResult =
        json.decodeFromString(ScreenShotResult.serializer(), jsonText)

    @Test
    fun runsInTheAppDomainNotTheShellDomain() {
        val d = runCatching { File("/proc/self/attr/current").readText().trim().trim('\u0000') }
            .getOrDefault("unknown")
        println("SELinux domain under test: $d")
        assertTrue("Must run in the app domain, ran as '$d'", d.startsWith("u:r:untrusted_app"))
    }

    /**
     * The issue 43 path, end to end: start, wait, stop, and check that a success
     * claim is backed by bytes on disk.
     */
    @Test
    fun startThenStopNeverClaimsSuccessOverAnEmptyFile() = runBlocking {
        val executor = ScreenToolExecutor(context)

        val start = executor.execute(
            ScreenToolDefinitions.RECORD_SCREEN,
            mapOf("action" to "start", "duration" to 3),
        )
        val startResult = decode(start.rawJson)
        println("recordScreen start -> success=${startResult.success} path=${startResult.path} bytes=${startResult.sizeBytes} error=${startResult.error}")

        // Whatever start says, if it claims success the file must be a real path.
        if (startResult.success) {
            assertTrue("success with a blank path", startResult.path.isNotBlank())
        }

        Thread.sleep(4000)

        val stop = executor.execute(
            ScreenToolDefinitions.RECORD_SCREEN,
            mapOf("action" to "stop"),
        )
        val stopResult = decode(stop.rawJson)
        val onDisk = if (stopResult.path.isNotBlank()) File(stopResult.path) else null
        println("recordScreen stop -> success=${stopResult.success} path=${stopResult.path} bytes=${stopResult.sizeBytes} error=${stopResult.error}")
        println("on disk: exists=${onDisk?.exists()} length=${onDisk?.length()}")

        if (stopResult.success) {
            assertTrue(
                "REGRESSION of issue 43: stop reported success with no file at all.",
                onDisk != null && onDisk.isFile,
            )
            assertTrue(
                "REGRESSION of issue 43: stop reported success over a ZERO-BYTE file. " +
                    "That is the exact false success the issue recorded.",
                onDisk!!.length() > 0,
            )
            assertTrue(
                "the reported sizeBytes disagrees with the file on disk",
                stopResult.sizeBytes == onDisk.length(),
            )
        } else {
            // A failure is acceptable ONLY with an explanation. The old defect was
            // success with nothing; a refusal is honest, silence is not.
            assertTrue(
                "stop reported failure with no reason, which tells the caller nothing",
                !stopResult.error.isNullOrBlank(),
            )
        }
    }

    /**
     * Stopping without ever starting must refuse, not invent a path.
     */
    @Test
    fun stopWithoutStartRefuses() = runBlocking {
        val executor = ScreenToolExecutor(context)
        val stop = executor.execute(
            ScreenToolDefinitions.RECORD_SCREEN,
            mapOf("action" to "stop"),
        )
        val result = decode(stop.rawJson)
        println("stop with no start -> success=${result.success} error=${result.error}")

        assertFalse(
            "stop with no recording must not report success. This is how issue 43 " +
                "handed back a file from an earlier session.",
            result.success,
        )
        assertTrue("a refusal must say why", !result.error.isNullOrBlank())
    }
}
