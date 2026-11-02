// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.innermind

import com.unuslumen.app.database.dao.ConversationDao
import com.unuslumen.app.database.dao.FabricDao
import com.unuslumen.app.database.dao.GuruAgentDao
import com.unuslumen.app.database.dao.GuruNoteToSelfDao
import com.unuslumen.app.database.dao.GuruThoughtCycleDao
import com.unuslumen.app.database.dao.LuxifyDao
import com.unuslumen.app.database.dao.MemoryCrossReferenceDao
import com.unuslumen.app.database.dao.MemoryEdgeDao
import com.unuslumen.app.database.dao.NoteDao
import com.unuslumen.app.database.dao.ProjectDao
import com.unuslumen.app.database.dao.TaskDao
import com.unuslumen.app.database.dao.UserProfileDao
import com.unuslumen.app.database.entity.ConversationEntity
import com.unuslumen.app.database.entity.FabricDeviceEntity
import com.unuslumen.app.database.entity.GuruAgentEntity
import com.unuslumen.app.database.entity.GuruNoteToSelfEntity
import com.unuslumen.app.database.entity.GuruThoughtCycleEntity
import com.unuslumen.app.database.entity.LuxifyEntity
import com.unuslumen.app.database.entity.MemoryCrossReferenceEntity
import com.unuslumen.app.database.entity.MemoryEdgeEntity
import com.unuslumen.app.database.entity.NoteEntity
import com.unuslumen.app.database.entity.ProjectEntity
import com.unuslumen.app.database.entity.TaskEntity
import com.unuslumen.app.database.entity.UserProfileEntity
import com.unuslumen.app.domain.memory.MemoryFact
import com.unuslumen.app.domain.memory.MemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.log10

/**
 * What the map could not read, named rather than hidden.
 *
 * An empty family and a family whose store refused to answer are different
 * truths about the machine. The first is a mind that has not learned something
 * yet; the second is a mind that could not be asked. Collapsing them into one
 * empty circle is the defect this app keeps having to remove, so the graph
 * carries the refusal to the screen and the room says so out loud.
 */
data class MindReadFailure(val family: MindFamily, val reason: String)

/** A read of the whole mind, and whether each part of it answered. */
data class MindRead(
    val graph: MindGraph,
    val failures: List<MindReadFailure>,
) {
    val complete: Boolean get() = failures.isEmpty()
}

/**
 * Builds the map from the stores that actually hold the mind.
 *
 * WHY THE DAOs AND NOT EIGHT REPOSITORIES. Every family already has a domain
 * repository, and each returns a shape built for its own screen: a note listing
 * truncates the body to 150 characters, a task returns an enum priority where
 * the map wants a number. Reading the rows here keeps the map honest about what
 * is stored, and stops this room acquiring eight presentation-shaped contracts
 * it would only immediately undo.
 *
 * THE BRAIN IS THE EXCEPTION. Memories do not live in Room at all: they are in
 * Cerebrum behind [MemoryRepository], so that one path is used as it is.
 *
 * HOW MANY REACH THE CANVAS. The relaxation is quadratic in node count, so
 * drawing every row of a large mind would cost more than a phone has. Each
 * family contributes at most [PER_FAMILY_CAP] rows, heaviest and newest first,
 * and the remainder is represented by a growth node carrying the true number.
 * The hub always prints the real total, so the fold is visible rather than a
 * quiet lie.
 */
