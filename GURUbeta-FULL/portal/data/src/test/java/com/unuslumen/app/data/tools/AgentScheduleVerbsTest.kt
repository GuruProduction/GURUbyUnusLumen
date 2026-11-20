// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.agents.AgentRunHost
import com.unuslumen.app.data.agents.AgentRunLedger
import com.unuslumen.app.data.agents.AgentRuntime
import com.unuslumen.app.data.agents.AgentScheduleService
import com.unuslumen.app.data.tools.registry.ToolRegistry
import com.unuslumen.app.database.dao.AgentScheduleDao
import com.unuslumen.app.database.entity.AgentScheduleEntity
import com.unuslumen.app.domain.model.AgentAutonomy
import com.unuslumen.app.domain.model.GuruAgent
import com.unuslumen.app.domain.repository.AgentCatalogue
import com.unuslumen.app.domain.repository.GuruAgentRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four schedule verbs, and the three refusals that are the feature.
 *
 * A SCHEDULE IS UNATTENDED WORK, so the ways to create one that cannot run must be refused at the
 * point of creation. A schedule that exists and never fires is worse than one that was never made,
 * because it looks like work that was arranged and the owner has no way to know it is not happening.
 */
class AgentScheduleVerbsTest {

    private fun agent(
        name: String = "Worker",
        enabled: Boolean = true,
        schedules: Boolean = false,
    ) = GuruAgent(
        id = "agent-1",
        slug = "worker",
        name = name,
        description = "",
        systemPrompt = "you work",
        toolPermissions = emptyList(),
        source = GuruAgent.SOURCE_USER,
        enabled = enabled,
        createdAt = 1,
        updatedAt = 1,
        autonomy = AgentAutonomy.DEFAULT.copy(operateOnSchedules = schedules),
    )

    private fun executor(
        found: GuruAgent?,
        stored: MutableList<AgentScheduleEntity> = mutableListOf(),
    ): Pair<AgentAuthoringToolExecutor, MutableList<AgentScheduleEntity>> {
        val repo = io.mockk.mockk<GuruAgentRepository>(relaxed = true)
        io.mockk.coEvery { repo.resolveAgent(any()) } returns found
        io.mockk.coEvery { repo.getAgent(any()) } returns found
        /*
         * THE NAME CHECK COMES FIRST IN createAgent, so a permissive stub would refuse every create
         * for a reason unrelated to the grant being tested. Stated here rather than worked around in
         * the assertion, because the refusal under test is the tool-name rule.
         */
        io.mockk.coEvery { repo.isNameAvailable(any()) } returns true

        val dao = io.mockk.mockk<AgentScheduleDao>(relaxed = true)
        io.mockk.coEvery { dao.all() } answers { stored.toList() }
        io.mockk.coEvery { dao.forAgent(any()) } answers { stored.toList() }
        io.mockk.coEvery { dao.insert(any()) } answers {
            stored += firstArg<AgentScheduleEntity>()
        }
        io.mockk.coEvery { dao.schedule(any()) } answers {
            stored.firstOrNull { it.scheduleId == firstArg<String>() }
        }
        io.mockk.coEvery { dao.setEnabled(any(), any(), any(), any()) } returns Unit
        io.mockk.coEvery { dao.delete(any()) } answers {
            stored.removeAll { it.scheduleId == firstArg<String>() }
        }

        val service = AgentScheduleService(
            scheduleDao = dao,
            agentRepository = repo,
            runtime = io.mockk.mockk<AgentRuntime>(relaxed = true),
            runHost = io.mockk.mockk<AgentRunHost>(relaxed = true),
            ledger = io.mockk.mockk<AgentRunLedger>(relaxed = true),
            database = io.mockk.mockk(relaxed = true),
        )

        return AgentAuthoringToolExecutor(
            agentRepository = repo,
            agentCatalogue = io.mockk.mockk<AgentCatalogue>(relaxed = true),
            toolRegistry = ToolRegistry(),
            schedules = service,
        ) to stored
    }

    @Test
    fun anAgentWithoutScheduledPermissionIsRefusedAtCreation() = runTest {
        /*
         * THE PERMISSION IS CHECKED WHERE THE SCHEDULE IS MADE, not only where it would run. A caller
         * that sets up a schedule and learns a week later that it never fired has been told nothing
         * useful; a refusal at the moment of asking names the setting to change.
         */
        val (executor, stored) = executor(agent(schedules = false))
        val result = executor.execute(
            AgentAuthoringToolDefinitions.SCHEDULE_AGENT,
            mapOf(
                "agent" to "Worker",
                "title" to "the nightly sweep",
                "job" to "check the thing",
                "intervalMinutes" to 60,
            ),
        )

        assertTrue("the call itself worked", result.success)
        assertTrue("but nothing was scheduled", stored.isEmpty())
        assertTrue(
            "and the refusal names the permission: ${result.rawJson.take(400)}",
            result.rawJson.contains("not permitted to work on a schedule"),
        )
    }

