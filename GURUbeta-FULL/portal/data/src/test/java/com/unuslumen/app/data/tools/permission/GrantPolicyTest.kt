// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import com.unuslumen.app.data.tools.registry.permission.GrantEnvironment
import com.unuslumen.app.data.tools.registry.permission.GrantPlan
import com.unuslumen.app.data.tools.registry.permission.GrantPolicy
import com.unuslumen.app.data.tools.registry.permission.GrantRoute
import com.unuslumen.app.data.tools.registry.permission.PermissionSpec
import com.unuslumen.app.data.tools.registry.permission.PermissionTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route decision, proven on the JVM.
 *
 * WHY THIS IS THE RIGHT PLACE TO PROVE IT. Every rule below is a statement about
 * what Android allows, and a statement about Android can be checked here in
 * milliseconds. The alternative is a device run per rule, which is minutes per rule
 * and proves the same thing. The device is needed for whether a route *executes*;
 * this file proves whether a route *should be chosen at all*.
 *
 * The one rule that changed the design is the `development` flag. #47 assumed
 * Shizuku or Device Owner can grant any privileged permission. The platform
 * documentation says `pm grant` works only for permissions carrying the
 * `development` protection flag, and the CTS test for setPermissionGrantState uses
 * runtime permissions only. Both are pinned below so a future refactor cannot
 * quietly reintroduce the false promise.
 */
class GrantPolicyTest {

    private val plain = GrantEnvironment()

    private fun spec(tier: PermissionTier, permission: String = "android.permission.EXAMPLE") =
        PermissionSpec(
            permission = permission,
            tier = tier,
            label = "Example",
            why = "GURU can do the example thing",
            grantRoute = "Settings > Apps > GURU > Permissions > Example > Allow",
        )

    // ------------------------------------------------------------------
    // Runtime
    // ------------------------------------------------------------------

    @Test
    fun aRuntimePermissionUsesTheDialogWhenThereIsNoDeviceOwner() {
        val plan = GrantPolicy.plan(spec(PermissionTier.RUNTIME), plain)
        assertEquals(GrantRoute.RUNTIME_DIALOG, plan.route)
        assertTrue("a dialog always exists for a runtime permission", plan.canSucceed)
        assertNull(plan.blockedBy)
    }

    /**
     * The single biggest quality-of-life win the owner path buys. A runtime
     * permission granted silently is a conversation that never stops to ask, so
     * owner must be preferred over the dialog rather than equal to it.
     */
    @Test
    fun deviceOwnerTakesPrecedenceOverTheDialogForRuntimePermissions() {
        val plan = GrantPolicy.plan(spec(PermissionTier.RUNTIME), plain.copy(isDeviceOwner = true))
        assertEquals(GrantRoute.DEVICE_OWNER, plan.route)
        assertTrue(plan.canSucceed)
    }

    // ------------------------------------------------------------------
    // Install and self-guard: nothing to ask for
    // ------------------------------------------------------------------

    @Test
    fun anInstallTierPermissionNeedsNoRoute() {
        val plan = GrantPolicy.plan(spec(PermissionTier.INSTALL), plain)
        assertEquals(GrantRoute.SELF_GUARD, plan.route)
        assertTrue(plan.canSucceed)
    }

    @Test
    fun aSelfGuardPermissionNeedsNoRoute() {
        val plan = GrantPolicy.plan(spec(PermissionTier.SELF_GUARD), plain)
        assertEquals(GrantRoute.SELF_GUARD, plan.route)
        assertTrue(plan.canSucceed)
    }

    // ------------------------------------------------------------------
    // App-op, service, role
    // ------------------------------------------------------------------

    @Test
    fun anAppOpPermissionOpensTheSettingsScreen() {
        val plan = GrantPolicy.plan(spec(PermissionTier.APPOP), plain)
        assertEquals(GrantRoute.APP_OP_SETTINGS, plan.route)
        assertTrue(plan.canSucceed)
    }

    @Test
    fun aServicePermissionOpensTheServiceSettingsScreen() {
        val plan = GrantPolicy.plan(spec(PermissionTier.SERVICE), plain)
        assertEquals(GrantRoute.SERVICE_SETTINGS, plan.route)
        assertTrue(plan.canSucceed)
    }

    @Test
    fun aRoleIsRequestableWhenTheBuildDeclaresTheComponent() {
        val plan = GrantPolicy.plan(spec(PermissionTier.ROLE), plain.copy(roleRequestable = true))
        assertEquals(GrantRoute.ROLE, plan.route)
        assertTrue(plan.canSucceed)
    }

    /**
     * A role request for a component the build does not declare cannot succeed, and
     * the person must be told before they tap anything rather than after the
     * platform refuses.
     */
    @Test
    fun aRoleIsReportedAsBlockedWhenNoComponentIsDeclared() {
        val plan = GrantPolicy.plan(spec(PermissionTier.ROLE), plain)
        assertEquals(GrantRoute.ROLE, plan.route)
        assertFalse(plan.canSucceed)
        assertNotNull("must explain why not", plan.blockedBy)
        assertTrue(
            "must name the missing declaration: ${plan.blockedBy}",
            plan.blockedBy!!.contains("does not declare the component"),
        )
    }

