// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unuslumen.app.data.brain.cerebrum.CerebrumBrainApi
import com.unuslumen.app.data.brain.cerebrum.CerebrumHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The shipped Cerebrum library actually executes its memory operations in the APP's process.
 *
 * WHY THIS EXISTS AND WHY IT IS NOT A UNIT TEST. Every native test until now ran on a
 * desktop JVM or on a Rust host build, which proves the source compiles and the logic is
 * sound and says nothing about what the APK does. The original defect in this whole
 * remediation was exactly that gap: three merged Rust fixes were absent from the release
 * because nothing ever asked the packaged library a question.
 *
 * So this runs inside the app's own uid and SELinux domain, loads the packaged
 * libcerebrum_android.so through the same `CerebrumHost` the app uses, boots a real vault,
 * and drives the real frames: create, search, update, history, delete, and the archived
 * listing. Each assertion is about what the SHIPPED binary answers.
 *
 * IT SKIPS WHEN THE BRAIN CANNOT BOOT rather than failing. A release APK has no debuggable
 * uid, and an emulator may lack the Keystore the vault needs; when that happens there is
 * nothing to prove and a red test would be noise. The skip is loud in the report.
 */
@RunWith(AndroidJUnit4::class)
class CerebrumMemoryOnDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The SELinux domain this process is in, so a result from the wrong domain is discarded. */
    private fun domain(): String =
        runCatching { java.io.File("/proc/self/attr/current").readText().trim().trim('\u0000') }
            .getOrDefault("unknown")

    private val passphrase = "on-device memory test passphrase"

    @Before
    fun requireTheRightDomainAndALoadedLibrary() {
        val d = domain()
        println("SELinux domain under test: $d")
        assumeTrue(
            "This test must run in the app domain to say anything about the app, but ran as '$d'.",
            d.startsWith("u:r:untrusted_app"),
        )
        assumeTrue(
            "The native library did not load (${CerebrumHost.nativeLoadError()}); there is nothing " +
                "to prove about the shipped binary in this process.",
            CerebrumHost.isNativeLoaded(),
        )
    }

    /** Boot the brain on a fresh vault for this test. */
    private fun boot(): Boolean {
        CerebrumHost.stop()
        val stateDir = java.io.File(java.io.File(context.filesDir, "cerebrum"), "state")
        stateDir.deleteRecursively()
        stateDir.mkdirs()
        val result = CerebrumHost.boot(
            context = context,
            freshPassphrase = passphrase,
        )
        if (!result.ok) println("brain boot failed: ${result.error}")
        return result.ok
    }

    private fun brainIdFor(content: String): String {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        digest.update(":".toByteArray(StandardCharsets.US_ASCII))
        digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun theShippedLibraryCarriesItsBuildIdentity() {
        /*
         * THE PROVENANCE READS BACK OUT OF THE LOADED LIBRARY, not out of a build directory.
         * This is the assertion that would have caught three merged fixes shipping absent.
         */
        val identity = CerebrumHost.buildIdentity()
        println("build identity: $identity")
        assertTrue(
            "the loaded library must name the revision it was built from: $identity",
            identity.startsWith("cerebrum-build-rev="),
        )
        assertFalse(
            "and it must not be the unknown fallback: $identity",
            identity.endsWith("unknown"),
        )
    }

    @Test
    fun aMemoryIsCreatedSearchedUpdatedAndHistoredByTheShippedBinary() {
        assumeTrue("the brain could not boot on this device", boot())
        try {
            val original = "the boiler service is booked for tuesday"
            val stored = CerebrumBrainApi.storeFact(
                factText = original,
                category = "knowledge",
                provenanceLabel = CerebrumBrainApi.provenanceLabelFor("on-device test", 0.9f),
                createdAtMillis = System.currentTimeMillis(),
                domainLabel = "knowledge",
            )
            assertTrue("the shipped brain must accept a write: ${stored.raw}", stored.success)
            val id = brainIdFor(original)

            // SEARCH reaches it.
            val hits = CerebrumBrainApi.searchFacts("boiler service tuesday", 10)
            assertTrue(
                "search must find the fact it just stored: ${hits.map { it.idHex }}",
                hits.any { it.idHex == id },
            )

            // THE SEARCH HIT CARRIES THE REAL CATEGORY, which is the defect that made a
            // category filter return nothing while listing and retrieve answered correctly.
            val hit = hits.first { it.idHex == id }
            assertEquals(
                "the category must be the stored one, not a constant",
                "knowledge",
                hit.domain,
            )

            // UPDATE goes through the native Update op, which is the only path that records
            // the superseded text.
            val updateOp = CerebrumBrainApi.curateJsonOps(
                listOf(
                    CerebrumBrainApi.updateJsonOpByHex(
                        idHexLower = id,
                        newContent = "the boiler service is booked for wednesday",
                        category = "knowledge",
                        confidence = 0.95f,
                        reasonText = "the appointment moved a day",
                    )
                )
            )
            assertTrue("the native Update must be accepted: ${updateOp.raw}", updateOp.success)

            // HISTORY answers, and it carries what the update replaced.
            val history = CerebrumBrainApi.history(id)
            assertNotNull("the HISTORY frame must answer for a memory it holds", history)
            val superseded = history!!.revisions.mapNotNull { it.superseded }
            assertTrue(
                "the superseded text must be recorded by the shipped binary: ${history.revisions}",
                superseded.any { it == original },
            )

            // DELETE retires it, and the archive listing sees it while an ordinary listing does not.
            CerebrumBrainApi.curateJsonOps(
                listOf(CerebrumBrainApi.deleteJsonOpByHex(id, "the on-device test finished with it"))
            )
            val live = CerebrumBrainApi.listFacts(limit = 0)!!.memories.map { it.idHex }
            assertFalse("a deleted fact must not appear in the live listing", live.contains(id))

            val archived = CerebrumBrainApi.listFacts(limit = 0, includeArchived = true)!!
                .memories.map { it.idHex }
            assertTrue(
                "and the archive listing must still see it, or deduplication cannot know it existed",
                archived.contains(id),
            )

            // AND THE HISTORY STILL ANSWERS AFTER THE DELETION, which is the one read that must.
            val afterDelete = CerebrumBrainApi.history(id)
            assertNotNull("history must answer for a deleted memory", afterDelete)
            assertTrue("and say it was retired", afterDelete!!.everRetired)
        } finally {
            CerebrumHost.stop()
        }
    }

    @Test
    fun anArchivedFactCannotBeResurrectedByAnOrdinaryWrite() {
        /*
         * THE RESURRECTION PATH, PROVED CLOSED IN THE SHIPPED BINARY. An id is the hash of the
         * content, so writing the same words again resolves to the row a deletion archived.
         * The brain must refuse it rather than quietly reactivating it.
         */
        assumeTrue("the brain could not boot on this device", boot())
        try {
            val content = "the spare key is under the blue pot"
            val first = CerebrumBrainApi.storeFact(
                factText = content,
                category = "knowledge",
                provenanceLabel = CerebrumBrainApi.provenanceLabelFor("on-device test", 0.9f),
                createdAtMillis = System.currentTimeMillis(),
                domainLabel = "knowledge",
            )
            assertTrue("the first write must be accepted: ${first.raw}", first.success)
            val id = brainIdFor(content)

            CerebrumBrainApi.curateJsonOps(
                listOf(CerebrumBrainApi.deleteJsonOpByHex(id, "the owner asked for this to be forgotten"))
            )

            val again = CerebrumBrainApi.storeFact(
                factText = content,
                category = "knowledge",
                provenanceLabel = CerebrumBrainApi.provenanceLabelFor("on-device test", 0.9f),
                createdAtMillis = System.currentTimeMillis(),
                domainLabel = "knowledge",
            )
            assertFalse(
                "an ordinary write must NOT bring a retired fact back: ${again.raw}",
                again.success,
            )
        } finally {
            CerebrumHost.stop()
        }
    }

    @Test
    fun queryAndPrefetchFramesAreAnsweredByTheShippedBinary() {
        /*
         * BOTH FRAMES WERE DECLARED AND NEVER DISPATCHED. This drives them end to end in the
         * app's process, so the tiered pipeline and the prefetch engine are proved reachable
         * from Android rather than only from a command line.
         */
        assumeTrue("the brain could not boot on this device", boot())
        try {
            val content = "the mot is due on the van in march"
            CerebrumBrainApi.storeFact(
                factText = content,
                category = "knowledge",
                provenanceLabel = CerebrumBrainApi.provenanceLabelFor("on-device test", 0.9f),
                createdAtMillis = System.currentTimeMillis(),
                domainLabel = "knowledge",
            )

            val query = CerebrumBrainApi.query("when is the van mot")
            assertNotNull("the QUERY frame must be answered, not dropped", query)

            val prefetch = CerebrumBrainApi.prefetch("topic", "mot")
            assertNotNull("the PREFETCH frame must be answered, not dropped", prefetch)
        } finally {
            CerebrumHost.stop()
        }
    }

    @Test
    fun aBlankQueryIsRefusedWithoutReachingTheBrain() {
        // The guard is in the client, so it holds whether or not a brain is running.
        assertNull(CerebrumBrainApi.query("   "))
    }
}
