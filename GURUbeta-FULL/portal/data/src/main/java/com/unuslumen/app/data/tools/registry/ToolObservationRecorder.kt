// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry

import android.view.accessibility.AccessibilityNodeInfo
import com.unuslumen.app.database.dao.ToolObservationDao
import com.unuslumen.app.database.entity.ToolObservationEntity
import com.unuslumen.app.util.shell.GuruAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * ToolObservationRecorder — the write side of the action-and-observation log.
 *
 * Lives beside the dispatcher, not inside a tool set, because it is dispatch
 * infrastructure: the model never has to remember to call it. [recordFailure]
 * returns immediately and does its reading and writing inside a coroutine on
 * the application scope, so a slow screen read or a locked database can never
 * delay or fail a tool call that has already finished.
 *
 * Two deliberate constraints:
 *  - Nothing here throws. Every path is runCatching, so a recorder fault is
 *    invisible to the caller by design.
 *  - The capture is bounded. [CAPTURE_NODE_BUDGET] nodes, [CAPTURE_MAX_DEPTH]
 *    levels and [CAPTURE_TIMEOUT_MS] of wall clock. When the accessibility
 *    service is down, the window is unreadable, or the read runs long, the row
 *    is still written with a null tree — the call, its arguments and its error
 *    are real and worth keeping even when the screen could not be seen.
 */