    // ------------------------------------------------------------------
    // Privileged: the correction
    // ------------------------------------------------------------------

    /**
     * THE TEST THAT MATTERS MOST IN THIS FILE. A privileged permission without the
     * `development` flag has no route at all, and the plan must say that rather than
     * returning SHIZUKU and letting a person set up a shell bridge that cannot grant
     * it. This is the direct correction to #47's premise.
     */
    @Test
    fun aPrivilegedPermissionWithoutTheDevelopmentFlagHasNoRoute() {
        val plan = GrantPolicy.plan(
            spec(PermissionTier.PRIVILEGED, "android.permission.READ_PRECISE_PHONE_STATE"),
            plain.copy(hasDevelopmentFlag = false),
        )
        assertNull("no route exists, and null says so", plan.route)
        assertFalse(plan.canSucceed)
        assertTrue(
            "must be final, not hopeful: ${plan.blockedBy}",
            plan.blockedBy!!.contains("not grantable by any command"),
        )
        assertFalse(
            "must not send them to Shizuku: ${plan.blockedBy}",
            plan.blockedBy!!.contains("Shizuku"),
        )
    }

    /**
     * With the flag present but no Shizuku, the route exists and cannot run yet. The
     * distinction between "impossible" and "not set up" is the difference between a
     * person giving up and a person setting something up.
     */
    @Test
    fun aDevelopablePrivilegedPermissionNamesShizukuAsPendingWhenItIsNotSetUp() {
        val plan = GrantPolicy.plan(
            spec(PermissionTier.PRIVILEGED),
            plain.copy(hasDevelopmentFlag = true, shizukuAvailable = false),
        )
        assertEquals(GrantRoute.SHIZUKU, plan.route)
        assertFalse(plan.canSucceed)
        assertTrue(
            "must say the setup is missing, not that it is impossible: ${plan.blockedBy}",
            plan.blockedBy!!.contains("not set up on this device yet"),
        )
    }

    @Test
    fun aDevelopablePrivilegedPermissionCanSucceedOnceShizukuIsAuthorised() {
        val plan = GrantPolicy.plan(
            spec(PermissionTier.PRIVILEGED),
            plain.copy(hasDevelopmentFlag = true, shizukuAvailable = true),
        )
        assertEquals(GrantRoute.SHIZUKU, plan.route)
        assertTrue(plan.canSucceed)
        assertNull(plan.blockedBy)
    }

    // ------------------------------------------------------------------
    // Hardware
    // ------------------------------------------------------------------

    @Test
    fun absentHardwareHasNoRouteAtAll() {
        val plan = GrantPolicy.plan(spec(PermissionTier.HARDWARE), plain)
        assertNull(plan.route)
        assertFalse(plan.canSucceed)
        assertTrue(
            "must blame the device: ${plan.blockedBy}",
            plan.blockedBy!!.contains("does not have the"),
        )
    }

    // ------------------------------------------------------------------
    // The set of routes is complete and ordered sensibly
    // ------------------------------------------------------------------

    @Test
    fun everyGrantRouteExceptSelfGuardIsOfferedToAPerson() {
        val offered = GrantPolicy.orderedRoutes()
        assertEquals(
            "self-guard is not something a person does",
            6,
            offered.size,
        )
        assertFalse(GrantRoute.SELF_GUARD in offered)
        assertEquals("owner first, because it is silent", GrantRoute.DEVICE_OWNER, offered.first())
        assertTrue("dialog must be offered", GrantRoute.RUNTIME_DIALOG in offered)
        assertTrue("app-op must be offered", GrantRoute.APP_OP_SETTINGS in offered)
        assertTrue("service must be offered", GrantRoute.SERVICE_SETTINGS in offered)
    }

    /**
     * Every tier must produce a plan without throwing. A missing branch here would
     * be a crash in the app the first time that tier was met, and it would happen
     * while a person was asking for something.
     */
    @Test
    fun everyTierProducesAPlanAndABlockedPlanAlwaysExplainsItself() {
        PermissionTier.entries.forEach { tier ->
            val plan = GrantPolicy.plan(spec(tier), plain)
            if (!plan.canSucceed) {
                assertNotNull("$tier reported blocked with no reason", plan.blockedBy)
                assertTrue("$tier gave an empty reason", plan.blockedBy!!.isNotBlank())
                assertTrue(
                    "$tier reason must be one sentence ending in a full stop: ${plan.blockedBy}",
                    plan.blockedBy!!.trim().endsWith("."),
                )
            } else {
                assertNull("$tier claimed success but named a blocker", plan.blockedBy)
            }
        }
    }

    /**
     * A blocked reason is what a person reads, so it must not leak a raw platform
     * string or an uninterpolated template.
     */
    @Test
    fun noBlockedReasonLeaksAPlatformStringIntoProse() {
        PermissionTier.entries.forEach { tier ->
            val plan: GrantPlan = GrantPolicy.plan(spec(tier), plain)
            plan.blockedBy?.let { reason ->
                assertFalse("raw permission leaked: $reason", reason.contains("android.permission"))
                assertFalse("uninterpolated: $reason", reason.contains("\${"))
                assertFalse("doubled space: $reason", reason.contains("  "))
            }
        }
    }
}
