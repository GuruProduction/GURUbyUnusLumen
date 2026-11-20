// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where agent work lives, and where conversation context lives.
 *
 * TWO DEFECTS THIS FILE EXISTS FOR. Both were real, both were mine, and both had the same
 * shape: state that looked per-call but was shared.
 *
 * ONE. Execution ran inside whatever coroutine called spawn. A cancelled tool call or a
 * destroyed screen killed an agent that the foreground service was still describing as
 * running and still renewing a lease for. The person was told work was happening, the
 * recovery scan was told the owner was alive, and neither was true.
 *
 * TWO. The bound conversation was a `@Volatile` field on the runtime. Two conversations
 * dispatching at once would each write it, and the last writer decided which conversation
 * the other's run belonged to. Nothing errored. An agent's history simply appeared under a
 * chat nobody started it from.
 */
class AgentExecutionOwnershipTest {

    /**
     * Work started on the host outlives the coroutine that started it.
     *
     * THE CENTRAL GUARANTEE. The starter is cancelled the instant the work begins; the work
     * must still finish, because on a device the starter is a UI or a tool call and the
     * work is an agent the person was told was running.
     */
    @Test(timeout = 10_000)
    fun workOutlivesTheCoroutineThatStartedIt() = runBlocking {
        val host = AgentRunHost()
        val started = CountDownLatch(1)
        val finished = AtomicInteger(0)
        val deferred = CompletableDeferred<String>()

        // A child scope that we cancel almost immediately, standing in for a caller.
        val callerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job())
        callerScope.launch {
            host.start("run-1", deferred) {
                started.countDown()
                delay(300)
                finished.incrementAndGet()
                "finished"
            }
        }


        assertTrue("the work must have begun", started.await(2, TimeUnit.SECONDS))
        callerScope.cancel()

