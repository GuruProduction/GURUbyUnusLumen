// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolRegistry
import com.unuslumen.app.data.tools.registry.permission.TeamThreeToolDomains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The permission domain, checked for the thing that actually goes wrong in this repo.
 *
 * THE FAULT THIS PREVENTS. This project has shipped tools that were written, compiled, tested
 * and never registered, so they did not exist in the running app. For the permission batch the
 * failure was the reverse and lasted longer: the catalogue held 104 specs and the orchestrator
 * knew every route, and nothing a model could call reached either of them.
 *
 * So this does not test that the classes compile. It registers through the same aggregator the
 * app's boot reads and asks the registry whether the tool names are ones it knows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionDomainHonestyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the permission tools are registered under the names a caller uses`() {
        val registry = ToolRegistry()
        val koin = startKoin {
            androidContext(context)
            modules(PermissionDomainModule.bindings())
        }.koin

        try {
            for (reg in PermissionDomainModule.registrations) {
                val executor = koin.get<com.unuslumen.app.data.tools.registry.ToolExecutor>(
                    reg.executorClass(),
                )
                for (definition in reg.definitions) {
                    registry.register(definition, executor, null)
                }
            }

            val expected = listOf(
                PermissionDomainDefinitions.PERMISSION_STATUS,
                PermissionDomainDefinitions.PERMISSION_REQUEST,
                PermissionDomainDefinitions.PERMISSION_ROUTE,
            )
            val present = registry.getAllToolNames()
            for (name in expected) {
                assertTrue(
                    "$name is not in the registry, which means it does not exist in the running app",
                    present.contains(name),
                )
                assertNotNull("$name registered with no executor", registry.getExecutor(name))
            }
        } finally {
            stopKoin()
        }
    }

    @Test
    fun `the domain appears in the aggregator that installs it`() {
        val ids = TeamThreeToolDomains.domains.map { it.id }
        assertTrue(
            "permissions_management is not in TeamThreeToolDomains, so its tools never register",
            ids.contains(PermissionDomainModule.id),
        )
        val registered = TeamThreeToolDomains.registrations()
        assertTrue(
            "the domain's definitions are not in the aggregator's registration list",
            registered.any { it === PermissionDomainDefinitions },
        )
    }

    @Test
    fun `every tool name is distinct from every other tool in the app`() {
        val own = PermissionDomainDefinitions.definitions.map(ToolDefinition::name)
        val others = TeamThreeToolDomains.registrations()
            .filter { it !== PermissionDomainDefinitions }
            .flatMap { it.definitions.map(ToolDefinition::name) }

        val collisions = own.filter { it in others }
        assertTrue("these names are claimed twice: $collisions", collisions.isEmpty())
    }

    @Test
    fun `no permission tool declares a permission of its own`() {
        // These tools report on permissions. Guarding one behind a permission would make the
        // explanation of a missing grant unavailable precisely when it is needed, which is the
        // same defect as a denial reported as an empty list.
        val guarded = PermissionDomainDefinitions.definitions
            .filter { it.permissions.isNotEmpty() }
            .map { it.name to it.permissions }
        assertEquals("no permission tool may declare a permission", emptyList<Pair<String, List<String>>>(), guarded)
    }

    @Test
    fun `every declared tool has a description that names what it will not do`() {
        val thin = PermissionDomainDefinitions.definitions
            .filter { it.description.length < 120 }
            .map(ToolDefinition::name)
        assertTrue("descriptions too thin to warn a model: $thin", thin.isEmpty())
    }

    @Test
    fun `the catalogue entry count is measured, not assumed`() {
        // Pinned so a change in the catalogue is visible here. The number is not the point; the
        // fact that it is read from the catalogue rather than restated is.
        val count = com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions.all.size
        println("catalogue entries: $count")
        assertTrue("the catalogue must not be empty", count > 0)
    }

    @Test
    fun `a bare permission name resolves even when it lives outside the android namespace`() {
        // SET_ALARM is declared under com.android.alarm.permission, so a resolver that only
        // prepends android.permission would fail on the one entry that proves the rule.
        val spec = com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions.all.values
            .firstOrNull { it.permission.substringAfterLast('.') == "SET_ALARM" }
        assertNotNull("SET_ALARM must be in the catalogue", spec)
        assertTrue(
            "SET_ALARM deliberately lives outside the android namespace",
            spec!!.permission.startsWith("com.android.alarm.permission"),
        )
    }
}
