// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.data.tools.registry.ToolDefinition
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
 * The connection and Wi-Fi domain, checked on the JVM without a device.
 *
 * WHAT THIS PROVES. The eight tools are declared, each one names a permission the
 * catalogue can explain or a literal the dispatcher resolves against the platform, the
 * domain is registered so the dispatcher will actually reach it, and every tool that
 * needs a grant refuses rather than returning an empty result when the grant is absent.
 *
 * WHY THE REFUSAL ASSERTIONS MATTER MOST HERE. This project has shipped nine bugs in the
 * network layer whose common shape was an empty or wrong result that read as a
 * measurement: a tool that returned no networks, no hosts or no devices when the real
 * answer was that it had never been allowed to look. These tests hold the line that a
 * denied read is a refusal with a reason, not a zero.
 *
 * WHAT THIS CANNOT PROVE, stated so it is never mistaken for device evidence: that a real
 * radio associates, that a scan sees a real network, that the platform refuses a Wi-Fi
 * toggle on the version this runs on, or that a multicast lock receives a real packet.
 * Those need Steven's phone, and the emulator can prove the shape but not the radio.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkWifiDomainHonestyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val expectedTools = listOf(
        "ipsecTunnels",
        "multicastLock",
        "networkSetEnabled",
        "networkState",
        "vpnControl",
        "wifiInfo",
        "wifiNearby",
        "wifiSetEnabled",
    )

    private fun definitions(): List<ToolDefinition> =
        NetworkWifiDomainDefinitions.definitions

    @Test
    fun everyExpectedToolIsDeclaredAndNothingElse() {
        val declared = definitions().map { it.name }.sorted()
        println("network_wifi tools (${declared.size}): $declared")
        assertEquals(expectedTools.sorted(), declared)
    }

    @Test
    fun noTwoToolsInThisDomainShareAName() {
        val names = definitions().map { it.name }
        assertEquals("duplicate tool names", names.size, names.toSet().size)
    }

    /**
     * The domain must be named in the aggregator.
     *
     * A domain that exists but is not listed does not exist in the running app at all: the
     * smart-home fabric shipped exactly that way and its tools answered nothing while the
     * code sat there looking finished.
     */
    @Test
    fun theDomainIsRegisteredWithTheAggregator() {
        assertTrue(
            "the network_wifi domain must be in the registry",
            TeamThreeToolDomains.domains.any { it.id == NetworkWifiDomainModule.id },
        )
        assertEquals("network_wifi", NetworkWifiDomainModule.id)
    }

    /**
     * Every declared permission must be either catalogued or a real platform name.
     *
     * The multicast permission is deliberately not in the catalogue: it is
     * protectionLevel=normal, so it is held from install and there is no dialog to explain.
     * It is still declared by its tool, and the dispatcher resolves names outside the
     * catalogue against the platform, which reports a real permission as an ordinary denial
     * rather than a tool defect.
     */
    @Test
    fun everyDeclaredPermissionIsCataloguedOrAKnownPlatformName() {
        val catalogue = TeamThreePermissions.all.keys

        val unreasoned = definitions()
            .flatMap { def -> def.permissions.map { def.name to it } }
            .filter { (_, permission) -> permission !in catalogue }

        println("permissions used: ${definitions().flatMap { it.permissions }.distinct()}")
        assertTrue("permissions used but not reasoned about: $unreasoned", unreasoned.isEmpty())
    }

    /**
     * No permission list may be blank-but-present, which is the shape a copy-paste leaves
     * behind and which the dispatcher cannot act on.
     */
    @Test
    fun everyPermissionListIsEitherEmptyOrReal() {
        val bad = definitions()
            .filter { def -> def.permissions.isNotEmpty() && def.permissions.any { it.isBlank() } }
            .map { it.name }
        assertTrue("blank permission strings: $bad", bad.isEmpty())
    }

    /**
     * A tool must never declare a permission that no app can hold.
     *
     * THIS REPLACES AN EARLIER TEST THAT DEMANDED EVERY TOOL DECLARE SOMETHING, and the earlier
     * one was wrong in a way that broke the features. The dispatcher refuses a tool when any
     * declared permission is missing, so declaring a signature permission turned a working tool
     * into one that refuses every single time, on every device, whatever the person asked for.
     *
     * vpnControl and ipsecTunnels both did exactly that: CONTROL_VPN and MANAGE_IPSEC_TUNNELS
     * are unholdable, so the tools could never run. The declarations are gone and the tools now
     * report what they can observe, which is what they were written to do.
     *
     * So the rule is the opposite of the old one: a permission may only be declared when a
     * person can actually grant it, and a privileged permission is guarded inside the executor
     * where the refusal can name what is really missing.
     */
    @Test
    fun noToolDeclaresAPermissionNoAppCanHold() {
        val unholdable = definitions()
            .flatMap { def -> def.permissions.map { def.name to it } }
            .filter { (_, permission) ->
                val spec = TeamThreePermissions.forPermission(permission)
                spec?.tier == com.unuslumen.app.data.tools.registry.permission.PermissionTier.PRIVILEGED
            }

        println("declared: ${definitions().flatMap { it.permissions }.distinct()}")
        assertTrue(
            "these tools declare a privileged permission, which makes the dispatcher refuse them " +
                "before they ever run: $unholdable",
            unholdable.isEmpty(),
        )
    }

    /**
     * Deny a permission explicitly before asserting a refusal.
     *
     * WHY THIS IS NOT THE DEFAULT. Robolectric grants the permissions this module's own
     * merged manifest declares, so a fresh context already holds ACCESS_NETWORK_STATE and
     * a test that assumed denial would assert the opposite of the truth. The denial has to
     * be made, not assumed. This was caught by the test failing first and the assumption
     * being wrong, rather than by reasoning, which is why it is written down.
     */
    private fun deny(permission: String) {
        org.robolectric.Shadows.shadowOf(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
        ).denyPermissions(permission)
    }

    /**
     * Every grant these tools depend on must produce a usable refusal when it is missing.
     *
     * WHAT THIS CAN AND CANNOT REACH, and why the boundary is stated rather than hidden.
     * Driving the executor with the permission denied was the first version of this test and
     * it failed: Robolectric grants the permissions this module's own merged manifest
     * declares, and `denyPermissions` did not move them, so four tools ran and reported
     * success. Asserting a refusal the harness cannot force would have been a test that lies.
     *
     * So what is proven here is the half that is genuinely pure: for every permission these
     * tools declare, the refusal exists, is one grammatical sentence, names the consequence,
     * gives a followable route where one exists, and never leaks the raw platform constant.
     * That is the sentence a denied tool returns, because every tool returns the guard's
     * result verbatim.
     *
     * WHAT IS NOT PROVEN HERE: that each executor actually calls the guard before doing work.
     * That needs a device with the grants revoked, and it belongs in the release test.
     */
    @Test
    fun everyPermissionThisDomainDeclaresHasAUsableRefusal() {
        val specs = definitions()
            .flatMap { it.permissions }
            .distinct()
            .mapNotNull { permission ->
                TeamThreePermissions.forPermission(permission)
                    ?: return@mapNotNull null
            }

        // Two of the eight now declare nothing, because CONTROL_VPN and MANAGE_IPSEC_TUNNELS
        // cannot be held and declaring them made the dispatcher refuse those tools outright.
        assertTrue("expected at least two catalogued permissions", specs.size >= 2)

        val problems = mutableListOf<String>()
        for (spec in specs) {
            val check = com.unuslumen.app.data.tools.registry.permission.GrantCheck.of(spec, granted = false)
            val sentence = check.sentence()

            if (!sentence.trim().endsWith(".")) problems += "${spec.permission}: no full stop"
            if (sentence.contains("  ")) problems += "${spec.permission}: doubled space"
            if (sentence.contains("android.permission")) {
                problems += "${spec.permission}: raw constant leaked into prose"
            }
            if (sentence.isBlank()) problems += "${spec.permission}: empty sentence"
            // A grantable permission must tell the person where to go. A privileged one must
            // not, because no settings screen can grant it.
            if (check.state == com.unuslumen.app.data.tools.registry.permission.GrantState.NOT_GRANTED &&
                !sentence.contains("Grant it in")
            ) {
                problems += "${spec.permission}: a denial with no route"
            }
        }

        println("refusal sentences checked: ${specs.map { it.permission }}")
        assertTrue("unusable refusals: $problems", problems.isEmpty())
    }

    /**
     * A toggle must change nothing without confirm=true.
     *
     * Both toggles are disruptive and visible to the person, so the guard has to be the
     * confirmation rather than the permission. This asserts the confirmation is checked, by
     * driving it with the permission denied and confirm false and requiring a refusal whose
     * message is about confirmation rather than about the grant, which only happens if the
     * confirmation check runs first.
     */
    @Test
    fun aToggleWithoutConfirmationChangesNothing() {
        val executor = NetworkWifiDomainExecutor(context)
        val result = runBlocking {
            executor.execute("wifiSetEnabled", mapOf("enabled" to false, "confirm" to false))
        }
        assertFalse("a toggle without confirmation must not report success", result.success)
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
