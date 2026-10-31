// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import com.unuslumen.app.data.tools.registry.permission.GrantCheck
import com.unuslumen.app.data.tools.registry.permission.GrantState
import com.unuslumen.app.data.tools.registry.permission.PermissionSpec
import com.unuslumen.app.data.tools.registry.permission.PermissionTier
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the permission foundation.
 *
 * WHY THESE TESTS AND NOT A SCREENSHOT. The sentences below are what a person
 * reads when a tool refuses, and the tier decisions are what stop a tool telling
 * somebody to grant a privileged permission that no dialog can grant. Both are
 * pure logic, so they are checked here on the JVM where a failure names the exact
 * rule that broke. The device test next door proves the platform agrees.
 */
class GrantCheckTest {

    // ------------------------------------------------------------------
    // Tiers match the platform, not the brief
    // ------------------------------------------------------------------

    /**
     * The batch brief called PROCESS_OUTGOING_CALLS and ACCEPT_HANDOVER privileged.
     * On API 37 the platform database reports both as ordinary dangerous
     * permissions, so a tool that reported them as privileged would tell a person
     * their mistake was a missing system tier rather than a dialog they never
     * allowed. This test pins the platform's answer so a future "correction" back
     * to the brief's wording fails loudly.
     */
    @Test
    fun outgoingCallAndHandoverAreRuntimeNotPrivileged() {
        assertEquals(PermissionTier.RUNTIME, TeamThreePermissions.PROCESS_OUTGOING_CALLS.tier)
        assertEquals(PermissionTier.RUNTIME, TeamThreePermissions.ACCEPT_HANDOVER.tier)
    }

    @Test
    fun theThreeCapturePermissionsArePrivileged() {
        assertEquals(PermissionTier.PRIVILEGED, TeamThreePermissions.CAPTURE_AUDIO_OUTPUT.tier)
        assertEquals(PermissionTier.PRIVILEGED, TeamThreePermissions.CAPTURE_VIDEO_OUTPUT.tier)
        assertEquals(PermissionTier.PRIVILEGED, TeamThreePermissions.CAPTURE_SECURE_VIDEO_OUTPUT.tier)
        assertEquals(PermissionTier.PRIVILEGED, TeamThreePermissions.READ_PRECISE_PHONE_STATE.tier)
    }

    /**
     * protectionLevel=normal permissions are held from install. Reporting one as
     * "not granted, go to Settings" sends a person to a screen that cannot fix it.
     */
    @Test
    fun normalProtectionPermissionsAreInstallTierNotRuntime() {
        listOf(
            TeamThreePermissions.MANAGE_OWN_CALLS,
            TeamThreePermissions.MANAGE_ACCOUNTS,
            TeamThreePermissions.AUTHENTICATE_ACCOUNTS,
            TeamThreePermissions.USE_CREDENTIALS,
            TeamThreePermissions.MODIFY_AUDIO_SETTINGS,
            TeamThreePermissions.HIGH_SAMPLING_RATE_SENSORS,
        ).forEach {
            assertEquals("${it.permission} must be INSTALL", PermissionTier.INSTALL, it.tier)
            assertTrue("${it.permission} needs no dialog", it.grantedAtInstall)
            assertNull("${it.permission} must not offer a settings route", it.grantRoute)
        }
    }

    @Test
    fun everyCataloguedPermissionIsFindableByItsPlatformString() {
        val catalogue = listOf(
            TeamThreePermissions.CALL_PHONE,
            TeamThreePermissions.READ_SMS,
            TeamThreePermissions.SEND_SMS,
            TeamThreePermissions.READ_CONTACTS,
            TeamThreePermissions.ACCESS_FINE_LOCATION,
            TeamThreePermissions.UWB_RANGING,
        )
        catalogue.forEach {
            assertEquals(it, TeamThreePermissions.forPermission(it.permission))
        }
    }

    // ------------------------------------------------------------------
    // The refusal sentence
    // ------------------------------------------------------------------

