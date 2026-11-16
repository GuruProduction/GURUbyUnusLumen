// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.data.tools.registry.ToolDefinition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reboot, shutdown and the process limit: the gates and the honest reporting.
 *
 * WHY THE GATES ARE PROVED HERE RATHER THAN ON A DEVICE. A test that actually rebooted a phone
 * would be the worst possible way to verify a confirmation. What matters is that a call
 * WITHOUT the confirmation does nothing, and that is a pure decision the JVM can drive.
 *
 * WHAT THIS CANNOT PROVE: that `reboot` through Shizuku restarts a real phone, or that a real
 * device accepts a cached-process limit change. Those need the device, and Robolectric holds
 * no Shizuku channel at all, which is itself useful: it is the exact state of a phone where
 * Shizuku is absent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DevicePowerBootHonestyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val executor = DevicePowerIntrospectDomainExecutor(context)

    private fun definitions(): List<ToolDefinition> = DevicePowerIntrospectDomainDefinitions.definitions

    // ------------------------------------------------------------------
    // The three tools exist and are reachable
    // ------------------------------------------------------------------

    @Test
    fun theThreeToolsAreDeclared() {
        val names = definitions().map { it.name }.toSet()
        assertTrue(
            "deviceReboot must be declared, or it does not exist in the app",
            names.contains(DevicePowerIntrospectDomainDefinitions.DEVICE_REBOOT),
        )
        assertTrue(
            "deviceShutdown must be declared",
            names.contains(DevicePowerIntrospectDomainDefinitions.DEVICE_SHUTDOWN),
        )
        assertTrue(
            "processLimit must be declared",
            names.contains(DevicePowerIntrospectDomainDefinitions.PROCESS_LIMIT),
        )
    }

    @Test
    fun noToolDeclaresAPrivilegedPermissionThatWouldBlockIt() {
        // REBOOT, SHUTDOWN and SET_PROCESS_LIMIT are all privileged and unholdable, so a
        // declaration would make the dispatcher refuse before the confirmation was evaluated.
        val declared = definitions()
            .filter { it.permissions.isNotEmpty() }
            .map { it.name to it.permissions }
        assertEquals(
            "no device-power tool may declare a privileged permission",
            emptyList<Pair<String, List<String>>>(),
            declared,
        )
    }

    // ------------------------------------------------------------------
    // The confirmation gate
    // ------------------------------------------------------------------

    @Test
    fun aRebootWithoutTheConfirmFlagIssuesNothing() = runBlocking {
        val result = executor.execute(
            DevicePowerIntrospectDomainDefinitions.DEVICE_REBOOT,
            mapOf("alsoConfirmText" to DevicePowerIntrospectDomainDefinitions.POWER_CONFIRMATION_PHRASE),
        )
        println("reboot without flag: ${result.error}")
        assertFalse("a reboot without the flag must fail", result.success)
    }

    @Test
    fun aRebootWithoutThePhraseIssuesNothing() = runBlocking {
        val result = executor.execute(
            DevicePowerIntrospectDomainDefinitions.DEVICE_REBOOT,
            mapOf("confirm" to true),
        )
        println("reboot without phrase: ${result.error}")
        assertFalse("a reboot without the phrase must fail", result.success)
        assertTrue(
            "the refusal must say what phrase is required: ${result.error}",
            result.error.orEmpty().contains("RESTART THIS PHONE"),
        )
    }

    @Test
    fun aRebootWithTheWrongPhraseIssuesNothing() = runBlocking {
        val result = executor.execute(
            DevicePowerIntrospectDomainDefinitions.DEVICE_REBOOT,
            mapOf("confirm" to true, "alsoConfirmText" to "restart this phone"),
        )
        assertFalse("the phrase is matched exactly, so lower case must not pass", result.success)
    }

    /**
     * The two phrases are different, so a shutdown cannot be confirmed with a reboot's words.
     *
     * That is the whole reason each action has its own phrase: one literal is something a model
     * can produce from the schema of the other, and then a shutdown fires on a request to
     * restart.
     */
    @Test
    fun theShutdownPhraseIsNotTheRebootPhrase() {
        assertNotEquals(
            DevicePowerIntrospectDomainDefinitions.POWER_CONFIRMATION_PHRASE,
            DevicePowerIntrospectDomainDefinitions.SHUTDOWN_CONFIRMATION_PHRASE,
        )
    }

    @Test
    fun aShutdownCannotBeConfirmedWithTheRebootPhrase() = runBlocking {
        val result = executor.execute(
            DevicePowerIntrospectDomainDefinitions.DEVICE_SHUTDOWN,
            mapOf(
                "confirm" to true,
                "alsoConfirmText" to DevicePowerIntrospectDomainDefinitions.POWER_CONFIRMATION_PHRASE,
            ),
        )
        assertFalse(
            "the reboot phrase must not authorise a shutdown",
            result.success,
        )
        assertTrue(
            "the refusal must name the shutdown phrase: ${result.error}",
            result.error.orEmpty().contains("POWER OFF THIS PHONE"),
        )
    }

    // ------------------------------------------------------------------
    // The shell state is reported, not guessed
    // ------------------------------------------------------------------

    /**
     * With a correct confirmation and no Shizuku, the result names the channel's state.
     *
     * This is the shape of every ordinary install: the confirmations pass and the shell is
     * absent. Reporting "reboot failed" would leave the person with nothing to do, so the state
     * sentence names install, start or allow, whichever applies.
     */
    @Test
    fun aConfirmedRebootWithNoShellSaysWhichChannelIsMissing() = runBlocking {
        val result = executor.execute(
            DevicePowerIntrospectDomainDefinitions.DEVICE_REBOOT,
            mapOf(
                "confirm" to true,
                "alsoConfirmText" to DevicePowerIntrospectDomainDefinitions.POWER_CONFIRMATION_PHRASE,
            ),
        )
        println("confirmed reboot, no shell: success=${result.success} error=${result.error}")

        // On the JVM there is no Shizuku binder, so this must be a clean refusal rather than a
        // crash or a claimed reboot.
        assertFalse("nothing may be reported as issued with no shell channel", result.success)
        assertTrue("it must say why", !result.error.isNullOrBlank())
        assertTrue(
            "it must name Shizuku rather than only failing: ${result.error}",
            result.error.orEmpty().contains("Shizuku"),
        )
    }

    // ------------------------------------------------------------------
    // The process limit
    // ------------------------------------------------------------------

    @Test
    fun aProcessLimitGetNeverFailsForWantOfAPermission() = runBlocking {
        val result = executor.execute(
            DevicePowerIntrospectDomainDefinitions.PROCESS_LIMIT,
            mapOf("action" to "GET"),
        )
        val error = result.error.orEmpty()
        println("processLimit GET: success=${result.success} error=$error")

        assertFalse(
            "a read must never be refused for a missing permission: $error",
            error.contains("Permission denied", ignoreCase = true),
        )
    }

    @Test
    fun aProcessLimitSetWithoutANumberIsRefused() = runBlocking {
        val result = executor.execute(
            DevicePowerIntrospectDomainDefinitions.PROCESS_LIMIT,
            mapOf("action" to "SET"),
        )
        assertFalse("a SET with no limit must fail", result.success)
        assertTrue(
            "it must name the range: ${result.error}",
            result.error.orEmpty().contains("0 to 50"),
        )
    }

    @Test
    fun aProcessLimitOutsideTheRangeIsRefused() = runBlocking {
        val tooHigh = executor.execute(
            DevicePowerIntrospectDomainDefinitions.PROCESS_LIMIT,
            mapOf("action" to "SET", "limit" to 500),
        )
        val negative = executor.execute(
            DevicePowerIntrospectDomainDefinitions.PROCESS_LIMIT,
            mapOf("action" to "SET", "limit" to -3),
        )
        assertFalse("a limit outside the range must fail", tooHigh.success)
        assertFalse("a negative limit must fail", negative.success)
    }

    @Test
    fun anUnknownProcessLimitActionIsRefused() = runBlocking {
        val result = executor.execute(
            DevicePowerIntrospectDomainDefinitions.PROCESS_LIMIT,
            mapOf("action" to "RESET"),
        )
        assertFalse(result.success)
        assertTrue(
            "it must name the actions it accepts: ${result.error}",
            result.error.orEmpty().contains("GET"),
        )
    }

    // ------------------------------------------------------------------
    // Descriptions
    // ------------------------------------------------------------------

    @Test
    fun everyDescriptionWarnsAboutWhatTheToolDoes() {
        val thin = definitions().filter { it.description.length < 120 }.map { it.name }
        assertTrue("descriptions too thin to warn a model: $thin", thin.isEmpty())
    }

    @Test
    fun theDestructiveToolsScreamInTheirOwnDescription() {
        // A description is the only warning a model reads before choosing a tool, so both
        // destructive ones have to say what they end.
        val reboot = definitions().first { it.name == DevicePowerIntrospectDomainDefinitions.DEVICE_REBOOT }
        val shutdown = definitions().first { it.name == DevicePowerIntrospectDomainDefinitions.DEVICE_SHUTDOWN }

        assertTrue(
            "the reboot description must say it ends everything: ${reboot.description}",
            reboot.description.contains("ends every app"),
        )
        assertTrue(
            "the shutdown description must say it ends everything: ${shutdown.description}",
            shutdown.description.contains("ends everything"),
        )
    }
}
