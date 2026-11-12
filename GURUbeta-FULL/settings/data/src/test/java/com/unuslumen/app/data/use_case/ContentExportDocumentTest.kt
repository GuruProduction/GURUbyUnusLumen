// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.use_case

import com.unuslumen.app.data.model.JsonBackupData
import com.unuslumen.app.domain.exception.BackupDataException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the import accepts, and what it refuses by name.
 *
 * THE DEFECT THIS BRANCH FIXES WAS A SILENT SUCCESS, SO MOST OF THESE ARE REFUSALS.
 *
 * The import decoded straight into [JsonBackupData], whose five lists all default to empty.
 * Every valid JSON object therefore decoded: a settings dump, a contacts export, the manifest
 * inside a sealed brain snapshot. Nothing was written, and the screen said the import had
 * succeeded. Somebody could reasonably believe their journal was restored and delete the file
 * it came from.
 *
 * A test that imports a good file passes against that bug, because the bug is a bad file being
 * accepted. So the refusals are what is asserted here.
 *
 * NO ROBOLECTRIC AND NO DATABASE. The decision is a pure function of a string, which is why it
 * was extracted into its own object: it runs in milliseconds and cannot be flaky.
 */
class ContentExportDocumentTest {

    @Test
    fun `a file that is not JSON at all is refused as unreadable`() {
        val thrown = runCatching {
            ContentExportDocument.parse("this is not JSON at all {{{")
        }.exceptionOrNull()

        assertEquals(BackupDataException.CouldNotReadFile, thrown)
    }

    @Test
    fun `a valid JSON object with none of the export keys is refused as the wrong file`() {
        // The exact shape the old code accepted and reported as a successful import.
        val thrown = runCatching {
            ContentExportDocument.parse("""{"theme":"dark","notifications":true,"lastScreen":"lobby"}""")
        }.exceptionOrNull()

        assertEquals(
            "the wrong file must be named as the wrong file, not as unreadable",
            BackupDataException.NotAContentExport,
            thrown,
        )
    }

    @Test
    fun `a JSON array is refused rather than decoded into an empty export`() {
        val thrown = runCatching { ContentExportDocument.parse("[1,2,3]") }.exceptionOrNull()

        assertEquals(BackupDataException.NotAContentExport, thrown)
    }

    @Test
    fun `a JSON string literal is refused`() {
        val thrown = runCatching {
            ContentExportDocument.parse(""""just a string"""")
        }.exceptionOrNull()

        assertEquals(BackupDataException.NotAContentExport, thrown)
    }

    @Test
    fun `every serialised name the import looks for is the name the export writes`() {
        // The check that separates a real export from the wrong file is a set of string keys.
        // If JsonBackupData ever renames one, the import would start refusing files this app
        // wrote, and that failure would look exactly like a corrupt backup. This pins the two
        // together so a rename is a test failure rather than a support ticket.
        //
        // The encoder is the writer's own configuration, with defaults ON. Using the default
        // encoder here would serialise an all-empty export as "{}", which is exactly the shape
        // the import refuses, and this test would then be measuring the bug rather than the
        // contract.
        val emptyExport = JsonBackupData(
            notes = emptyList(),
            noteFolders = emptyList(),
            tasks = emptyList(),
            journal = emptyList(),
            bookmarks = emptyList(),
        )
        val keys = ExportJsonDataUseCaseImpl.EXPORT_JSON
            .encodeToJsonElement(JsonBackupData.serializer(), emptyExport)
            .jsonObject
            .keys

        assertEquals(
            "the import's key set must be exactly the export's serialised keys",
            keys,
            ContentExportDocument.CONTENT_KEYS,
        )
    }

    @Test
    fun `the writer and the reader agree on an export of nothing at all`() {
        // The writer emits all five keys even when every list is empty, and the reader accepts
        // that. This is the round trip that a defaults-omitted encoder breaks: it writes "{}",
        // which the reader correctly refuses, so the app would produce a file it cannot import.
        val emptyExport = JsonBackupData(
            notes = emptyList(),
            noteFolders = emptyList(),
            tasks = emptyList(),
            journal = emptyList(),
            bookmarks = emptyList(),
        )
        // The writer's own encoder, so this measures the real file rather than a copy of it.
        val written = ExportJsonDataUseCaseImpl.EXPORT_JSON
            .encodeToString(JsonBackupData.serializer(), emptyExport)

        assertTrue(
            "an export of nothing at all must still be a file this app can import: $written",
            ContentExportDocument.isContentExport(written),
        )
    }

    @Test
    fun `an export where the owner chose only one kind of content is accepted`() {
        // A bookmarks-only export carries the other four as empty arrays. Requiring all five
        // keys would refuse a file this app wrote, which is the worst false negative available
        // here, so one recognised key has to be enough.
        val bookmarksOnly =
            """{"notes":[],"noteFolders":[],"tasks":[],"journal":[],"bookmarks":[]}"""

        assertTrue(
            "a real export with only one list populated must be accepted",
            ContentExportDocument.isContentExport(bookmarksOnly),
        )
    }

    @Test
    fun `a document carrying one recognised key and unknown extras is accepted`() {
        // Forward compatibility: a later build may add a list this one does not know. Refusing
        // its export would make an old GURU unable to read a new one's file for no good reason.
        val withExtra = """{"notes":[],"futureThings":[{"x":1}]}"""

        assertTrue(ContentExportDocument.isContentExport(withExtra))
    }

    @Test
    fun `a real export parses into the content it carries`() {
        val export = """
            {
              "notes": [],
              "noteFolders": [],
              "tasks": [],
              "journal": [],
              "bookmarks": []
            }
        """.trimIndent()

        val parsed = ContentExportDocument.parse(export)

        assertEquals(0, parsed.notes.size)
        assertEquals(0, parsed.bookmarks.size)
    }

    @Test
    fun `a claimed export with a wrongly typed list is reported as unreadable, not as a wrong file`() {
        // It has our key, so it is one of ours in shape, but its content does not match. That is
        // damage rather than somebody picking the wrong file, and the two need different words.
        val malformed = """{"notes":"this should be an array","bookmarks":[]}"""

        val thrown = runCatching { ContentExportDocument.parse(malformed) }.exceptionOrNull()

        assertEquals(BackupDataException.CouldNotReadFile, thrown)
    }

    @Test
    fun `an empty object is refused`() {
        assertFalse(ContentExportDocument.isContentExport("{}"))
    }

    @Test
    fun `whitespace around a real export does not change the answer`() {
        val padded = "\n\n   {\"notes\":[]}   \n\n"

        assertTrue(ContentExportDocument.isContentExport(padded))
    }
}