    /**
     * A runtime denial must name three things and nothing more: what is missing,
     * why it matters, and the exact route. The route is asserted literally because
     * a vague "check your settings" is the failure mode this batch exists to
     * remove.
     */
    @Test
    fun aRuntimeDenialNamesTheMissingPermissionTheConsequenceAndTheRoute() {
        val check = GrantCheck.of(TeamThreePermissions.READ_SMS, granted = false)
        assertEquals(GrantState.NOT_GRANTED, check.state)
        assertTrue(check.denied)

        val sentence = check.sentence()
        assertTrue("must open by naming the denial: $sentence", sentence.startsWith("Permission denied: "))
        // The label is the name a person actually sees in Settings, not a phrase
        // invented for the message: that is what makes the route followable.
        assertTrue("must name the Settings group: $sentence", sentence.contains("the SMS permission"))
        assertTrue("must state the consequence: $sentence", sentence.contains("find the text you are talking about"))
        assertTrue("must give the exact route: $sentence", sentence.contains("Settings > Apps > GURU > Permissions > SMS > Allow"))
        assertTrue("must end with a full stop: $sentence", sentence.trim().endsWith("."))
        assertFalse("no double negative allowed: $sentence", sentence.contains("not un"))
        assertFalse("no singular/plural grammar break: $sentence", sentence.contains(" calls is "))
    }

    /**
     * A privileged denial must never sound like the person's fault, and must name
     * the only path that can satisfy it. This is the sentence that keeps #53, #54,
     * #57, #62 and the three capture permissions honest while Shizuku is absent.
     */
    @Test
    fun aPrivilegedDenialBlamesThePlatformAndNamesShizuku() {
        val check = GrantCheck.of(TeamThreePermissions.CAPTURE_AUDIO_OUTPUT, granted = false)
        assertEquals(GrantState.PRIVILEGED, check.state)
        assertTrue(check.denied)

        val sentence = check.sentence()
        assertTrue("must say the platform reserves it: $sentence", sentence.contains("reserves it for system apps"))
        assertTrue("must name the only path: $sentence", sentence.contains("Shizuku or Device Owner"))
        assertFalse("must not send them to a settings dialog: $sentence", sentence.contains("Settings > Apps"))
        assertFalse("must not blame the person: $sentence", sentence.contains("you have not"))
    }

    @Test
    fun aMissingInstallTierPermissionIsReportedAsABuildFault() {
        val check = GrantCheck.of(TeamThreePermissions.MODIFY_AUDIO_SETTINGS, granted = false)
        assertEquals(GrantState.INSTALL, check.state)
        val sentence = check.sentence()
        assertTrue("must name it as an install-time fault: $sentence", sentence.contains("Install-time fault"))
        assertTrue("must point at the manifest: $sentence", sentence.contains("manifest"))
        assertFalse("must not offer a dialog that cannot exist: $sentence", sentence.contains("Grant it in"))
    }

    @Test
    fun aGrantedPermissionReportsGrantedWithNoRoute() {
        val check = GrantCheck.of(TeamThreePermissions.CAMERA, granted = true)
        assertEquals(GrantState.GRANTED, check.state)
        assertTrue(check.allowed)
        assertFalse(check.denied)
        assertTrue(check.sentence().startsWith("Granted, so GURU can take a photo"))
    }

    /**
     * Absent hardware is not a permission problem. The sentence must be about the
     * device, in one line, and must never suggest a grant.
     */
    @Test
    fun absentHardwareIsReportedAsTheDeviceNotTheGrant() {
        val check = GrantCheck.absent(
            permission = "hardware:heart_rate",
            label = "heart-rate sensor",
            why = "GURU could read your heart rate",
        )
        assertEquals(GrantState.ABSENT, check.state)
        assertTrue(check.denied)
        val sentence = check.sentence()
        assertTrue("must blame the device: $sentence", sentence.contains("this device does not have it"))
        assertTrue("must state the consequence: $sentence", sentence.contains("GURU cannot read your heart rate"))
        assertFalse("must never suggest granting: $sentence", sentence.contains("Grant"))
    }

    /**
     * A permission the catalogue has never heard of is a tool defect. Guessing a
     * settings route here is how a tool sends somebody to a screen that has
     * nothing to do with the fault.
     */
    @Test
    fun anUnknownPermissionIsReportedAsAToolDefectNotAGrantProblem() {
        val check = GrantCheck.unknown("android.permission.SOME_FUTURE_THING")
        assertEquals(GrantState.UNKNOWN_SPEC, check.state)
        assertNull("no route may be invented", check.spec.grantRoute)
        val sentence = check.sentence()
        assertTrue("must name it a tool defect: $sentence", sentence.contains("Tool defect"))
        assertFalse("must not tell them to grant it: $sentence", sentence.contains("Grant it in"))
    }

    // ------------------------------------------------------------------
    // The catalogue is complete and honest
    // ------------------------------------------------------------------

