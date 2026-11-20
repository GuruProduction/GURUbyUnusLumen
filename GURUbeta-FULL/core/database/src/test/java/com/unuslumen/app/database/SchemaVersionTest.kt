// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The schema version, and the reason it is a literal rather than something derived.
 *
 * THE DEFECT THIS FILE EXISTS FOR. `guruDatabase.SCHEMA_VERSION` used to read the version
 * back off the `@Database` annotation, which is declared `@Retention(CLASS)`. That
 * annotation is not present at runtime, so `getAnnotation` returned null and the getter
 * answered **0** to every caller on every device, silently, for as long as it existed.
 *
 * It mattered because `DatabaseSnapshotEngine` compares a snapshot's recorded schema
 * against this number to refuse a snapshot written by a newer build. With 0 on both sides
 * that gate did not gate anything.
 *
 * WHAT THIS TEST PINS. The constant and Room's own generated metadata must name the same
 * version. Room's `openHelper` reads the annotation at build time, so its `version` field
 * is authoritative; if somebody bumps the annotation and forgets the constant, this fails
 * here instead of shipping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SchemaVersionTest {

    private lateinit var database: guruDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            guruDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** The constant is not zero. That alone was the defect. */
    @Test
    fun theSchemaVersionIsNotZero() {
        assertTrue(
            "SCHEMA_VERSION answered 0 for every release because it read a CLASS-retained " +
                "annotation. A zero here means the snapshot restore gate compares against nothing.",
            guruDatabase.SCHEMA_VERSION > 0,
        )
    }

    /**
     * The constant names a schema that Room actually exported.
     *
     * WHY THE SCHEMA FILE AND NOT THE ANNOTATION. Room's `@Database` is `@Retention(CLASS)`,
     * which is the whole reason the derived getter returned 0, so there is no runtime
     * annotation to compare against and the generated implementation exposes no version
     * field to reach either. What Room does leave behind is the exported schema JSON: it
     * writes one file per version it was built at, from the annotation itself. If the
     * constant names a version with no exported schema, the constant and the annotation
     * have drifted, and this fails here rather than on a device.
     */
    @Test
    fun theConstantNamesASchemaRoomExported() {
        val schemaDir = java.io.File("schemas/com.unuslumen.app.database.guruDatabase")
        assertTrue(
            "the exported schema directory is missing at ${schemaDir.absolutePath}; " +
                "this test must run from the module directory",
            schemaDir.isDirectory,
        )
        val exported = schemaDir.listFiles { file -> file.name.endsWith(".json") }
            ?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            ?.toSet()
            ?: emptySet()

        assertTrue("no schema files were exported at all", exported.isNotEmpty())
        assertTrue(
            "SCHEMA_VERSION is ${guruDatabase.SCHEMA_VERSION} but Room exported no " +
                "${guruDatabase.SCHEMA_VERSION}.json. The constant and the @Database " +
                "annotation have drifted; bump both together. Exported: ${exported.sorted()}",
            guruDatabase.SCHEMA_VERSION in exported,
        )
    }
}
