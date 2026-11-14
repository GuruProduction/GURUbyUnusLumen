// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * ToolResultOutcome — did a tool result actually report a failure?
 *
 * WHY THIS IS ITS OWN OBJECT. The write path decided success with
 *
 *     !truncatedResult.contains("\"success\":false") && !truncatedResult.contains("\"success\": false")
 *
 * which is a search for one spelling of one field. A tool whose failure payload
 * is shaped differently is recorded as a success, and that is not hypothetical:
 * five `createJournalEntry` calls returning `{"error":"Invalid mood: EXCITED"}`
 * are stored with `success = 1` in the owner's database.
 *
 * A substring test also fails in the other direction. Any result that merely
 * mentions the text `"success":false` — a config file being read, a log being
 * echoed, a JSON schema being described — is recorded as a failure it never
 * reported. Both directions are the same defect: the write path was reading a
 * string for meaning instead of reading the document.
 *
 * So this parses. Three independent pieces of evidence count as a reported
 * failure, and any one of them is enough:
 *
 *  1. a boolean `success` field that is false;
 *  2. a non-blank `error` field, which is how the majority of GURU's tools
 *     report a failure that is a real answer rather than an exception;
 *  3. a boolean `ok` field that is false, the third spelling in use.
 *
 * A string-typed `success` field is read too, because some tools serialise the
 * flag into a text field, but only when it says exactly "false" — a string that
 * merely CONTAINS the word is not a claim.
 *
 * Everything here is pure, so the decision is testable on the JVM without a
 * database or a device.
 */
object ToolResultOutcome {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Whether [rawJson] reports a failure.
     *
     * [transportFailed] is the dispatcher's own knowledge, which outranks the
     * payload: a call whose executor threw has no payload worth reading, and the
     * absence of an error field in an empty body is not evidence of success.
     */
    fun isFailure(rawJson: String, transportFailed: Boolean = false): Boolean {
        if (transportFailed) return true
        if (rawJson.isBlank()) return false

        val root = runCatching {
            json.parseToJsonElement(rawJson) as? JsonObject
        }.getOrNull() ?: return false

        if (booleanFieldIsFalse(root, "success")) return true
        if (booleanFieldIsFalse(root, "ok")) return true
        if (stringFieldSaysFalse(root, "success")) return true

        // A non-blank error field is the failure shape most GURU tools use.
        // Checked last so a payload that carries both an explicit success=true
        // and a stale error string is decided by the explicit flag first.
        if (hasReportedError(root)) return true

        return false
    }

    private fun booleanFieldIsFalse(root: JsonObject, name: String): Boolean {
        val primitive = root[name] as? JsonPrimitive ?: return false
        if (primitive.isString) return false
        return primitive.content.equals("false", ignoreCase = true)
    }

    private fun stringFieldSaysFalse(root: JsonObject, name: String): Boolean {
        val primitive = root[name] as? JsonPrimitive ?: return false
        if (!primitive.isString) return false
        return primitive.content.equals("false", ignoreCase = true)
    }

    private fun hasReportedError(root: JsonObject): Boolean {
        // An explicit success=true wins over an error string beside it: a tool
        // that reports both has said what it thinks, and the flag is the
        // deliberate statement while the string is often leftover context.
        if (explicitlySucceeded(root)) return false
        val primitive = root["error"] as? JsonPrimitive ?: return false
        if (!primitive.isString) {
            // A null error is the common "no error" encoding and is not evidence.
            return primitive.content.equals("false", ignoreCase = true)
        }
        val text = primitive.content.trim()
        if (text.isEmpty()) return false
        // The literal words tools use to mean "there was no error".
        return !text.equals("null", ignoreCase = true) &&
            !text.equals("none", ignoreCase = true)
    }

    private fun explicitlySucceeded(root: JsonObject): Boolean {
        val primitive = root["success"] as? JsonPrimitive ?: return false
        return primitive.content.equals("true", ignoreCase = true)
    }
}