        // The caller is gone. The work must not be.
        assertEquals("the work must run to completion", "finished", deferred.await())
        assertEquals(1, finished.get())
    }

    /** A cancelled waiter stops waiting; the work carries on and still finishes. */
    @Test(timeout = 10_000)
    fun cancellingTheWaiterDoesNotCancelTheWork() = runBlocking {
        val host = AgentRunHost()
        val finished = AtomicInteger(0)
        val deferred = CompletableDeferred<String>()

        host.start("run-2", deferred) {
            delay(250)
            finished.incrementAndGet()
            "done"
        }

        // A waiter that gives up after 50ms, long before the work finishes.
        val waiter = async { deferred.await() }
        delay(50)
        waiter.cancel()

        delay(600)
        assertEquals("the work must still have completed", 1, finished.get())
        assertTrue("the deferred must still be completed", deferred.isCompleted)
    }

    /** The host knows whether work is genuinely active, which is what the heartbeat asks. */
    @Test(timeout = 10_000)
    fun theHostReportsWhetherWorkIsActive() = runBlocking {
        val host = AgentRunHost()
        val deferred = CompletableDeferred<String>()

        assertFalse("nothing is active before a run starts", host.isActive("run-3"))
        host.start("run-3", deferred) {
            delay(200)
            "ok"
        }
        assertTrue("work must report active while running", host.isActive("run-3"))

        deferred.await()
        // A brief settle: the job completes and removes itself.
        delay(100)
        assertFalse("finished work must not report active", host.isActive("run-3"))
        assertTrue("no active runs remain", host.activeRuns().isEmpty())
    }

    /** A cancelled run is reported inactive, so a heartbeat stops rather than lying. */
    @Test(timeout = 10_000)
    fun cancellingARunStopsItReportingActive() = runBlocking {
        val host = AgentRunHost()
        val deferred = CompletableDeferred<String>()
        host.start("run-4", deferred) {
            delay(5_000)
            "never"
        }
        assertTrue(host.isActive("run-4"))
        host.cancel("run-4")
        delay(100)
        assertFalse("a cancelled run must not report active", host.isActive("run-4"))
    }

    /**
     * Two concurrent dispatches keep their own conversations apart.
     *
     * THIS IS THE RACE, PROVED ABSENT. Each coroutine installs its own scope; neither can
     * observe the other's. With the old shared field one of these would read the other's
     * conversation, and the test would report a mismatch rather than a crash, which is
     * exactly how the defect would have shipped.
     */
    @Test(timeout = 10_000)
    fun concurrentDispatchesEachSeeTheirOwnConversation() = runBlocking {
        val results = mutableMapOf<String, String?>()

        coroutineScope {
            val first = async {
                withDispatchScope("conversation-A") {
                    delay(50)
                    currentDispatchScope()?.conversationId()
                }
            }
            val second = async {
                withDispatchScope("conversation-B") {
                    delay(50)
                    currentDispatchScope()?.conversationId()
                }
            }
            results["A"] = first.await()
            results["B"] = second.await()
        }

        assertEquals("conversation-A", results["A"])
        assertEquals("conversation-B", results["B"])
    }

    /** With nothing installed, the scope reads as absent rather than inheriting a stale one. */
    @Test(timeout = 10_000)
    fun noScopeInstalledMeansNoConversationRatherThanAStaleOne() = runBlocking {
        // Set one, then leave that coroutine.
        withDispatchScope("conversation-C") {
            assertEquals("conversation-C", currentDispatchScope()?.conversationId())
        }
        // A fresh coroutine must not inherit it.
        assertNull(
            "a sibling coroutine must not see another's conversation",
            currentDispatchScope()?.conversationId(),
        )
    }

    /** A null conversation installs explicitly, so the refusal names the cause rather than a leftover. */
    @Test(timeout = 10_000)
    fun aDeliberatelyNullConversationIsNotNull() = runBlocking {
        val seen = withDispatchScope(null) {
            currentDispatchScope()
        }
        assertTrue("a null id must still install a scope so it is not confused with absent", seen != null)
        assertNull(seen?.conversationId())
    }

    // ------------------------------------------------------------------
    // One run id is one execution
    // ------------------------------------------------------------------

    /**
     * A second start for a live run id does not execute the block.
     *
     * THE DEFECT THIS FILE EXISTS FOR NOW. The host used to log a warning and launch anyway,
     * so two jobs under one id each ran the same assignment and repeated every side effect
     * behind it. Either one's cleanup could also clear the shared entry, making the host
     * report a run inactive while it was still working.
     */
    @Test(timeout = 10_000)
    fun aSecondStartForALiveRunIdIsRefusedAndRunsNothing() = runBlocking {
        val host = AgentRunHost()
        val firstRan = AtomicInteger(0)
        val secondRan = AtomicInteger(0)
        val first = CompletableDeferred<String>()
        val second = CompletableDeferred<String>()

        val firstAdmission = host.start("run-dup", first) {
            firstRan.incrementAndGet()
            delay(400)
            "first"
        }
        val secondAdmission = host.start("run-dup", second) {
            secondRan.incrementAndGet()
            "second"
        }

        assertTrue("the first start wins the id", firstAdmission is AgentRunHost.Admission.Started)
        assertTrue(
            "the second start must be refused rather than launched",
            secondAdmission is AgentRunHost.Admission.AlreadyRunning,
        )

        assertEquals("the first block ran exactly once", 1, firstRan.get())
        assertEquals("the refused block must never run", 0, secondRan.get())
        assertEquals("the winner still completes", "first", first.await())
    }

    /** A refused start completes its deferred exceptionally rather than leaving a caller hanging. */
    @Test(timeout = 10_000)
    fun aRefusedStartDoesNotLeaveItsCallerWaitingForever() = runBlocking {
        val host = AgentRunHost()
        val first = CompletableDeferred<String>()
        val refused = CompletableDeferred<String>()

        host.start("run-wait", first) {
            delay(300)
            "held"
        }
        host.start("run-wait", refused) { "never" }

        assertTrue("a refusal must not leave a deferred pending", refused.isCancelled)
        // The winner still completes, so a refusal does not strand the run that does own it.
        assertEquals("held", first.await())
    }

    /**
     * Duplicate resume attempts do not both carry an answer into one run.
     *
     * THE CASE FROM THE REVIEW. Two answers to one question, or a retried tool call, arrive
     * under one session id. Only the first may reach the runtime, because a resume executes
     * real work and running it twice repeats every side effect behind it.
     */
    @Test(timeout = 10_000)
    fun twoResumesForOneRunAdmitOnlyTheFirst() = runBlocking {
        val host = AgentRunHost()
        val executed = mutableListOf<String>()
        val first = CompletableDeferred<String>()
        val second = CompletableDeferred<String>()

        host.start("session-1", first) {
            executed += "answer-one"
            delay(400)
            "done"
        }
        host.start("session-1", second) {
            executed += "answer-two"
            "done"
        }

        first.await()
        assertEquals(
            "only the first answer may reach the run: $executed",
            listOf("answer-one"),
            executed,
        )
    }

    /**
     * One job finishing does not deregister another that is still working.
     *
     * THE CLEANUP RACE. Two-argument `remove` compares the value as well as the key, so a
     * job can only clear its own entry. With a one-argument remove, the older job's finally
     * block would clear a newer execution's entry and the heartbeat would stop renewing a
     * lease for work that was still going.
     */
    @Test(timeout = 10_000)
    fun aFinishingJobDoesNotClearAnotherRunsEntry() = runBlocking {
        val host = AgentRunHost()
        val short = CompletableDeferred<String>()
        val long = CompletableDeferred<String>()

        host.start("run-short", short) { "quick" }
        host.start("run-long", long) {
            delay(500)
            "slow"
        }

        short.await()
        delay(100)

        assertFalse("the finished run is gone", host.isActive("run-short"))
        assertTrue("the other run must still be registered", host.isActive("run-long"))
        assertTrue("run-long" in host.activeRuns())
        assertEquals("slow", long.await())
    }

    /** A run id becomes available again once its execution has genuinely finished. */
    @Test(timeout = 10_000)
    fun aRunIdCanBeReusedAfterItsExecutionFinishes() = runBlocking {
        val host = AgentRunHost()
        val first = CompletableDeferred<String>()
        host.start("run-reuse", first) { "one" }
        first.await()
        delay(100)

        val second = CompletableDeferred<String>()
        val admission = host.start("run-reuse", second) { "two" }

        assertTrue(
            "a finished run must release its id for the next execution",
            admission is AgentRunHost.Admission.Started,
        )
        assertEquals("two", second.await())
    }
}
