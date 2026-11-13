// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 181's capability map, held against the acceptance list.
 *
 * The map's whole job is being true about what works, so every test here is about a way it could
 * mislead: saying a missing grant is unfixable, saying a missing sensor is a missing grant, or
 * burying the thing the person came to the screen for.
 */
class CapabilityMapTest {

    private fun spec(
        permission: String,
        label: String,
        why: String,
        tier: PermissionTier = PermissionTier.RUNTIME,
        route: String? = "Settings > Apps > GURU > Permissions",
    ) = PermissionSpec(permission, tier, label, why, route)

    private fun granted(permission: String, why: String, tier: PermissionTier = PermissionTier.RUNTIME) =
        GrantCheck.of(spec(permission, "the thing", why, tier), granted = true)

    private fun denied(permission: String, why: String, tier: PermissionTier = PermissionTier.RUNTIME) =
        GrantCheck.of(spec(permission, "the thing", why, tier), granted = false)

    @Test
    fun anAvailableCapabilityReadsAsAPlainStatement() {
        val entry = CapabilityMap.entryOf(granted("android.permission.READ_SMS", "GURU can read your messages"))
        assertTrue(entry.available)
        assertEquals("I can read your messages.", entry.sentence)
        assertNull("nothing to unlock, so nothing is named", entry.unlockedBy)
    }

    @Test
    fun aMissingGrantReadsAsAnActionNamingThatOneGrant() {
        val entry = CapabilityMap.entryOf(denied("android.permission.READ_SMS", "GURU can read your messages"))
        assertFalse(entry.available)
        assertEquals(
            "I cannot read your messages yet. Granting the thing would let me.",
            entry.sentence,
        )
        assertTrue(
            "the sentence must name what to grant: ${entry.sentence}",
            entry.sentence.contains("Granting"),
        )
    }

    @Test
    fun aMissingSensorIsADeviceFactNotAGrant() {
        // Saying "granting X would let me" about a phone with no sensor sends a person to a
        // settings screen that cannot help, which is the failure this whole issue is about.
        val absent = GrantCheck.absent(
            permission = "android.permission.BODY_SENSORS",
            label = "heart-rate sensor",
            why = "GURU can read your heart rate",
        )
        val entry = CapabilityMap.entryOf(absent)
        assertFalse(entry.available)
        assertTrue(entry.isDeviceLimitation)
        assertNull("no grant exists that would help", entry.unlockedBy)
        assertTrue(
            "and the sentence must blame the phone: ${entry.sentence}",
            entry.sentence.contains("this phone does not have"),
        )
        assertFalse(
            "it must not invite a grant: ${entry.sentence}",
            entry.sentence.contains("Granting"),
        )
    }

    @Test
    fun anUngrantablePermissionDoesNotPromiseAGrant() {
        // A signature permission without the development flag cannot be granted by anything, so
        // "granting X would let me" would send somebody through a setup that cannot succeed.
        val check = GrantCheck.of(
            spec("android.permission.SOME_SIGNATURE", "signature thing", "GURU can do it", PermissionTier.PRIVILEGED),
            granted = false,
            developable = false,
        )
        val entry = CapabilityMap.entryOf(check)
        assertNull("nothing can be granted here", entry.unlockedBy)
        assertEquals(
            "I cannot do it, and no setting on this phone changes that.",
            entry.sentence,
        )
    }

    @Test
    fun aPrivilegedPermissionNamesTheRouteThatExists() {
        val check = GrantCheck.of(
            spec("android.permission.READ_PHONE_STATE", "Phone", "GURU can read call state", PermissionTier.PRIVILEGED),
            granted = false,
            developable = true,
        )
        val entry = CapabilityMap.entryOf(check)
        assertEquals("Phone", entry.unlockedBy)
        assertTrue(
            "the sentence must name the grant: ${entry.sentence}",
            entry.sentence.contains("Granting Phone"),
        )
    }

    @Test
    fun theCapabilityClauseLosesItsSubjectSoTheSentenceDoesNotRepeatIt() {
        // The catalogue writes "GURU can read your messages", and the entry's sentence supplies its
        // own subject. Left in, every row would read "I can GURU can read your messages."
        for (clause in listOf(
            "GURU can read your messages",
            "GURU could read your messages",
            "GURU reads your messages",
        )) {
            val entry = CapabilityMap.entryOf(granted("android.permission.READ_SMS", clause))
            assertFalse(
                "the clause must not still name GURU: ${entry.capability}",
                entry.capability.contains("GURU"),
            )
            assertTrue(
                "and it must begin in lower case so the sentence reads: ${entry.capability}",
                entry.capability.first().isLowerCase(),
            )
        }
    }

    @Test
    fun aClauseWithAFullStopLosesIt() {
        val entry = CapabilityMap.entryOf(granted("android.permission.READ_SMS", "GURU can read your messages."))
        assertEquals("read your messages", entry.capability)
    }

