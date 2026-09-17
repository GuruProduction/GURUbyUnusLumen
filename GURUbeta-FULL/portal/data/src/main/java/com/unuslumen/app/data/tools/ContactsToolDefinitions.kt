package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object ContactsToolDefinitions : ToolSetRegistration {

    const val SEARCH_CONTACTS = "searchContacts"
    const val GET_CONTACT = "getContact"

    override val definitions = listOf(
        ToolDefinition(
            name = SEARCH_CONTACTS,
            description = "Search contacts by name, email, or phone number. Use when your human says 'message Sarah' or 'call my mum' — you need to find the right contact first.",
            category = "contacts",
            parameters = listOf(
                ToolParameter("query", ToolParameterType.String, required = true, description = "Search query — name, email, or phone number"),
                ToolParameter("limit", ToolParameterType.Integer, required = false, description = "Maximum results, default 20")
            ),
            permissions = listOf("android.permission.READ_CONTACTS")
        ),
        ToolDefinition(
            name = GET_CONTACT,
            description = "Get full details for a specific contact by ID. Use after searchContacts to get complete information.",
            category = "contacts",
            parameters = listOf(
                ToolParameter("contactId", ToolParameterType.String, required = true, description = "Contact ID from searchContacts results")
            ),
            permissions = listOf("android.permission.READ_CONTACTS")
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = ContactsToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}