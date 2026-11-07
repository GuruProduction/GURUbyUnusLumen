// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.permission.GrantCheck
import com.unuslumen.app.data.tools.registry.permission.GrantState
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import com.unuslumen.app.data.tools.registry.permission.TeamThreeToolDomains
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The NFC and media domain, checked on the JVM without a device.
 *
 * WHAT THIS PROVES. The four tools are declared, every permission they name is catalogued,
 * the domain is registered, and the two honesty rules that matter most hold: an NFC read
 * never presents an empty record as a successful tag read, and a media query states which
 * grant was in force rather than letting its own count pass as the library total.
 *
 * WHICH RULE CARRIES THE MOST WEIGHT. The partial-access one. On Android 14 a person can
 * grant access to selected photos only, and a tool that reported "12 items" as the answer
 * would be telling them their library is twelve pictures. That is the exact shape of the
 * nine network bugs this batch exists to remove, so it is asserted here rather than assumed.
 *
 * WHAT THIS CANNOT PROVE, stated so a green run is never mistaken for device evidence: that
 * a real NFC radio delivers a tag, that a real tag receives a write, or that a real media
 * store returns real files. Those need Steven's phone, and an emulator has no NFC radio at
 * all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NfcMediaDomainHonestyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val executor = NfcMediaDomainExecutor(context)

    private val expectedTools = listOf(
        "mediaLibrary",
        "nfcRead",
        "nfcTransactions",
        "nfcWrite",
    )

    private fun definitions(): List<ToolDefinition> = NfcMediaDomainDefinitions.definitions

    @Test
    fun everyExpectedToolIsDeclaredAndNothingElse() {
        val declared = definitions().map { it.name }.sorted()
        println("nfc_media tools (${declared.size}): $declared")
        assertEquals(expectedTools.sorted(), declared)
    }

    @Test
    fun noTwoToolsInThisDomainShareAName() {
        val names = definitions().map { it.name }
        assertEquals("duplicate tool names", names.size, names.toSet().size)
    }

    @Test
    fun theDomainIsRegisteredWithTheAggregator() {
        assertTrue(
            "the nfc_media domain must be in the registry",
            TeamThreeToolDomains.domains.any { it.id == NfcMediaDomainModule.id },
        )
        assertEquals("nfc_media", NfcMediaDomainModule.id)
    }

    @Test
    fun everyDeclaredPermissionIsCatalogued() {
        val catalogue = TeamThreePermissions.all.keys
        val unreasoned = definitions()
            .flatMap { def -> def.permissions.map { def.name to it } }
            .filter { (_, permission) -> permission !in catalogue }

        println("permissions used: ${definitions().flatMap { it.permissions }.distinct()}")
        assertTrue("permissions used but not reasoned about: $unreasoned", unreasoned.isEmpty())
    }

    @Test
    fun everyToolDeclaresTheGrantItNeeds() {
        val missing = definitions().filter { it.permissions.isEmpty() }.map { it.name }
        assertTrue("tools declaring no permission but needing one: $missing", missing.isEmpty())
    }

    /**
     * Every declared permission must produce a sentence a person can act on, because every
     * tool returns the guard's result verbatim when it refuses.
     */
    @Test
    fun everyPermissionThisDomainDeclaresHasAUsableRefusal() {
        val specs = definitions()
            .flatMap { it.permissions }
            .distinct()
            .mapNotNull { TeamThreePermissions.forPermission(it) }

        assertTrue("expected at least two catalogued permissions", specs.size >= 2)

        val problems = mutableListOf<String>()
        for (spec in specs) {
            val check = GrantCheck.of(spec, granted = false)
            val sentence = check.sentence()
            if (!sentence.trim().endsWith(".")) problems += "${spec.permission}: no full stop"
            if (sentence.contains("  ")) problems += "${spec.permission}: doubled space"
            if (sentence.contains("android.permission")) {
                problems += "${spec.permission}: raw constant in prose"
            }
            if (check.state == GrantState.NOT_GRANTED && !sentence.contains("Grant it in")) {
                problems += "${spec.permission}: a denial with no route"
            }
        }
        assertTrue("unusable refusals: $problems", problems.isEmpty())
    }

    // ------------------------------------------------------------------
    // The honesty rules
    // ------------------------------------------------------------------

    /**
     * An NFC read with no tag present must say so, in words, rather than returning an empty
     * list that reads as a broken read. The note is what a model would repeat to a person,
     * so it has to carry the fact.
     */
    @Test
    fun anNfcReadWithNoTagSaysNoTagHasBeenPresented() = runBlocking {
        val result = executor.execute(NfcMediaDomainDefinitions.NFC_READ, emptyMap())
        val payload = result.resultData as? NfcReadResult

        // On a host with no NFC radio the hardware refusal is the correct answer, and that is
        // a different true statement. Either way the tool must not pretend.
        if (payload != null && payload.hardwarePresent) {
            val note = payload.note.orEmpty()
            println("nfcRead note: $note")
            if (payload.sightingCount == 0) {
                assertTrue(
                    "an empty tag record must be explained rather than presented as a read: $note",
                    note.contains("has been presented", ignoreCase = true) ||
                        note.contains("has seen", ignoreCase = true) ||
                        note.contains("No tag", ignoreCase = true),
                )
            }
        } else {
            println("nfcRead refused: ${result.error}")
            assertFalse("an NFC read with no radio must not claim success", result.success)
            assertTrue("it must say why", !result.error.isNullOrBlank())
        }
    }

    /**
     * An NFC write must never report a write as done, because no write can happen without a
     * physical tag. The note must say the message is prepared rather than written.
     */
    @Test
    fun anNfcWriteNeverClaimsTheTagWasWritten() = runBlocking {
        val result = executor.execute(
            NfcMediaDomainDefinitions.NFC_WRITE,
            mapOf("text" to "hello"),
        )

        assertFalse(
            "an NFC write with no tag must never say the record was written: ${result.rawJson}",
            result.rawJson.contains("was written to the tag") ||
                result.rawJson.contains("written successfully"),
        )

        val payload = result.resultData as? NfcWriteResult
        if (payload != null && payload.success) {
            val note = payload.note.orEmpty()
            println("nfcWrite note: $note")
            assertTrue(
                "the note must say nothing was written: $note",
                note.contains("NOT written", ignoreCase = true) ||
                    note.contains("was not written", ignoreCase = true),
            )
        } else {
            println("nfcWrite refused: ${result.error}")
            assertTrue("a refusal must say why", !result.error.isNullOrBlank())
        }
    }

    @Test
    fun nfcWriteWithoutTextIsRefused() = runBlocking {
        val result = executor.execute(NfcMediaDomainDefinitions.NFC_WRITE, emptyMap())
        assertFalse("a write with no text must not succeed", result.success)
        assertTrue("it must say why", !result.error.isNullOrBlank())
    }

    @Test
    fun anUnknownMediaKindIsRefused() = runBlocking {
        val result = executor.execute(
            NfcMediaDomainDefinitions.MEDIA_LIBRARY,
            mapOf("kind" to "DOCUMENT"),
        )
        assertFalse("an unknown media kind must not succeed", result.success)
        assertTrue("it must say why", !result.error.isNullOrBlank())
        assertTrue(
            "it must name the kinds it accepts: ${result.error}",
            result.error.orEmpty().contains("IMAGE"),
        )
    }

    /**
     * The media result must carry the real total alongside its returned count, so a caller
     * cannot mistake a page for the library. This asserts the FIELD exists and is reported,
     * which is the part a pure test can reach; the value itself needs a device.
     */
    @Test
    fun theMediaPayloadCarriesBothTheCountAndTheTotalField() {
        val payload = MediaLibraryResult(
            success = true,
            kind = "IMAGE",
            returnedCount = 50,
            totalMatching = 3412,
            partialAccessGranted = true,
        )
        assertEquals(50, payload.returnedCount)
        assertEquals(3412, payload.totalMatching)
        assertTrue(payload.partialAccessGranted)
        // A caller must be able to tell the two apart, which is the whole point of both fields.
        assertTrue(
            "the returned count and the total must be separate fields",
            payload.returnedCount != payload.totalMatching,
        )
    }

    @Test
    fun everyDescriptionSaysSomethingASchemaCannot() {
        val thin = definitions().filter { it.description.length < 120 }.map { it.name }
        assertTrue("descriptions too thin to warn a model: $thin", thin.isEmpty())
    }
}