    @Test
    fun whatIsMissingSortsBeforeWhatWorksInsideEachValueBand() {
        // The thing a person came for is what is MISSING. A list that opens with fifteen working
        // rows buries the one they wanted to fix.
        val entries = CapabilityMap.build(
            listOf(
                granted("android.permission.POST_NOTIFICATIONS", "GURU can notify you"),
                denied("android.permission.READ_SMS", "GURU can read your messages"),
                granted("android.permission.CAMERA", "GURU can use the camera"),
            ),
        )
        val highValue = entries.filter { it.value == CapabilityMap.Value.HIGH }
        assertEquals("both HIGH entries are present", 2, highValue.size)

        val firstAvailable = highValue.indexOfFirst { it.available }
        val lastUnavailable = highValue.indexOfLast { !it.available }
        assertTrue(
            "the missing capability must precede the working one: ${highValue.map { it.id to it.available }}",
            firstAvailable < 0 || lastUnavailable < 0 || lastUnavailable < firstAvailable,
        )
        assertFalse(
            "and the first row in the band must be the one that does not work",
            highValue.first().available,
        )
    }

    @Test
    fun valueOrderingPutsTheCapabilitiesThatMatterFirst() {
        val entries = CapabilityMap.build(
            listOf(
                denied("android.permission.READ_CALENDAR", "GURU can read your calendar"),
                denied("android.permission.READ_SMS", "GURU can read your messages"),
            ),
        )
        assertEquals(
            "reading messages outranks reading the calendar",
            "android.permission.READ_SMS",
            entries.first().id,
        )
    }

    @Test
    fun anInstallTimePermissionIsTheLeastInterestingRow() {
        val check = GrantCheck.of(
            spec("android.permission.INTERNET", "internet", "GURU can reach the network", PermissionTier.INSTALL),
            granted = true,
        )
        assertEquals(CapabilityMap.Value.LOW, CapabilityMap.valueOf(check.spec))
    }

    @Test
    fun anUnknownPermissionGetsAValueRatherThanCrashing() {
        val check = GrantCheck.of(
            spec("com.example.SOMETHING_NEW", "new thing", "GURU can do a new thing"),
            granted = false,
        )
        assertEquals(CapabilityMap.Value.MEDIUM, CapabilityMap.valueOf(check.spec))
    }

    @Test
    fun theSummaryCountsWhatWorks() {
        val entries = CapabilityMap.build(
            listOf(
                granted("android.permission.READ_SMS", "GURU can read your messages"),
                denied("android.permission.CAMERA", "GURU can use the camera"),
                denied("android.permission.RECORD_AUDIO", "GURU can record audio"),
            ),
        )
        val summary = CapabilityMap.summarise(entries)
        assertEquals(1, summary.available)
        assertEquals(3, summary.total)
        assertTrue(
            "the one line a person reads first must count: ${summary.sentence}",
            summary.sentence.contains("1 of 3"),
        )
    }

    @Test
    fun anEmptyMapSaysSoRatherThanClaimingEverythingWorks() {
        val summary = CapabilityMap.summarise(emptyList())
        assertTrue(
            "an empty map must not read as a working app: ${summary.sentence}",
            summary.sentence.contains("nothing to show"),
        )
    }

    @Test
    fun everythingWorkingIsStatedPlainly() {
        val entries = CapabilityMap.build(
            listOf(granted("android.permission.READ_SMS", "GURU can read your messages")),
        )
        assertEquals(
            "Everything GURU can do on this phone is working.",
            CapabilityMap.summarise(entries).sentence,
        )
    }

    @Test
    fun nothingWorkingNamesTheRouteInTheSummaryItself() {
        val entries = CapabilityMap.build(
            listOf(denied("android.permission.READ_SMS", "GURU can read your messages")),
        )
        val sentence = CapabilityMap.summarise(entries).sentence
        assertTrue(
            "with nothing working the summary must say the rows explain how to fix it: $sentence",
            sentence.contains("names the one grant"),
        )
    }

    @Test
    fun onePermissionAppearsOnceHoweverManyToolsNeedIt() {
        // Forty-four tools share far fewer permissions, so a row per tool would be unreadable and a
        // row per permission is the only list a person can act on.
        val entries = CapabilityMap.build(
            listOf(
                granted("android.permission.READ_SMS", "GURU can read your messages"),
                granted("android.permission.READ_SMS", "GURU can read your messages"),
            ),
        )
        assertEquals("one row per permission", 1, entries.size)
        assertEquals("android.permission.READ_SMS", entries.first().id)
    }

    @Test
    fun aCapabilityThatWorksForOneToolIsNotHiddenByADenialForAnother() {
        // Two tools can share a permission, and if one check says granted and another says denied the
        // capability genuinely works. Keeping the denial would tell a person something is broken
        // when it is not, which is the pessimism in the other direction.
        val entries = CapabilityMap.build(
            listOf(
                denied("android.permission.READ_SMS", "GURU can read your messages"),
                granted("android.permission.READ_SMS", "GURU can read your messages"),
            ),
        )
        assertEquals(1, entries.size)
        assertTrue("the working one wins", entries.first().available)
    }
}
