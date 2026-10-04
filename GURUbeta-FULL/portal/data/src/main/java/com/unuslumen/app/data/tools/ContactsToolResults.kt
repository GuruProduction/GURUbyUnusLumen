// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable
data class ContactEntry(
    val id: String,
    val name: String,
    val phones: List<String>,
    val emails: List<String>
) : ToolResultData

@Serializable
data class ContactsSearchResult(
    val contacts: List<ContactEntry>,
    val error: String?
) : ToolResultData

@Serializable
data class ContactDetail(
    val id: String,
    val name: String,
    val phones: List<String>,
    val emails: List<String>,
    val addresses: List<String>
) : ToolResultData

@Serializable
data class ContactDetailResult(
    val contact: ContactDetail?,
    val error: String?
) : ToolResultData