class MindSource(
    private val memoryRepository: MemoryRepository,
    private val conversationDao: ConversationDao,
    private val taskDao: TaskDao,
    private val noteDao: NoteDao,
    private val projectDao: ProjectDao,
    private val agentDao: GuruAgentDao,
    private val skillDao: LuxifyDao,
    private val thoughtDao: GuruThoughtCycleDao,
    private val noteToSelfDao: GuruNoteToSelfDao,
    private val fabricDao: FabricDao,
    private val userProfileDao: UserProfileDao,
    private val edgeDao: MemoryEdgeDao,
    private val crossReferenceDao: MemoryCrossReferenceDao,
) {

    companion object {
        /** How many rows of one family reach the canvas. */
        const val PER_FAMILY_CAP = 40

        /** Prefixes that keep two stores' primary keys from ever colliding. */
        const val MEMORY_PREFIX = "memory:"
        const val SESSION_PREFIX = "session:"
        const val TASK_PREFIX = "task:"
        const val NOTE_PREFIX = "note:"
        const val PROJECT_PREFIX = "project:"
        const val AGENT_PREFIX = "agent:"
        const val SKILL_PREFIX = "skill:"
        const val THOUGHT_PREFIX = "thought:"
        const val NOTE_TO_SELF_PREFIX = "selftip:"
        const val DEVICE_PREFIX = "device:"
        const val PERSON_PREFIX = "person:"
        const val CORE_ID = "core"
        const val HUB_PREFIX = "hub:"
        const val GROW_PREFIX = "grow:"

        /** The id the profile table keeps its single row under. */
        const val PROFILE_ID = "user_profile"
    }

    /**
     * Read the whole mind.
     *
     * Each family is read in its own [runCatching] so one store failing costs
     * that family its nodes and nothing else: a broken task table must not empty
     * a mind that holds four hundred memories.
     */
    suspend fun read(): MindRead = withContext(Dispatchers.IO) {
        val failures = ArrayList<MindReadFailure>(4)
        val members = ArrayList<MindNode>(256)
        val folds = ArrayList<MindNode>(4)
        val crossLinks = ArrayList<MindEdge>(512)
        val totals = LinkedHashMap<MindFamily, Int>(MindFamily.entries.size)

        readFamily(MindFamily.MEMORY, failures, totals, members) { readMemories() }
        readFamily(MindFamily.SESSION, failures, totals, members) { readSessions() }
        readFamily(MindFamily.TASK, failures, totals, members) { readTasks() }
        readFamily(MindFamily.NOTE, failures, totals, members) { readNotes() }
        readFamily(MindFamily.PROJECT, failures, totals, members) { readProjects() }
        readFamily(MindFamily.AGENT, failures, totals, members) { readAgents() }
        readFamily(MindFamily.SKILL, failures, totals, members) { readSkills() }
        readFamily(MindFamily.THOUGHT, failures, totals, members) { readThoughts() }
        readFamily(MindFamily.PERSON, failures, totals, members) { readPeople() }
        readFamily(MindFamily.DEVICE, failures, totals, members) { readDevices() }

        // Memories only: the edge tables key on brain ids, so an id in them that
        // is not a memory id is a row the brain itself cannot resolve.
        val memoryIds = members
            .filter { it.family == MindFamily.MEMORY }
            .map { it.id }
            .toSet()

        if (memoryIds.isNotEmpty()) {
            readEdges(memoryIds, crossLinks, failures)
        }

        val graph = MindGraph(
            nodes = buildList {
                add(coreNode())
                addAll(hubNodes(totals))
                addAll(members)
            },
            edges = buildSpokes(totals, members) + crossLinks,
            totalByFamily = totals,
        )

        MindRead(graph = graph, failures = failures)
    }

    /** The centre every family hangs off. */
    private fun coreNode() = MindNode(
        id = CORE_ID,
        family = MindFamily.MEMORY,
        label = "GURU",
        body = "The centre of the map. Every memory, conversation, task and agent here " +
            "belongs to this mind and is reachable from it.",
        weight = 1f,
        updatedAt = 0L,
        kind = NodeKind.CORE,
    )

    private fun hubNodes(totals: Map<MindFamily, Int>): List<MindNode> =
        totals.map { (family, total) ->
            MindNode(
                id = HUB_PREFIX + family.name,
                family = family,
                label = family.label,
                body = "The ${family.label.lowercase()} family. $total stored " +
                    if (total == 1) "row." else "rows.",
                weight = 1f,
                updatedAt = 0L,
                kind = NodeKind.HUB,
                facts = listOf(total.toString()),
            )
        }

    private fun buildSpokes(totals: Map<MindFamily, Int>, drawn: List<MindNode>): List<MindEdge> {
        val spokes = ArrayList<MindEdge>(drawn.size + totals.size)
        totals.keys.forEach { family ->
            spokes.add(MindEdge(CORE_ID, HUB_PREFIX + family.name, EdgeKind.SPOKE, weight = 1f))
            drawn
                .filter { it.family == family && it.kind != NodeKind.HUB }
                .forEach { member ->
                    spokes.add(MindEdge(HUB_PREFIX + family.name, member.id, EdgeKind.SPOKE, weight = 1f))
                }
        }
        return spokes
    }

    /**
     * One family's rows, split into what the canvas draws and what a fold holds.
     *
     * The held rows are real and already loaded; splitting here rather than
     * discarding them is what lets a fold reveal more without touching a store,
     * and means the "+412" on a hub is a true count rather than a claim.
     */
    private class FamilyRead(
        val nodes: List<MindNode>,
        val total: Int,
        val heldBack: List<MindNode> = emptyList(),
    )

    /**
     * Read one family, recording either its rows or the reason it could not
     * answer.
     */
    private inline fun readFamily(
        family: MindFamily,
        failures: MutableList<MindReadFailure>,
        totals: MutableMap<MindFamily, Int>,
        into: MutableList<MindNode>,
        read: () -> FamilyRead,
    ) {
        runCatching { read() }.fold(
            onSuccess = { result ->
                totals[family] = result.total
                into.addAll(result.nodes)
                if (result.heldBack.isNotEmpty()) {
                    into.add(foldFor(family, result.heldBack))
                }
            },
            onFailure = { error ->
                failures.add(
                    MindReadFailure(
                        family = family,
                        reason = error.message ?: error::class.simpleName ?: "could not be read",
                    )
                )
                totals[family] = 0
            },
        )
    }

    /** The fold that holds a family's undrawn rows, ready to reveal them. */
    private fun foldFor(family: MindFamily, held: List<MindNode>): MindNode = MindNode(
        id = GROW_PREFIX + family.name,
        family = family,
        label = "+${held.size}",
        body = "${held.size} more ${family.label.lowercase()} are stored than this view draws. " +
            "Tap to bring a few into the map.",
        weight = 0f,
        updatedAt = 0L,
        kind = NodeKind.GROW,
        facts = listOf(held.size.toString()),
        pending = held,
    )

    // ---------------------------------------------------------------- memories

    private suspend fun readMemories(): FamilyRead {
        val facts: List<MemoryFact> = memoryRepository.getAllFacts()
        val ordered = facts.sortedWith(
            compareByDescending<MemoryFact> { it.confidence }.thenByDescending { it.extractedDate }
        )
        val mapped = ordered.map { fact ->
            MindNode(
                id = MEMORY_PREFIX + fact.id,
                family = MindFamily.MEMORY,
                label = firstLine(fact.fact, 90),
                body = fact.fact,
                weight = fact.confidence.coerceIn(0f, 1f),
                updatedAt = fact.extractedDate,
                facts = buildList {
                    add("CONFIDENCE ${formatTwoPlaces(fact.confidence)}")
                    add("LAYER ${fact.layer.uppercase()}")
                    add("RECALLED ${fact.accessCount}x")
                    if (fact.domain.isNotBlank()) add("DOMAIN ${fact.domain.uppercase()}")
                    if (fact.category.isNotBlank()) add("FILED ${fact.category.uppercase()}")
                },
            )
        }
        return FamilyRead(
            nodes = mapped.take(PER_FAMILY_CAP),
            total = facts.size,
            heldBack = mapped.drop(PER_FAMILY_CAP),
        )
    }

    // ------------------------------------------------------------ conversations

    private suspend fun readSessions(): FamilyRead {
        val rows: List<ConversationEntity> = conversationDao.getAllConversationsSnapshot()
        val mapped = rows
            .sortedByDescending { it.updatedDate }
            .map { row ->
                MindNode(
                    id = SESSION_PREFIX + row.id,
                    family = MindFamily.SESSION,
                    label = row.title.ifBlank { "Untitled conversation" },
                    body = "${row.title.ifBlank { "An untitled conversation" }}. " +
                        "${row.messageCount} ${if (row.messageCount == 1) "message" else "messages"}.",
                    weight = scaleCount(row.messageCount),
                    updatedAt = row.updatedDate,
                    facts = listOf("${row.messageCount} MESSAGES"),
                )
            }
        return FamilyRead(mapped.take(PER_FAMILY_CAP), rows.size, mapped.drop(PER_FAMILY_CAP))
    }

    // ------------------------------------------------------------------- tasks

    private suspend fun readTasks(): FamilyRead {
        val rows: List<TaskEntity> = taskDao.getAllFullTasks()
        val mapped = rows
            .sortedWith(
                compareByDescending<TaskEntity> { it.priority }
                    .thenByDescending { it.updatedDate }
            )
            .map { row ->
                MindNode(
                    id = TASK_PREFIX + row.id,
                    family = MindFamily.TASK,
                    label = row.title,
                    body = row.description.ifBlank { row.title },
                    // Priority is 0..3 in this domain, so a full three is the heaviest.
                    weight = (row.priority / 3f).coerceIn(0f, 1f),
                    updatedAt = row.updatedDate,
                    facts = buildList {
                        add(if (row.isCompleted) "DONE" else "OPEN")
                        if (row.recurring) add("RECURRING")
                        if (row.subTasks.isNotEmpty()) add("${row.subTasks.size} SUBTASKS")
                    },
                )
            }
        return FamilyRead(mapped.take(PER_FAMILY_CAP), rows.size, mapped.drop(PER_FAMILY_CAP))
    }

    // ------------------------------------------------------------------- notes

    private suspend fun readNotes(): FamilyRead {
        val rows: List<NoteEntity> = noteDao.getAllFullNotes()
        val mapped = rows
            .sortedByDescending { it.updatedDate }
            .map { row ->
                MindNode(
                    id = NOTE_PREFIX + row.id,
                    family = MindFamily.NOTE,
                    label = row.title.ifBlank { "Untitled note" },
                    body = row.content.ifBlank { row.title },
                    weight = scaleTextLength(row.content.length),
                    updatedAt = row.updatedDate,
                    facts = buildList {
                        if (row.pinned) add("PINNED")
                        if (!row.folderId.isNullOrBlank()) add("IN A FOLDER")
                    },
                )
            }
        return FamilyRead(mapped.take(PER_FAMILY_CAP), rows.size, mapped.drop(PER_FAMILY_CAP))
    }

    // ---------------------------------------------------------------- projects

    private suspend fun readProjects(): FamilyRead {
        val rows: List<ProjectEntity> = projectDao.getAllProjectsSnapshot()
        val mapped = rows
            .sortedByDescending { it.updatedDate }
            .map { row ->
                MindNode(
                    id = PROJECT_PREFIX + row.id,
                    family = MindFamily.PROJECT,
                    label = row.title,
                    body = row.description.ifBlank { row.title },
                    weight = scaleCount(row.messageCount + row.documentCount),
                    updatedAt = row.updatedDate,
                    facts = buildList {
                        add("${row.messageCount} MESSAGES")
                        add("${row.documentCount} DOCUMENTS")
                        if (!row.isActive) add("CLOSED")
                    },
                )
            }
        return FamilyRead(mapped.take(PER_FAMILY_CAP), rows.size, mapped.drop(PER_FAMILY_CAP))
    }

    // ------------------------------------------------------------------ agents

    private suspend fun readAgents(): FamilyRead {
        val rows: List<GuruAgentEntity> = agentDao.getAllAgents()
        val mapped = rows
            .sortedByDescending { it.updatedAt }
            .map { row ->
                val tools = countCsv(row.toolPermissions)
                MindNode(
                    id = AGENT_PREFIX + row.id,
                    family = MindFamily.AGENT,
                    label = row.name,
                    body = row.description.ifBlank { firstLine(row.systemPrompt, 500) },
                    weight = scaleCount(tools),
                    updatedAt = row.updatedAt,
                    facts = buildList {
                        add("$tools TOOLS")
                        add(if (row.enabled) "ON" else "OFF")
                        if (row.source.isNotBlank()) add(row.source.uppercase())
                    },
                )
            }
        return FamilyRead(mapped.take(PER_FAMILY_CAP), rows.size, mapped.drop(PER_FAMILY_CAP))
    }

    // ------------------------------------------------------------------ skills

    private suspend fun readSkills(): FamilyRead {
        val rows: List<LuxifyEntity> = skillDao.getAllLuxify()
        val mapped = rows
            .sortedByDescending { it.updatedAt }
            .map { row ->
                val tools = countCsv(row.allowedTools)
                MindNode(
                    id = SKILL_PREFIX + row.id,
                    family = MindFamily.SKILL,
                    label = row.name,
                    body = row.description.ifBlank { firstLine(row.bodyMarkdown, 500) },
                    weight = scaleTextLength(row.bodyMarkdown.length),
                    updatedAt = row.updatedAt,
                    facts = buildList {
                        if (tools > 0) add("$tools TOOLS")
                        add(if (row.enabled) "ON" else "OFF")
                        if (row.source.isNotBlank()) add(row.source.uppercase())
                    },
                )
            }
        return FamilyRead(mapped.take(PER_FAMILY_CAP), rows.size, mapped.drop(PER_FAMILY_CAP))
    }

    // ---------------------------------------------------------------- thoughts

    private suspend fun readThoughts(): FamilyRead {
        val cycles: List<GuruThoughtCycleEntity> = thoughtDao.getAllCycles()
        val tips: List<GuruNoteToSelfEntity> = noteToSelfDao.getAll()

        // Two stores share one family: the reflection cycles, and the notes GURU
        // writes to steer its own future behaviour. Both are the mind thinking
        // about itself, which is why they belong on one branch.
        val cycleNodes = cycles
            .sortedByDescending { it.lastRunAt ?: it.createdAt }
            .map { row ->
                MindNode(
                    id = THOUGHT_PREFIX + row.id,
                    family = MindFamily.THOUGHT,
                    label = row.displayName.ifBlank { row.name },
                    body = row.description.ifBlank { firstLine(row.thoughtProcess, 400) },
                    weight = scaleCount(row.insightCount + row.actionCount),
                    updatedAt = row.lastRunAt ?: row.createdAt,
                    facts = buildList {
                        add("${row.runCount} RUNS")
                        add("${row.insightCount} INSIGHTS")
                        add(if (row.enabled) "ON" else "OFF")
                    },
                )
            }

        val tipNodes = tips
            .sortedByDescending { it.updatedAt }
            .map { row ->
                MindNode(
                    id = NOTE_TO_SELF_PREFIX + row.id,
                    family = MindFamily.THOUGHT,
                    label = row.name,
                    body = row.content,
                    weight = scaleCount(row.fireCount),
                    updatedAt = row.updatedAt,
                    facts = buildList {
                        add("${row.fireCount}x FIRED")
                        add(if (row.enabled) "ON" else "OFF")
                        if (row.triggerKeywords.isNotBlank()) add("TRIGGERED")
                    },
                )
            }

        // One shared budget across both stores, so the branch cannot exceed the
        // cap by having two lists that each respected it on their own.
        val combined = cycleNodes + tipNodes
        return FamilyRead(
            nodes = combined.take(PER_FAMILY_CAP),
            total = cycles.size + tips.size,
            heldBack = combined.drop(PER_FAMILY_CAP),
        )
    }

    // ------------------------------------------------------------------ people

    /**
     * People are the humans the machine actually holds a record of.
     *
     * There is no contact table in this database, so a people branch padded with
     * invented names would be the one part of the map that was decoration. What
     * exists is the profile its owner filled in, which is a real person the mind
     * knows about, plus whoever the profile lists as family or work. Each is a
     * node with the field it came from as its provenance.
     */
    private suspend fun readPeople(): FamilyRead {
        // The profile row is optional by design: a fresh install has none until
        // onboarding writes it, which means "no profile" is a real answer and not
        // a read failure. It is read out to a non-null local so the compiler can
        // see that every use below is safe.
        val profile: UserProfileEntity = userProfileDao.getProfile(PROFILE_ID)
            ?: return FamilyRead(emptyList(), 0)

        val nodes = ArrayList<MindNode>(4)

        val ownerName = profile.preferredName.ifBlank { profile.fullName }.ifBlank { "The owner" }
        nodes.add(
            MindNode(
                id = PERSON_PREFIX + "owner",
                family = MindFamily.PERSON,
                label = ownerName,
                body = buildString {
                    append(ownerName)
                    append(". The person this mind belongs to and works for.")
                    if (profile.location.isNotBlank()) append(" Based in ${profile.location}.")
                    if (profile.work.isNotBlank()) append(" Works in ${profile.work}.")
                },
                weight = 1f,
                updatedAt = profile.updatedAt,
                facts = buildList {
                    if (profile.pronouns.isNotBlank()) add(profile.pronouns.uppercase())
                    if (profile.location.isNotBlank()) add(profile.location.uppercase())
                    if (profile.currentFocus.isNotBlank()) add("FOCUS")
                },
            )
        )

        // Free-text answers the profile holds about other people. Only the ones
        // that genuinely name somebody are nodes; an empty field is an
        // unanswered question, not a person.
        listOf(
            Triple("family", profile.family, "Family"),
            Triple("work", profile.work, "Work"),
        ).forEach { (key, value, _) ->
            if (value.isBlank()) return@forEach
            nodes.add(
                MindNode(
                    id = PERSON_PREFIX + key,
                    family = MindFamily.PERSON,
                    label = firstLine(value, 60),
                    body = value,
                    weight = 0.6f,
                    updatedAt = profile.updatedAt,
                    facts = listOf(key.uppercase(), "FROM PROFILE"),
                )
            )
        }

        return FamilyRead(nodes, nodes.size)
    }

    // ----------------------------------------------------------------- devices

    private suspend fun readDevices(): FamilyRead {
        val rows: List<FabricDeviceEntity> = fabricDao.getAllDevices()
        val mapped = rows
            .sortedWith(
                compareByDescending<FabricDeviceEntity> { it.available }
                    .thenByDescending { it.lastSeen }
            )
            .map { row ->
                MindNode(
                    id = DEVICE_PREFIX + row.id,
                    family = MindFamily.DEVICE,
                    label = row.name,
                    body = "${row.name}. A ${row.deviceClass.replace('_', ' ').lowercase()} " +
                        "reached over ${row.transport.lowercase()}.",
                    weight = if (row.available) 1f else 0.3f,
                    updatedAt = row.lastSeen,
                    facts = buildList {
                        add(if (row.available) "REACHABLE" else "OFFLINE")
                        row.room?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
                        add(row.transport.uppercase())
                    },
                )
            }
        return FamilyRead(mapped.take(PER_FAMILY_CAP), rows.size, mapped.drop(PER_FAMILY_CAP))
    }

    // ------------------------------------------------------------------- edges

    /**
     * The brain's recorded relationships, filtered to ids on this canvas.
     *
     * Both stores are read because they mean different things: a semantic edge is
     * the brain noticing two memories are alike, and a cross reference is the
     * brain recording that they relate, contradict or supersede. Drawing only one
     * would drop half of what the mind knows about itself.
     */
    private suspend fun readEdges(
        memoryIds: Set<String>,
        into: MutableList<MindEdge>,
        failures: MutableList<MindReadFailure>,
    ) {
        runCatching {
            val all: List<MemoryEdgeEntity> = edgeDao.getAllEdges()
            all.forEach { edge ->
                val from = MEMORY_PREFIX + edge.sourceId
                val to = MEMORY_PREFIX + edge.targetId
                if (from == to || from !in memoryIds || to !in memoryIds) return@forEach
                into.add(
                    MindEdge(
                        fromId = from,
                        toId = to,
                        kind = edgeKindOf(edge.edgeType),
                        weight = edge.weight.coerceIn(0f, 1f),
                    )
                )
            }
        }.onFailure {
            failures.add(MindReadFailure(MindFamily.MEMORY, "memory edges could not be read"))
        }

        runCatching {
            val refs: List<MemoryCrossReferenceEntity> = crossReferenceDao.getAllCrossReferences()
            refs.forEach { ref ->
                val from = MEMORY_PREFIX + ref.factIdA
                val to = MEMORY_PREFIX + ref.factIdB
                if (from == to || from !in memoryIds || to !in memoryIds) return@forEach
                into.add(
                    MindEdge(
                        fromId = from,
                        toId = to,
                        kind = crossReferenceKindOf(ref.refType),
                        weight = 1f,
                    )
                )
            }
        }.onFailure {
            failures.add(MindReadFailure(MindFamily.MEMORY, "memory cross references could not be read"))
        }
    }

    private fun edgeKindOf(raw: String): EdgeKind = when (raw.uppercase()) {
        "TEMPORAL" -> EdgeKind.TEMPORAL
        "CAUSAL" -> EdgeKind.CAUSAL
        "ENTITY" -> EdgeKind.ENTITY
        // SEMANTIC is the brain's default relationship and also the safe reading
        // of an edge type added after this build shipped: two memories the brain
        // chose to join are, at minimum, related.
        else -> EdgeKind.SEMANTIC
    }

    private fun crossReferenceKindOf(raw: String): EdgeKind = when (raw.uppercase()) {
        "CONTRADICTS" -> EdgeKind.CONTRADICTS
        "SUPERSEDES" -> EdgeKind.SUPERSEDES
        "DUPLICATE" -> EdgeKind.DUPLICATE
        else -> EdgeKind.RELATED
    }

    // ------------------------------------------------------------------ shapes

    /** A count turned into a 0..1 weight on a log scale, so 500 is not 500x 1. */
    private fun scaleCount(count: Int): Float {
        if (count <= 0) return 0.08f
        return (log10(count.toFloat() + 1f) / 2f).coerceIn(0.08f, 1f)
    }

    /** Body length as a weight, because a long note is a heavier note. */
    private fun scaleTextLength(length: Int): Float = scaleCount(length / 40)

    /** How many comma-separated entries a stored tool list holds. */
    private fun countCsv(raw: String): Int =
        if (raw.isBlank()) 0 else raw.split(',').count { it.isNotBlank() }

    /** The first sentence-ish slice of a body, for a map label. */
    private fun firstLine(text: String, limit: Int): String {
        val flattened = text.replace('\n', ' ').trim()
        if (flattened.length <= limit) return flattened
        return flattened.take(limit).trimEnd() + "\u2026"
    }

    /** Two decimal places, because a map label is not the place for a float dump. */
    private fun formatTwoPlaces(value: Float): String {
        val rounded = kotlin.math.round(value * 100f) / 100f
        return rounded.toString()
    }
}
