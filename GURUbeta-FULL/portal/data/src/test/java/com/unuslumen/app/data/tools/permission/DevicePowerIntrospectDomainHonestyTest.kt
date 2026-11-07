// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The device-power and introspection domain, proven on the JVM without a device.
 *
 * WHAT THIS PROVES. The domain declares exactly its five agreed tools, every tool
 * name is distinct, every permission it names resolves through the catalogue, the
 * module is wired under its agreed id, and every refusal that matters fails
 * closed with a sentence a person could act on.
 *
 * WHY THE FACTORY-RESET ASSERTIONS CARRY THE MOST WEIGHT. factoryReset erases
 * everything and has no undo. The two failures this test pins are the two ways a
 * destructive tool can go wrong without its author noticing: it wipes when it was
 * not asked to, or it reports a wipe it never issued. Both must be impossible.
 *
 * WHAT THIS CANNOT PROVE, stated so a green run is never mistaken for device
 * evidence: that a real phone wakes, charges, dumps a service or wipes itself.
 * Robolectric's PowerManager and BatteryManager values are stubs, so this test
 * asserts the shape of refusals and declarations rather than any reading.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DevicePowerIntrospectDomainHonestyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val executor: ToolExecutor = DevicePowerIntrospectDomainExecutor(context)

    /**
     * The grant controls live on the application's own context shadow, which is the
     * route this repository already uses: ShadowPackageManager has no grant method
     * and ShadowInstrumentation's are package-private.
     */
    private val shadowContext
        get() = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())

    /** The five names the domain owns, kept sorted so the comparison reads as a set. */
    private val expectedTools = listOf(
        "batteryDetail",
        "factoryReset",
        "powerControl",
        "readLogs",
        "systemDump",
    )

    @Test
    fun theDomainDeclaresExactlyTheFiveAgreedToolNames() {
        val declared = DevicePowerIntrospectDomainDefinitions.definitions.map { it.name }.sorted()
        println("device power tools (${declared.size}): $declared")
        assertEquals(expectedTools, declared)
    }

    /**
     * A permission string the catalogue does not know would make the dispatcher's
     * refusal either generic or accuse the tool of a defect, which sends a person
     * somewhere that cannot help.
     */
    @Test
    fun everyDeclaredPermissionResolvesThroughTheCatalogue() {
        val unresolved = DevicePowerIntrospectDomainDefinitions.definitions
            .flatMap { definition -> definition.permissions }
            .filter { permission -> TeamThreePermissions.forPermission(permission) == null }

        val declared = DevicePowerIntrospectDomainDefinitions.definitions.flatMap { it.permissions }
        println("declared permissions: $declared")
        assertTrue("permissions with no catalogue entry: $unresolved", unresolved.isEmpty())
    }

    @Test
    fun theModuleIdIsDevicePowerIntrospect() {
        assertEquals("device_power_introspect", DevicePowerIntrospectDomainModule.id)
    }

    /**
     * No flag, no wipe. The refusal must say what is missing rather than fail with
     * an empty error a model cannot act on.
     */
    @Test
    fun factoryResetWithoutConfirmRefuses() = runBlocking {
        val result = executor.execute(
            DevicePowerIntrospectDomainDefinitions.FACTORY_RESET,
            emptyMap(),
        )
        val error = result.error.orEmpty()
        println("factoryReset without confirm: success=${result.success} error=$error")

        assertFalse("a reset with no confirmation must not claim success", result.success)
        assertTrue("the refusal must say something: '$error'", error.isNotBlank())
        assertTrue(
            "the refusal must mention wiping or confirmation: $error",
            error.contains("wip", ignoreCase = true) || error.contains("confirm", ignoreCase = true),
        )
    }

    /**
     * A boolean is something a model can pass by accident while reasoning about a
     * schema, so the literal phrase is what actually decides. A near miss must not
     * be accepted.
     */
    @Test
    fun factoryResetWithTheWrongPhraseStillRefuses() = runBlocking {
        val result = executor.execute(
            DevicePowerIntrospectDomainDefinitions.FACTORY_RESET,
            mapOf("confirm" to true, "alsoConfirmText" to "wipe this phone"),
        )
        val error = result.error.orEmpty()
        println("factoryReset with a wrong phrase: success=${result.success} error=$error")

        assertFalse("a wrong phrase must not wipe anything", result.success)
        assertTrue(
            "the refusal must name the exact phrase requirement: $error",
            error.contains(DevicePowerIntrospectDomainDefinitions.WIPE_CONFIRMATION_PHRASE),
        )
        assertTrue("the refusal must say the phrase did not match: $error", error.contains("phrase"))
    }

    /**
     * THE SAFETY ASSERTION. Both confirmations are correct and the permission is
     * held, and the only thing standing between this call and an erased phone is
     * that the app is not Device Owner. That must be a refusal, and it must never
     * be reported as a wipe that was issued.
     *
     * The grant is applied deliberately: without it the permission guard would be
     * what refuses, and the Device Owner branch would never be reached.
     */
    @Test
    fun factoryResetOnANonOwnerDeviceFailsClosedWithoutIssuingAWipe() {
        shadowContext.grantPermissions(TeamThreePermissions.MASTER_CLEAR.permission)

        val result = runBlocking {
            executor.execute(
                DevicePowerIntrospectDomainDefinitions.FACTORY_RESET,
                mapOf(
                    "confirm" to true,
                    "alsoConfirmText" to DevicePowerIntrospectDomainDefinitions.WIPE_CONFIRMATION_PHRASE,
                ),
            )
        }
        val error = result.error.orEmpty()
        println("factoryReset fully confirmed on a non-owner: success=${result.success} error=$error")

        assertFalse("a destructive tool on a non-owner device must fail", result.success)
        assertTrue(
            "the refusal must name Device Owner or the Settings screen: $error",
            error.contains("Device Owner") || error.contains("Settings"),
        )
        assertFalse(
            "a wipe must never be reported as issued: ${result.rawJson}",
            result.rawJson.contains("The wipe has been issued"),
        )

        val payload = result.resultData as? FactoryResetResult
        assertNotNull("the refusal must carry a factory-reset payload", payload)
        assertFalse("wipeIssued must be false when this build is not Device Owner", payload!!.wipeIssued)
    }

    /**
     * A denial is the default state under Robolectric, but it is denied explicitly
     * so the test cannot pass because someone granted it elsewhere.
     */
    @Test
    fun readLogsReportsARefusalWhenThePermissionIsDenied() = runBlocking {
        shadowContext.denyPermissions(TeamThreePermissions.READ_LOGS.permission)

        val result = executor.execute(DevicePowerIntrospectDomainDefinitions.READ_LOGS, emptyMap())
        val error = result.error.orEmpty()
        println("readLogs denied: success=${result.success} error=$error")

        assertFalse("a denied log read must not claim success", result.success)
        assertTrue("the refusal must say something: '$error'", error.isNotBlank())
    }

    /**
     * A dump with no service name must be refused on its own terms. The grant is
     * applied so the service validation is what refuses, and the sentence naming
     * the missing service is asserted, rather than accepting any refusal at all.
     */
    @Test
    fun systemDumpRefusesWithoutAServiceName() {
        shadowContext.grantPermissions(TeamThreePermissions.DUMP.permission)

        val blank = runBlocking {
            executor.execute(
                DevicePowerIntrospectDomainDefinitions.SYSTEM_DUMP,
                mapOf("service" to "   "),
            )
        }
        val absent = runBlocking {
            executor.execute(DevicePowerIntrospectDomainDefinitions.SYSTEM_DUMP, emptyMap())
        }

        println("systemDump blank service: success=${blank.success} error=${blank.error}")
        println("systemDump absent service: success=${absent.success} error=${absent.error}")

        assertFalse("a blank service must not be dumped", blank.success)
        assertTrue("the refusal must say something: '${blank.error}'", blank.error.orEmpty().isNotBlank())
        assertTrue(
            "the refusal must name the service requirement: ${blank.error}",
            blank.error.orEmpty().contains("system service"),
        )

        assertFalse("an absent service must not be dumped", absent.success)
        assertTrue("the refusal must say something: '${absent.error}'", absent.error.orEmpty().isNotBlank())
        assertTrue(
            "the refusal must name the service requirement: ${absent.error}",
            absent.error.orEmpty().contains("system service"),
        )
    }

    /**
     * A description that does not state the limit is a description that lets a
     * model over-claim, and every tool here has a limit worth stating.
     */
    @Test
    fun everyDescriptionIsLongEnoughToWarnAModel() {
        val thin = DevicePowerIntrospectDomainDefinitions.definitions
            .filter { definition -> definition.description.length < 120 }
            .map { definition -> definition.name }

        assertTrue("descriptions too thin to warn a model: $thin", thin.isEmpty())
    }

    @Test
    fun noTwoToolNamesAreEqual() {
        val names = DevicePowerIntrospectDomainDefinitions.definitions.map { it.name }
        val duplicates = names.groupBy { it }.filter { it.value.size > 1 }.keys
        assertEquals("duplicate tool names: $duplicates", names.size, names.toSet().size)
    }
}
