// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether an agent may interrupt its owner, decided once rather than per call site.
 *
 * THE DEFECT THIS PREVENTS. `AgentNotifications` carried an enabled flag, an urgency threshold and a
 * quiet window for a phase and nothing read them, so an agent's notification settings were a control
 * that changed nothing. The gate is what makes them real, and a gate is only a control if each of its
 * three inputs actually decides something, which is what these tests pin.
 */
class AgentNotificationPolicyTest {

    private fun verdict(
        notifications: AgentNotifications = AgentNotifications.DEFAULT,
        urgency: AgentUrgency = AgentUrgency.NORMAL,
        minute: Int = 12 * 60,
    ) = AgentNotificationPolicy.evaluate(notifications, urgency, minute)

    @Test
    fun aDefaultAgentAtNormalUrgencyInTheAfternoonIsHeard() {
        val result = verdict()
        assertTrue("the default is to notify", result.shouldNotify)
        assertNull("and a permitted message carries no reason to suppress it", result.suppressedBecause)
    }

    @Test
    fun notificationsOffSilencesEverythingWhateverTheUrgency() {
        /*
         * THE MASTER SWITCH BEATS THE THRESHOLD. An owner who turned notifications off meant silence,
         * and an agent that can override that by claiming BLOCKING has not been configured, it has
         * been given a loophole.
         */
        val off = AgentNotifications(enabled = false)
        for (urgency in AgentUrgency.entries) {
            val result = verdict(notifications = off, urgency = urgency)
            assertFalse("$urgency must not interrupt when notifications are off", result.shouldNotify)
            assertNotNull("and the reason is stated", result.suppressedBecause)
        }
    }

    @Test
    fun aMessageBelowTheThresholdDoesNotInterrupt() {
        val highOnly = AgentNotifications(minUrgency = AgentUrgency.HIGH)
        assertFalse(
            "normal urgency is below a HIGH threshold",
            verdict(notifications = highOnly, urgency = AgentUrgency.NORMAL).shouldNotify,
        )
        assertTrue(
            "and high urgency meets it",
            verdict(notifications = highOnly, urgency = AgentUrgency.HIGH).shouldNotify,
        )
        assertTrue(
            "as does blocking",
            verdict(notifications = highOnly, urgency = AgentUrgency.BLOCKING).shouldNotify,
        )
    }

    @Test
    fun theThresholdIsInclusive() {
        val normalOnly = AgentNotifications(minUrgency = AgentUrgency.NORMAL)
        assertTrue(
            "a message at exactly the threshold interrupts",
            verdict(notifications = normalOnly, urgency = AgentUrgency.NORMAL).shouldNotify,
        )
    }

    @Test
    fun lowUrgencyIsSilentWhenTheThresholdIsNormal() {
        assertFalse(verdict(urgency = AgentUrgency.LOW).shouldNotify)
    }

    @Test
    fun aQuietWindowHoldsAnOrdinaryMessage() {
        val quiet = AgentNotifications(quietFromMinute = 22 * 60, quietToMinute = 7 * 60)
        assertFalse(
            "23:00 is inside the window",
            verdict(notifications = quiet, urgency = AgentUrgency.NORMAL, minute = 23 * 60).shouldNotify,
        )
        assertFalse(
            "02:00 crosses midnight and is still inside",
            verdict(notifications = quiet, urgency = AgentUrgency.HIGH, minute = 2 * 60).shouldNotify,
        )
        assertTrue(
            "09:00 is outside it",
            verdict(notifications = quiet, urgency = AgentUrgency.NORMAL, minute = 9 * 60).shouldNotify,
        )
    }

    @Test
    fun blockingBeatsTheQuietWindowAndNothingElseDoes() {
        /*
         * AN AGENT THAT HAS STOPPED DEAD AT 03:00 IS NOT BEING CONSIDERATE BY STAYING QUIET. The
         * window means "do not wake me for ordinary work", and BLOCKING is the urgency reserved for
         * work that cannot continue at all. Everything below it waits for morning.
         */
        val quiet = AgentNotifications(quietFromMinute = 22 * 60, quietToMinute = 7 * 60)
        assertTrue(
            "blocking interrupts through the window",
            verdict(notifications = quiet, urgency = AgentUrgency.BLOCKING, minute = 3 * 60).shouldNotify,
        )
        assertFalse(
            "high urgency does not",
            verdict(notifications = quiet, urgency = AgentUrgency.HIGH, minute = 3 * 60).shouldNotify,
        )
    }

    @Test
    fun theRankingIsTheDeclaredOrderAndATestPinsIt() {
        /*
         * EVERY THRESHOLD IN THE APP IS A COMPARISON AGAINST THIS ORDER. Reordering the enum would
         * silently invert all of them, so the order is asserted rather than assumed.
         */
        assertEquals(0, AgentNotificationPolicy.rank(AgentUrgency.LOW))
        assertEquals(1, AgentNotificationPolicy.rank(AgentUrgency.NORMAL))
        assertEquals(2, AgentNotificationPolicy.rank(AgentUrgency.HIGH))
        assertEquals(3, AgentNotificationPolicy.rank(AgentUrgency.BLOCKING))
    }

    @Test
    fun aSuppressionAlwaysCarriesItsReason() {
        /*
         * A SILENT SUPPRESSION IS INDISTINGUISHABLE FROM A BROKEN ONE. Every path that says no says
         * why, so a person wondering why nothing arrived can read the answer rather than guess.
         */
        val cases = listOf(
            AgentNotifications(enabled = false),
            AgentNotifications(minUrgency = AgentUrgency.BLOCKING),
            AgentNotifications(quietFromMinute = 0, quietToMinute = 60 * 24),
        )
        cases.forEachIndexed { index, notifications ->
            val result = verdict(notifications = notifications, urgency = AgentUrgency.LOW, minute = 60)
            assertFalse("case $index is suppressed", result.shouldNotify)
            assertNotNull("case $index states why", result.suppressedBecause)
            assertTrue("case $index says something", result.suppressedBecause!!.isNotBlank())
        }
    }

    @Test
    fun suppressingTheAlertNeverSuppressesTheMessage() {
        /*
         * THIS POLICY DECIDES ONLY WHETHER TO INTERRUPT. A person who set a quiet window asked not to
         * be woken, not to be kept ignorant, so the verdict carries no instruction to drop anything.
         * The type proves that by having no such field to set.
         */
        val quiet = AgentNotifications(enabled = false)
        val result = verdict(notifications = quiet, urgency = AgentUrgency.BLOCKING)
        assertTrue("the run is still recorded", result.suppressedBecause!!.contains("room"))
    }

    @Test
    fun aQuietWindowWithNoBoundsNeverSuppresses() {
        val halfWindow = AgentNotifications(quietFromMinute = null, quietToMinute = null)
        assertTrue(verdict(notifications = halfWindow, urgency = AgentUrgency.NORMAL).shouldNotify)
    }
}