    /**
     * All 44 permissions named in the batch brief must be present. A missing entry
     * would surface as an UNKNOWN_SPEC refusal at runtime, which reads to a person
     * as a tool defect, so the completeness is checked here rather than discovered
     * on a device.
     */
    @Test
    fun everyPermissionInTheBatchBriefIsCatalogued() {
        val expected = listOf(
            "android.permission.CALL_PHONE",
            "android.permission.ANSWER_PHONE_CALLS",
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_PHONE_NUMBERS",
            "android.permission.READ_PRECISE_PHONE_STATE",
            "android.permission.PROCESS_OUTGOING_CALLS",
            "android.permission.USE_SIP",
            "android.permission.MANAGE_OWN_CALLS",
            "android.permission.ACCEPT_HANDOVER",
            "android.permission.READ_SMS",
            "android.permission.SEND_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.RECEIVE_MMS",
            "android.permission.RECEIVE_WAP_PUSH",
            "android.permission.READ_CELL_BROADCASTS",
            "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALL_LOG",
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
            "android.permission.GET_ACCOUNTS",
            "android.permission.MANAGE_ACCOUNTS",
            "android.permission.AUTHENTICATE_ACCOUNTS",
            "android.permission.USE_CREDENTIALS",
            "android.permission.READ_CALENDAR",
            "android.permission.WRITE_CALENDAR",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.ACCESS_MEDIA_LOCATION",
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.CAPTURE_AUDIO_OUTPUT",
            "android.permission.CAPTURE_VIDEO_OUTPUT",
            "android.permission.CAPTURE_SECURE_VIDEO_OUTPUT",
            "android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.HIGH_SAMPLING_RATE_SENSORS",
            "android.permission.BODY_SENSORS",
            "android.permission.BODY_SENSORS_BACKGROUND",
            "android.permission.ACTIVITY_RECOGNITION",
            "android.permission.UWB_RANGING",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_ADVERTISE",
        )
        // 43, not 42. The batch brief listed 42 permissions across its three
        // agents and said Agent A covered "#49 to #62", but GitHub issue #63 is
        // READ_CELL_BROADCASTS and the brief never mentioned it. Its existence
        // shifts every later issue number by one, which is why READ_CALL_LOG is
        // issue #64 on GitHub and not #63 as the brief states. The permission is
        // in the same domain, it is already declared in the manifest, so it is
        // catalogued here rather than left as an UNKNOWN_SPEC at runtime.
        assertEquals("catalogue size", 43, TeamThreePermissions.all.size)
        expected.forEach {
            assertNotNull("missing from catalogue: $it", TeamThreePermissions.forPermission(it))
        }
        assertNotNull(
            "READ_CELL_BROADCASTS exists on GitHub as #63 and must be catalogued",
            TeamThreePermissions.forPermission("android.permission.READ_CELL_BROADCASTS"),
        )
    }

    /**
     * Every refusal sentence must be a single line of grammar a person can act on,
     * which means no empty labels, no doubled spaces, and no sentence that failed
     * to interpolate. A half-built sentence is worse than none because it reads
     * like the app is broken rather than the permission.
     */
    @Test
    fun everyRefusalSentenceIsGrammaticalAndActionable() {
        val everyState = TeamThreePermissions.all.values.flatMap { spec ->
            listOf(
                GrantCheck.of(spec, granted = false),
                GrantCheck.of(spec, granted = true),
            )
        } + GrantCheck.of(TeamThreePermissions.READ_SMS, granted = false)

        everyState.forEach { check ->
            val sentence = check.sentence()
            assertTrue("empty sentence for ${check.spec.permission}", sentence.isNotBlank())
            assertTrue("ends with a full stop: $sentence", sentence.trim().endsWith("."))
            assertFalse("doubled space: $sentence", sentence.contains("  "))
            assertFalse("uninterpolated: $sentence", sentence.contains("\${"))
            assertFalse("raw permission string leaked into prose: $sentence", sentence.contains("android.permission"))
        }
    }

    @Test
    fun aSpecWithNoGrantRouteCannotProduceAGrantInstruction() {
        val spec = PermissionSpec(
            permission = "android.permission.FAKE",
            tier = PermissionTier.RUNTIME,
            label = "a fake thing",
            why = "GURU can do nothing with it",
            grantRoute = null,
        )
        val sentence = GrantCheck.of(spec, granted = false).sentence()
        assertTrue("must say it is not grantable: $sentence", sentence.contains("cannot be granted on this build"))
    }
}
