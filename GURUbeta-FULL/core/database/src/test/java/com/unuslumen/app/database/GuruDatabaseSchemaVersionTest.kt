// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The schema version this build reports, and the one Room will actually write.
 *
 * WHY THIS IS A TEST AND NOT A REFLECTION CALL. The version used to be read off the `@Database`
 * annotation at runtime, and that annotation is CLASS-retained, so it is in the bytecode and invisible
 * to the JVM. The read returned null, the fallback produced 0, and every build reported schema 0 to
 * everything that asked. An agent declaring `minimumRuntimeSchemaVersion = 1` was then refused with
 * "this agent needs runtime schema 1 and this build is 0, so it was made for a newer app", which
 * reads like the agent's fault and is the app's.
 *
 * So the constant is a literal and this test is the guard that reflection was pretending to be. It
 * reads the annotation out of the SOURCE, where it is visible, and fails if the two disagree.
 */
class GuruDatabaseSchemaVersionTest {

    private val source = File("src/main/java/com/unuslumen/app/database/GuruDatabase.kt")

    /** `version = N` on the @Database annotation, read from the file that declares it. */
    private fun declaredVersion(): Int {
        assertTrue("GuruDatabase.kt must exist at the path this test reads", source.exists())
        val text = source.readText()
        val match = Regex("""version\s*=\s*(\d+)""").find(text)
            ?: error("no `version = N` found on the @Database annotation")
        return match.groupValues[1].toInt()
    }

    @Test
    fun theReportedSchemaVersionIsNotZero() {
        /*
         * THE FAULT ITSELF. Zero is not a valid Answer for "what schema does this build write": it is
         * below every version Room has ever produced here, so anything comparing against it concludes
         * either that an agent is too new or that a snapshot is. A zero here is always a broken read.
         */
        assertTrue(
            "SCHEMA_VERSION must be the real number, not the old failed read's fallback",
            guruDatabase.SCHEMA_VERSION > 0,
        )
    }

    @Test
    fun theReportedSchemaVersionMatchesTheDatabaseAnnotation() {
        /*
         * THE DRIFT GUARD. A bump that misses this constant makes the build's answer wrong in exactly
         * the way the reflection made it wrong, only with a number instead of a zero, which is harder
         * to notice.
         */
        assertEquals(
            "SCHEMA_VERSION must equal the @Database version in GuruDatabase.kt",
            declaredVersion(),
            guruDatabase.SCHEMA_VERSION,
        )
    }

    @Test
    fun theAgentRuntimeSchemaAtLeastMatchesThisBuild() {
        /*
         * THE CHECK THAT WAS FAILING ON DEVICE. `AgentRuntime.runtimeCapabilities` reports this value
         * as `runtimeSchemaVersion`, and `AgentConfigurationValidator` refuses any agent whose manifest
         * asks for more. With the old read this was 0, so a manifest asking for the minimum possible
         * version was refused. A build reporting its own schema must at least satisfy the lowest
         * version anybody could reasonably declare.
         */
        assertTrue(
            "schema ${guruDatabase.SCHEMA_VERSION} would refuse an agent asking for schema 1",
            guruDatabase.SCHEMA_VERSION >= 1,
        )
    }
}
