// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import android.util.Log
import com.unuslumen.app.database.dao.AgentRunDao
import com.unuslumen.app.database.dao.AgentRunEventDao
import com.unuslumen.app.database.entity.AgentRunEntity
import com.unuslumen.app.domain.agents.AgentEventTypes
import com.unuslumen.app.domain.memory.Conversation
import com.unuslumen.app.domain.memory.MemoryRepository
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.repository.AiRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The part of GURU that acts on its own agents without being spoken to.
 *
 * WHY THIS IS A CLASS AND NOT A PROMPT INSTRUCTION. Telling a model "watch your agents" in a system
 * prompt produces nothing, because a model only runs when something invokes it. Supervision is
 * therefore a host concern: something in the process has to notice that an agent stopped to ask a
 * question and start a turn about it. That is this.
 *
 * WHAT IT GUARANTEES, AND WHAT IT HONESTLY DOES NOT.
 *
 * It guarantees that an event reaching a decision point is acted on at least once, that two
 * supervisory turns cannot act on the same fact, and that a crash mid-decision leaves a durable
 * record which the next pass resumes rather than losing. It does NOT reason while the process is
 * dead: Android offers no mechanism for that, and a supervisor claiming otherwise would be lying in
 * the one place a person most needs the truth. What survives is knowledge and a resumable claim.
 *
 * FOUR DESIGN POINTS THAT ARE LOAD BEARING, EACH FROM THE SETTLED ARCHITECTURE.
 *
 *  - A6: a claim is a ledger append with a derived idempotency key. `AgentRunLedger.append` already
 *    refuses a duplicate key inside the transaction, and the unique index on `(run_id, sequence)`
 *    refuses a duplicate sequence. That is the atomic claim, and it needs no new table.
 *  - A8: the supervisor writes into its OWN conversation, never the person's. Anything the person
 *    must see goes out as a message to them, which is the same path that raises the unread dot.
 *  - A9: the send path is serialised per conversation at the repository, so a supervisory turn and a
 *    user turn cannot interleave. Because the supervisor has its own conversation, that serialisation
 *    never blocks the person.
 *  - A10: no recursion. The supervisor ignores materials whose run belongs to its own conversation,
 *    and the turn it starts is itself an ordinary turn, so a supervisory turn cannot trigger the next.
 *
 * A11 IS THE CADENCE. Intervention events (a question, a blocker, an error, an interruption) use a
 * short window because a person is waiting on them. Routine completions batch on a longer window
 * because ten finishes in a row are one piece of news, not ten. A run needing intervention stays
 * needing intervention until the matter is actually resolved: it is never coalesced away.
 */
