// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restricted-settings rules, proven on the JVM with no Android and no device.
 *
 * WHAT THIS PROVES. Which app-op states count as the sideload block, that a
 * capability already switched on is reported as on whatever the app-op says, that
 * a deliberate policy block is not described with a menu item that will not be
 * there, and that a failed probe is never reported as a block.
 *
 * WHY THAT LAST ONE MATTERS MOST. Reporting an unreadable app-op as "restricted"
 * would send someone to a menu item that does not exist, which is the same class of
 * defect as issue 30's apt probe reporting "binary missing" when its own
 * environment was the problem.
 *
 * Measured on an API 37 emulator on 2026-10-31: the release build read
 * `ACCESS_RESTRICTED_SETTINGS: default; rejectTime=+11h…`, with
 * `installerPackageName=null` and `initiatingPackageName=com.android.shell`, which
 * is the sideload that sets it.
 */
class RestrictedSettingsTest {

    private fun verdict(
        granted: Boolean = false,
        state: RestrictedSettings.AppOpState,
        menuKnown: Boolean = true,
    ) = RestrictedSettings.decide(
        capabilityLabel = "Accessibility",
        alreadyGranted = granted,
        state = state,
        settingsMenuItemKnown = menuKnown,
    )

    @Test
    fun aGrantedCapabilityIsReportedAsOnWhateverTheAppOpSays() {
        // The grant is the fact; the app-op is only the mechanism. Telling somebody to
        // clear a block on something already working would be wrong in the expensive
        // direction, because it sends them looking for a problem that is not there.
        for (state in RestrictedSettings.AppOpState.entries) {
            val result = verdict(granted = true, state = state)
            assertFalse(
                "a granted capability must never be called blocked (state=$state)",
                result.blockedByRestrictedSettings,
            )
            assertTrue("must say it is on (state=$state)", result.sentence.contains("is on"))
        }
    }

    @Test
    fun theSideloadDefaultIsReportedAsRestrictedSettings() {
        val result = verdict(state = RestrictedSettings.AppOpState.DEFAULT)
        assertTrue("default on a sideload is the block", result.blockedByRestrictedSettings)
        assertTrue(
            "must name the menu item: ${result.sentence}",
            result.sentence.contains("Allow restricted settings"),
        )
        assertTrue(
            "must name where the menu is: ${result.sentence}",
            result.sentence.contains("Settings > Apps > GURU"),
        )
        assertTrue(
            "must explain why, not just refuse: ${result.sentence}",
            result.sentence.contains("outside the Play Store"),
        )
    }

    @Test
    fun anAllowedAppOpWithTheCapabilityOffIsAnOrdinaryDenial() {
        // Allowed means the block is cleared, so the capability is simply switched off.
        // Dressing that up as restricted settings would be a lie in the other direction.
        val result = verdict(state = RestrictedSettings.AppOpState.ALLOWED)
        assertFalse("allowed is not blocked", result.blockedByRestrictedSettings)
        assertTrue("must point at Settings: ${result.sentence}", result.sentence.contains("Settings"))
        assertFalse(
            "must not blame restricted settings: ${result.sentence}",
            result.sentence.contains("Allow restricted settings"),
        )
    }

    @Test
    fun aPolicyBlockDoesNotSendThemLookingForAMenuItemThatIsNotThere() {
        // IGNORED is a deliberate device policy, not the sideload default. The menu item
        // is absent in that case, so naming it would waste somebody's time.
        val result = verdict(state = RestrictedSettings.AppOpState.IGNORED)
        assertTrue("a policy block still blocks", result.blockedByRestrictedSettings)
        assertFalse(
            "must not name a menu item that is not present: ${result.sentence}",
            result.sentence.contains("choose Allow restricted settings"),
        )
        assertTrue(
            "must name the real cause: ${result.sentence}",
            result.sentence.contains("device policy"),
        )
    }

    @Test
    fun aFailedProbeIsNotReportedAsABlock() {
        // Issue 30's shape: a probe that could not read the answer must not invent one.
        val result = verdict(state = RestrictedSettings.AppOpState.UNKNOWN)
        assertFalse("an unreadable app-op is not a block", result.blockedByRestrictedSettings)
        assertFalse(
            "must not send them hunting for the menu: ${result.sentence}",
            result.sentence.contains("Allow restricted settings"),
        )
    }

    @Test
    fun anUnknownMenuOffersThePathThatWorksWithoutTheMenuItem() {
        val result = verdict(state = RestrictedSettings.AppOpState.DEFAULT, menuKnown = false)
        assertTrue("still a block", result.blockedByRestrictedSettings)
        assertTrue(
            "must name the App info screen when the menu may be absent: ${result.sentence}",
            result.sentence.contains("App info"),
        )
        // It must never simply tell them to grant the permission, because there is no
        // permission to grant. That was the original defect.
        assertFalse(
            "must not pretend a grant is the answer: ${result.sentence}",
            result.sentence.trim().endsWith("then switch Accessibility on again."),
        )
    }

    @Test
    fun theSentenceAlwaysNamesTheCapability() {
        // The person needs to know WHICH switch they are being sent to, so the label has
        // to survive into every branch.
        for (state in RestrictedSettings.AppOpState.entries) {
            for (granted in listOf(true, false)) {
                val result = verdict(granted = granted, state = state)
                assertTrue(
                    "every sentence must name the capability (state=$state granted=$granted): " +
                        result.sentence,
                    result.sentence.contains("Accessibility"),
                )
            }
        }
    }

    @Test
    fun theVerdictCarriesTheStateItWasGiven() {
        // The caller renders the state and the sentence together, so a verdict that
        // changed the state underneath would make the two disagree on screen.
        for (state in RestrictedSettings.AppOpState.entries) {
            assertEquals(state, verdict(state = state).state)
        }
    }
}
