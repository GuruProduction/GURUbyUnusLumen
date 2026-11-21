// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions a schedule makes before anything runs.
 *
 * A SCHEDULE FIRES WITH NOBODY WATCHING, so the checks that guard it are the whole safety of the
 * feature. Two of them are pure arithmetic and belong here rather than in a device test:
 *
 *  - an interval below the floor is raised to it, because a schedule due faster than a model answers
 *    would queue work it can never drain;
 *  - a window missed by more than the grace period is counted rather than run, because starting
 *    hours of queued work at once does more harm than admitting the device was away.
 *
 * The rest of the runner needs a database and is tested against a real one, but these two are the
 * difference between a schedule and a stampede and they are decided entirely by numbers.
 */
class AgentScheduleRulesTest {

    @Test
    fun theIntervalFloorIsOneMinuteBecauseAModelCannotAnswerFaster() {
        /*
         * THE FLOOR IS ARITHMETIC, NOT PATERNALISM. A schedule due every second is due again before
         * the first run can finish, so the sweep would start work faster than anything completes and
         * the queue would grow without bound. One minute is the shortest interval at which one run
         * has any chance of finishing before the next is due.
         */
        assertEquals(60_000L, AgentScheduleRules.MIN_INTERVAL_MILLIS)
    }

    @Test
    fun theGracePeriodIsFifteenMinutes() {
        /*
         * A WINDOW MISSED BY MORE THAN THIS IS COUNTED, NOT RUN. The value is the decision: long
         * enough to absorb a device that was asleep or busy, short enough that the work is still
         * about the world it was scheduled for.
         */
        assertEquals(15 * 60_000L, AgentScheduleRules.MAX_LATE_MILLIS)
    }

    @Test
    fun aWindowInsideTheGracePeriodIsStillWorthRunning() {
        val dueAt = 1_000_000L
        val now = dueAt + 5 * 60_000L
        assertTrue(
            "five minutes late is within the grace and the work is still current",
            now - dueAt <= AgentScheduleRules.MAX_LATE_MILLIS,
        )
    }

    @Test
    fun aWindowPastTheGracePeriodIsNotRunLate() {
        val dueAt = 1_000_000L
        val now = dueAt + 3 * 60 * 60_000L
        assertFalse(
            "three hours late is not a late run, it is a missed one",
            now - dueAt <= AgentScheduleRules.MAX_LATE_MILLIS,
        )
    }

    @Test
    fun scheduledOperationIsOffByDefaultOnAnAgent() {
        /*
         * AN AGENT THAT HAS NOT OPTED IN DOES NOT RUN UNATTENDED. The default is the permission the
         * runner checks, so a default of true would mean every agent ever created starts work on a
         * schedule the moment one exists. The default is what makes the flag a permission rather
         * than a formality.
         */
        assertFalse(
            "scheduled operation must be granted rather than assumed",
            com.unuslumen.app.domain.model.AgentAutonomy.DEFAULT.operateOnSchedules,
        )
    }

    @Test
    fun anAgentCanBeGrantedScheduledOperationWithoutGrantingAnythingElse() {
        /*
         * AUTONOMY IS PER DIMENSION. Turning on schedules must not quietly turn on external side
         * effects or delegation as well, because those are different risks and a person granting one
         * has not granted the others.
         */
        val granted = com.unuslumen.app.domain.model.AgentAutonomy.DEFAULT.copy(operateOnSchedules = true)
        assertTrue(granted.operateOnSchedules)
        assertFalse("schedules do not imply external side effects", granted.performExternalSideEffects)
        assertFalse("nor delegation", granted.requestDelegation)
    }

    @Test
    fun aPlanProposedStepIsNotCountedAsDone() {
        /*
         * A PLAN ITEM MARKED PROPOSED IS NOT WORK THAT HAPPENED. The progress count is the room's
         * only quantitative claim, and a proposed step counted as done would report a plan complete
         * before anything was attempted.
         */
        val steps = listOf(
            PlanStepView("step-1", "one", PlanStepView.STATE_PROPOSED, 0),
            PlanStepView("step-2", "two", PlanStepView.STATE_PROPOSED, 0),
        )
        val run = RunView(
            runId = "r",
            conversationId = "c",
            assignment = "a",
            status = AgentRunStatuses.RUNNING,
            startedAt = 1,
            finishedAt = null,
            failureMessage = null,
            resultSummary = null,
            planSteps = steps,
            plan = null,
            toolActivity = emptyList(),
            messages = emptyList(),
            reasoning = ReasoningView("", true, true),
            decisions = emptyList(),
            verifications = emptyList(),
            contextSupplied = 0,
            contextOffered = 0,
            contextSources = emptyList(),
            artefacts = emptyList(),
            pendingApprovals = emptyList(),
            effectiveConfigSummary = "",
        )
        assertEquals("0 of 2 steps done", run.planProgress)
    }
}
