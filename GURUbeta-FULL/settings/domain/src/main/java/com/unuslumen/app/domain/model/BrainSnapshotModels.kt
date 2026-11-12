// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

/**
 * What the Settings screen can be told about a brain snapshot.
 *
 * These are the domain's own words rather than the engine's types, because the settings
 * domain is a plain JVM module and the snapshot engine lives in the Android database
 * module. Keeping the vocabulary here means the screen never imports SQLite, and a
 * change to how snapshots are stored cannot ripple into presentation.
 */
enum class BrainSnapshotVerdict {
    /** The file decrypts, matches its manifest and is installable. */
    USABLE,

    /** The file is not one of ours: wrong marker, or shorter than a header. */
    NOT_A_SNAPSHOT,

    /** Written by a newer GURU, so installing it would drop columns this build has. */
    SCHEMA_TOO_NEW,

    /** Wrong passphrase, or the file was altered. The platform cannot tell those apart. */
    CANNOT_OPEN,

    /** Decrypted, but a file inside does not match the hash taken when it was written. */
    CORRUPT_CONTENT,

    /** SQLite says the database is damaged. */
    DAMAGED_DATABASE,

    /** Opened and readable, but it is not the database the manifest describes. */
    COUNT_MISMATCH,
}

/** The result of taking a snapshot, in terms the screen can render. */
data class BrainSnapshotOutcome(
    val succeeded: Boolean,
    /** The name the file was given, for a completion message. */
    val fileName: String?,
    val bytes: Long,
    val tables: Int,
    val totalRows: Long,
    val schemaVersion: Int,
    /** Older snapshots removed to honour the keep limit. */
    val prunedCount: Int,
    val message: String,
)

/** The result of checking a snapshot without installing it. */
data class BrainSnapshotCheck(
    val verdict: BrainSnapshotVerdict,
    val usable: Boolean,
    val createdAtMillis: Long?,
    val tables: Int,
    val totalRows: Long,
    val schemaVersion: Int,
    val message: String,
)

/** The result of restoring one. */
data class BrainRestoreOutcome(
    val succeeded: Boolean,
    val restartRequired: Boolean,
    val replacedExistingBrain: Boolean,
    val tables: Int,
    val totalRows: Long,
    val schemaVersion: Int,
    val message: String,
)
