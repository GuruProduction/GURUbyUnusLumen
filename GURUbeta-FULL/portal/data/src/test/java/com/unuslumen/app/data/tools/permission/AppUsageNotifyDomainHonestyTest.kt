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
 * The app-usage and notifications domain, checked on the JVM without a device.
 *
 * WHAT THIS PROVES. The three tools are declared with the grants that gate them, the domain
 * is registered, and the one deliberate asymmetry holds: dndControl declares NO permission,
 * because reading the Do Not Disturb filter needs none and refusing a true reading to keep a
 * uniform declaration would be the wrong trade.
 *
 * WHY THAT ASYMMETRY IS ASSERTED. It is the kind of thing a later reader would "fix" by
 * adding the permission to the declaration, which would silently make the read path refuse
 * for everyone without the toggle. Pinning it here means the correction has to be a decision
 * rather than a tidy-up.
 *
 * WHAT THIS CANNOT PROVE, stated so a green run is never mistaken for device evidence: that
 * real usage statistics return real rows, that a notification actually appears in the shade,
 * or that Do Not Disturb changes on a real phone. Those need Steven's device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUsageNotifyDomainHonestyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val executor = AppUsageNotifyDomainExecutor(context)

    private val expectedTools = listOf(
        "appUsage",
        "dndControl",
        "notify",
    )

    private fun definitions(): List<ToolDefinition> = AppUsageNotifyDomainDefinitions.definitions

    private fun grant(vararg permissions: String) {
        org.robolectric.Shadows.shadowOf(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
        ).grantPermissions(*permissions)
    }

    @Test
    fun everyExpectedToolIsDeclaredAndNothingElse() {
        val declared = definitions().map { it.name }.sorted()
        println("app_usage_notify tools (${declared.size}): $declared")
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
            "the app_usage_notify domain must be in the registry",
            TeamThreeToolDomains.domains.any { it.id == AppUsageNotifyDomainModule.id },
        )
        assertEquals("app_usage_notify", AppUsageNotifyDomainModule.id)
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

    /**
     * dndControl must declare nothing, and the other two must declare their grant.
     *
     * This is the assertion that protects the read path: adding ACCESS_NOTIFICATION_POLICY to
     * the declaration would make the dispatcher refuse a read that needs no permission.
     */
    @Test
    fun dndControlDeclaresNoPermissionAndTheOthersDo() {
        val byName = definitions().associateBy { it.name }

        assertTrue(
            "dndControl must declare no permission, so its unguarded read keeps working",
            byName["dndControl"]?.permissions?.isEmpty() == true,
        )
        assertTrue(
            "appUsage must declare the Usage access permission",
            byName["appUsage"]?.permissions?.contains(
                TeamThreePermissions.PACKAGE_USAGE_STATS.permission,
            ) == true,
        )
        assertTrue(
            "notify must declare the notifications permission",
            byName["notify"]?.permissions?.contains(
                TeamThreePermissions.POST_NOTIFICATIONS.permission,
            ) == true,
        )
    }

    @Test
    fun everyPermissionThisDomainDeclaresHasAUsableRefusal() {
        val specs = definitions()
            .flatMap { it.permissions }
            .distinct()
            .mapNotNull { TeamThreePermissions.forPermission(it) }

        assertTrue("expected both catalogued permissions", specs.size >= 2)

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
    // Validation and the read asymmetry
    // ------------------------------------------------------------------

    /**
     * The DND read must answer without any grant, because the filter is readable by any app.
     * Whether the manager is a working stub under Robolectric is beside the point: what is
     * asserted is that no permission refusal comes back, which is the behaviour that matters.
     */
    @Test
    fun dndGetIsNotRefusedForWantOfAPermission() = runBlocking {
        val result = executor.execute(
            AppUsageNotifyDomainDefinitions.DND_CONTROL,
            mapOf("action" to "GET"),
        )
        println("dnd GET: success=${result.success} error=${result.error}")

        val error = result.error.orEmpty()
        assertFalse(
            "a DND read must never be refused for a missing permission: $error",
            error.contains("Permission denied", ignoreCase = true),
        )
    }

    @Test
    fun anUnknownNotifyTitleOrBodyIsRefused() = runBlocking {
        grant(TeamThreePermissions.POST_NOTIFICATIONS.permission)

        val noTitle = executor.execute(
            AppUsageNotifyDomainDefinitions.NOTIFY,
            mapOf("body" to "something"),
        )
        val noBody = executor.execute(
            AppUsageNotifyDomainDefinitions.NOTIFY,
            mapOf("title" to "something"),
        )

        assertFalse("a notify with no title must not succeed", noTitle.success)
        assertTrue("it must say why", !noTitle.error.isNullOrBlank())
        assertFalse("a notify with no body must not succeed", noBody.success)
        assertTrue("it must say why", !noBody.error.isNullOrBlank())
    }

    @Test
    fun anUnknownDndActionIsRefused() = runBlocking {
        val result = executor.execute(
            AppUsageNotifyDomainDefinitions.DND_CONTROL,
            mapOf("action" to "TOGGLE"),
        )
        assertFalse("an unknown DND action must not succeed", result.success)
        assertTrue("it must say why", !result.error.isNullOrBlank())
        assertTrue(
            "it must name the actions it accepts: ${result.error}",
            result.error.orEmpty().contains("GET"),
        )
    }

    @Test
    fun aBackwardsUsageWindowIsRefusedRatherThanQueried() = runBlocking {
        grant(TeamThreePermissions.PACKAGE_USAGE_STATS.permission)

        val result = executor.execute(
            AppUsageNotifyDomainDefinitions.APP_USAGE,
            mapOf("fromMillis" to 2_000_000L, "toMillis" to 1_000_000L),
        )
        val error = result.error.orEmpty()
        println("backwards usage window: success=${result.success} error=$error")

        assertFalse("a backwards window must not report success", result.success)
        assertTrue("it must say why", error.isNotBlank())
        assertTrue(
            "it must distinguish this from finding no activity: $error",
            error.contains("No", ignoreCase = true) || error.contains("window", ignoreCase = true),
        )
    }

    @Test
    fun everyDescriptionSaysSomethingASchemaCannot() {
        val thin = definitions().filter { it.description.length < 120 }.map { it.name }
        assertTrue("descriptions too thin to warn a model: $thin", thin.isEmpty())
    }
}
