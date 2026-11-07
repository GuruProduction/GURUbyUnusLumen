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
 * The settings and device-control domain, checked on the JVM without a device.
 *
 * WHAT THIS PROVES. The eleven tools are declared with the permissions that actually gate
 * them, the domain is registered so the dispatcher will reach it, no two tools share a
 * name, every declared permission has an actionable refusal sentence, and the tools whose
 * input can be validated purely refuse a bad call without touching the platform.
 *
 * THE RULE THIS DOMAIN EXISTS TO KEEP, and the one most worth testing here: a write reports
 * the value read back rather than the value sent. That cannot be proven without a device,
 * because it depends on a settings table that only a running platform has. What IS proven
 * is the refusals, which is the half that decides whether a person is told the truth when a
 * write does not happen.
 *
 * WHAT THIS CANNOT PROVE, stated so it is never mistaken for device evidence: that a
 * brightness write lands, that an overlay appears, that the shade opens, that a wallpaper
 * sets, that biometrics are enrolled, or that the phone vibrates. Those need Steven's phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsControlDomainHonestyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val expectedTools = listOf(
        "biometricAuth",
        "configurationChange",
        "expandStatusBar",
        "keepAwake",
        "overlayCreate",
        "overlayRemove",
        "secureSettingsWrite",
        "setWallpaper",
        "settingsWrite",
        "timeSet",
        "vibrate",
    )

    private fun definitions(): List<ToolDefinition> = SettingsControlDomainDefinitions.definitions

    @Test
    fun everyExpectedToolIsDeclaredAndNothingElse() {
        val declared = definitions().map { it.name }.sorted()
        println("settings_control tools (${declared.size}): $declared")
        assertEquals(expectedTools.sorted(), declared)
    }

    @Test
    fun noTwoToolsInThisDomainShareAName() {
        val names = definitions().map { it.name }
        assertEquals("duplicate tool names", names.size, names.toSet().size)
    }

    /**
     * A domain that is not in the aggregator does not exist in the running app at all, which
     * is how the smart-home fabric once shipped tools that answered nothing.
     */
    @Test
    fun theDomainIsRegisteredWithTheAggregator() {
        assertTrue(
            "the settings_control domain must be in the registry",
            TeamThreeToolDomains.domains.any { it.id == SettingsControlDomainModule.id },
        )
        assertEquals("settings_control", SettingsControlDomainModule.id)
    }

    /**
     * Every declared permission must be in the catalogue.
     *
     * The dispatcher builds its pre-flight refusal from that catalogue, so a permission it
     * has never heard of comes back as an unknown permission and reads to a person as a
     * broken app rather than a missing grant.
     */
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
     * Every tool here must declare at least one permission. Each one touches a gated API,
     * and an empty list would let the dispatcher run the tool and let the platform throw
     * underneath, which produces a worse sentence than a refusal.
     */
    @Test
    fun everyToolDeclaresTheGrantItNeeds() {
        val missing = definitions().filter { it.permissions.isEmpty() }.map { it.name }
        assertTrue("tools declaring no permission but needing one: $missing", missing.isEmpty())
    }

    /**
     * Every declared permission must produce a sentence a person can act on.
     *
     * This is the sentence a denied tool returns verbatim, because every tool returns the
     * guard's result first. What is proven here is the wording, which is the part that is
     * genuinely pure: a full stop, no doubled space, no raw platform constant in the prose,
     * and a followable route wherever a dialog can actually grant it.
     */
    @Test
    fun everyPermissionThisDomainDeclaresHasAUsableRefusal() {
        val specs = definitions()
            .flatMap { it.permissions }
            .distinct()
            .mapNotNull { TeamThreePermissions.forPermission(it) }

        assertTrue("expected at least eight catalogued permissions", specs.size >= 8)

        val problems = mutableListOf<String>()
        for (spec in specs) {
            val check = GrantCheck.of(spec, granted = false)
            val sentence = check.sentence()

            if (!sentence.trim().endsWith(".")) problems += "${spec.permission}: no full stop"
            if (sentence.contains("  ")) problems += "${spec.permission}: doubled space"
            if (sentence.contains("android.permission")) {
                problems += "${spec.permission}: raw constant in prose"
            }
            if (sentence.isBlank()) problems += "${spec.permission}: empty sentence"
            if (check.state == GrantState.NOT_GRANTED && !sentence.contains("Grant it in")) {
                problems += "${spec.permission}: a denial with no route"
            }
        }

        println("refusal sentences checked: ${specs.map { it.permission }}")
        assertTrue("unusable refusals: $problems", problems.isEmpty())
    }

    // ------------------------------------------------------------------
    // Validation that needs no platform
    // ------------------------------------------------------------------

    /**
     * settingsWrite must reject an unknown setting name rather than writing something.
     *
     * This runs with the grant held or denied, whichever the harness gives, so the assertion
     * is only that it never reports success for a name that matches nothing.
     */
    @Test
    fun anUnknownSettingNameIsRefused() {
        val executor = SettingsControlDomainExecutor(context)
        val result = runBlocking {
            executor.execute("settingsWrite", mapOf("setting" to "NOT_A_SETTING", "value" to "1"))
        }
        assertFalse("an unknown setting must not report success", result.success)
        assertTrue("it must say why", !result.error.isNullOrBlank())
    }

    @Test
    fun settingsWriteWithoutAValueIsRefused() {
        val executor = SettingsControlDomainExecutor(context)
        val result = runBlocking {
            executor.execute("settingsWrite", mapOf("setting" to "SCREEN_BRIGHTNESS"))
        }
        assertFalse("a write with no value must not report success", result.success)
        assertTrue("it must say why", !result.error.isNullOrBlank())
    }

    @Test
    fun anUnknownTimeTargetIsRefused() {
        val executor = SettingsControlDomainExecutor(context)
        val result = runBlocking {
            executor.execute("timeSet", mapOf("what" to "WEEKDAY"))
        }
        assertFalse("an unknown clock target must not report success", result.success)
        assertTrue("it must say why", !result.error.isNullOrBlank())
    }

    @Test
    fun anUnknownConfigurationActionIsRefused() {
        val executor = SettingsControlDomainExecutor(context)
        val result = runBlocking {
            executor.execute("configurationChange", mapOf("action" to "SET_SOMETHING"))
        }
        assertFalse("an unknown configuration action must not report success", result.success)
        assertTrue("it must say why", !result.error.isNullOrBlank())
    }

    @Test
    fun anUnknownStatusBarActionIsRefused() {
        val executor = SettingsControlDomainExecutor(context)
        val result = runBlocking {
            executor.execute("expandStatusBar", mapOf("action" to "TOGGLE"))
        }
        assertFalse("an unknown shade action must not report success", result.success)
        assertTrue("it must say why", !result.error.isNullOrBlank())
    }

    @Test
    fun overlayRemoveWithoutAnIdOrAllIsRefused() {
        val executor = SettingsControlDomainExecutor(context)
        val result = runBlocking { executor.execute("overlayRemove", emptyMap<String, Any?>()) }
        assertFalse("a removal with no target must not report success", result.success)
        assertTrue("it must say why", !result.error.isNullOrBlank())
    }

    @Test
    fun anUnknownWakeLockActionIsRefused() {
        val executor = SettingsControlDomainExecutor(context)
        val result = runBlocking {
            executor.execute("keepAwake", mapOf("action" to "TOGGLE"))
        }
        assertFalse("an unknown wake-lock action must not report success", result.success)
        assertTrue("it must say why", !result.error.isNullOrBlank())
    }

    /**
     * biometricAuth must demand a reason, because the reason is what a person is shown when
     * the gate would matter. A prompt with no explanation is worse than no prompt.
     */
    @Test
    fun biometricAuthWithoutAReasonIsRefused() {
        val executor = SettingsControlDomainExecutor(context)
        val result = runBlocking { executor.execute("biometricAuth", emptyMap<String, Any?>()) }
        assertFalse("biometricAuth with no reason must not report success", result.success)
        assertTrue("it must say why", !result.error.isNullOrBlank())
    }

    /**
     * Every description must be long enough to warn a model about a limit. A description
     * that only names the tool is how a model comes to believe a capability it does not
     * have, which is the fault class behind issues 40 and 44.
     */
    @Test
    fun everyDescriptionSaysSomethingASchemaCannot() {
        val thin = definitions().filter { it.description.length < 120 }.map { it.name }
        assertTrue("descriptions too thin to warn a model: $thin", thin.isEmpty())
    }
}
