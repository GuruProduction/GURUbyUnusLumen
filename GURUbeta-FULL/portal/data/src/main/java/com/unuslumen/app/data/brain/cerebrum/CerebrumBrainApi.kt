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
 * - listFacts / listByDomain / listByCategory: RETRIEVE-backed via store's
 *   own memory map through SEARCH (query = the domain text) with limit high
 *   enough to round-trip small batches fast enough on-device.
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

    /** one search hit from the SEARCH frame; id is 64-hex lower, score 0..1f real. */
    data class BrainHit(val idHex: String, val score: Float, val content: String)

    /** deep retrieve body's real full shape from the RETRIEVE wire. */
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
    ) {
        val isArchived get() = archived
    }

    // --------------------------------------------------------------------
    // frame pushers
    // --------------------------------------------------------------------

    /** push raw frame bytes to JNI and pull the plain-JSON UTF8 string out; null on brain-down. */
    private fun dispatchOrNull(frameType: Int, jsonPayloadText: String): String? {
        val payloadBytes = jsonPayloadText.toByteArray(StandardCharsets.UTF_8)
        val hexIn = bytesToHex(payloadBytes)
        val responseHex = CerebrumHost.executeFrame(frameType, hexIn) ?: return null
        if (responseHex.isEmpty()) return null
        return String(hexToBytes(responseHex), StandardCharsets.UTF_8)
    }

    private fun brainDown(): Boolean = !CerebrumHost.isRunning()

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
        return curateJsonOps(listOf(upsertJson))
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
            hits.add(BrainHit(hexId, hitScore, hitContent))
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

        for ((eachKey, eachValue) in bodyObject) {
            when (eachKey) {
                "id" -> { parsedIdHex = eachValue.jsonPrimitive.content }
                "domain" -> { parsedDomain = eachValue.jsonPrimitive.content }
                "topic" -> { parsedTopic = eachValue.jsonPrimitive.content }
                "subtopic" -> { parsedSubtopic = eachValue.jsonPrimitive.content }
                "content" -> { /* shallow carries no content key */ parsedContent = runCatching { eachValue.jsonPrimitive.content }.getOrDefault("") }
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

    fun graphTraverseFromLabelledStart(startIdHex: String, maxDepth: Int, maxNodes: Int): List<BrainHit> {
        if (brainDown()) return emptyList()
        val startJsonArray = memoryIdArrayJson(startIdHex)
        val askJson = "{\"start\":{\"memory_id\":[$startJsonArray]},\"query_type\":\"Composite\"," +
            "\"budget\":{\"max_tokens\":$maxNodes,\"used_tokens\":0}}"
        val replyBody = dispatchOrNull(FRAME_GRAPH_TRAVERSE, askJson) ?: return emptyList()
        val parser = runCatching { wire.parseToJsonElement(replyBody).jsonArray }.getOrNull() ?: return emptyList()
        val parsed = mutableListOf<BrainHit>()
        for (valueElement in parser) {
            runCatching {
                val objectEntry = when {
                    valueElement.toString().startsWith("[", false) -> null
                    else -> valueElement.jsonObject
                }
                if (objectEntry != null) {
                    // graph hits may also be a 2-list `[id, score]`
                    val idHexValue = if (objectEntry.containsKey("0")) (objectEntry["0"]!!.jsonPrimitive.content) else null
                    val idScoreValue = if (objectEntry.containsKey("1")) (objectEntry["1"]!!.jsonPrimitive.content.toFloatOrNull()) else null
                    if (idHexValue != null && idScoreValue != null) {
                        parsed.add(BrainHit(idHexValue, idScoreValue, ""))
                    }
                } else {
                    val arrayPairEntry = valueElement.jsonArray
                    val hexId = arrayPairEntry[0].jsonPrimitive.content
                    val scoreIn = arrayPairEntry[1].jsonPrimitive.content.toFloatOrNull() ?: 0f
                    parsed.add(BrainHit(hexId, scoreIn, ""))
                }
            }
        }
        return parsed
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

    fun lastErrorOrNull(): String? = runCatching { CerebrumHost.lastError() }.getOrNull()?.let { text ->
        if (text.isBlank()) null else text
    }
}