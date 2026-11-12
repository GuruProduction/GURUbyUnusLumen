// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.BrainRestoreOutcome
import com.unuslumen.app.domain.model.BrainSnapshotCheck
import com.unuslumen.app.domain.model.BrainSnapshotOutcome

/**
 * The three operations the Settings screen needs, as interfaces.
 *
 * WHY INTERFACES AND AN IMPLEMENTATION RATHER THAN A DIRECT CALL. The implementation needs
 * SQLite, the content resolver and a Context, and none of those exist in a plain JVM
 * module. Declaring the contract here lets the screen depend on the shape of the work
 * while the Android half stays in the data module, which is the same split the existing
 * export and import use cases already use.
 *
 * The tree URI appears rather than a File path because Settings hands the owner a folder
 * through the Storage Access Framework. A SAF folder has no filesystem path, so the
 * snapshot's bytes are produced by the engine and written through the resolver. That is
 * also why nothing here mentions a File.
 */
interface CreateBrainSnapshotUseCase {
    /**
     * Take an encrypted snapshot into [folderUri] and verify it before keeping it.
     *
     * [keep] is the number of snapshots to retain in that folder, oldest deleted beyond it.
     * It defaults to five: enough that a mistake discovered weeks later still has a
     * snapshot behind it, few enough that a phone does not quietly fill with copies of one
     * brain. Only files this app named are ever deleted.
     */
    suspend operator fun invoke(
        folderUri: String,
        passphrase: String,
        keep: Int = 5,
    ): BrainSnapshotOutcome
}

interface CheckBrainSnapshotUseCase {
    /** Explain whether a snapshot can be restored, without installing it. */
    suspend operator fun invoke(fileUri: String, passphrase: String): BrainSnapshotCheck
}

interface RestoreBrainSnapshotUseCase {
    /**
     * Replace this device's brain with a snapshot's contents.
     *
     * Always reports that a restart is required, because the running process holds the old
     * database open. A restore that claims to be live while the old handle is in use would
     * be the lie this whole feature exists to remove.
     */
    suspend operator fun invoke(fileUri: String, passphrase: String): BrainRestoreOutcome
}
