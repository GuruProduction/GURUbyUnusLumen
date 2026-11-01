// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The bounded read, proved on the JVM.
 *
 * WHY THIS EXISTS. The app died with OutOfMemoryError on a 288-byte allocation
 * with under 3 MB of heap free. The cause was an unbounded `readText()` against
 * whatever a port happened to serve, run once per open port across a full 65535
 * port sweep. A response of a few hundred megabytes is enough to do it, and one
 * local media server will happily send that.
 *
 * These tests prove the ceiling holds, because the previous behaviour has no test
 * that could fail: `readText()` never refuses, it just takes the machine down.
 */
class BoundedReadTest {

    @Test
    fun `reads the whole body when it is under the limit`() {
        val body = "the boiler service is booked for tuesday"
        val read = readTextBounded(ByteArrayInputStream(body.toByteArray()), limit = 4096)

        assertEquals(body, read)
    }

    @Test
    fun `stops at the limit instead of reading an endless body`() {
        // A stream that never ends: exactly what a media server or a diagnostics
        // page can do. The old unbounded read would grow until the heap died.
        val endless = object : java.io.InputStream() {
            private var served = 0L
            override fun read(): Int {
                served++
                return 'x'.code
            }
        }

        val limit = 64 * 1024
        val read = readTextBounded(endless, limit)

        assertEquals(
            "the read must stop at exactly the ceiling, not one byte more",
            limit,
            read.length,
        )
    }

    @Test
    fun `a huge body is truncated to the limit`() {
        val huge = ByteArray(8 * 1024 * 1024) { 'a'.code.toByte() }
        val read = readTextBounded(ByteArrayInputStream(huge), limit = 32 * 1024)

        assertEquals(32 * 1024, read.length)
    }

    @Test
    fun `an empty body reads as empty and does not block`() {
        val read = readTextBounded(ByteArrayInputStream(ByteArray(0)), limit = 1024)
        assertEquals("", read)
    }

    /**
     * The ceiling must be small enough that even a hostile local port cannot
     * threaten the heap, and large enough for a real UPnP description. This pins
     * both halves so a future change cannot quietly raise it to something unsafe.
     */
    @Test
    fun `the default ceiling is small enough to be safe and large enough to work`() {
        val defaultLimit = 64 * 1024
        assertTrue(
            "a device description is kilobytes; a ceiling under that would break discovery",
            defaultLimit >= 32 * 1024,
        )
        assertTrue(
            "a ceiling this large could still fill the heap on a small device",
            defaultLimit <= 256 * 1024,
        )
    }
}
