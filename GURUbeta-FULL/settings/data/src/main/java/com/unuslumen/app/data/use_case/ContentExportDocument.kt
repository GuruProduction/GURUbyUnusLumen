// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.use_case

import com.unuslumen.app.data.model.JsonBackupData
import com.unuslumen.app.domain.exception.BackupDataException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Decides whether a JSON document is a GURU content export, and reads it if it is.
 *
 * WHY THIS IS ITS OWN OBJECT. The import used to decode straight into [JsonBackupData], whose
 * five lists all default to empty, so every valid JSON object decoded: a settings dump, a
 * contacts export, the manifest from inside a sealed brain snapshot. Nothing was written and the
 * screen reported a successful import, which could lead somebody to delete the file they thought
 * had just been restored.
 *
 * The decision that fixes that is pure: given a string, is this one of ours. Keeping it here
 * rather than inline in the use case means it can be proved with a string and an assertion, with
 * no database, no content resolver and no device. The use case keeps the parts that genuinely
 * need those.
 */
object ContentExportDocument {

    /**
     * The five names a content export carries.
     *
     * Taken from [JsonBackupData]'s own @SerialName values and pinned to them by a test. If one
     * is renamed there and not here, every real export would be refused as the wrong file, and
     * that failure would look exactly like a corrupt backup.
     */
    val CONTENT_KEYS: Set<String> = setOf(
        "notes",
        "noteFolders",
        "tasks",
        "journal",
        "bookmarks",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse [text] as a content export.
     *
     * ONE recognised key is enough to accept. An export where the owner chose only bookmarks
     * carries the other four as empty arrays, and requiring all five would refuse a file this
     * app wrote, which is the worst false negative available here.
     *
     * Throws [BackupDataException.CouldNotReadFile] when the text is not JSON at all, and
     * [BackupDataException.NotAContentExport] when it is JSON of some other shape. The two are
     * kept apart because the actions differ: pick again, versus pick a different kind of file.
     */
    fun parse(text: String): JsonBackupData {
        val document = try {
            json.parseToJsonElement(text)
        } catch (_: Exception) {
            throw BackupDataException.CouldNotReadFile
        }

        val root = document as? JsonObject
            ?: throw BackupDataException.NotAContentExport

        // A root object with none of the export's keys is a different document, however valid
        // it is as JSON. This is the check the old code did not make.
        if (CONTENT_KEYS.none { it in root.keys }) {
            throw BackupDataException.NotAContentExport
        }

        return try {
            json.decodeFromJsonElement(JsonBackupData.serializer(), root)
        } catch (_: Exception) {
            // It claimed to be an export and its content did not match the shape, which is
            // damage rather than a wrong file.
            throw BackupDataException.CouldNotReadFile
        }
    }

    /**
     * Whether [text] would be accepted, without throwing.
     *
     * For diagnostics and tests. The import path calls [parse] and lets the exception carry the
     * specific reason, because "why" is the part that reaches the person holding the file.
     */
    fun isContentExport(text: String): Boolean = runCatching { parse(text) }.isSuccess
}
