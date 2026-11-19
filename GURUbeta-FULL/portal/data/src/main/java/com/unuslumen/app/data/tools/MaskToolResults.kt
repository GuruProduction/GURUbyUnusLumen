// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

/** One mask in the catalogue. */
@Serializable
data class MaskEntry(
    val name: String,
    val purpose: String,
    val isDefault: Boolean,
) : ToolResultData

/**
 * The catalogue, plus the sentence that says what it is.
 *
 * `note` is a field rather than an inference because a caller reading a list of masks
 * needs to know the list is built in. A list presented without that fact reads as a
 * store the caller could add to.
 */
@Serializable
data class MaskListResult(
    val total: Int,
    val defaultMask: String,
    val masks: List<MaskEntry>,
    val note: String = "",
) : ToolResultData

@Serializable
data class MaskReadResult(
    val success: Boolean,
    val name: String,
    val purpose: String,
    val isDefault: Boolean = false,
    val error: String? = null,
) : ToolResultData
