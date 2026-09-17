package com.unuslumen.app.data.tools

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class ContactsToolExecutor(
    private val context: Context
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            ContactsToolDefinitions.SEARCH_CONTACTS -> searchContacts(args)
            ContactsToolDefinitions.GET_CONTACT -> getContact(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }

    private suspend fun searchContacts(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val query = args["query"] as? String
            ?: return@withContext ToolExecutionResult.error("Missing 'query' parameter")
        val limit = (args["limit"] as? Number)?.toInt() ?: 20

        try {
            val results = mutableListOf<ContactEntry>()
            val uri = ContactsContract.Contacts.CONTENT_URI
            val selection = "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
            val cursor: Cursor? = context.contentResolver.query(
                uri, null, selection, arrayOf("%$query%"),
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
            )
            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < limit) {
                    val id = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)) ?: "Unknown"
                    val hasPhone = it.getInt(it.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)) > 0
                    val phones = if (hasPhone) getContactPhones(id) else emptyList()
                    val emails = getContactEmails(id)
                    results.add(ContactEntry(id = id, name = name, phones = phones, emails = emails))
                    count++
                }
            }
            val result = ContactsSearchResult(contacts = results, error = null)
            ToolExecutionResult.success(result, json.encodeToString(ContactsSearchResult.serializer(), result))
        } catch (e: SecurityException) {
            val result = ContactsSearchResult(contacts = emptyList(), error = "Contacts permission not granted. Enable in Settings > Apps > guru > Permissions > Contacts.")
            ToolExecutionResult.success(result, json.encodeToString(ContactsSearchResult.serializer(), result))
        } catch (e: Exception) {
            val result = ContactsSearchResult(contacts = emptyList(), error = "Contacts search failed: ${e.message}")
            ToolExecutionResult.success(result, json.encodeToString(ContactsSearchResult.serializer(), result))
        }
    }

    private suspend fun getContact(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val contactId = args["contactId"] as? String
            ?: return@withContext ToolExecutionResult.error("Missing 'contactId' parameter")

        try {
            val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId)
            val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)) ?: "Unknown"
                    val phones = getContactPhones(contactId)
                    val emails = getContactEmails(contactId)
                    val addresses = getContactAddresses(contactId)
                    val result = ContactDetailResult(
                        contact = ContactDetail(id = contactId, name = name, phones = phones, emails = emails, addresses = addresses),
                        error = null
                    )
                    return@withContext ToolExecutionResult.success(result, json.encodeToString(ContactDetailResult.serializer(), result))
                }
            }
            val result = ContactDetailResult(contact = null, error = "Contact not found: $contactId")
            ToolExecutionResult.success(result, json.encodeToString(ContactDetailResult.serializer(), result))
        } catch (e: Exception) {
            val result = ContactDetailResult(contact = null, error = "Error: ${e.message}")
            ToolExecutionResult.success(result, json.encodeToString(ContactDetailResult.serializer(), result))
        }
    }

    private fun getContactPhones(contactId: String): List<String> {
        val phones = mutableListOf<String>()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId), null
        )
        cursor?.use {
            while (it.moveToNext()) {
                val number = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                phones.add(number)
            }
        }
        return phones
    }

    private fun getContactEmails(contactId: String): List<String> {
        val emails = mutableListOf<String>()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI, null,
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
            arrayOf(contactId), null
        )
        cursor?.use {
            while (it.moveToNext()) {
                val email = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS))
                emails.add(email)
            }
        }
        return emails
    }

    private fun getContactAddresses(contactId: String): List<String> {
        val addresses = mutableListOf<String>()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI, null,
            "${ContactsContract.CommonDataKinds.StructuredPostal.CONTACT_ID} = ?",
            arrayOf(contactId), null
        )
        cursor?.use {
            while (it.moveToNext()) {
                val addr = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS))
                addresses.add(addr)
            }
        }
        return addresses
    }
}