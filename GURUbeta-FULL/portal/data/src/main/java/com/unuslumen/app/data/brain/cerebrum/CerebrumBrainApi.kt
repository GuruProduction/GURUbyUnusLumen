// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.brain.cerebrum

import android.util.Log
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * CerebrumBrainApi — the typed brain client: the single Kotlin surface which
 * wraps every GURU memory call as a real Cerebrum wire frame.
 *
 * Frame bytes cross `CerebrumHost.executeJNI` (hex-in / hex-out of the same
 * JSON the unix/TCP doors carry). This object is the ONLY place in GURU that
 * builds brain JSON, so the whole wire contract lives in one file.
 *
 * Call surfaces (each = one real brain frame):
 * - storeFact: CURATE (Upsert with a deterministic 32-byte id from content)
 * - searchFacts(search query): SEARCH (signature-space; the engine's rank)
 * - retrieveFact(id): RETRIEVE deep (full body) or shallow (index-only)
 * - listFacts: LIST, a real enumeration of every live memory. This replaced a
 *   SEARCH for the literal word "memory": a relevance query can only answer
 *   "which memories relate to these words", so using it as a listing silently
 *   omitted every fact whose wording did not contain that word.
 * - recallFact: decay strengthening on the brain happens during search or
 *   explicit RETRIEVE; recall = RETRIEVE shallow for real strengthening.
 * - dream: CONSOLIDATE frame real dispatch on the brain's dream engine
 * - deleteFact: CURATE Delete
 * - graphQuery: GRAPH_TRAVERSE from the label-start-selected node
 * - status: cerebrum host status json passthrough
 *
 * Determinism: same content -> same memory id; brain curate Upsert replaces,
 * never duplicates, making every GURU write idempotent at memory level.
 */
object CerebrumBrainApi {
    private const val TAG = "guru_cerebrum"

    private val wire = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val wirePretty = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = false }

    // --------------------------------------------------------------------
    // hex utils: JNI payload cross is hex-in-out; these two own the edges.
    // --------------------------------------------------------------------
    internal fun bytesToHex(bytesIn: ByteArray): String {
        val builder = StringBuilder(bytesIn.size * 2)
        for (eachByte in bytesIn) {
            builder.append(Character.forDigit((eachByte.toInt() shr 4) and 0x0F, 16))
            builder.append(Character.forDigit(eachByte.toInt() and 0x0F, 16))
        }
        return builder.toString()
    }

    internal fun hexToBytes(hexIn: String): ByteArray {
        val safe = hexIn.trim()
        if (safe.isEmpty()) return ByteArray(0)
        require(safe.length % 2 == 0) { "brain payload hex odd length" }
        val output = ByteArray(safe.length / 2)
        for (index in safe.indices step 2) {
            val high = Character.digit(safe[index], 16)
            val low = Character.digit(safe[index + 1], 16)
            require(high >= 0 && low >= 0) { "brain payload hex invalid at $index" }
            output[index / 2] = ((high shl 4) or low).toByte()
        }
        return output
    }

    // --------------------------------------------------------------------
    // id helper (identical shape to the migration path)
    // --------------------------------------------------------------------

    /** 32-byte deterministic id from content: sha256("content:len") -> 64-hex. */
    private fun deterministicBrainId(content: String): String {
        val contentBytes = content.toByteArray(StandardCharsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(contentBytes)
        digest.update(":".toByteArray(StandardCharsets.US_ASCII))
        digest.update(contentBytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        return bytesToHex(digest.digest())
    }

    /** memory_id wire shape: a raw 32-byte JSON num array from the id hex. */
    internal fun memoryIdArrayJson(idHexLower: String): String {
        require(idHexLower.length == 64)
        val builder = StringBuilder(96)
        for (index in idHexLower.indices step 2) {
            val high = Character.digit(idHexLower[index], 16)
            val low = Character.digit(idHexLower[index + 1], 16)
            if (index > 0) builder.append(',')
            builder.append((high shl 4) or low)
        }
        return builder.toString()
    }

    private fun jsonEscape(rawText: String): String {
        val builder = StringBuilder(rawText.length + 8)
        for (inputChar in rawText) {
            when (inputChar) {
                '\\' -> builder.append("\\\\")
                '"' -> builder.append("\\\"")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (inputChar < ' ') builder.append("\\u%04x".format(inputChar.code)) else builder.append(inputChar)
            }
        }
        return builder.toString()
    }

    private fun rfc3339Of(epochMilli: Long): String = Instant.ofEpochMilli(epochMilli).toString()
    private fun now(): Long = System.currentTimeMillis()

    // --------------------------------------------------------------------
    // responses (typed for the 3 shapes GURU actually consumes)
    // --------------------------------------------------------------------

    @Serializable
    data class ExecutedDetail(val memory_id: String = "", val status: String = "", val detail: String = "")

    data class CurateAck(
        val success: Boolean,
        val createdInEngine: Int,
        val rawCounts: CurateSideCounts,
        val perItem: List<ExecutedDetail>,
        val raw: String,
    )

    @Serializable
    data class CurateSideCounts(
        val created: Int = 0,
        val updated: Int = 0,
        val merged: Int = 0,
        val deleted: Int = 0,
        val dwm_count: Int = 0,
        val hamming_count: Int = 0,
        val graph_nodes: Int = 0,
        val graph_edges: Int = 0,
        val events: Int = 0,
        val cross_references: Int = 0,
    )

    /**
     * One search hit from the SEARCH frame: id, score, body, and the row's metadata.
     *
     * THE METADATA IS ON THE WIRE NOW, AND THAT WAS THE DEFECT. A hit used to carry
     * only `{memory_id, score, content}`, so the Kotlin boundary had to invent a
     * category and an origin, and it invented constants that were wrong in a way no
     * test caught: the category `memory` matched no real category, and the origin
     * `cerebrum` was the storage backend rather than the writer. Listing and retrieve
     * read the real values from the same rows, so the three read paths disagreed.
     *
     * The defaults are empty rather than a plausible stand-in, so a brain that predates
     * these fields produces an obviously absent value rather than a confident wrong one.
     */
    data class BrainHit(
        val idHex: String,
        val score: Float,
        val content: String,
        val domain: String = "",
        val topic: String = "",
        val subtopic: String = "",
        val confidence: Float = 1.0f,
        val provenance: String = "",
        val lifecycle: String = "",
        val layer: String = "",
        val strength: Float = 0f,
    )

    /**
     * One retrieved memory's real shape from the RETRIEVE wire.
     *
     * `rationale` and `provenanceSource` carry the caller's category and the
     * write that stored it; `confidence` is the value the write carried, parsed
     * back out of the provenance label. They exist because the brain's own
     * hierarchy has no slot for a caller's confidence, so without them a read
     * back could only ever guess.
     */
    data class RetrievedBody(
        val idHex: String,
        val domain: String,
        val topic: String,
        val subtopic: String,
        val content: String,
        val decayLayer: String,
        val decayStrength: Float,
        val decayAccesses: Long,
        val archived: Boolean,
        val episodePresent: Boolean,
        val rationale: String = "",
        val provenanceSource: String = "",
        val confidence: Float = 1.0f,
    ) {
        val isArchived get() = archived
    }

    /** One memory as the LIST frame reports it. */
    data class ListedMemory(
        val idHex: String,
        val domain: String,
        val topic: String,
        val subtopic: String,
        val content: String,
        val confidence: Float,
        val rationale: String,
        val updatedAt: String,
        /** Active, Hibernated or Archived, as the tree holds it. */
        val lifecycle: String = "",
        /** BUFFER, EPISODIC or SEMANTIC, empty when decay has no row. */
        val layer: String = "",
    )

    /**
     * One LIST reply: the page of rows plus the true totals.
     *
     * `totalMatching` and `totalLive` are separate from `memories.size` on
     * purpose, so a capped listing can never present its own page length as the
     * number of memories the brain holds.
     */
    data class ListResult(
        val memories: List<ListedMemory>,
        val totalMatching: Int,
        val totalLive: Int,
    )

    // --------------------------------------------------------------------
    // frame pushers
    // --------------------------------------------------------------------

    /** push raw frame bytes to JNI and pull the plain-JSON UTF8 string out; null on brain-down. */
    private fun dispatchOrNull(frameType: Int, jsonPayloadText: String): String? {
        val payloadBytes = jsonPayloadText.toByteArray(StandardCharsets.UTF_8)
        val hexIn = bytesToHex(payloadBytes)
        // The host owns the last hop so a test can substitute it and prove what this object
        // would actually have sent. Every byte before that hop is the production encoder.
        val responseHex = CerebrumHost.dispatchFrame(frameType, hexIn) ?: return null
        if (responseHex.isEmpty()) return null
        return String(hexToBytes(responseHex), StandardCharsets.UTF_8)
    }

    private fun brainDown(): Boolean = !CerebrumHost.isRunningForDispatch()

    // --------------------------------------------------------------------
    // 1. store/curate (real CURATE frame; upsert semantics inside)
    // --------------------------------------------------------------------

    /**
     * The write gate: memory facts are PROSE the numen says about the world,
     * plain English with no machine-blob syntax. Defect verdict 2026-10-07:
     * raw tool-result JSON was entering the memory as facts, forming a
     * feedback loop (a search writes a fact whose content matches the next
     * search's raw blob). Raw JSON blobs now refuse at the ONE write gate
     * here so NO upstream lane (HiveMind, tool result dumpers, thought
     * cycles) can poison the vault again. The structured lanes that need a
     * machine shape (conversation rows, chat-message markers, tool-result
     * context rows, markers) carry real structured prose markers through
     * other writers whose shape IS prose-with-markers and pass the same
     * gate below via the allowMarkerPrefix set.
     */
    private val markerPrefixes: Set<String> = setOf(
        "conversation ", "message conv=", "thread ", "hive processed",
        "tool result: ", "message ",  // internal structured lanes stay legitimate
    )

    private fun isRawMachineBlob(body: String): Boolean {
        val trimmedBody = body.trim()
        if (trimmedBody.startsWith("{") || trimmedBody.startsWith("[")) return true
        if (trimmedBody.startsWith("\"response\":" ) ||
            trimmedBody.startsWith("\"results\":") ||
            trimmedBody.contains("Tool execution result") ||
            trimmedBody.contains("\"tool_call_id\"")) {
            return true
        }
        // A real prose sentence very rarely contains more raw json-pairs
        // density; treat as suspicious at >=5 occurrences of '":'
        val doubleQuoteColonsInBody = Regex("\\\":").findAll(trimmedBody).count()
        if (doubleQuoteColonsInBody >= 5) return true
        return false
    }

    private fun isStructuredMarkerRow(body: String): Boolean =
        markerPrefixes.any { markerPrefix -> body.trim().startsWith(markerPrefix) }

    /**
     * Encode the caller's provenance plus their confidence into the one string
     * the wire carries.
     *
     * The core `ContextEntry` has a string where the curate layer has a typed
     * `Provenance`, so confidence has to ride inside the label or it is lost.
     * The delimiter is chosen so the source text stays readable on its own and
     * the server splits it back out with the same rule.
     */
    fun provenanceLabelFor(sourceLabel: String, confidence: Float): String =
        "${sourceLabel.trim().ifEmpty { "guru" }}|confidence=${"%.4f".format(confidence.coerceIn(0f, 1f))}"

    fun storeFact(
        factText: String,
        category: String,
        provenanceLabel: String,
        createdAtMillis: Long,
        domainLabel: String = "memory",
        topicLabel: String = "recall",
        subtopicLabel: String = "fact",
    ): CurateAck {
        require(factText.isNotBlank()) { "empty fact text refuses" }
        if (isRawMachineBlob(factText) && !isStructuredMarkerRow(factText)) {
            Log.w(TAG, "storeFact GATED: raw machine blob refused as fact (starts ${'{'} or match-heavy); category='$category' first=${factText.take(60)}")
            return CurateAck(success = false, createdInEngine = 0, rawCounts = zeroCounts, perItem = emptyList(), raw = "write gate: raw tool-result/JSON blobs are not memory facts")
        }
        val idHex = deterministicBrainId(factText)
        val upsertJson = upsertJsonShape(
            idHex,
            domainLabel,
            topicLabel,
            subtopicLabel,
            factText,
            provenanceLabel,
            "guru storeFact: $category",
            createdAtMillis,
            now(),
        )
        val ack = curateJsonOps(listOf(upsertJson))
        // Honest identity: carry the REAL deterministic hex id on the ack's
        // sole detail row so callers (tool lane) learn the true storage id,
        // not a random uuid nothing in the brain can resolve.
        return if (ack.success && ack.perItem.isEmpty()) {
            ack.copy(perItem = listOf(ExecutedDetail(memory_id = idHex, status = "upserted", detail = "guru storeFact")))
        } else ack
    }

    /** The real deterministic content id FOR one storeFact call (same hash
     * the wire carries) — callers use it to know what the brain holds BEFORE
     * dispatch, enabling tool-side honest round trips. */
    fun realIdFor(factText: String): String = deterministicBrainId(factText)

    /**
     * Store one record with the FULL typed hierarchy (issue 486).
     *
     * WHY THIS IS A SECOND WRITER RATHER THAN A WIDER `storeFact`. `storeFact` is
     * the lane every automatic writer uses: the hive, tool-result dumps, thought
     * cycles. Those callers have a category and no hierarchy, so widening that
     * signature would make every one of them carry three empty parameters and
     * would not tell a reader which lane meant what.
     *
     * This is the caller-facing lane, where an agent has decided where a memory
     * belongs and how it connects. The wire shape is the same `Upsert` the brain
     * already accepts; what changes is that domain, topic, subtopic, rationale and
     * relations reach it instead of being discarded one layer up.
     *
     * `relations` arrives as 64-hex ids because that is what a tool result hands
     * back, and it is decoded to the brain's 32-byte array shape here. An id that
     * is not 64 hex characters is a caller error and is refused by the tool layer
     * before it reaches this point, so a malformed relation cannot silently become
     * a wire-level parse failure.
     */
    fun storeRecord(
        contentText: String,
        domainLabel: String,
        topicLabel: String,
        subtopicLabel: String,
        provenanceLabel: String,
        rationaleText: String,
        relationIdsHex: List<String>,
        createdAtMillis: Long,
    ): CurateAck {
        require(contentText.isNotBlank()) { "empty record text refuses" }
        if (isRawMachineBlob(contentText) && !isStructuredMarkerRow(contentText)) {
            Log.w(TAG, "storeRecord GATED: raw machine blob refused; domain='$domainLabel' first=${contentText.take(60)}")
            return CurateAck(
                success = false, createdInEngine = 0, rawCounts = zeroCounts,
                perItem = emptyList(), raw = "write gate: raw tool-result/JSON blobs are not memory facts",
            )
        }
        val idHex = deterministicBrainId(contentText)
        val relationsJson = relationIdsHex.joinToString(",") { oneId ->
            "[" + memoryIdArrayJson(oneId.lowercase()) + "]"
        }
        val upsertJson = "{\"Upsert\":{\"entry\":{" +
            "\"memory_id\":[" + memoryIdArrayJson(idHex) + "]," +
            "\"domain\":\"" + jsonEscape(domainLabel.ifBlank { "memory" }) + "\"," +
            "\"topic\":\"" + jsonEscape(topicLabel.ifBlank { "general" }) + "\"," +
            "\"subtopic\":\"" + jsonEscape(subtopicLabel.ifBlank { "general" }) + "\"," +
            "\"content\":\"" + jsonEscape(contentText) + "\"," +
            "\"relations\":[" + relationsJson + "]," +
            "\"provenance\":\"" + jsonEscape(provenanceLabel) + "\"," +
            "\"rationale\":\"" + jsonEscape(rationaleText) + "\"," +
            "\"created_at\":\"" + rfc3339Of(createdAtMillis) + "\"," +
            "\"modified_at\":\"" + rfc3339Of(now()) + "\"" +
            "},\"reason\":\"" + jsonEscape(rationaleText.ifBlank { "cortex record" }) + "\"}}"
        val ack = curateJsonOps(listOf(upsertJson))
        return if (ack.success && ack.perItem.isEmpty()) {
            ack.copy(perItem = listOf(ExecutedDetail(memory_id = idHex, status = "upserted", detail = "cortex record")))
        } else ack
    }

    fun upsertJsonShape(
        idHexLower: String,
        domainIn: String,
        topicIn: String,
        subtopicIn: String,
        contentIn: String,
        provenanceIn: String,
        rationaleIn: String,
        createdAtMs: Long,
        modifiedAtMs: Long,
    ): String = "" +
        "{\"Upsert\":{\"entry\":{" +
            "\"memory_id\":[" + memoryIdArrayJson(idHexLower) + "]," +
            "\"domain\":\"" + jsonEscape(domainIn.ifBlank { "memory" }) + "\"," +
            "\"topic\":\"" + jsonEscape(topicIn.ifBlank { "general" }) + "\"," +
            "\"subtopic\":\"" + jsonEscape(subtopicIn.ifBlank { "general" }) + "\"," +
            "\"content\":\"" + jsonEscape(contentIn) + "\"," +
            "\"relations\":[]," +
            "\"provenance\":\"" + jsonEscape(provenanceIn) + "\"," +
            "\"rationale\":\"" + jsonEscape(rationaleIn) + "\"," +
            "\"created_at\":\"" + rfc3339Of(createdAtMs) + "\"," +
            "\"modified_at\":\"" + rfc3339Of(modifiedAtMs) + "\"" +
        "},\"reason\":\"" + jsonEscape(rationaleIn) + "\"}}"

    /** CurateOp::Delete wire shape (the real brain-side archive path). */
    fun deleteJsonOpByHex(idHexLower: String, reasonText: String): String =
        "{\"Delete\":{\"memory_id\":[" + memoryIdArrayJson(idHexLower) + "],\"reason\":\"" +
            jsonEscape(reasonText) + "\"}}"

    fun curateJsonOps(oneOpsJson: List<String>): CurateAck = run {
        if (brainDown()) {
            return CurateAck(success = false, createdInEngine = 0, rawCounts = zeroCounts, perItem = emptyList(), raw = "brain down")
        }
        val batchJson = "[" + oneOpsJson.joinToString(separator = ",") + "]"
        val brainReply = dispatchOrNull(FRAME_CURATE, batchJson) ?: return run {
            Log.e(TAG, "curate: no brain response")
            CurateAck(false, 0, zeroCounts, emptyList(), "<null>")
        }
        require(brainReply.isNotBlank()) { "brain curation returned an EMPTY response json" }
        if (brainReply.contains("\"error\"")) {
            Log.e(TAG, "curate error frame: $brainReply")
        }
        acknowledgeOf(brainReply)
    }

    private fun acknowledgeOf(replyBody: String): CurateAck {
        val jsonRoot = runCatching { wire.parseToJsonElement(replyBody).jsonObject }.getOrElse {
            // not an ack: fail-fast in the shape, never silent-yes
            return CurateAck(false, 0, zeroCounts, emptyList(), replyBody)
        }
        val executedObject = jsonRoot["executed"]?.jsonObject
        val wasSuccess = executedObject?.get("success")?.jsonPrimitive?.content == "true"
        val sideObject = jsonRoot["side_effects"]?.jsonObject
        val sideCounts = sideObject?.let {
            runCatching {
                wire.decodeFromJsonElement(CurateSideCounts.serializer(), it)
            }.getOrDefault(zeroCounts)
        } ?: zeroCounts
        val detailArray = executedObject?.get("details")?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
        val perItemList = detailArray.mapNotNull { entry ->
            runCatching {
                val detailObj = entry.jsonObject
                val perStatus = detailObj["status"]?.jsonPrimitive?.content ?: "?"
                val detailContent = detailObj["detail"]?.jsonPrimitive?.content ?: ""
                // memory_id serializes array; just accept count + status + detail text
                val realIdArray = detailObj["memory_id"]?.jsonArray
                val arrayFirstByte = realIdArray?.firstOrNull()?.jsonPrimitive?.content ?: "0"
                ExecutedDetail(arrayFirstByte, perStatus, detailContent)
            }.getOrNull()
        }
        return CurateAck(
            success = wasSuccess,
            createdInEngine = detailArray.size,
            rawCounts = sideCounts,
            perItem = perItemList,
            raw = replyBody,
        )
    }

    private val zeroCounts = CurateSideCounts()

    // --------------------------------------------------------------------
    // 2. search
    // --------------------------------------------------------------------

    fun searchFacts(searchQuery: String, limitIn: Int = 20): List<BrainHit> {
        if (brainDown()) return emptyList()
        val askJson = "{\"query\":\"" + jsonEscape(searchQuery) + "\",\"radius\":64,\"limit\":" + limitIn + "}"
        val responseText = dispatchOrNull(FRAME_SEARCH, askJson) ?: return emptyList()
        val responseRoot = runCatching { wire.parseToJsonElement(responseText) }.getOrElse { return emptyList() }
        // response shapes: array of `{memory_id: [..], score, content}`
        val responseArray = runCatching { responseRoot.jsonArray }.getOrNull() ?: return emptyList()
        val hits = mutableListOf<BrainHit>()
        for (hitElement in responseArray) {
            val hitObject = runCatching { hitElement.jsonObject }.getOrNull() ?: continue
            val idArray = hitObject["memory_id"]?.jsonArray ?: continue
            if (idArray.size != 32) continue
            val hexBuilder = StringBuilder(64)
            for (byteValueElement in idArray) {
                val byteValueIn = byteValueElement.jsonPrimitive.content.toIntOrNull() ?: continue
                hexBuilder.append("%02x".format(byteValueIn and 0xFF))
            }
            val hexId = hexBuilder.toString()
            val hitScore = hitObject["score"]?.jsonPrimitive?.content?.toFloatOrNull() ?: continue
            val hitContent = hitObject["content"]?.jsonPrimitive?.content ?: continue
            hits.add(
                BrainHit(
                    idHex = hexId,
                    score = hitScore,
                    content = hitContent,
                    domain = hitObject["domain"]?.jsonPrimitive?.content ?: "",
                    topic = hitObject["topic"]?.jsonPrimitive?.content ?: "",
                    subtopic = hitObject["subtopic"]?.jsonPrimitive?.content ?: "",
                    confidence = hitObject["confidence"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 1.0f,
                    provenance = hitObject["provenance"]?.jsonPrimitive?.content ?: "",
                    lifecycle = hitObject["lifecycle"]?.jsonPrimitive?.content ?: "",
                    layer = hitObject["layer"]?.jsonPrimitive?.content ?: "",
                    strength = hitObject["strength"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
                )
            )
        }
        return hits
    }

    // --------------------------------------------------------------------
    // 3. retrieve (real body + metadata + decay)
    // --------------------------------------------------------------------

    fun retrieveDeepOrShallow(idHexLower: String, wantDeep: Boolean): RetrievedBody? {
        if (brainDown()) return null
        val levelJson = if (wantDeep) "\"Deep\"" else "\"Shallow\""
        val reqJson = "{\"memory_id\":[" + memoryIdArrayJson(idHexLower) + "],\"level\":" + levelJson + "}"
        val responseText = dispatchOrNull(FRAME_RETRIEVE, reqJson) ?: return null
        val responseRoot = runCatching { wire.parseToJsonElement(responseText).jsonObject }.getOrNull() ?: return null
        val dataRawElement = responseRoot["data"]?.jsonArray ?: return null
        val bodyBytes = ByteArray(dataRawElement.size) { arrayIndex ->
            (dataRawElement[arrayIndex].jsonPrimitive.content.toIntOrNull() ?: 0).toByte()
        }
        val dataText = String(bodyBytes, StandardCharsets.UTF_8)
        val bodyObject = runCatching { wire.parseToJsonElement(dataText).jsonObject }.getOrNull() ?: return null

        var parsedIdHex: String? = null
        var parsedDomain = ""
        var parsedTopic = ""
        var parsedSubtopic = ""
        var parsedContent = ""
        var decayLayer = "BUFFER"
        var decayStrength = 0.0f
        var decayAccesses = 0L
        var archived = false
        var episodePresent = false
        var parsedRationale = ""
        var parsedProvenanceSource = ""
        var parsedConfidence = 1.0f

        for ((eachKey, eachValue) in bodyObject) {
            when (eachKey) {
                "id" -> { parsedIdHex = eachValue.jsonPrimitive.content }
                "domain" -> { parsedDomain = eachValue.jsonPrimitive.content }
                "topic" -> { parsedTopic = eachValue.jsonPrimitive.content }
                "subtopic" -> { parsedSubtopic = eachValue.jsonPrimitive.content }
                "content" -> { /* shallow carries no content key */ parsedContent = runCatching { eachValue.jsonPrimitive.content }.getOrDefault("") }
                "rationale" -> { parsedRationale = runCatching { eachValue.jsonPrimitive.content }.getOrDefault("") }
                "provenance" -> {
                    val provenanceObject = runCatching { eachValue.jsonObject }.getOrNull()
                    if (provenanceObject != null) {
                        parsedProvenanceSource = provenanceObject["source"]
                            ?.jsonPrimitive?.content ?: ""
                        parsedConfidence = provenanceObject["confidence"]
                            ?.jsonPrimitive?.content?.toFloatOrNull() ?: 1.0f
                    }
                }
                "decay" -> { // the tuple [layer,strength,accesses,archived]
                    val decayArray = runCatching { eachValue.jsonArray }.getOrNull()
                    if (decayArray != null) {
                        if (decayArray.size >= 1) decayLayer = decayArray[0].jsonPrimitive.content.replace("?", "", true)
                        if (decayArray.size >= 2) decayStrength = decayArray[1].jsonPrimitive.content.toFloatOrNull() ?: 0f
                        if (decayArray.size >= 3) decayAccesses = decayArray[2].jsonPrimitive.content.toLongOrNull() ?: 0L
                        if (decayArray.size >= 4) archived = decayArray[3].jsonPrimitive.content == "true"
                    }
                }
                "episode" -> {
                    // the wire `episode` is Null when none; any object => present
                    episodePresent = !eachValue.toString().equals("null", true)
                }
            }
        }

        return RetrievedBody(
            parsedIdHex ?: idHexLower,
            parsedDomain,
            parsedTopic,
            parsedSubtopic,
            parsedContent,
            decayLayer,
            decayStrength,
            decayAccesses,
            archived,
            episodePresent,
            parsedRationale,
            parsedProvenanceSource,
            parsedConfidence,
        )
    }

    /**
     * Enumerate memories from the brain, newest first.
     *
     * `domainFilter` blank means everything. `limit` of zero means the brain's
     * own unbounded listing, which the tool lane uses because a caller asking
     * what it remembers must be shown all of it.
     *
     * `includeArchived` selects the RETIRED rows instead of the live ones, and there
     * is one caller that needs it: the deduplication pass. The brain's id for a
     * memory is the hash of its content, so writing a sentence a second time resolves
     * to the row a delete archived. A pass that can only see live rows cannot tell
     * that a fact is already known, and the write it proposes lands on that retired
     * row. Seeing the archive lets it conclude "already known, do not write".
     *
     * Returns null when the brain is down, so a caller can report "the brain is
     * not running" rather than an empty memory that looks like a real zero.
     */
    fun listFacts(
        domainFilter: String = "",
        limit: Int = 0,
        includeArchived: Boolean = false,
    ): ListResult? {
        if (brainDown()) return null
        val requestJson = "{\"domain\":\"" + jsonEscape(domainFilter) + "\",\"limit\":" + limit +
            ",\"include_archived\":" + includeArchived + "}"
        val responseText = dispatchOrNull(FRAME_LIST, requestJson) ?: return null
        val responseRoot = runCatching { wire.parseToJsonElement(responseText).jsonObject }
            .getOrNull() ?: return null
        val memoryArray = responseRoot["memories"]?.jsonArray ?: return null
        val parsedRows = mutableListOf<ListedMemory>()
        for (rowElement in memoryArray) {
            val rowObject = runCatching { rowElement.jsonObject }.getOrNull() ?: continue
            val idArray = rowObject["memory_id"]?.jsonArray ?: continue
            if (idArray.size != 32) continue
            val hexBuilder = StringBuilder(64)
            for (byteValueElement in idArray) {
                val byteValueIn = byteValueElement.jsonPrimitive.content.toIntOrNull() ?: continue
                hexBuilder.append("%02x".format(byteValueIn and 0xFF))
            }
            parsedRows.add(
                ListedMemory(
                    idHex = hexBuilder.toString(),
                    domain = rowObject["domain"]?.jsonPrimitive?.content ?: "",
                    topic = rowObject["topic"]?.jsonPrimitive?.content ?: "",
                    subtopic = rowObject["subtopic"]?.jsonPrimitive?.content ?: "",
                    content = rowObject["content"]?.jsonPrimitive?.content ?: "",
                    confidence = rowObject["confidence"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 1.0f,
                    rationale = rowObject["rationale"]?.jsonPrimitive?.content ?: "",
                    updatedAt = rowObject["updated_at"]?.jsonPrimitive?.content ?: "",
                    lifecycle = rowObject["lifecycle"]?.jsonPrimitive?.content ?: "",
                    layer = rowObject["layer"]?.jsonPrimitive?.content ?: "",
                )
            )
        }
        return ListResult(
            memories = parsedRows,
            totalMatching = responseRoot["total_matching"]?.jsonPrimitive?.content?.toIntOrNull() ?: parsedRows.size,
            totalLive = responseRoot["total_live"]?.jsonPrimitive?.content?.toIntOrNull() ?: parsedRows.size,
        )
    }

    // --------------------------------------------------------------------
    // 4. dream (consolidation). Same call, real engine dispatch everywhere.
    // --------------------------------------------------------------------

    fun runDreamConsolidation(): Boolean {
        if (brainDown()) return false
        val dreamReply = dispatchOrNull(FRAME_CONSOLIDATE, "{}") ?: return false
        if (!dreamReply.contains("\"decayed_all\"")) {
            Log.w(TAG, "dream: odd response shape: ${dreamReply.take(300)}")
        }
        return true
    }

    // --------------------------------------------------------------------
    // 5. graph query. Real wire, the engine's own shape; hits mirror SEARCH.
    // --------------------------------------------------------------------

    /**
     * Traverse the memory graph around a subject, ranked by the engine.
     *
     * THE WIRE SHAPE IS THE ENGINE'S, NOT OURS. The graph crate parses a
     * `Query { text, budget: { max_nodes, max_depth }, query_type }` and chooses
     * its own start node: a labelled node whose label holds a token of the text,
     * or the highest-degree node in that view when no label matches. So this
     * sends text and a real budget, NOT a start memory id.
     *
     * The previous version sent `{"start":{...},"budget":{"max_tokens":..}}`,
     * which is the shape of an older engine. It named a start id the graph does
     * not accept and a token budget that no longer exists, so `maxDepth` was
     * silently dropped and every traverse either errored or answered about
     * whatever node the engine picked. Anything built on it was reading a
     * different subject than the one it asked for.
     *
     * `queryType` is one of Semantic, Temporal, Causal, Entity or Composite. The
     * engine's own spelling is used because serde matches the variant name.
     */
    fun graphTraverse(
        subjectText: String,
        queryType: String = "Composite",
        maxDepth: Int = 3,
        maxNodes: Int = 100,
    ): List<BrainHit> {
        if (brainDown()) return emptyList()
        val askJson = "{\"text\":\"" + jsonEscape(subjectText) + "\"," +
            "\"query_type\":\"" + jsonEscape(queryType) + "\"," +
            "\"budget\":{\"max_nodes\":$maxNodes,\"max_depth\":$maxDepth}}"
        val replyBody = dispatchOrNull(FRAME_GRAPH_TRAVERSE, askJson) ?: return emptyList()
        val parser = runCatching { wire.parseToJsonElement(replyBody).jsonArray }.getOrNull() ?: return emptyList()
        val parsed = mutableListOf<BrainHit>()
        for (valueElement in parser) {
            // The engine returns `[(MemoryId, f32)]`, which serialises as an array
            // of two-element arrays: `[[byte, ...32], score]`. The id is a BYTE
            // ARRAY, not a hex string, so it is hexed here with the same rule the
            // search and listing parsers use. Reading it as a string was the old
            // bug: a 32-byte array read through `jsonPrimitive.content` yields a
            // number, so every id came back wrong or the whole parse was dropped.
            val pair = runCatching { valueElement.jsonArray }.getOrNull() ?: continue
            if (pair.size < 2) continue
            val idBytes = runCatching { pair[0].jsonArray }.getOrNull() ?: continue
            if (idBytes.size != 32) continue
            val hexBuilder = StringBuilder(64)
            var readable = true
            for (byteValueElement in idBytes) {
                val byteValueIn = byteValueElement.jsonPrimitive.content.toIntOrNull()
                if (byteValueIn == null) { readable = false; break }
                hexBuilder.append("%02x".format(byteValueIn and 0xFF))
            }
            if (!readable) continue
            val scoreIn = pair[1].jsonPrimitive.content.toFloatOrNull() ?: 0f
            parsed.add(BrainHit(hexBuilder.toString(), scoreIn, ""))
        }
        return parsed
    }

    // --------------------------------------------------------------------
    // 6. QUERY — the tiered pipeline, dispatched rather than merely declared
    // --------------------------------------------------------------------

    /**
     * One result of a QUERY frame: what the tiered retrieval produced.
     *
     * QUERY IS NOT SEARCH, AND THAT IS WHY IT IS A SEPARATE FRAME. SEARCH is two lexical
     * lanes fused by reciprocal rank, which answers "which stored words match these
     * words". QUERY runs the tiered pipeline: a hot-cache hit, then a DWM pass, then a
     * graph traversal, then a vector fallback, stopping at the first tier that returns
     * anything, and it reports WHICH TIER answered. That is a different question with a
     * different cost profile, and the brain has always been able to answer it.
     *
     * IT WAS DEFINED AND NEVER DISPATCHED. `FRAME_QUERY` existed as a constant and no
     * Kotlin code ever sent it, so the whole tiered pipeline was dead weight inside the
     * shipped library: reachable over the unix socket by the CLI, unreachable by the app.
     */
    data class QueryOutcome(
        /** The ids the chosen tier produced, in the engine's order. */
        val memoryIds: List<String>,
        /** The engine's own confidence in the result, 0..1. */
        val confidence: Float,
        /** Which retrieval tier answered: HOT_CACHE, DWM_SEARCH, GRAPH_TRAVERSAL, etc. */
        val tierUsed: String,
        /** How long the engine spent, as it measured it. */
        val latencyMs: Long,
    )

    /**
     * Run one QUERY frame through the tiered pipeline.
     *
     * Returns null when the brain is down, so a caller can report an unreachable brain
     * rather than an empty memory: the two are different facts about the machine.
     *
     * An invalid or empty query is refused here rather than sent, because the engine
     * would answer a blank query with whatever its tiers happen to hold and that answer
     * would be indistinguishable from a real one.
     */
    fun query(queryText: String, maxTokens: Int = 2048): QueryOutcome? {
        if (brainDown()) return null
        if (queryText.isBlank()) return null
        val askJson = "{\"query_type\":\"Semantic\",\"text\":\"" + jsonEscape(queryText) +
            "\",\"budget\":{\"max_tokens\":" + maxTokens + "}}"
        val replyBody = dispatchOrNull(FRAME_QUERY, askJson) ?: return null
        val root = runCatching { wire.parseToJsonElement(replyBody).jsonObject }.getOrNull()
            ?: return null

        val resultIds = root["results"]?.jsonArray.orEmpty().mapNotNull { element ->
            hexFromByteArray(element)
        }
        return QueryOutcome(
            memoryIds = resultIds,
            confidence = root["confidence"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
            tierUsed = root["tier_used"]?.jsonPrimitive?.content ?: "",
            latencyMs = root["latency_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        )
    }

    // --------------------------------------------------------------------
    // 7. PREFETCH — registered memories made available before they are asked for
    // --------------------------------------------------------------------

    /**
     * What a PREFETCH frame produced: the ids it believes are about to be needed.
     *
     * [hit] is the engine's own verdict on whether the trigger matched anything it holds,
     * which is different from an empty list because the engine distinguishes "nothing
     * matched" from "nothing registered".
     */
    data class PrefetchOutcome(
        val memoryIds: List<String>,
        val hit: Boolean,
    )

    /**
     * Ask the engine to surface what a named specialist, topic or event will need.
     *
     * THE TRIGGER SHAPE IS THE ENGINE'S. `PrefetchTrigger` is an externally tagged enum,
     * so the wire is `{"SpecialistActivated":"doctor"}` and not a `{kind, value}` pair.
     * Sending a shape it does not parse is how a frame appears to succeed while
     * answering nothing.
     *
     * `kind` is one of specialist, topic or event; anything else is refused here rather
     * than sent as a variant the engine will reject.
     */
    fun prefetch(kind: String, value: String): PrefetchOutcome? {
        if (brainDown()) return null
        if (value.isBlank()) return null
        val variant = when (kind.trim().lowercase()) {
            "specialist" -> "SpecialistActivated"
            "topic" -> "TopicShift"
            "event" -> "ScheduledEvent"
            else -> return null
        }
        val askJson = "{\"" + variant + "\":\"" + jsonEscape(value) + "\"}"
        val replyBody = dispatchOrNull(FRAME_PREFETCH, askJson) ?: return null
        val root = runCatching { wire.parseToJsonElement(replyBody).jsonObject }.getOrNull()
            ?: return null
        return PrefetchOutcome(
            memoryIds = root["prefetched"]?.jsonArray.orEmpty().mapNotNull { element ->
                hexFromByteArray(element)
            },
            hit = root["hit"]?.jsonPrimitive?.content == "true",
        )
    }

    /**
     * The 64-hex form of one serialised MemoryId, or null when the element is not one.
     *
     * The engine serialises a `MemoryId` as its 32-byte array, so an id arrives as a JSON
     * array of numbers. Reading it as a string yields a number and every id comes back
     * wrong, which is the bug the graph parser already had to fix once.
     */
    private fun hexFromByteArray(element: kotlinx.serialization.json.JsonElement): String? {
        val bytes = runCatching { element.jsonArray }.getOrNull() ?: return null
        if (bytes.size != 32) return null
        val builder = StringBuilder(64)
        for (byteElement in bytes) {
            val value = byteElement.jsonPrimitive.content.toIntOrNull() ?: return null
            builder.append("%02x".format(value and 0xFF))
        }
        return builder.toString()
    }

    // --------------------------------------------------------------------
    // frame constants; identical bytes to the JNI exports contract
    // --------------------------------------------------------------------
    internal const val FRAME_QUERY = 1
    internal const val FRAME_CURATE = 2
    internal const val FRAME_RETRIEVE = 3
    internal const val FRAME_SEARCH = 4
    internal const val FRAME_GRAPH_TRAVERSE = 5
    internal const val FRAME_CONSOLIDATE = 6
    internal const val FRAME_PREFETCH = 7
    internal const val FRAME_LIST = 8

    fun lastErrorOrNull(): String? = runCatching { CerebrumHost.lastError() }.getOrNull()?.let { text ->
        if (text.isBlank()) null else text
    }
}