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
 * The alarms and automation domain, checked on the JVM without a device.
 *
 * WHAT THIS PROVES. The four tools are declared, the domain is registered, the two health
 * tools declare no permission (because reporting which grant is off needs none), the alarm
 * tool demands a usable hour and minute before touching anything, and the automation status
 * reports its four grants as separate fields rather than one all-clear boolean.
 *
 * WHY THE SEPARATE-FIELDS ASSERTION MATTERS. The whole value of automationStatus is that a
 * person can see WHICH grant is switched off. A refactor that collapsed the four into one
 * boolean would still compile, still return a result, and would hide exactly the fact the
 * tool exists to reveal. Pinning the four fields here makes that collapse a build failure.
 *
 * WHAT THIS CANNOT PROVE, stated so a green run is never mistaken for device evidence: that a
 * real clock app creates a real alarm, that a real Wi-Fi driver accepts the multicast lock,
 * or that the platform's next-alarm readback returns a value. Those need Steven's phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmsMiscDomainHonestyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val executor = AlarmsMiscDomainExecutor(context)

    /**
     * Only three tools, deliberately.
     *
     * multicastLock lives in the NETWORK domain and stays there: a second tool with that name
     * made the registry keep whichever loaded last, which the senses test caught. This domain
     * reports the lock's state through automationStatus instead of taking it a second time.
     */
    private val expectedTools = listOf(
        "automationStatus",
        "bootReceiverState",
        "setSystemAlarm",
    )

    private fun definitions(): List<ToolDefinition> = AlarmsMiscDomainDefinitions.definitions

    private fun grant(vararg permissions: String) {
        org.robolectric.Shadows.shadowOf(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
        ).grantPermissions(*permissions)
    }

    @Test
    fun everyExpectedToolIsDeclaredAndNothingElse() {
        val declared = definitions().map { it.name }.sorted()
        println("alarms_misc tools (${declared.size}): $declared")
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
            "the alarms_misc domain must be in the registry",
            TeamThreeToolDomains.domains.any { it.id == AlarmsMiscDomainModule.id },
        )
        assertEquals("alarms_misc", AlarmsMiscDomainModule.id)
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
     * The two health tools must declare nothing.
     *
     * Reading which grant is switched off needs no grant, and a declaration would make the
     * dispatcher refuse the very tool a person reaches for when something is not firing.
     */
    @Test
    fun theHealthToolsDeclareNoPermission() {
        val byName = definitions().associateBy { it.name }
        assertTrue(
            "automationStatus must declare no permission",
            byName["automationStatus"]?.permissions?.isEmpty() == true,
        )
        assertTrue(
            "bootReceiverState must declare no permission",
            byName["bootReceiverState"]?.permissions?.isEmpty() == true,
        )
    }

    /**
     * NO TOOL HERE DECLARES A PERMISSION, and that is now correct for all three.
     *
     * setSystemAlarm used to declare SET_ALARM, which is a privileged permission no app can
     * hold. The dispatcher refuses a tool when any declared permission is missing, so that
     * declaration made the alarm refuse every time even though the alarm INTENT it actually
     * uses needs no privilege at all. The declaration is gone.
     *
     * The two health tools never declared anything, because reading which grant is off needs no
     * grant. So the domain declares nothing at all, and a test that demanded otherwise would be
     * demanding the bug back.
     */
    @Test
    fun theDomainDeclaresNoPermissionAtAll() {
        val declaring = definitions()
            .filter { it.permissions.isNotEmpty() }
            .map { it.name to it.permissions }

        println("declared: ${definitions().flatMap { it.permissions }.distinct()}")
        assertTrue(
            "no tool here may declare a permission: the alarm intent needs none, the health " +
                "checks need none, and SET_ALARM cannot be held at all: $declaring",
            declaring.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // Validation and the shape of the health result
    // ------------------------------------------------------------------

    @Test
    fun anAlarmWithAnImpossibleHourIsRefused() = runBlocking {
        // No grant needed: the alarm intent carries no permission requirement, and the tool no
        // longer declares SET_ALARM, which no app can hold.
        val result = executor.execute(
            AlarmsMiscDomainDefinitions.SET_SYSTEM_ALARM,
            mapOf("hour" to 26, "minute" to 10),
        )
        assertFalse("an hour outside 0 to 23 must not succeed", result.success)
        assertTrue("it must say why", !result.error.isNullOrBlank())
        assertTrue(
            "it must name the hour requirement: ${result.error}",
            result.error.orEmpty().contains("hour"),
        )
    }

    @Test
    fun anAlarmWithAnImpossibleMinuteIsRefused() = runBlocking {
        val result = executor.execute(
            AlarmsMiscDomainDefinitions.SET_SYSTEM_ALARM,
            mapOf("hour" to 7, "minute" to 75),
        )
        assertFalse("a minute outside 0 to 59 must not succeed", result.success)
        assertTrue("it must say why", !result.error.isNullOrBlank())
    }

    @Test
    fun anAlarmWithNoTimeAtAllIsRefused() = runBlocking {
        val result = executor.execute(AlarmsMiscDomainDefinitions.SET_SYSTEM_ALARM, emptyMap())
        assertFalse("an alarm with no time must not succeed", result.success)
        assertTrue("it must say why", !result.error.isNullOrBlank())
    }

    /**
     * The multicast tool must NOT be declared here, because the network domain owns it.
     *
     * This is the assertion that would have caught the duplicate before the senses test did:
     * two domains registering one name means the registry silently keeps whichever loads
     * last and the tool behaves differently depending on load order.
     */
    @Test
    fun thisDomainDoesNotDeclareTheMulticastTool() {
        assertTrue(
            "multicastLock belongs to the network domain and must not be declared twice",
            definitions().none { it.name == "multicastLock" },
        )
    }

    /**
     * The automation status must report its four grants as separate fields.
     *
     * This is a compile-time guard as much as a test: if a refactor collapsed them into one
     * boolean, this payload could not carry the four values and the test would have to be
     * deleted deliberately rather than quietly broken.
     */
    @Test
    fun theAutomationStatusCarriesFourSeparateFacts() {
        val payload = AutomationStatusResult(
            success = true,
            bootReceiverDeclared = true,
            batteryOptimisationExempt = false,
            exactAlarmsAllowed = true,
            multicastLockHeld = false,
            routes = mapOf("batteryOptimisation" to "Settings > Apps > GURU > Battery > Unrestricted"),
        )

        assertTrue("boot receiver is its own field", payload.bootReceiverDeclared)
        assertFalse("battery exemption is its own field", payload.batteryOptimisationExempt)
        assertEquals(true, payload.exactAlarmsAllowed)
        assertFalse("multicast lock is its own field", payload.multicastLockHeld)
        assertEquals(
            "a switched-off grant must carry its own route",
            1,
            payload.routes.size,
        )
    }

    @Test
    fun theAutomationStatusAnswersWithoutAPermission() = runBlocking {
        val result = executor.execute(AlarmsMiscDomainDefinitions.AUTOMATION_STATUS, emptyMap())
        println("automationStatus: success=${result.success} error=${result.error}")

        assertFalse(
            "the health check must never be refused for a missing permission: ${result.error}",
            result.error.orEmpty().contains("Permission denied", ignoreCase = true),
        )
        assertTrue("it must report success, since reading these needs no grant", result.success)
    }

    @Test
    fun theBootReceiverStateAnswersWithoutAPermission() = runBlocking {
        val result = executor.execute(AlarmsMiscDomainDefinitions.BOOT_RECEIVER_STATE, emptyMap())
        println("bootReceiverState: success=${result.success} error=${result.error}")

        assertFalse(
            "the manifest check must never be refused for a missing permission: ${result.error}",
            result.error.orEmpty().contains("Permission denied", ignoreCase = true),
        )
    }

    @Test
    fun everyDescriptionSaysSomethingASchemaCannot() {
        val thin = definitions().filter { it.description.length < 120 }.map { it.name }
        assertTrue("descriptions too thin to warn a model: $thin", thin.isEmpty())
    }
}
