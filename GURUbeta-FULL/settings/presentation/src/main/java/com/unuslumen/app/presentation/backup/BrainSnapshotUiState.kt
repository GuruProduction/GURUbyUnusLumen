// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.backup

/**
 * What the brain snapshot card renders.
 *
 * [busy] covers both a snapshot being taken and a restore being applied, because both are
 * one long operation on a file the owner picked and both must disable the buttons while
 * they run. [succeeded] is separate from [message] because a message exists on every
 * completed operation while the colour needs to say whether it worked.
 */
data class BrainSnapshotUiState(
    val busy: Boolean = false,
    val succeeded: Boolean = false,
    val message: String? = null,
) {
    companion object {
        val Idle = BrainSnapshotUiState()
    }
}
