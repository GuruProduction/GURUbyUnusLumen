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