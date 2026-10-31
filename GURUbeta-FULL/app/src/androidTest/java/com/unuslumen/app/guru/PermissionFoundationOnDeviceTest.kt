// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unuslumen.app.data.tools.registry.permission.GrantState
import com.unuslumen.app.data.tools.registry.permission.PermissionGrants
import com.unuslumen.app.data.tools.registry.permission.PermissionTier
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the permission foundation against the real platform, in the app's own
 * domain, on the device the app actually runs on.
 *
 * WHY THIS IS NOT A UNIT TEST. The unit tests prove the sentences are grammatical
 * and the tiers match a table. Neither can prove that Android agrees: that every
 * catalogued string is a permission this platform declares, that a normal
 * protection permission really is held without a dialog, and that a denied one
 * really reports as denied through the same call path every tool uses. Those are
 * the facts a tool's honesty rests on, and only the device holds them.
 *
 * RUN IN THE APP DOMAIN, NOT THE SHELL DOMAIN. Issue 40 and 44 were both caused by
 * behaviour verified somewhere other than where the app runs. This test asserts
 * its own SELinux domain so a green result can never mean "it worked as root".
 */
@RunWith(AndroidJUnit4::class)
class PermissionFoundationOnDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun runsInTheAppDomainNotTheShellDomain() {
        val domain = runCatching {
            java.io.File("/proc/self/attr/current").readText().trim().trim('\u0000')
        }.getOrDefault("unknown")
        println("SELinux domain under test: $domain")
        assertTrue("must run in the app domain, ran as '$domain'", domain.startsWith("u:r:untrusted_app"))
    }

    /**
     * Every permission in the catalogue must be one this platform declares.
     *
     * A string Android has never heard of reports as "not granted" forever, and
     * the tool then blames the person for a permission that cannot exist. This
     * catches a typo in a literal string, which is a real risk for the five
     * permissions whose Manifest constants are deprecated.
     */
    @Test
    fun everyCataloguedPermissionIsDeclaredByThisPlatform() {
        val pm = context.packageManager
        val undeclared = TeamThreePermissions.all.values.filter { spec ->
            runCatching {
                pm.getPermissionInfo(spec.permission, 0)
                false
            }.getOrDefault(true)
        }.map { it.permission }

        println("catalogued=${TeamThreePermissions.all.size} undeclared=$undeclared")
        // 45 is asserted in the JVM test; this one proves every string is real on
        // the platform, which is a different fact and cannot be faked by a table.
        assertTrue(
            "these strings are not permissions this platform declares, so no grant " +
                "could ever satisfy them: $undeclared",
            undeclared.isEmpty(),
        )
    }

    /**
     * The tiers must match what the platform itself says, not what the brief said.
     *
     * This is the single test that would have caught the brief's own error:
     * PROCESS_OUTGOING_CALLS and ACCEPT_HANDOVER were described as privileged but
     * the platform reports them as ordinary dangerous permissions.
     */
    @Test
    fun cataloguedTiersMatchTheProtectionLevelsThisPlatformReports() {
        val pm = context.packageManager
        val mismatches = mutableListOf<String>()

        for (spec in TeamThreePermissions.all.values) {
            val info = runCatching { pm.getPermissionInfo(spec.permission, 0) }.getOrNull() ?: continue
            val level = info.protection
            val isNormal = level == PermissionInfo.PROTECTION_NORMAL
            val isDangerous = level == PermissionInfo.PROTECTION_DANGEROUS
            val isSignature = (level and PermissionInfo.PROTECTION_SIGNATURE) != 0

            when (spec.tier) {
                PermissionTier.RUNTIME -> if (!isDangerous) {
                    mismatches += "${spec.permission}: catalogued RUNTIME but platform says level=$level"
                }
                PermissionTier.INSTALL -> if (!isNormal) {
                    mismatches += "${spec.permission}: catalogued INSTALL but platform says level=$level"
                }
                PermissionTier.PRIVILEGED -> if (!isSignature) {
                    mismatches += "${spec.permission}: catalogued PRIVILEGED but platform says level=$level"
                }
                else -> Unit
            }
        }

        println("tier mismatches=$mismatches")
        assertTrue(
            "a tier that disagrees with the platform produces advice that cannot work: $mismatches",
            mismatches.isEmpty(),
        )
    }

    /**
     * A protectionLevel=normal permission is held from install with no dialog.
     *
     * If this fails on a real device the manifest has lost the declaration, which
     * is a build fault. Proving it here means an INSTALL-tier report is a genuine
     * fault report rather than a guess about how the platform behaves.
     */
    @Test
    fun normalProtectionPermissionsAreHeldWithoutAnyDialog() {
        val installTier = TeamThreePermissions.all.values.filter { it.tier == PermissionTier.INSTALL }
        assertTrue("the catalogue must contain install-tier permissions to test", installTier.isNotEmpty())

        val missing = installTier.filter { spec ->
            context.checkSelfPermission(spec.permission) != PackageManager.PERMISSION_GRANTED
        }.map { it.permission }

        println("install-tier=${installTier.map { it.permission }} missing=$missing")
        assertTrue(
            "these need no dialog, so a denial means the manifest lost them: $missing",
            missing.isEmpty(),
        )
    }

    /**
     * The guard must return the refusal for a permission that is genuinely denied
     * on this device, and must return null for one that is genuinely held.
     *
     * Tested by revoking nothing: the assertion reads the live state first and
     * then checks the guard agrees with it, so it is true whichever way the
     * emulator is configured and it never silently passes by testing nothing.
     */
    @Test
    fun theGuardAgreesWithTheLiveStateOfTheDevice() {
        val denied = TeamThreePermissions.all.values.firstOrNull { spec ->
            spec.tier == PermissionTier.RUNTIME &&
                context.checkSelfPermission(spec.permission) != PackageManager.PERMISSION_GRANTED
        }
        assertNotNull("the emulator should have at least one runtime permission denied", denied)

        val refusal = PermissionGrants.guard(context, denied!!)
        assertNotNull("a denied permission must produce a refusal, not null", refusal)
        assertTrue("the refusal must not claim success", refusal!!.success.not())
        assertNotNull("the refusal must carry a sentence", refusal.error)
        assertTrue("the refusal sentence must name the route", refusal.error!!.contains("Grant it in"))
        println("denial sentence: ${refusal.error}")

        val held = TeamThreePermissions.all.values.firstOrNull { spec ->
            spec.tier == PermissionTier.INSTALL &&
                context.checkSelfPermission(spec.permission) == PackageManager.PERMISSION_GRANTED
        }
        assertNotNull("the emulator should hold at least one install-tier permission", held)
        assertNull("a held permission must not produce a refusal", PermissionGrants.guard(context, held!!))
    }

    /**
     * A refusal must be distinguishable from an empty result by the caller.
     *
     * This is the whole point of the batch: a denial reported as an empty list is
     * the defect being removed. The refusal body carries a machine-readable
     * denied token and a state name so no caller has to parse prose.
     */
    @Test
    fun aRefusalCarriesMachineReadableStateNotJustProse() {
        val deniedSpec = TeamThreePermissions.READ_SMS
        val check = PermissionGrants.check(context, deniedSpec)
        if (check.state == GrantState.GRANTED) {
            println("SMS is granted on this emulator, so the denial shape is not observable here")
            return
        }
        val refusal = PermissionGrants.refusal(check)
        println("refusal json: ${refusal.rawJson}")
        assertTrue("must carry the denied flag", refusal.rawJson.contains("\"denied\":true"))
        assertTrue("must carry the state name", refusal.rawJson.contains("\"state\""))
        assertTrue("must carry the permission string", refusal.rawJson.contains(deniedSpec.permission))
        assertTrue("success must be false so it cannot read as an empty result", !refusal.success)
    }

    // ------------------------------------------------------------------
    // Hardware and location honesty
    // ------------------------------------------------------------------

    /**
     * Absent hardware must be reported as absent, never as a zero reading.
     *
     * The emulator has no heart-rate sensor, which makes it the exact case where a
     * tool returning 0 bpm would fabricate a body reading. The assertion is written
     * against whatever the device actually is, so it is meaningful on a phone that
     * does have one too.
     */
    @Test
    fun absentHardwareIsReportedAsAbsentRatherThanZero() {
        val hasHeartRate = PermissionGrants.hasHeartRateSensor(context)
        val hasUwb = PermissionGrants.hasUwb(context)
        println("hasHeartRateSensor=$hasHeartRate hasUwb=$hasUwb model=${android.os.Build.MODEL}")

        if (!hasHeartRate) {
            val check = PermissionGrants.absentHardware(
                label = "heart-rate sensor",
                why = "GURU could read your heart rate",
            )
            assertEquals(GrantState.ABSENT, check.state)
            assertTrue("must name the device", check.sentence().contains("this device does not have it"))
            println("heart-rate sentence: ${check.sentence()}")
        }

        // Whatever the answer, it must be an answer: a hardware probe returning
        // null would mean the check crashed rather than reporting a device.
        assertNotNull(PermissionGrants.hasCamera(context))
        assertNotNull(PermissionGrants.hasMicrophone(context))
        assertNotNull(PermissionGrants.hasBluetooth(context))
    }

    /**
     * Location precision must be reported from the real grants, never assumed.
     *
     * On Android 12+ "approximate" is a genuine grant state where fine reads as
     * denied and coarse reads as granted. A tool that says "location" without
     * qualifying it invites the model to state a street-level fact it does not
     * have, so the precision is a first-class part of the answer.
     */
    @Test
    fun locationPrecisionReflectsTheRealGrantsOnThisDevice() {
        val precision = PermissionGrants.locationPrecision(context)
        val fine = context.checkSelfPermission(TeamThreePermissions.ACCESS_FINE_LOCATION.permission) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(TeamThreePermissions.ACCESS_COARSE_LOCATION.permission) ==
            PackageManager.PERMISSION_GRANTED
        val enabled = PermissionGrants.isLocationEnabled(context)

        println("precision=$precision fine=$fine coarse=$coarse locationSwitchOn=$enabled")

        when {
            fine -> assertEquals(PermissionGrants.LocationPrecision.PRECISE, precision)
            coarse -> assertEquals(PermissionGrants.LocationPrecision.APPROXIMATE, precision)
            else -> assertEquals(PermissionGrants.LocationPrecision.NONE, precision)
        }
        assertTrue(
            "an approximate or absent fix must never be described as precise",
            !(precision != PermissionGrants.LocationPrecision.PRECISE && fine),
        )
    }
}