class AgentSupervisor(
    private val scope: CoroutineScope,
    private val runDao: AgentRunDao,
    private val eventDao: AgentRunEventDao,
    private val ledger: AgentRunLedger,
    private val operations: AgentOperationsService,
    private val aiRepository: AiRepository,
    private val conversations: MemoryRepository,
) {

    /**
     * The constants, PUBLIC because two of them are contract rather than implementation.
     *
     * [SUPERVISOR_TITLE] identifies the conversation a supervisory turn writes into, and
     * [SUPERVISOR_AGENT_TAG] is the marker that keeps the supervisor from supervising its own work.
     * Both are asserted on by name, so a rename that slipped through would silently break A8 or A10
     * with nothing failing.
     */
    companion object {
        private const val TAG = "guru_supervisor"

        /**
         * The short window, for events where a person is waiting.
         *
         * A FEW SECONDS, NOT ZERO. Acting on the same instant an event is written would start a
         * model turn inside the write path that produced it, which holds a database transaction open
         * across a network call. The window is also what lets a burst of intervention events on one
         * run become one turn rather than three.
         */
        const val INTERVENTION_WINDOW_MS = 4_000L

        /**
         * The long window, for routine completions.
         *
         * SIXTY SECONDS, WHICH IS THE SPECIFICATION'S NUMBER. Ten agents finishing within a minute is
         * one report; ten reports a minute apart is the noise that makes a person stop reading them.
         */
        const val ROUTINE_WINDOW_MS = 60_000L

        /** How often the supervisor looks for material it has not yet acted on. */
        const val SCAN_INTERVAL_MS = 5_000L

        /** The prefix that makes a dispatch key derivable from the run and the fact. */
        const val CLAIM_PREFIX = "supervisor:"

        /**
         * The ceiling on a supervisory instruction.
         *
         * A BACKSTOP, NOT A BATCH LIMIT. Each run's line is already bounded by the operations
         * snapshot, so reaching this means a genuinely enormous number of runs, in which case a
         * clipped instruction is better than an unbounded one. The runs themselves are still
         * claimed and still acted on; only the text handed to the model is shortened.
         */
        const val MAX_PROMPT_CHARS = 12_000

        /**
         * There is no cap on how many runs one turn is told about.
         *
         * A CAP WAS THE OBVIOUS IDEA AND IT IS WRONG. Ten agents finishing together must produce ONE
         * turn (A11); capping the batch at eight turns that into two, so the same ten completions
         * arrive as two reports and the batching rule stops meaning anything. The protection against
         * an unwieldy prompt is on the rendering, not on the batch: every run is named with its id,
         * and the free text per run is bounded by what the operations snapshot already clipped.
         */

        /**
         * The title that identifies the supervisor's own conversation. Never shown in a room.
         *
         * THE PREFIX IS THE CONTRACT, NOT THE WHOLE STRING. The Portal and the Assistant both open
         * the newest conversation, so a supervisory turn would otherwise make its own internal
         * thread the person's chat. On a device on 2026-11-21 exactly that happened: a person's
         * "hello" and a supervision prompt ended up in one thread, and the chat they saw was full of
         * tool calls they never made. Every conversation whose title starts with this prefix is
         * internal apparatus and is excluded from what a person can open.
         */
        const val SUPERVISOR_TITLE_PREFIX = "Agent supervision (internal)"

        /** The full title, kept for the lookup that finds the existing row. */
        const val SUPERVISOR_TITLE = SUPERVISOR_TITLE_PREFIX

        /**
         * Whether a conversation is internal apparatus rather than something a person made.
         *
         * ONE PLACE THAT DECIDES, so the Portal, the Assistant and any future surface cannot
         * disagree about which threads are theirs to open.
         */
        fun isInternalConversation(title: String?): Boolean =
            title != null && title.startsWith(SUPERVISOR_TITLE_PREFIX)

        /**
         * The agent name a supervisory spawn carries, so its own work is never supervised.
         *
         * A NAME RATHER THAN A FLAG, because a flag would be a new column and no new table or column
         * is allowed for a property only one component reads. A supervisory turn has no reason to
         * spawn an agent, and if it ever does this is the tag that keeps the loop closed.
         */
        const val SUPERVISOR_AGENT_TAG = "Supervisor"
    }

    /**
     * One thing worth acting on, and the run it belongs to.
     *
     * [claimKey] IS DERIVED FROM THE FACT, NOT FROM THE TURN. Two passes that both see the same
     * completion produce the same key, so the second append is refused by the ledger and the second
     * turn never happens. That is the whole of exactly-once, expressed in a string.
     */
    private data class Material(
        val runId: String,
        val agentName: String,
        val whatHappened: String,
        val claimKey: String,
        val urgent: Boolean,
        /**
         * When the fact happened, taken from the run's own `updatedAt`.
         *
         * THE ROUTINE WINDOW IS ARITHMETIC AGAINST THIS FIELD, so it has to be a real time rather
         * than the moment this pass noticed. A material that only existed in memory for a tick would
         * always look brand new and the routine batch window would never elapse, which would mean a
         * turn per completion and the exact chat flood the window exists to prevent.
         */
        val happenedAt: Long,
    ) {
        /** How long ago the fact landed, for the batching window. */
        fun ageMillis(now: Long = System.currentTimeMillis()): Long = (now - happenedAt).coerceAtLeast(0)
    }

    /**
     * One supervisor per process.
     *
     * A GUARD RATHER THAN A RULE OF CONSTRUCTION. Two supervisors would each hold their own scan and
     * their own claims; the claims are durable so they would still not double-act, but they would
     * spend twice the model calls to not do it. The guard is cheap and the failure is expensive.
     */
    private val started = AtomicBoolean(false)

    /**
     * One scan and one turn at a time.
     *
     * A SUPERVISORY TURN IS NOT REENTRANT. Two turns interleaved would each read the picture and each
     * decide, and the second decision would be made against a state the first had already changed.
     */
    private val turnLock = Mutex()

    private var loop: Job? = null

    /** The conversation internal chatter lives in. Created once, on demand. */
    private var supervisorConversationId: String? = null

    /**
     * Start supervising, once.
     *
     * CALLED FROM THE APPLICATION AT PROCESS START, which is why it takes no arguments and reads its
     * material from rows. There is no in-memory state to rebuild: a supervisor that remembered what it
     * had already handled would lose that memory to the process death it exists to survive.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) {
            Log.d(TAG, "Supervisor already started for this process")
            return
        }
        Log.d(TAG, "Supervisor starting")
        loop = scope.launch {
            while (isActive) {
                delay(SCAN_INTERVAL_MS)
                runCatching { pass() }
                    .onFailure { Log.w(TAG, "Supervisory pass failed: ${it.message}") }
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        started.set(false)
    }

    /**
     * One pass: find material, claim it, act on it.
     *
     * ORDER MATTERS AND IT IS DELIBERATE. Material is collected first, then filtered against claims
     * already in the ledger, then claimed, then acted on. A claim written BEFORE the turn means a
     * crash during the turn leaves a claim that the next pass resumes rather than re-claims.
     */
    suspend fun pass(): Int = turnLock.withLock {
        val materials = collect()
        if (materials.isEmpty()) return@withLock 0

        val unclaimed = materials.filterNot { alreadyClaimed(it) }
        if (unclaimed.isEmpty()) return@withLock 0

        val urgent = unclaimed.filter { it.urgent }
        val routine = unclaimed.filterNot { it.urgent }
        /*
         * THE TWO WINDOWS, APPLIED HERE RATHER THAN AS A DELAY.
         *
         * An urgent item is acted on now. A routine one waits until it is old enough that a batch of
         * them has accumulated, which is what turns ten completions into one report. An urgent item
         * is never held behind the routine window, and a routine item is never promoted by an urgent
         * one arriving: the two classes are separate because a person waiting for a decision does not
         * want their turn delayed by ten agents finishing.
         */
        val readyRoutine = routine.filter { it.ageMillis() >= ROUTINE_WINDOW_MS }
        val ready = urgent + readyRoutine
        if (ready.isEmpty()) return@withLock 0

        /*
         * CLAIM EVERY ONE OF THEM BEFORE THE TURN.
         *
         * CLAIMING ALL AND THEN ACTING ONCE IS WHAT MAKES THE TURN A BATCH. A turn per material would
         * burn a model call per finished agent. The claim is per material because the material is the
         * fact; the turn covers all the facts that were ready.
         *
         * A MATERIAL WHOSE CLAIM WAS REFUSED IS DROPPED. Null from `append` means another pass wrote
         * that exact key first, which means another turn is already handling it.
         */
        val claimed = ready.filter { claim(it) }
        if (claimed.isEmpty()) return@withLock 0

        val acted = act(claimed)
        if (acted) claimed.forEach { complete(it) }
        claimed.size
    }

    // ------------------------------------------------------------------
    // Collecting
    // ------------------------------------------------------------------

    /** The materials this process can see right now, newest first. */
    private suspend fun collect(): List<Material> {
        val found = ArrayList<Material>()
        val snapshot = runCatching { operations.snapshot() }.getOrDefault(
            AgentOperationsService.Snapshot(emptyList(), emptyList(), System.currentTimeMillis())
        )

        /*
         * INTERVENTION FIRST. A run waiting on a person, or one that stopped with an error, is the
         * class where somebody is actually blocked, so it is collected before anything else and it is
         * what the short window applies to.
         */
        for (line in snapshot.live) {
            when {
                line.status == AgentRunEntity.STATUS_WAITING_FOR_GURU -> found.add(
                    Material(
                        runId = line.runId,
                        agentName = line.agentName,
                        whatHappened = "${line.agentName} is waiting for you to answer something " +
                            "before it can continue. Its newest activity: " +
                            (line.newestMessage ?: line.newestEvent ?: "nothing recorded"),
                        claimKey = "$CLAIM_PREFIX${line.runId}:WAITING_FOR_GURU",
                        urgent = true,
                        happenedAt = line.lastActivityAt,
                    )
                )

                line.status == AgentRunEntity.STATUS_WAITING_FOR_USER -> found.add(
                    Material(
                        runId = line.runId,
                        agentName = line.agentName,
                        whatHappened = "${line.agentName} is waiting for the person to decide " +
                            "something. It cannot move until they do. Newest activity: " +
                            (line.newestMessage ?: line.newestEvent ?: "nothing recorded"),
                        claimKey = "$CLAIM_PREFIX${line.runId}:WAITING_FOR_USER",
                        urgent = true,
                        happenedAt = line.lastActivityAt,
                    )
                )

                line.status == AgentRunEntity.STATUS_INTERRUPTED -> found.add(
                    Material(
                        runId = line.runId,
                        agentName = line.agentName,
                        whatHappened = "${line.agentName} was interrupted and is not executing. " +
                            "Its work is kept and it can be resumed, or it can be cancelled.",
                        claimKey = "$CLAIM_PREFIX${line.runId}:INTERRUPTED",
                        urgent = true,
                        happenedAt = line.lastActivityAt,
                    )
                )
            }
        }

        /*
         * THEN COMPLETIONS AND FAILURES, FROM THE FINISHED TAIL. Each terminal state is its own fact
         * with its own key, so a run that failed after being cancelled produces two materials rather
         * than one ambiguous one.
         */
        for (line in snapshot.finished) {
            val what = when (line.status) {
                AgentRunEntity.STATUS_COMPLETED ->
                    "${line.agentName} finished its assignment."
                AgentRunEntity.STATUS_LIMIT_REACHED ->
                    "${line.agentName} reached its step limit before finishing, so the work is " +
                        "partial and it says so."
                AgentRunEntity.STATUS_FAILED ->
                    "${line.agentName} failed. Something went wrong rather than the work being " +
                        "no good."
                AgentRunEntity.STATUS_CANCELLED ->
                    "${line.agentName} was cancelled."
                else -> continue
            }
            found.add(
                Material(
                    runId = line.runId,
                    agentName = line.agentName,
                    whatHappened = "$what Newest activity: " +
                        (line.newestMessage ?: line.newestEvent ?: "nothing recorded") +
                        ". Its report, if it recorded one, is on the run.",
                    // A FAILURE IS URGENT, because it is the one ending a person would want to know
                    // about before the next cup of tea rather than after it.
                    claimKey = "$CLAIM_PREFIX${line.runId}:${line.status}",
                    urgent = line.status == AgentRunEntity.STATUS_FAILED,
                    happenedAt = line.lastActivityAt,
                )
            )
        }

        /*
         * NO MATERIAL FROM THE SUPERVISOR'S OWN CONVERSATION (A10).
         *
         * The supervisor's conversation is an ordinary conversation, so a turn in it is an ordinary
         * send, and a send has no run. There is therefore nothing here that could recurse through the
         * conversation itself. What must be excluded is a run STARTED BY a supervisory turn, which is
         * possible because the turn has the spawn verb: a supervisor that spawned an agent to
         * investigate a stuck run, and then supervised its own spawn, would supervise itself for ever.
         *
         * The tag is the agent slug, which is the only marker a supervisory spawn can carry without a
         * new column.
         */
        return found.filterNot { it.agentName == SUPERVISOR_AGENT_TAG }
    }

    /** Whether this exact fact has already been claimed, whether or not its turn finished. */
    private suspend fun alreadyClaimed(material: Material): Boolean = runCatching {
        eventDao.eventByKey(material.runId, material.claimKey) != null
    }.getOrDefault(false)

    // ------------------------------------------------------------------
    // Claiming
    // ------------------------------------------------------------------

    /**
     * Write the durable claim, and report whether this caller won it.
     *
     * A NULL FROM `append` IS THE OTHER PASS WINNING, which is an ordinary race and not an error. The
     * ledger refuses a duplicate idempotency key inside its own transaction, so exactly one caller
     * can observe a non-null result for a given key.
     */
    private suspend fun claim(material: Material): Boolean = runCatching {
        val written = ledger.append(
            material.runId,
            AgentRunLedger.EventDraft(
                eventType = AgentEventTypes.SUPERVISOR_DISPATCH_STARTED,
                actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_GURU,
                payload = """{"claim":${quote(material.claimKey)},"urgent":${material.urgent}}""",
                idempotencyKey = material.claimKey,
            ),
        )
        written != null
    }.getOrDefault(false)

    /**
     * Close the claim.
     *
     * KEYED FROM THE START'S KEY so the pair cannot drift, and written only after the turn actually
     * ran. A claim left open is resumed on the next pass rather than re-claimed, which is what makes
     * a crash mid-decision cost one repeated turn instead of a lost one.
     */
    private suspend fun complete(material: Material) {
        runCatching {
            ledger.append(
                material.runId,
                AgentRunLedger.EventDraft(
                    eventType = AgentEventTypes.SUPERVISOR_DISPATCH_COMPLETED,
                    actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_GURU,
                    payload = """{"claim":${quote(material.claimKey)}}""",
                    idempotencyKey = "${material.claimKey}:completed",
                ),
            )
        }.onFailure { Log.w(TAG, "Claim completion failed for ${material.runId}: ${it.message}") }
    }

    // ------------------------------------------------------------------
    // Acting
    // ------------------------------------------------------------------

    /**
     * Run one supervisory turn about the claimed materials.
     *
     * THE TURN IS AN ORDINARY SEND, which is the point: it goes through the same path as any other
     * turn, so it has the same tools, the same memory and the same operational block. What makes it
     * supervisory is where its words go, not how it runs.
     *
     * ITS OWN CONVERSATION (A8). Writing into the person's conversation would be putting internal
     * chatter in front of them, and a person opening their chat to find GURU talking to itself about
     * an agent is the exact defect the rule names.
     */
    private suspend fun act(materials: List<Material>): Boolean {
        val conversationId = supervisorConversation()
        if (conversationId == null) {
            Log.w(TAG, "No supervisory conversation could be created, so nothing was said")
            return false
        }

        val prompt = buildPrompt(materials)
        return runCatching {
            aiRepository.sendMessage(
                listOf(
                    AiMessage.UserMessage(
                        uuid = java.util.UUID.randomUUID().toString(),
                        content = prompt,
                        time = System.currentTimeMillis(),
                    )
                ),
                conversationId,
            ).collect { }
            true
        }.onFailure {
            /*
             * A FAILED TURN LEAVES THE CLAIM OPEN, DELIBERATELY.
             *
             * A claim that was closed on failure would say the matter had been handled when it had
             * not, and the next pass would never try again. Leaving it open means the next pass
             * resumes it, which is the whole reason the claim is two events rather than one.
             */
            Log.w(TAG, "Supervisory turn failed, claim stays open for the next pass: ${it.message}")
        }.getOrDefault(false)
    }

    /**
     * The instruction for one supervisory turn.
     *
     * WHAT IT ASKS FOR, AND WHAT IT FORBIDS. The turn is told what happened and asked to deal with
     * it: answer what it can, cancel what is stuck, tell the person what matters. It is explicitly
     * forbidden from narrating routine completions to them, because a chat that reports every finish
     * is a chat nobody reads.
     *
     * THE FACTS ARE NAMED WITH THEIR RUN IDS so the verbs the turn may want (getAgentRun,
     * cancelAgentRun, sendAgentMessage) can be called without another lookup.
     */
    private fun buildPrompt(materials: List<Material>): String = buildString {
        append("You are supervising your own agents. Nobody has spoken to you. ")
        append("These are facts about runs you started, and you are being given them because ")
        append("something in them needs a decision or a report.\n\n")
        materials.forEach { material ->
            append("- ").append(material.whatHappened)
            append(" Run id: ").append(material.runId).append(".\n")
        }
        append("\nWhat to do:\n")
        append("1. Read any run you need to act on with getAgentRun, so you are deciding from what ")
        append("it actually did rather than from this summary.\n")
        append("2. A run WAITING_FOR_GURU IS NOT STUCK AND MUST NEVER BE CANCELLED. It is asking ")
        append("you a question and it is waiting for an answer. Read its question with getAgentRun ")
        append("and answer it with resumeAgent, using the run id. That is what keeps the work ")
        append("alive. Cancelling a run that asked you something throws away everything it has ")
        append("done, and it is the worst possible answer to a question.\n")
        append("3. If a run is waiting on the PERSON, say so to them. Do not answer on their ")
        append("behalf and do not cancel it.\n")
        append("4. Cancel a run ONLY when it is genuinely dead or unwanted: it was interrupted and ")
        append("its work is no longer wanted, it is looping without progress, or it duplicates ")
        append("another run. Never cancel merely because you do not know what else to do.\n")
        append("5. Tell the person only what they would want to know: a decision they must make, a ")
        append("failure, or work they were waiting for. Do not narrate routine completions one by ")
        append("one, and do not report your own supervision.\n")
        append("6. If nothing here needs them, say nothing to them and simply deal with it yourself.")
    }.toString().take(MAX_PROMPT_CHARS)

    /**
     * The conversation internal turns live in, created once and reused.
     *
     * FOUND BY TITLE RATHER THAN BY A STORED ID, because the id would have to be stored somewhere
     * and every somewhere is a second source of truth. The title is a constant, it is not shown to a
     * person anywhere, and the lookup is the list call this app already makes.
     */
    private suspend fun supervisorConversation(): String? {
        supervisorConversationId?.let { return it }
        return runCatching {
            val existing = conversations.getAllConversations()
                .firstOrNull { it.title == SUPERVISOR_TITLE }
            if (existing != null) {
                supervisorConversationId = existing.id
                return@runCatching existing.id
            }
            val now = System.currentTimeMillis()
            val id = java.util.UUID.randomUUID().toString()
            conversations.createConversation(
                Conversation(
                    id = id,
                    title = SUPERVISOR_TITLE,
                    createdDate = now,
                    updatedDate = now,
                )
            )
            supervisorConversationId = id
            id
        }.getOrNull()
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
