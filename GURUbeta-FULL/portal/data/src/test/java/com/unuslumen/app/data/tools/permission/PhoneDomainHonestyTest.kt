// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.data.tools.phone.PhoneDomainModule
import com.unuslumen.app.data.tools.phone.PhoneToolDefinitions
import com.unuslumen.app.data.tools.phone.PhoneToolExecutor
import com.unuslumen.app.data.tools.registry.ToolExecutor
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
 * The phone domain, proven on the JVM without a device.
 *
 * WHAT THIS PROVES: every tool is registered and declared with the permission that
 * gates it, a destructive tool refuses without confirmation, a denied permission
 * produces a refusal rather than a success, and the privileged tools report the
 * dependency instead of attempting a call the platform would refuse underneath.
 *
 * WHAT IT CANNOT PROVE: that a call is really placed, an SMS really arrives, or a
 * dual-SIM phone reports two numbers. Those need Steven's own phone. Stating the
 * split here is the point; a green run must never be read as "the call worked".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneDomainHonestyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val executor: ToolExecutor = PhoneToolExecutor(context)

    private val expectedTools = listOf(
        "phoneCall", "answerCall", "endCall", "phoneState", "phoneNumbers",
        "precisePhoneState", "outgoingCallWatch", "sipStatus", "registerCallAccount",
        "handoverCall", "smsRead", "smsSend", "smsWatch", "wapPush", "cellBroadcasts",
    )

    @Test
    fun everyPhoneToolIsDeclaredAndRegistered() {
        val declared = PhoneToolDefinitions.definitions.map { it.name }.sorted()
        println("phone tools (${declared.size}): $declared")
        assertEquals(expectedTools.sorted(), declared)
        assertTrue(
            "the phone domain must be in the registry",
            TeamThreeToolDomains.domains.any { it.id == PhoneDomainModule.id },
        )
    }

    /**
     * Every tool must declare the permission that gates it. An empty list on a tool
     * that reads the phone would let the dispatcher run it and let the platform throw
     * underneath, which produces a worse sentence than a refusal.
     */
    @Test
    fun everyPhoneToolDeclaresItsGatingPermission() {
        val missing = PhoneToolDefinitions.definitions
            .filter { it.permissions.isEmpty() }
            .map { it.name }
        assertTrue("tools with no declared permission: $missing", missing.isEmpty())
    }

    /**
     * Both irreversible actions refuse without confirmation, and say what to pass.
     *
     * This is the assertion that protects the one mistake that cannot be undone: an
     * SMS that leaves the phone, or a call that rings, because the flag defaulted to
     * true somewhere.
     */
    @Test
    fun smsSendRefusesWithoutConfirmation() = runBlocking {
        val result = executor.execute(
            "smsSend",
            mapOf("number" to "+447700900123", "message" to "hello"),
        )
        println("smsSend unconfirmed: success=${result.success} error=${result.error}")
        assertFalse("an unconfirmed send must not claim success", result.success)
        assertTrue(
            "the refusal must say what to pass: ${result.rawJson}",
            result.rawJson.contains("PENDING_CONFIRMATION") && result.rawJson.contains("confirmed = true"),
        )
    }

    @Test
    fun phoneCallRefusesWithoutConfirmationOrNamesTheGrant() = runBlocking {
        val result = executor.execute(
            "phoneCall",
            mapOf("number" to "+447700900123"),
        )
        println("phoneCall unconfirmed: success=${result.success} error=${result.error} raw=${result.rawJson}")
        assertFalse("an unconfirmed call must not claim success", result.success)
        // Either the grant is missing, in which case the refusal names the route, or it
        // was held and the confirmation is what stopped it. Both are honest; a success
        // is not.
        val honest = result.rawJson.contains("PENDING_CONFIRMATION") ||
            result.error.orEmpty().contains("Grant it in")
        assertTrue("neither the grant nor the confirmation stopped it: ${result.error}", honest)
    }

    /**
     * A denied permission must produce a failure, not a fabricated empty result.
     */
    @Test
    fun smsReadIsARefusalNotAnEmptyListWhenDenied() = runBlocking {
        org.robolectric.Shadows.shadowOf(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
        ).denyPermissions("android.permission.READ_SMS")

        val result = executor.execute("smsRead", emptyMap())
        println("smsRead denied: success=${result.success} error=${result.error}")
        assertFalse("a denied read must not report success", result.success)
        assertTrue(
            "must name the SMS route: ${result.error}",
            result.error.orEmpty().contains("Grant it in"),
        )
    }

    /**
     * The three privileged and platform-limited tools must report the limitation
     * rather than claim the capability.
     */
    @Test
    fun privilegedAndPlatformLimitedToolsReportTheLimitation() = runBlocking {
        val sip = executor.execute("sipStatus", emptyMap())
        println("sipStatus: ${sip.rawJson.take(220)}")
        if (sip.success) {
            assertTrue(
                "SIP must say the stack is gone rather than pretend to be configured",
                sip.rawJson.contains("removed the SIP API") || sip.rawJson.contains("no SIP stack"),
            )
        }

        val account = executor.execute("registerCallAccount", emptyMap())
        println("registerCallAccount: ${account.rawJson.take(220)}")
        if (!account.success) {
            assertTrue(
                "a refused registration must name the missing ConnectionService: ${account.error}",
                account.error.orEmpty().contains("ConnectionService") ||
                    account.error.orEmpty().contains("Grant it in"),
            )
        }

        val wap = executor.execute("wapPush", emptyMap())
        println("wapPush: ${wap.rawJson.take(200)}")
        if (wap.success) {
            assertTrue(
                "wapPush must say the default messaging role is what gates it",
                wap.rawJson.contains("default messaging"),
            )
        }
    }

    /**
     * A description that does not carry a warning is a description that lets the model
     * over-claim. Each of these tools has a limit worth stating, so each must be long
     * enough to state one.
     */
    @Test
    fun everyPhoneDescriptionCarriesItsLimit() {
        val thin = PhoneToolDefinitions.definitions
            .filter { it.description.length < 140 }
            .map { it.name }
        assertTrue("descriptions too thin to warn a model: $thin", thin.isEmpty())
    }
}