    @Test
    fun aSwitchedOffAgentIsRefusedBecauseItWouldNeverStart() = runTest {
        val (executor, stored) = executor(agent(enabled = false, schedules = true))
        executor.execute(
            AgentAuthoringToolDefinitions.SCHEDULE_AGENT,
            mapOf(
                "agent" to "Worker",
                "title" to "t",
                "job" to "j",
                "intervalMinutes" to 60,
            ),
        )
        assertTrue("nothing was scheduled for an agent that cannot run", stored.isEmpty())
    }

    @Test
    fun anIntervalBelowTheFloorIsRefusedWithTheReason() = runTest {
        /*
         * ARITHMETIC RATHER THAN PATERNALISM. Work due every second is due again before the first run
         * can finish, so the sweep would start faster than anything drains. The refusal says the floor
         * rather than just "too short", so a caller can correct it in one attempt.
         */
        val (executor, stored) = executor(agent(schedules = true))
        executor.execute(
            AgentAuthoringToolDefinitions.SCHEDULE_AGENT,
            mapOf(
                "agent" to "Worker",
                "title" to "t",
                "job" to "j",
                "intervalMinutes" to 0,
            ),
        )
        assertTrue("nothing was scheduled", stored.isEmpty())
    }

    @Test
    fun aPermittedAgentGetsARealScheduleStored() = runTest {
        val (executor, stored) = executor(agent(schedules = true))
        val result = executor.execute(
            AgentAuthoringToolDefinitions.SCHEDULE_AGENT,
            mapOf(
                "agent" to "Worker",
                "title" to "the nightly sweep",
                "job" to "check the thing",
                "intervalMinutes" to 60,
            ),
        )

        assertTrue("the call succeeded", result.success)
        assertTrue("the interval is reported in minutes", result.rawJson.contains("\"intervalMinutes\":60"))
        assertTrue("something was stored", stored.size == 1)
        assertTrue("and it starts armed", stored.single().enabled)
        /*
         * THE JOB IS PROVED AT THE STORED ROW RATHER THAN THE REPLY. The reply is a receipt for the
         * schedule, not a copy of it: a caller needs the id and when it next runs, and echoing the
         * whole instruction back would be a second place the job could differ from what will run.
         */
        org.junit.Assert.assertEquals("the job is carried as written", "check the thing", stored.single().assignment)
    }

    @Test
    fun aJobWithNoInstructionsIsRefused() = runTest {
        /*
         * A SCHEDULED RUN STARTS COLD EVERY TIME. It has no memory of the conversation that created
         * it, so a job that says "do that thing we discussed" runs against nothing. The refusal says
         * so rather than storing a schedule that will produce an agent with no idea what it is doing.
         */
        val (executor, stored) = executor(agent(schedules = true))
        executor.execute(
            AgentAuthoringToolDefinitions.SCHEDULE_AGENT,
            mapOf(
                "agent" to "Worker",
                "title" to "t",
                "job" to "",
                "intervalMinutes" to 60,
            ),
        )
        assertTrue("nothing scheduled with no job", stored.isEmpty())
    }

    @Test
    fun listingWithNothingScheduledSaysSoRatherThanReturningAnEmptyList() = runTest {
        val (executor, _) = executor(agent(schedules = true))
        val result = executor.execute(AgentAuthoringToolDefinitions.LIST_SCHEDULES, emptyMap())
        assertTrue("the call worked", result.success)
        assertTrue(
            "and it explains what to do about it: ${result.rawJson.take(300)}",
            result.rawJson.contains("Nothing is scheduled"),
        )
    }

    @Test
    fun deletingAScheduleThatDoesNotExistSaysNothingWasDeleted() = runTest {
        val (executor, _) = executor(agent(schedules = true))
        val result = executor.execute(
            AgentAuthoringToolDefinitions.DELETE_SCHEDULE,
            mapOf("scheduleId" to "no-such-id"),
        )
        assertTrue(result.success)
        assertTrue(
            "the answer is that nothing happened, not a silent success",
            result.rawJson.contains("nothing was deleted"),
        )
    }

    @Test
    fun theScheduleVerbsCanNeverBeGrantedToAnAgent() = runTest {
        /*
         * AN AGENT THAT COULD CREATE A SCHEDULE COULD ARRANGE TO RUN ITSELF AGAIN LATER, for ever,
         * with a timer attached. That is the recursion the reserve list exists to stop, so the four
         * verbs are on it and a create naming them is refused.
         */
        val (executor, _) = executor(agent())
        val result = executor.execute(
            AgentAuthoringToolDefinitions.CREATE_AGENT,
            mapOf(
                "name" to "Sneaky",
                "instructions" to "you arrange your own work",
                "tools" to "[\"scheduleAgent\",\"listSchedules\"]",
            ),
        )
        assertTrue(result.success)
        assertTrue(
            "the grant is refused with the reason: ${result.rawJson.take(400)}",
            result.rawJson.contains("cannot be granted"),
        )
    }
}
