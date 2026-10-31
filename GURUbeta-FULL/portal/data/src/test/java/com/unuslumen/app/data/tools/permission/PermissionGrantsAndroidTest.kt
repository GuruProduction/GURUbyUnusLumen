// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import android.Manifest
import android.content.Context
import android.content.pm.PermissionInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.data.tools.registry.permission.GrantState
import com.unuslumen.app.data.tools.registry.permission.PermissionGrants
import com.unuslumen.app.data.tools.registry.permission.PermissionTier
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Android-facing half of the permission foundation, proven on the JVM.
 *
 * WHY ROBOLECTRIC RATHER THAN A DEVICE. The platform facts that matter here are:
 * does Android declare this permission string, what protection level does it
 * report, is a normal-tier permission held without a dialog, and what happens when
 * a sensor is absent. Robolectric answers all four with Android's own framework
 * code, in seconds, with no emulator eating the machine it runs on.
 *
 * WHAT THIS STILL DOES NOT PROVE, and must never be reported as if it did: that a
 * real phone with real hardware, real messages and real accounts returns real data
 * from a granted tool. That requires the physical device and is Steven's own test.
 *
 * The framework under test is the app's, not a generic one: Robolectric reads this
 * module's merged manifest, so a permission reported missing here is missing from
 * the build rather than from the stand-in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionGrantsAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * Grant and deny, through the application's context shadow.
     *
     * In Robolectric 4.14 the grant controls are public on ShadowContextWrapper and
     * are reached from the application, while ShadowPackageManager has no grant
     * method at all and ShadowInstrumentation's own are package-private. This wraps
     * the one working route so the tests read plainly.
     */
    private val shadowContext
        get() = org.robolectric.Shadows.shadowOf(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
        )

    private fun grant(permission: String) {
        shadowContext.grantPermissions(permission)
    }

    private fun deny(permission: String) {
        shadowContext.denyPermissions(permission)
    }

    // ------------------------------------------------------------------
    // The platform declares every permission we catalogued
    // ------------------------------------------------------------------

    /**
     * Every catalogued permission must be a well-formed platform permission name.
     *
     * WHAT THIS CAN AND CANNOT PROVE, stated because the difference matters.
     * Robolectric ships a partial permission database: it does not carry the full
     * platform set, so asking it whether Android declares CALL_PHONE answers no for
     * a permission the real platform absolutely has. An earlier version of this
     * test asserted that and failed on 38 true permissions, which was the test
     * being wrong rather than the catalogue.
     *
     * The "declared by the platform" fact was proven on a real API 37 device:
     * catalogued=43, undeclared=[]. That result lives in this batch's PR body and
     * is the only evidence that counts for it.
     *
     * What is still worth checking here is the shape of every string, because a
     * typo in a literal is a live risk for the five permissions whose Manifest
     * constants are deprecated. A malformed name cannot match anything anywhere.
     */
    @Test
    fun everyCataloguedPermissionNameIsWellFormed() {
        val malformed = TeamThreePermissions.all.values
            .map { it.permission }
            .filter { !it.startsWith("android.permission.") || it.contains(' ') || it.endsWith('.') }

        println("catalogued=${TeamThreePermissions.all.size} malformed=$malformed")
        assertTrue("malformed permission names: $malformed", malformed.isEmpty())

        // And the names must be distinct, because a duplicate would silently shadow
        // one entry in the map the helpers look up.
        assertEquals(
            "every catalogued permission must be distinct",
            TeamThreePermissions.all.size,
            TeamThreePermissions.all.values.map { it.permission }.toSet().size,
        )
    }

    /**
     * The tiers must match the platform's own protection levels, not the brief's.
     *
     * This is the assertion that catches the brief's own error: it called
     * PROCESS_OUTGOING_CALLS and ACCEPT_HANDOVER privileged, and the platform does
     * not. A tier that disagrees produces advice that cannot work.
     *
     * Robolectric ships a partial permission database, so a permission it does not
     * carry is skipped and counted rather than treated as a mismatch: absence from
     * the stand-in is not absence from the platform. The full comparison ran on a
     * real API 37 device and returned mismatches=[] across all 43 permissions of
     * this batch's scope.
     */
    @Test
    fun cataloguedTiersMatchTheFrameworkForEveryPermissionItKnows() {
        val pm = context.packageManager
        val mismatches = mutableListOf<String>()
        var checked = 0

        for (spec in TeamThreePermissions.all.values) {
            val info = runCatching { pm.getPermissionInfo(spec.permission, 0) }.getOrNull() ?: continue
            checked++
            val level = info.protection
            val isNormal = level == PermissionInfo.PROTECTION_NORMAL
            val isDangerous = level == PermissionInfo.PROTECTION_DANGEROUS
            val isSignature = (level and PermissionInfo.PROTECTION_SIGNATURE) != 0

            when (spec.tier) {
                PermissionTier.RUNTIME -> if (!isDangerous) {
                    mismatches += "${spec.permission}: RUNTIME but framework says level=$level"
                }
                PermissionTier.INSTALL -> if (!isNormal) {
                    mismatches += "${spec.permission}: INSTALL but framework says level=$level"
                }
                PermissionTier.PRIVILEGED -> if (!isSignature) {
                    mismatches += "${spec.permission}: PRIVILEGED but framework says level=$level"
                }
                else -> Unit
            }
        }

        println("tiers checked=$checked of ${TeamThreePermissions.all.size} mismatches=$mismatches")
        assertTrue("a tier that disagrees with the framework cannot be acted on: $mismatches", mismatches.isEmpty())
        assertTrue("the framework knew none of these permissions, so this proved nothing", checked > 0)
    }

    /**
     * The catalogue must cover this batch's scope exactly, plus the two extra
     * permissions added so the dispatcher can explain them.
     *
     * The count is pinned deliberately. It is not decoration: a permission silently
     * added or dropped changes what every refusal in the app says, and a test that
     * let the number drift would let a whole capability disappear without a word.
     * POST_NOTIFICATIONS and SCHEDULE_EXACT_ALARM are catalogued because other tool
     * sets legitimately declare them and the dispatcher would otherwise report a
     * grantable permission as a tool defect.
     */
    @Test
    fun theCatalogueCoversTheBatchScopeExactly() {
        val scope = TeamThreePermissions.all.size - 2
        println("catalogue=${TeamThreePermissions.all.size} batchScope=$scope")
        assertEquals("this batch owns 43 permissions", 43, scope)
        assertEquals("two extras make 45", 45, TeamThreePermissions.all.size)
    }

    // ------------------------------------------------------------------
    // The guard follows the real grant state
    // ------------------------------------------------------------------

    @Test
    fun aGrantedRuntimePermissionProducesNoRefusal() {
        grant(TeamThreePermissions.READ_SMS.permission)
        val check = PermissionGrants.check(context, TeamThreePermissions.READ_SMS)
        assertEquals(GrantState.GRANTED, check.state)
        assertNull("a held permission must not produce a refusal", PermissionGrants.guard(context, TeamThreePermissions.READ_SMS))
    }

    @Test
    fun aDeniedRuntimePermissionProducesARefusalNamingTheRoute() {
        deny(TeamThreePermissions.READ_SMS.permission)
        val check = PermissionGrants.check(context, TeamThreePermissions.READ_SMS)
        assertEquals(GrantState.NOT_GRANTED, check.state)

        val refusal = PermissionGrants.guard(context, TeamThreePermissions.READ_SMS)
        assertNotNull("a denied permission must produce a refusal", refusal)
        assertFalse("the refusal must not claim success", refusal!!.success)
        assertTrue("must name the route: ${refusal.error}", refusal.error!!.contains("Grant it in"))
        assertTrue("must carry machine-readable state", refusal.rawJson.contains("\"denied\":true"))
        println("device refusal: ${refusal.error}")
    }

    /**
     * An install-tier permission is held with no dialog. If this fails, the
     * manifest has lost the declaration, which is a build fault and must be
     * reported as one rather than as something the person can grant.
     */
    @Test
    fun installTierPermissionsNeedNoDialogAndReportAsAHeldGrant() {
        val spec = TeamThreePermissions.MODIFY_AUDIO_SETTINGS
        assertEquals(PermissionTier.INSTALL, spec.tier)
        val check = PermissionGrants.check(context, spec)
        println("install tier state=${check.state} sentence=${check.sentence()}")
        assertTrue("install-tier must be allowed, got ${check.state}", check.allowed)
    }

    /**
     * A privileged permission cannot be granted by any dialog, so the refusal must
     * name the shell-UID route and must never send the person to a settings screen
     * that cannot help.
     *
     * CORRECTED AFTER #172. This test previously required "Shizuku or Device Owner".
     * That was wrong: setPermissionGrantState handles runtime permissions, and the
     * platform lets `pm grant` satisfy a signature permission only when it carries
     * the `development` protection flag. Naming Device Owner sent a person down a
     * setup that could not work, so the assertion now pins what the platform allows.
     */
    @Test
    fun aPrivilegedPermissionReportsThePrivilegedStateNotAGrantRoute() {
        deny(TeamThreePermissions.CAPTURE_AUDIO_OUTPUT.permission)
        val check = PermissionGrants.check(context, TeamThreePermissions.CAPTURE_AUDIO_OUTPUT)
        println("privileged state=${check.state} sentence=${check.sentence()}")
        assertEquals(GrantState.PRIVILEGED, check.state)
        val sentence = check.sentence()
        assertTrue("must name the shell-UID route: $sentence", sentence.contains("through Shizuku"))
        assertFalse("Device Owner cannot grant a signature permission: $sentence", sentence.contains("Device Owner"))
        assertFalse("must not offer a settings dialog: $sentence", sentence.contains("Settings > Apps"))
    }

    // ------------------------------------------------------------------
    // Permissions outside this batch's catalogue still behave
    // ------------------------------------------------------------------

    /**
     * ToolDispatcher consults the helper for every tool in the app, and the rest of
     * the app declares real permissions this batch does not list. Calling one of
     * those a tool defect would tell a person the app is broken when a single
     * toggle would fix it.
     */
    @Test
    fun aRealPermissionOutsideTheCatalogueIsAnOrdinaryDenialNotADefect() {
        // The permission is registered in the stand-in's own database rather than
        // relied on being there already, because Robolectric carries only part of
        // the platform set and a test that guessed at one of the missing names
        // would prove the fallback against a permission it cannot see.
        val permission = "android.permission.A_REAL_GRANTABLE_PERMISSION"
        shadowOf(context.packageManager).addPermissionInfo(
            PermissionInfo().apply {
                name = permission
                protectionLevel = PermissionInfo.PROTECTION_DANGEROUS
                packageName = "android"
            },
        )
        deny(permission)

        val check = PermissionGrants.check(context, permission)
        println("out-of-catalogue state=${check.state} sentence=${check.sentence()}")
        // UNCATALOGUED, not NOT_GRANTED: the permission is real and grantable, so it
        // is an ordinary denial, but it is deliberately distinct from a catalogued
        // entry because its tier and its settings group are not known here.
        assertEquals(GrantState.UNCATALOGUED, check.state)
        assertTrue("a real grantable permission must be refused", check.denied)
        assertFalse("must not be reported as a defect", check.sentence().contains("Tool defect"))
        assertTrue("must still name a route", check.sentence().contains("Grant it in"))
    }

    @Test
    fun aPermissionTheFrameworkHasNeverHeardOfIsReportedAsAToolDefect() {
        val check = PermissionGrants.check(context, "android.permission.NOT_A_REAL_PERMISSION")
        assertEquals(GrantState.UNKNOWN_SPEC, check.state)
        assertTrue(check.sentence().contains("Tool defect"))
    }

    /**
     * The dispatcher's pre-flight path must produce the same sentence shape as a
     * tool's own guard, because the pre-flight runs first and therefore decides
     * what the model reads.
     */
    @Test
    fun theDispatcherPreflightProducesTheSameSentenceAsTheToolGuard() {
        deny(TeamThreePermissions.READ_SMS.permission)
        val preflight = PermissionGrants.refusalForPermission(context, TeamThreePermissions.READ_SMS.permission)
        val toolGuard = PermissionGrants.check(context, TeamThreePermissions.READ_SMS)
        assertEquals(toolGuard.state, preflight.state)
        assertEquals(toolGuard.sentence(), preflight.sentence())
        assertTrue("the pre-flight must be actionable: ${preflight.sentence()}", preflight.sentence().contains("Grant it in"))
    }

    // ------------------------------------------------------------------
    // Hardware absence
    // ------------------------------------------------------------------

    /**
     * Absent hardware is not a permission problem. A heart-rate read on a device
     * with no sensor cannot be fixed by any grant, so the answer must be about the
     * device, and it must never be a zero reading presented as a measurement.
     */
    @Test
    fun anAbsentSensorIsReportedAsAbsentHardwareNotAsZero() {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val hasHeartRate = manager.getDefaultSensor(Sensor.TYPE_HEART_RATE) != null
        println("framework reports heart-rate sensor present=$hasHeartRate")
        assertEquals(
            "the helper must agree with the framework about sensor presence",
            hasHeartRate,
            PermissionGrants.hasHeartRateSensor(context),
        )

        val check = PermissionGrants.absentHardware("heart-rate sensor", "GURU could read your heart rate")
        assertEquals(GrantState.ABSENT, check.state)
        assertTrue(check.sentence().contains("this device does not have it"))

        val refusal = PermissionGrants.guardHardware(false, "heart-rate sensor", "GURU could read your heart rate")
        assertNotNull("absent hardware must produce a refusal", refusal)
        assertFalse("absent hardware must not report success", refusal!!.success)
    }

    @Test
    fun aPresentCapabilityProducesNoRefusal() {
        assertNull(
            "a present capability must not produce a refusal",
            PermissionGrants.guardHardware(true, "camera", "GURU can take a photo"),
        )
    }

    // ------------------------------------------------------------------
    // Location precision
    // ------------------------------------------------------------------

    @Test
    fun locationPrecisionFollowsTheGrantThatIsActuallyHeld() {
        deny(TeamThreePermissions.ACCESS_FINE_LOCATION.permission)
        deny(TeamThreePermissions.ACCESS_COARSE_LOCATION.permission)
        assertEquals(
            PermissionGrants.LocationPrecision.NONE,
            PermissionGrants.locationPrecision(context),
        )

        grant(TeamThreePermissions.ACCESS_COARSE_LOCATION.permission)
        assertEquals(
            "coarse alone must read as APPROXIMATE and never as precise",
            PermissionGrants.LocationPrecision.APPROXIMATE,
            PermissionGrants.locationPrecision(context),
        )

        grant(TeamThreePermissions.ACCESS_FINE_LOCATION.permission)
        assertEquals(
            PermissionGrants.LocationPrecision.PRECISE,
            PermissionGrants.locationPrecision(context),
        )
    }

    // ------------------------------------------------------------------
    // The refusal body is honest about what it holds
    // ------------------------------------------------------------------

    /**
     * A refusal body must never contain the raw platform identifier in prose. The
     * sentence is what a person reads, and a dotted permission name in the middle
     * of it is the machine leaking through.
     */
    @Test
    fun aRefusalSentenceNeverLeaksTheRawPlatformIdentifier() {
        deny(TeamThreePermissions.READ_CALL_LOG.permission)
        val check = PermissionGrants.check(context, TeamThreePermissions.READ_CALL_LOG)
        val sentence = check.sentence()
        println("call log refusal: $sentence")
        assertFalse("raw identifier leaked into prose: $sentence", sentence.contains("android.permission"))
        assertTrue("must be one line ending in a full stop", sentence.trim().endsWith("."))
        assertFalse("no doubled spaces", sentence.contains("  "))
    }

    /**
     * The tier token in the body is what a caller branches on, so it must be the
     * real tier rather than a guess. A privileged permission reported as RUNTIME
     * would let a caller believe a dialog could fix it.
     */
    @Test
    fun theRefusalBodyCarriesTheRealTierAndState() {
        deny(TeamThreePermissions.CAPTURE_VIDEO_OUTPUT.permission)
        val check = PermissionGrants.check(context, TeamThreePermissions.CAPTURE_VIDEO_OUTPUT)
        val body = PermissionGrants.refusalBodyJson(check)
        println("privileged refusal body: $body")
        assertTrue(body.contains("\"tier\":\"PRIVILEGED\""))
        // PRIVILEGED or NOT_GRANTABLE depending on the permission's `development`
        // flag, which is the distinction #172 added: privileged and grantable
        // through shell UID are different facts and only one of them makes a setup
        // worthwhile. Both are honest states, so both are accepted here.
        assertTrue(
            "the state must say the platform is the obstacle: $body",
            body.contains("\"state\":\"PRIVILEGED\"") || body.contains("\"state\":\"NOT_GRANTABLE\""),
        )
        assertTrue(body.contains("\"denied\":true"))
        assertEquals(
            "a privileged refusal must not carry a grant route",
            null,
            check.spec.grantRoute,
        )
    }

    @Test
    fun firstRefusalReturnsTheFirstDeniedPermissionAndNullWhenAllAreHeld() {
        grant(TeamThreePermissions.READ_SMS.permission)
        deny(TeamThreePermissions.READ_CALL_LOG.permission)

        val refusal = PermissionGrants.firstRefusal(
            context,
            listOf(TeamThreePermissions.READ_SMS, TeamThreePermissions.READ_CALL_LOG),
        )
        assertNotNull("must find the denied one", refusal)
        assertEquals(TeamThreePermissions.READ_CALL_LOG.permission, refusal!!.spec.permission)

        grant(TeamThreePermissions.READ_CALL_LOG.permission)
        assertNull(
            "must return null when every permission is held",
            PermissionGrants.firstRefusal(
                context,
                listOf(TeamThreePermissions.READ_SMS, TeamThreePermissions.READ_CALL_LOG),
            ),
        )
    }
}