class ToolObservationRecorder(
    private val dao: ToolObservationDao,
    private val applicationScope: CoroutineScope
) {

    companion object {
        private const val CAPTURE_NODE_BUDGET = 400
        private const val CAPTURE_MAX_DEPTH = 12
        private const val CAPTURE_TIMEOUT_MS = 2_000L
        private const val MAX_NODE_TEXT_CHARS = 80
        private const val MAX_FINGERPRINT_ENTRIES = 200
        private const val TAG = "guru"

        /**
         * Argument names whose value names the node the tool acted on. Used only
         * to exclude that node from the fingerprint; the values never leave this
         * file and are never written to the row.
         */
        private val TARGET_ARG_KEYS = listOf("text", "viewId", "contentDescription", "textFilter", "target")
    }

    /**
     * What the screen looked like at capture time. Every field is nullable
     * because a window with no readable package or class is a real state.
     */
    data class ScreenSnapshot(
        val packageName: String?,
        val activityClass: String?,
        val fingerprint: String?,
        val treeJson: String?
    )

    /**
     * Read the active window once: the visible resource ids as a sorted
     * fingerprint with the target node excluded, plus the compact tree. Null
     * when the accessibility service is not connected or the read fails.
     *
     * Bounded by [CAPTURE_TIMEOUT_MS] and a node budget. Both bounds exist for
     * the same reason: the tree belongs to another process and a hostile or
     * enormous window must not turn a completed tool call into a long wait.
     */
    suspend fun captureSnapshot(targetValues: Collection<String> = emptyList()): ScreenSnapshot? =
        withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            withContext(Dispatchers.Main) { readSnapshot(targetValues) }
        }

    private fun readSnapshot(targetValues: Collection<String>): ScreenSnapshot? = runCatching {
        val service = GuruAccessibilityService.instance ?: return null
        val root = service.rootInActiveWindow ?: return null

        val excluded = targetResourceIds(root, targetValues)
        val ids = mutableListOf<String>()
        val nodes = mutableListOf<JsonObject>()
        var visited = 0

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (visited >= CAPTURE_NODE_BUDGET) return
            visited++

            val shortId = node.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            if (shortId != null && shortId !in excluded) ids.add(shortId)
            nodes.add(describeNode(node, depth, shortId))

            if (depth >= CAPTURE_MAX_DEPTH) return
            for (i in 0 until node.childCount) {
                if (visited >= CAPTURE_NODE_BUDGET) return
                node.getChild(i)?.let { walk(it, depth + 1) }
            }
        }

        walk(root, 0)

        val fingerprint = buildJsonArray {
            ids.distinct().sorted().take(MAX_FINGERPRINT_ENTRIES).forEach { add(JsonPrimitive(it)) }
        }.toString()

        ScreenSnapshot(
            packageName = root.packageName?.toString()?.takeIf { it.isNotBlank() },
            activityClass = root.className?.toString()?.takeIf { it.isNotBlank() },
            fingerprint = fingerprint,
            treeJson = JsonArray(nodes).toString()
        )
    }.getOrNull()

    /**
     * Write one failed observation off the caller's thread and return at once.
     *
     * The snapshot is taken inside the launched coroutine, never before it, so
     * the caller pays nothing for it. A null snapshot still produces a real row.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun recordFailure(
        packageName: String?,
        activityClass: String?,
        toolName: String,
        argumentsJson: String,
        resultSummary: String,
        conversationId: String?,
        durationMs: Long,
        targetValues: Collection<String> = emptyList()
    ) {
        val arguments = ToolObservationEntity.truncateArguments(argumentsJson)
        val summary = ToolObservationEntity.truncateSummary(resultSummary)
        val observedAt = System.currentTimeMillis()

        applicationScope.launch {
            try {
                val snapshot = captureSnapshot(targetValues)
                dao.insert(
                    ToolObservationEntity(
                        id = Uuid.random().toString(),
                        packageName = snapshot?.packageName ?: packageName.orEmpty(),
                        activityClass = snapshot?.activityClass ?: activityClass,
                        toolName = toolName,
                        argumentsJson = arguments,
                        resultSummary = summary,
                        success = 0,
                        screenFingerprint = snapshot?.fingerprint,
                        accessibilityTreeJson = snapshot?.treeJson,
                        conversationId = conversationId,
                        timestamp = observedAt,
                        durationMs = durationMs
                    )
                )
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Observation write skipped: ${e.message}")
            }
        }
    }

    /** Every string value in a repaired-argument map that could name the target node. */
    fun targetValuesFrom(args: Map<String, Any?>): List<String> =
        TARGET_ARG_KEYS.mapNotNull { key -> (args[key] as? String)?.takeIf { it.isNotBlank() } }

    /** Every string value in a raw-argument object that could name the target node. */
    fun targetValuesFrom(rawArgs: JsonObject): List<String> =
        TARGET_ARG_KEYS.mapNotNull { key ->
            (rawArgs[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        }

    /**
     * Resource ids of the nodes whose text, description or id matches one of the
     * tool's locator values. This is the closest honest identification of "the
     * target" that exists after the call has already run; when nothing matches,
     * the fingerprint simply carries every visible id.
     */
    private fun targetResourceIds(root: AccessibilityNodeInfo, targetValues: Collection<String>): Set<String> {
        if (targetValues.isEmpty()) return emptySet()
        val wanted = targetValues.map { it.lowercase() }.toSet()
        val result = mutableSetOf<String>()
        var visited = 0

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (visited >= CAPTURE_NODE_BUDGET) return
            visited++

            val text = node.text?.toString()?.lowercase()
            val desc = node.contentDescription?.toString()?.lowercase()
            val shortId = node.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            val matches = (text != null && text in wanted) ||
                (desc != null && desc in wanted) ||
                (shortId != null && shortId.lowercase() in wanted)
            if (matches && shortId != null) result.add(shortId)

            if (depth >= CAPTURE_MAX_DEPTH) return
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it, depth + 1) }
            }
        }

        walk(root, 0)
        return result
    }

    /**
     * One node in the compact tree form: short class, short id, capped text and
     * description, bounds, and the three booleans that decide what a selector
     * can do with the node.
     */
    private fun describeNode(node: AccessibilityNodeInfo, depth: Int, shortId: String?): JsonObject {
        val bounds = android.graphics.Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        return buildJsonObject {
            put("depth", JsonPrimitive(depth))
            put("class", JsonPrimitive(node.className?.toString()?.substringAfterLast('.') ?: ""))
            put("id", JsonPrimitive(shortId ?: ""))
            put("text", JsonPrimitive(node.text?.toString()?.take(MAX_NODE_TEXT_CHARS) ?: ""))
            put("desc", JsonPrimitive(node.contentDescription?.toString()?.take(MAX_NODE_TEXT_CHARS) ?: ""))
            put(
                "bounds",
                JsonArray(
                    listOf(
                        JsonPrimitive(bounds.left),
                        JsonPrimitive(bounds.top),
                        JsonPrimitive(bounds.right),
                        JsonPrimitive(bounds.bottom)
                    )
                )
            )
            put("clickable", JsonPrimitive(node.isClickable))
            put("editable", JsonPrimitive(node.isEditable))
            put("scrollable", JsonPrimitive(node.isScrollable))
        }
    }
}
