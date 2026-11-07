// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import android.accounts.AccountManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.registry.permission.DomainToolModule
import com.unuslumen.app.data.tools.registry.permission.PermissionGrants
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.reflect.KClass

/**
 * The personal-data domain: the call log GURU can read and write, and the address
 * book it can search, create in and correct.
 *
 * WHY THESE EIGHT TOOLS ARE ONE DOMAIN. They are the three stores a person treats as
 * their own record of who they know and who called: the call log, the contacts
 * database, and the account list that says what is already signed in. Every one of
 * them is a runtime permission a person grants by name, and every one of them fails
 * in the same shape if the grant is missing — which is why they share one refusal
 * helper rather than eight hand-written messages.
 *
 * WHAT IS NOT HERE. Reading an SMS thread and placing a call live in the phone
 * domain, because they are governed by a different permission group (SMS and Phone)
 * and the person is sent to a different Settings screen to grant them. Splitting on
 * the grant is what keeps each refusal pointing at the right page.
 *
 * THE RULE THIS FILE EXISTS TO KEEP. "No calls matched" and "GURU is not allowed to
 * see your calls" are different facts, and a person acts differently on each. Every
 * read below returns a refusal naming the grant when the grant is what stopped it,
 * and an empty list only when the query genuinely ran and genuinely found nothing.
 */

@Serializable
data class CallLogEntry(
    val id: Long,
    val number: String,
    val cachedName: String? = null,
    /** INCOMING, OUTGOING, MISSED, REJECTED, VOICEMAIL, BLOCKED or UNKNOWN. */
    val type: String,
    val dateMillis: Long,
    val durationSeconds: Long,
) : ToolResultData

@Serializable
data class CallLogReadResult(
    val success: Boolean,
    val entries: List<CallLogEntry>,
    val count: Int,
    /** One sentence stating exactly what was queried, so the count is never ambiguous. */
    val scope: String,
    val error: String? = null,
) : ToolResultData

@Serializable
data class CallLogWriteResult(
    val success: Boolean,
    val id: Long? = null,
    val uri: String? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class ContactEntry(
    val id: Long,
    val displayName: String? = null,
    val phoneNumbers: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
) : ToolResultData

@Serializable
data class ContactsListResult(
    val success: Boolean,
    val contacts: List<ContactEntry>,
    val count: Int,
    val scope: String,
    val error: String? = null,
) : ToolResultData

@Serializable
data class ContactWriteResult(
    val success: Boolean,
    val contactId: Long? = null,
    val lookupUri: String? = null,
    val updated: Boolean = false,
    /** What actually changed, read back from the provider rather than assumed. */
    val changedFields: List<String> = emptyList(),
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class ContactPhotoResult(
    val success: Boolean,
    val byteCount: Long = 0,
    val mimeType: String? = null,
    val writtenTo: String? = null,
    /** Real size on disk after writing, which is the only proof the write landed. */
    val writtenByteCount: Long? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class AccountEntry(
    val name: String,
    val type: String,
) : ToolResultData

@Serializable
data class AccountsListResult(
    val success: Boolean,
    val accounts: List<AccountEntry>,
    val count: Int,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

/**
 * The call log, the address book and the account list, as one set of declarations.
 *
 * EVERY TOOL DECLARES ITS REAL PERMISSION. The dispatcher refuses before the
 * executor runs when a declared permission is missing, and the sentence names that
 * permission's Settings group. A tool that declared nothing would run, throw
 * underneath, and produce a worse sentence than a refusal.
 */
object CallLogContactsDomainDefinitions : ToolSetRegistration {
    const val CALL_LOG_READ = "callLogRead"
    const val CALL_LOG_WRITE = "callLogWrite"
    const val CONTACTS_LIST = "contactsList"
    const val CONTACT_CREATE = "contactCreate"
    const val CONTACTS_UPDATE = "contactsUpdate"
    const val CONTACTS_DELETE = "contactsDelete"
    const val CONTACT_PHOTO = "contactPhoto"
    const val ACCOUNTS_LIST = "accountsList"

    override val definitions = listOf(
        ToolDefinition(
            name = CALL_LOG_READ,
            description = "Read the phone's call log: who called, when, whether it was answered and " +
                "how long it lasted. Filter to one number or a time window. This reads the device's " +
                "own call history, which is a different store from GURU's conversation list. A " +
                "refusal here means the Call log grant is missing, which is a different answer from " +
                "an empty log and must be reported as such.",
            category = "call_log",
            parameters = listOf(
                ToolParameter(
                    "limit", ToolParameterType.Integer, false,
                    "How many of the most recent entries to return, 1 to 500. Default 50."
                ),
                ToolParameter(
                    "sinceMillis", ToolParameterType.Long, false,
                    "Only entries at or after this epoch millisecond. Default 0, meaning no lower bound."
                ),
                ToolParameter(
                    "number", ToolParameterType.String, false,
                    "Restrict to one phone number, matched exactly as the log stores it."
                ),
            ),
            permissions = listOf(TeamThreePermissions.READ_CALL_LOG.permission),
        ),
        ToolDefinition(
            name = CALL_LOG_WRITE,
            description = "Insert an entry into the phone's call log. This writes into the record the " +
                "person sees in their own dialler, so the entry looks exactly like a call that " +
                "happened. Use it to backfill a call GURU placed on a SIM it could not log " +
                "automatically, never to fabricate history.",
            category = "call_log",
            parameters = listOf(
                ToolParameter("number", ToolParameterType.String, true, "The phone number the entry records."),
                ToolParameter(
                    "type", ToolParameterType.Enum, true,
                    "Which kind of entry to write.", listOf("INCOMING", "OUTGOING", "MISSED")
                ),
                ToolParameter(
                    "durationSeconds", ToolParameterType.Integer, false,
                    "How long the call lasted in seconds. Default 0."
                ),
                ToolParameter(
                    "timestampMillis", ToolParameterType.Long, false,
                    "When the call happened, epoch milliseconds. Default now."
                ),
            ),
            permissions = listOf(TeamThreePermissions.WRITE_CALL_LOG.permission),
        ),
        ToolDefinition(
            name = CONTACTS_LIST,
            description = "Search the device address book for people and their numbers and email " +
                "addresses. Filter by a partial display name, or require a phone number or an email " +
                "address to be present. This is the person's real contact store, so a name it returns " +
                "can be used to place a call or send a message. A refusal means the Contacts grant is " +
                "missing, which is a different answer from an address book that genuinely has no match.",
            category = "contacts",
            parameters = listOf(
                ToolParameter(
                    "query", ToolParameterType.String, false,
                    "Case-insensitive partial match on display name. Omit for every contact."
                ),
                ToolParameter("limit", ToolParameterType.Integer, false, "1 to 500. Default 50."),
                ToolParameter(
                    "withPhoneOnly", ToolParameterType.Boolean, false,
                    "Only contacts that have at least one phone number. Default false."
                ),
                ToolParameter(
                    "withEmailOnly", ToolParameterType.Boolean, false,
                    "Only contacts that have at least one email address. Default false."
                ),
            ),
            permissions = listOf(TeamThreePermissions.READ_CONTACTS.permission),
        ),
        ToolDefinition(
            name = CONTACT_CREATE,
            description = "Create a contact in the device address book, or update the one that already " +
                "has exactly the same display name when updateIfExists is true. Requires both the " +
                "read and write Contacts grants, because deciding whether a person already exists " +
                "means reading the store first. Returns the new contact id and the lookup uri other " +
                "apps use to resolve it.",
            category = "contacts",
            parameters = listOf(
                ToolParameter("displayName", ToolParameterType.String, true, "The name to store."),
                ToolParameter("phone", ToolParameterType.String, false, "An optional phone number."),
                ToolParameter("email", ToolParameterType.String, false, "An optional email address."),
                ToolParameter(
                    "updateIfExists", ToolParameterType.Boolean, false,
                    "When a contact already has exactly this display name, add to it instead of " +
                        "creating a duplicate. Default false."
                ),
            ),
            permissions = listOf(
                TeamThreePermissions.READ_CONTACTS.permission,
                TeamThreePermissions.WRITE_CONTACTS.permission,
            ),
        ),
        ToolDefinition(
            name = CONTACTS_UPDATE,
            description = "Change a contact's display name, phone number or email address by its id. " +
                "Reports which fields genuinely changed by reading the store back afterwards, and " +
                "reports changed=false when the values already matched, because a write that " +
                "reports the values it sent is not evidence that anything happened.",
            category = "contacts",
            parameters = listOf(
                ToolParameter("contactId", ToolParameterType.Long, true, "The contact id to change."),
                ToolParameter("displayName", ToolParameterType.String, false, "A new display name."),
                ToolParameter("phone", ToolParameterType.String, false, "A new phone number, replacing the first one."),
                ToolParameter("email", ToolParameterType.String, false, "A new email address, replacing the first one."),
            ),
            permissions = listOf(
                TeamThreePermissions.READ_CONTACTS.permission,
                TeamThreePermissions.WRITE_CONTACTS.permission,
            ),
        ),
        ToolDefinition(
            name = CONTACTS_DELETE,
            description = "Delete a contact from the device address book by its id. This cannot be " +
                "undone and the contact is not recoverable from the phone afterwards. It refuses " +
                "unless confirm is true, and the refusal names exactly what is missing rather than " +
                "silently doing nothing.",
            category = "contacts",
            parameters = listOf(
                ToolParameter("contactId", ToolParameterType.Long, true, "The contact id to delete."),
                ToolParameter(
                    "confirm", ToolParameterType.Boolean, true,
                    "Must be true. A false or absent value refuses without deleting anything."
                ),
            ),
            permissions = listOf(TeamThreePermissions.WRITE_CONTACTS.permission),
        ),
        ToolDefinition(
            name = CONTACT_PHOTO,
            description = "Read the photo stored against a contact and report its byte size, plus the " +
                "mime type when the provider states one. Binary image data is never returned into the " +
                "conversation. Give writeToPath to have the image written there; the byte count is then " +
                "measured on disk afterwards, which is the only proof the file actually landed. A " +
                "refusal means the Contacts grant is missing; a contact with no photo is reported as " +
                "the contact having no picture, which is a different fact.",
            category = "contacts",
            parameters = listOf(
                ToolParameter("contactId", ToolParameterType.Long, true, "The contact id whose photo to read."),
                ToolParameter(
                    "writeToPath", ToolParameterType.String, false,
                    "An absolute file path to write the photo to. Omit to report the size only."
                ),
            ),
            permissions = listOf(TeamThreePermissions.READ_CONTACTS.permission),
        ),
        ToolDefinition(
            name = ACCOUNTS_LIST,
            description = "List the accounts registered on this phone, such as the Google account or a " +
                "work profile, along with the type of each one. This is what tells GURU what is " +
                "already connected before it offers to connect something. It returns the account " +
                "name and type only: AccountManager exposes no public call that lists an account's " +
                "stored keys, so none are claimed here. Account names are visible to any app holding " +
                "the Contacts permission group, which is worth saying out loud rather than " +
                "discovering later.",
            category = "contacts",
            parameters = listOf(
                ToolParameter(
                    "typeFilter", ToolParameterType.String, false,
                    "Only accounts of this exact type, for example com.google. Omit for every account."
                ),
            ),
            permissions = listOf(TeamThreePermissions.GET_ACCOUNTS.permission),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = CallLogContactsDomainExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

/**
 * The executor behind all eight tools.
 *
 * EVERY REFUSAL COMES FROM PermissionGrants AND IS RETURNED FIRST. Nothing here
 * catches SecurityException into a success payload: a grant that disappeared
 * mid-call is reported as the grant, because that is what it is.
 */
class CallLogContactsDomainExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            CallLogContactsDomainDefinitions.CALL_LOG_READ -> callLogRead(args)
            CallLogContactsDomainDefinitions.CALL_LOG_WRITE -> callLogWrite(args)
            CallLogContactsDomainDefinitions.CONTACTS_LIST -> contactsList(args)
            CallLogContactsDomainDefinitions.CONTACT_CREATE -> contactCreate(args)
            CallLogContactsDomainDefinitions.CONTACTS_UPDATE -> contactsUpdate(args)
            CallLogContactsDomainDefinitions.CONTACTS_DELETE -> contactsDelete(args)
            CallLogContactsDomainDefinitions.CONTACT_PHOTO -> contactPhoto(args)
            CallLogContactsDomainDefinitions.ACCOUNTS_LIST -> accountsList(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    // ------------------------------------------------------------------
    // Call log
    // ------------------------------------------------------------------

    private suspend fun callLogRead(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.READ_CALL_LOG)?.let { return it }

        val limit = ((args["limit"] as? Number)?.toInt() ?: 50).coerceIn(1, 500)
        val sinceMillis = (args["sinceMillis"] as? Number)?.toLong() ?: 0L
        val number = args["number"] as? String

        // The selection is built as a pair of fragments rather than one concatenated
        // string so a number containing a quote cannot break out of the clause. The
        // provider takes the value as a bound argument.
        val whereParts = mutableListOf<String>()
        val whereArgs = mutableListOf<String>()
        if (sinceMillis > 0L) {
            whereParts.add("${CallLog.Calls.DATE} >= ?")
            whereArgs.add(sinceMillis.toString())
        }
        if (!number.isNullOrBlank()) {
            whereParts.add("${CallLog.Calls.NUMBER} = ?")
            whereArgs.add(number)
        }
        val selection = whereParts.takeIf { it.isNotEmpty() }?.joinToString(" AND ")

        val scope = buildString {
            append("Read the device call log, newest first, capped at $limit entries")
            if (sinceMillis > 0) append(" from $sinceMillis onwards")
            if (!number.isNullOrBlank()) append(" matching the number $number")
            append(". This is the phone's own call history, not a GURU conversation record.")
        }

        // A null here is a provider that did not answer, which is neither "no rows"
        // nor "no permission". Returning it as an empty list with success = true was
        // the defect this batch exists to remove, so it is reported as its own failure.
        val entries: List<CallLogEntry>? = withContext(Dispatchers.IO) {
            val collected = mutableListOf<CallLogEntry>()
            val cursor = runCatching {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(
                        CallLog.Calls._ID,
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.CACHED_NAME,
                        CallLog.Calls.TYPE,
                        CallLog.Calls.DATE,
                        CallLog.Calls.DURATION,
                    ),
                    selection,
                    whereArgs.takeIf { it.isNotEmpty() }?.toTypedArray(),
                    "${CallLog.Calls.DATE} DESC",
                )
            }.getOrNull() ?: return@withContext null

            cursor.use { c ->
                val idIndex = c.getColumnIndex(CallLog.Calls._ID)
                val numberIndex = c.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIndex = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIndex = c.getColumnIndex(CallLog.Calls.TYPE)
                val dateIndex = c.getColumnIndex(CallLog.Calls.DATE)
                val durationIndex = c.getColumnIndex(CallLog.Calls.DURATION)
                while (c.moveToNext() && collected.size < limit) {
                    collected.add(
                        CallLogEntry(
                            id = if (idIndex >= 0) c.getLong(idIndex) else -1L,
                            number = if (numberIndex >= 0) c.getString(numberIndex).orEmpty() else "",
                            cachedName = if (nameIndex >= 0) c.getString(nameIndex) else null,
                            type = callTypeWord(if (typeIndex >= 0) c.getInt(typeIndex) else 0),
                            dateMillis = if (dateIndex >= 0) c.getLong(dateIndex) else 0L,
                            durationSeconds = if (durationIndex >= 0) c.getLong(durationIndex) else 0L,
                        )
                    )
                }
            }
            collected
        }

        if (entries == null) {
            val r = CallLogReadResult(
                success = false,
                entries = emptyList(),
                count = 0,
                scope = scope,
                error = "The call log provider did not answer the query, so GURU cannot say whether " +
                    "any calls matched. This is neither an empty log nor a missing grant.",
            )
            return emitPayload(json, json.encodeToString(CallLogReadResult.serializer(), r), r, r.success, r.error)
        }

        val r = CallLogReadResult(
            success = true,
            entries = entries,
            count = entries.size,
            scope = scope,
        )
        return emitPayload(json, json.encodeToString(CallLogReadResult.serializer(), r), r, r.success, r.error)
    }

    private suspend fun callLogWrite(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.WRITE_CALL_LOG)?.let { return it }

        val number = (args["number"] as? String)?.trim()
        if (number.isNullOrBlank()) {
            val message = "This call needs the 'number' of the entry to write."
            return failure(
                CallLogWriteResult.serializer(),
                CallLogWriteResult(success = false, error = message),
                message,
            )
        }
        val typeWord = ((args["type"] as? String) ?: "").uppercase()
        val typeOrdinal = when (typeWord) {
            "INCOMING" -> CallLog.Calls.INCOMING_TYPE
            "OUTGOING" -> CallLog.Calls.OUTGOING_TYPE
            "MISSED" -> CallLog.Calls.MISSED_TYPE
            else -> {
                val message = "The 'type' must be INCOMING, OUTGOING or MISSED, and '$typeWord' is none of them."
                return failure(
                    CallLogWriteResult.serializer(),
                    CallLogWriteResult(success = false, error = message),
                    message,
                )
            }
        }
        val duration = ((args["durationSeconds"] as? Number)?.toInt() ?: 0).coerceAtLeast(0)
        val timestamp = (args["timestampMillis"] as? Number)?.toLong() ?: System.currentTimeMillis()

        val values = ContentValues().apply {
            put(CallLog.Calls.NUMBER, number)
            put(CallLog.Calls.TYPE, typeOrdinal)
            put(CallLog.Calls.DURATION, duration)
            put(CallLog.Calls.DATE, timestamp)
        }

        val inserted = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.insert(CallLog.Calls.CONTENT_URI, values) }.getOrNull()
        }

        if (inserted == null) {
            val message = "The call log provider refused the insert, so nothing was written."
            return failure(
                CallLogWriteResult.serializer(),
                CallLogWriteResult(success = false, error = message),
                message,
            )
        }

        val r = CallLogWriteResult(
            success = true,
            id = runCatching { ContentUris.parseId(inserted) }.getOrNull(),
            uri = inserted.toString(),
            note = "Wrote one $typeWord entry for $number into the phone's own call log. It is visible " +
                "in the person's dialler exactly like a real call.",
        )
        return emitPayload(json, json.encodeToString(CallLogWriteResult.serializer(), r), r, true, null)
    }

    private fun callTypeWord(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
        CallLog.Calls.MISSED_TYPE -> "MISSED"
        CallLog.Calls.REJECTED_TYPE -> "REJECTED"
        CallLog.Calls.VOICEMAIL_TYPE -> "VOICEMAIL"
        CallLog.Calls.BLOCKED_TYPE -> "BLOCKED"
        else -> "UNKNOWN"
    }

    // ------------------------------------------------------------------
    // Contacts
    // ------------------------------------------------------------------

    private suspend fun contactsList(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.READ_CONTACTS)?.let { return it }

        val query = args["query"] as? String
        val limit = ((args["limit"] as? Number)?.toInt() ?: 50).coerceIn(1, 500)
        val phoneOnly = args["withPhoneOnly"] as? Boolean ?: false
        val emailOnly = args["withEmailOnly"] as? Boolean ?: false

        val scope = buildString {
            append("Read the device address book, capped at $limit contacts")
            if (!query.isNullOrBlank()) append(", matching '$query' on display name")
            if (phoneOnly) append(", limited to contacts with a phone number")
            if (emailOnly) append(", limited to contacts with an email address")
            append(".")
        }

        val contacts: List<ContactEntry>? = withContext(Dispatchers.IO) {
            val found = mutableListOf<ContactEntry>()
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.HAS_PHONE_NUMBER,
            )

            val selectionParts = mutableListOf<String>()
            val selectionArgs = mutableListOf<String>()
            if (!query.isNullOrBlank()) {
                // Both clauses are bound rather than interpolated, so a name with an
                // apostrophe in it cannot break the query.
                selectionParts.add("(${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?)")
                selectionArgs.add("%$query%")
            }
            if (phoneOnly) {
                selectionParts.add("${ContactsContract.Contacts.HAS_PHONE_NUMBER} > 0")
            }

            val cursor = runCatching {
                context.contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    projection,
                    selectionParts.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
                    selectionArgs.takeIf { it.isNotEmpty() }?.toTypedArray(),
                    "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC",
                )
            }.getOrNull() ?: return@withContext null

            cursor.use { c ->
                val idIndex = c.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIndex = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                while (c.moveToNext() && found.size < limit) {
                    val id = if (idIndex >= 0) c.getLong(idIndex) else continue
                    val name = if (nameIndex >= 0) c.getString(nameIndex) else null
                    val numbers = phoneNumbersFor(id)
                    val emails = emailsFor(id)
                    // The email filter is applied after the per-contact read, because
                    // whether a person has an email is not a column on the contact row.
                    if (emailOnly && emails.isEmpty()) continue
                    found.add(ContactEntry(id = id, displayName = name, phoneNumbers = numbers, emails = emails))
                }
            }
            found
        }

        if (contacts == null) {
            val r = ContactsListResult(
                success = false,
                contacts = emptyList(),
                count = 0,
                scope = scope,
                error = "The contacts provider did not answer the query, so GURU cannot say whether " +
                    "any contact matched. This is neither an empty address book nor a missing grant.",
            )
            return emitPayload(json, json.encodeToString(ContactsListResult.serializer(), r), r, r.success, r.error)
        }

        val r = ContactsListResult(
            success = true,
            contacts = contacts,
            count = contacts.size,
            scope = scope,
        )
        return emitPayload(json, json.encodeToString(ContactsListResult.serializer(), r), r, r.success, r.error)
    }

    private fun phoneNumbersFor(contactId: Long): List<String> {
        val out = mutableListOf<String>()
        val cursor = runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null,
            )
        }.getOrNull()
        cursor?.use { c ->
            val index = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                if (index >= 0) c.getString(index)?.let { out.add(it) }
            }
        }
        return out
    }

    private fun emailsFor(contactId: Long): List<String> {
        val out = mutableListOf<String>()
        val cursor = runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null,
            )
        }.getOrNull()
        cursor?.use { c ->
            val index = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
            while (c.moveToNext()) {
                if (index >= 0) c.getString(index)?.let { out.add(it) }
            }
        }
        return out
    }

    private suspend fun contactCreate(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.READ_CONTACTS)?.let { return it }
        PermissionGrants.guard(context, TeamThreePermissions.WRITE_CONTACTS)?.let { return it }

        val name = (args["displayName"] as? String)?.trim()
        if (name.isNullOrBlank()) {
            val message = "This call needs a non-blank 'displayName'."
            return failure(
                ContactWriteResult.serializer(),
                ContactWriteResult(success = false, error = message),
                message,
            )
        }
        val phone = (args["phone"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val email = (args["email"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val updateIfExists = args["updateIfExists"] as? Boolean ?: false

        val existingId = if (updateIfExists) withContext(Dispatchers.IO) { findContactIdByName(name) } else null
        if (existingId != null) {
            return updateContact(
                contactId = existingId,
                displayName = null,
                phone = phone,
                email = email,
                insertedNote = "Found an existing contact named $name, so its entry was added to rather than duplicated.",
            )
        }

        val rawContactUri = withContext(Dispatchers.IO) {
            runCatching {
                val rawValues = ContentValues().apply {
                    put(ContactsContract.RawContacts.ACCOUNT_TYPE, null as String?)
                    put(ContactsContract.RawContacts.ACCOUNT_NAME, null as String?)
                }
                val raw = context.contentResolver.insert(ContactsContract.RawContacts.CONTENT_URI, rawValues)
                    ?: return@runCatching null
                val rawId = ContentUris.parseId(raw)

                val nameValues = ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    put(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                    )
                    put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                }
                context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, nameValues)

                phone?.let {
                    val phoneValues = ContentValues().apply {
                        put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                        put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        put(ContactsContract.CommonDataKinds.Phone.NUMBER, it)
                    }
                    context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, phoneValues)
                }
                email?.let {
                    val emailValues = ContentValues().apply {
                        put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                        put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                        put(ContactsContract.CommonDataKinds.Email.ADDRESS, it)
                    }
                    context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, emailValues)
                }
                raw
            }.getOrNull()
        }

        if (rawContactUri == null) {
            val message = "The contacts provider refused the insert, so no contact was created."
            return failure(
                ContactWriteResult.serializer(),
                ContactWriteResult(success = false, error = message),
                message,
            )
        }

        // The raw contact row being written is not proof a contact exists: the name,
        // phone and email rows are separate inserts whose results were not checked.
        // Reading the store back is what turns the intent into a result, so success is
        // taken from that read rather than from the insert above.
        val created = withContext(Dispatchers.IO) {
            findContactIdByName(name)?.let { id ->
                ContactEntry(
                    id = id,
                    displayName = displayNameFor(id),
                    phoneNumbers = phoneNumbersFor(id),
                    emails = emailsFor(id),
                )
            }
        }

        if (created == null) {
            val message = "The contacts provider accepted a raw row, but reading the store back found " +
                "no contact named $name, so the create did not land."
            return failure(
                ContactWriteResult.serializer(),
                ContactWriteResult(success = false, error = message),
                message,
            )
        }

        // Which requested fields are genuinely present now, rather than which were sent.
        val landed = buildList {
            if (created.displayName == name) add("displayName")
            if (phone != null && created.phoneNumbers.contains(phone)) add("phone")
            if (email != null && created.emails.contains(email)) add("email")
        }
        val missing = buildList {
            if (created.displayName != name) add("displayName")
            if (phone != null && !created.phoneNumbers.contains(phone)) add("phone")
            if (email != null && !created.emails.contains(email)) add("email")
        }

        val r = ContactWriteResult(
            success = true,
            contactId = created.id,
            lookupUri = lookupUriFor(created.id),
            updated = false,
            changedFields = landed,
            note = buildString {
                append("Created contact ${created.id} in the device address book.")
                if (missing.isNotEmpty()) {
                    append(" Reading it back does not show ")
                    append(missing.joinToString(" and "))
                    append(", so that part of the write did not land.")
                } else {
                    append(" Reading it back confirms ${landed.joinToString(" and ")}.")
                }
            },
        )
        return emitPayload(json, json.encodeToString(ContactWriteResult.serializer(), r), r, r.success, r.error)
    }

    private suspend fun contactsUpdate(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.READ_CONTACTS)?.let { return it }
        PermissionGrants.guard(context, TeamThreePermissions.WRITE_CONTACTS)?.let { return it }

        val contactId = (args["contactId"] as? Number)?.toLong()
            ?: return failure(
                ContactWriteResult.serializer(),
                ContactWriteResult(
                    success = false,
                    error = "This call needs 'contactId' as a number.",
                ),
                "This call needs 'contactId' as a number.",
            )

        return updateContact(
            contactId = contactId,
            displayName = (args["displayName"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            phone = (args["phone"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            email = (args["email"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            insertedNote = null,
        )
    }

    private suspend fun updateContact(
        contactId: Long,
        displayName: String?,
        phone: String?,
        email: String?,
        insertedNote: String?,
    ): ToolExecutionResult {
        if (displayName == null && phone == null && email == null) {
            val message = "Nothing to change: give at least one of displayName, phone or email."
            return failure(
                ContactWriteResult.serializer(),
                ContactWriteResult(success = false, error = message),
                message,
            )
        }

        // Existence is established before anything is read as "before", so a missing
        // contact is reported as missing rather than as a contact whose fields are all
        // null. The two would look identical in the diff below.
        val rawId = withContext(Dispatchers.IO) { rawContactIdFor(contactId) }
        if (rawId == null) {
            val message = "No contact with id $contactId exists in the address book."
            return failure(
                ContactWriteResult.serializer(),
                ContactWriteResult(success = false, contactId = contactId, error = message),
                message,
            )
        }

        val before = withContext(Dispatchers.IO) {
            ContactEntry(
                id = contactId,
                displayName = displayNameFor(contactId),
                phoneNumbers = phoneNumbersFor(contactId),
                emails = emailsFor(contactId),
            )
        }

        withContext(Dispatchers.IO) {
            displayName?.let { newName ->
                replaceFirstKind(
                    rawId = rawId,
                    mimeType = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                    column = ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                    value = newName,
                )
            }
            phone?.let { newPhone ->
                replaceFirstKind(
                    rawId = rawId,
                    mimeType = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                    column = ContactsContract.CommonDataKinds.Phone.NUMBER,
                    value = newPhone,
                )
            }
            email?.let { newEmail ->
                replaceFirstKind(
                    rawId = rawId,
                    mimeType = ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                    column = ContactsContract.CommonDataKinds.Email.ADDRESS,
                    value = newEmail,
                )
            }
        }

        // Read the store back and diff against what was there before. A write that
        // reported the values it sent would be reporting an intention, not a result.
        val after = withContext(Dispatchers.IO) {
            ContactEntry(
                id = contactId,
                displayName = displayNameFor(contactId),
                phoneNumbers = phoneNumbersFor(contactId),
                emails = emailsFor(contactId),
            )
        }

        // A read-back that lost everything the contact previously had is a provider that
        // stopped answering, not a contact that emptied itself. Diffing that would
        // report a change nobody made, in the wrong direction.
        val afterIsEmpty = after.displayName == null && after.phoneNumbers.isEmpty() && after.emails.isEmpty()
        val beforeWasNotEmpty =
            before.displayName != null || before.phoneNumbers.isNotEmpty() || before.emails.isNotEmpty()
        if (afterIsEmpty && beforeWasNotEmpty) {
            val message = "The update was sent, but the contact could not be read back afterwards, so " +
                "GURU cannot confirm what changed."
            return failure(
                ContactWriteResult.serializer(),
                ContactWriteResult(success = false, contactId = contactId, error = message),
                message,
            )
        }

        val changed = buildList {
            if (before.displayName != after.displayName) add("displayName")
            if (before.phoneNumbers != after.phoneNumbers) add("phone")
            if (before.emails != after.emails) add("email")
        }

        val r = ContactWriteResult(
            success = true,
            contactId = after.id,
            lookupUri = lookupUriFor(after.id),
            updated = changed.isNotEmpty(),
            changedFields = changed,
            note = buildString {
                // The inserted note explains which path was taken; the read-back verdict
                // still has to be stated, because it is the evidence and the note is not.
                insertedNote?.let { append(it).append(" ") }
                if (changed.isEmpty()) {
                    append("The provider accepted the call, but reading the contact back shows the same " +
                        "values as before, so nothing changed.")
                } else {
                    append("Changed ")
                    append(changed.joinToString(" and "))
                    append(" and confirmed it by reading the contact back.")
                }
            },
        )
        return emitPayload(json, json.encodeToString(ContactWriteResult.serializer(), r), r, r.success, r.error)
    }

    /**
     * Replace the first value of a data kind, inserting one when none exists.
     *
     * ONE ROW, NOT EVERY ROW. A selection of raw-contact-plus-mimetype matches every
     * number a person has stored, so updating through it with one value would silently
     * delete their other phone numbers while the tool reported a single replacement.
     * The row's own `_ID` is resolved first and only that row is written, which is the
     * behaviour the tool's description promises.
     *
     * Nothing is returned on purpose: the caller reads the contact back afterwards and
     * diffs it, which is the only statement about what happened that is evidence. What
     * this must not do is let a provider exception escape into the caller's write path,
     * so every call is contained and the read-back carries the answer.
     */
    private fun replaceFirstKind(rawId: Long, mimeType: String, column: String, value: String) {
        val selection = "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
        val args = arrayOf(rawId.toString(), mimeType)

        val rowId = runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                selection,
                args,
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val index = c.getColumnIndex(ContactsContract.Data._ID)
                    if (index >= 0) c.getLong(index) else null
                } else null
            }
        }.getOrNull()

        if (rowId != null) {
            val updated = runCatching {
                context.contentResolver.update(
                    ContactsContract.Data.CONTENT_URI,
                    ContentValues().apply { put(column, value) },
                    "${ContactsContract.Data._ID} = ?",
                    arrayOf(rowId.toString()),
                )
            }.getOrDefault(0)
            if (updated > 0) return
        }

        val insert = ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            put(ContactsContract.Data.MIMETYPE, mimeType)
            put(column, value)
        }
        runCatching { context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, insert) }
    }

    private suspend fun contactsDelete(args: Map<String, Any?>): ToolExecutionResult {
        // No grant guard for reading here: the delete itself is the only call made, and
        // it is gated by WRITE_CONTACTS. Reading the contact first would need
        // READ_CONTACTS, which the caller may not hold, and refusing for that would be
        // refusing a delete the person is allowed to perform.
        PermissionGrants.guard(context, TeamThreePermissions.WRITE_CONTACTS)?.let { return it }

        val contactId = (args["contactId"] as? Number)?.toLong()
            ?: return failure(
                ContactWriteResult.serializer(),
                ContactWriteResult(
                    success = false,
                    error = "This call needs 'contactId' as a number.",
                ),
                "This call needs 'contactId' as a number.",
            )
        if (args["confirm"] != true) {
            val message = "Nothing was deleted. Deleting a contact cannot be undone, so this call " +
                "needs confirm=true to proceed."
            return failure(
                ContactWriteResult.serializer(),
                ContactWriteResult(success = false, contactId = contactId, error = message),
                message,
            )
        }

        // Deleting through the lookup uri rather than the raw id, because aggregation
        // can change the id while the lookup key still resolves to the same person.
        val lookup = withContext(Dispatchers.IO) { contactLookupUri(contactId) }
        if (lookup == null) {
            val message = "Contact $contactId could not be resolved to a lookup uri, so nothing was " +
                "deleted. Either no contact has that id or the provider did not answer."
            return failure(
                ContactWriteResult.serializer(),
                ContactWriteResult(success = false, contactId = contactId, error = message),
                message,
            )
        }

        val deleted = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.delete(lookup, null, null) }.getOrNull()
        }

        // A null here is the provider throwing, which is not the same as it deleting
        // nothing. Both leave the contact in place, but only one is a refusal.
        if (deleted == null) {
            val message = "The contacts provider threw on the delete, so contact $contactId is still there."
            return failure(
                ContactWriteResult.serializer(),
                ContactWriteResult(success = false, contactId = contactId, error = message),
                message,
            )
        }
        if (deleted == 0) {
            val message = "The contacts provider deleted no row, so contact $contactId is still there."
            return failure(
                ContactWriteResult.serializer(),
                ContactWriteResult(success = false, contactId = contactId, error = message),
                message,
            )
        }

        val r = ContactWriteResult(
            success = true,
            contactId = contactId,
            updated = false,
            changedFields = listOf("deleted"),
            note = "Deleted contact $contactId from the device address book. It is not recoverable " +
                "from the phone.",
        )
        return emitPayload(json, json.encodeToString(ContactWriteResult.serializer(), r), r, true, null)
    }

    private suspend fun contactPhoto(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.READ_CONTACTS)?.let { return it }

        val requestedId = (args["contactId"] as? Number)?.toLong()
        if (requestedId == null) {
            val message = "This call needs 'contactId' as a number."
            return failure(
                ContactPhotoResult.serializer(),
                ContactPhotoResult(success = false, error = message),
                message,
            )
        }
        val contactId = requestedId
        val writeTo = (args["writeToPath"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        // Three states, reported separately: the contact has no photo, the contact has
        // one, or the provider did not answer. Collapsing the third into the first is
        // the "no data" for "no access" mistake in its other form.
        val declaresPhoto = withContext(Dispatchers.IO) { contactDeclaresPhoto(contactId) }
        if (declaresPhoto == null) {
            val message = "The contacts provider did not answer the photo lookup for contact $contactId, " +
                "so GURU cannot say whether it has a picture."
            return failure(
                ContactPhotoResult.serializer(),
                ContactPhotoResult(success = false, error = message),
                message,
            )
        }
        if (!declaresPhoto) {
            val message = "No photo is stored against contact $contactId."
            return failure(
                ContactPhotoResult.serializer(),
                ContactPhotoResult(
                    success = false,
                    note = "The contact row declares no photo, so this is the contact having no picture " +
                        "rather than a missing grant; the Contacts grant check above passed.",
                    error = message,
                ),
                message,
            )
        }

        // The stream is opened and consumed once here. The descriptor's length is not
        // trusted as the size: Android returns UNKNOWN_LENGTH for some providers, so
        // counting the bytes as they are copied is the measurement that is always true.
        val copy = withContext(Dispatchers.IO) { copyPhoto(contactId, writeTo) }
        val mimeType = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.getType(contactPhotoUri(contactId)) }.getOrNull()
        }

        if (copy == null) {
            val message = if (writeTo == null) {
                "Contact $contactId declares a photo, but the provider would not serve its bytes."
            } else {
                "The photo stream could not be written to $writeTo, so no file was produced."
            }
            return failure(
                ContactPhotoResult.serializer(),
                ContactPhotoResult(
                    success = false,
                    mimeType = mimeType,
                    writtenTo = writeTo,
                    error = message,
                ),
                message,
            )
        }

        if (copy.byteCount <= 0L) {
            val message = "Contact $contactId declares a photo, but the stream contained no bytes."
            return failure(
                ContactPhotoResult.serializer(),
                ContactPhotoResult(
                    success = false,
                    mimeType = mimeType,
                    writtenTo = writeTo,
                    error = message,
                ),
                message,
            )
        }

        val r = ContactPhotoResult(
            success = true,
            byteCount = copy.byteCount,
            mimeType = mimeType,
            writtenTo = copy.writtenTo,
            writtenByteCount = copy.writtenByteCount,
            note = if (copy.writtenTo == null) {
                "No writeToPath was given, so the photo was read and counted but not kept: " +
                    "${copy.byteCount} bytes. Give an absolute path to have the image written to disk."
            } else {
                "Wrote the contact photo to ${copy.writtenTo} and measured ${copy.writtenByteCount} " +
                    "bytes on disk afterwards."
            },
        )
        return emitPayload(json, json.encodeToString(ContactPhotoResult.serializer(), r), r, r.success, r.error)
    }

    // ------------------------------------------------------------------
    // Accounts
    // ------------------------------------------------------------------

    private suspend fun accountsList(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.GET_ACCOUNTS)?.let { return it }

        val typeFilter = (args["typeFilter"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        // A null here is AccountManager refusing to answer, which is not "no accounts".
        // Reporting it as an empty list with success = true would be the same defect the
        // call log and contacts tools above are written to avoid.
        val accounts: List<AccountEntry>? = withContext(Dispatchers.IO) {
            runCatching {
                AccountManager.get(context).accounts
                    .filter { typeFilter == null || it.type == typeFilter }
                    .map { account -> AccountEntry(name = account.name, type = account.type) }
            }.getOrNull()
        }

        if (accounts == null) {
            val r = AccountsListResult(
                success = false,
                accounts = emptyList(),
                count = 0,
                error = "AccountManager did not return the account list, so GURU cannot say what is " +
                    "signed in on this phone. This is not the same as the phone having no accounts.",
            )
            return emitPayload(json, json.encodeToString(AccountsListResult.serializer(), r), r, r.success, r.error)
        }

        val r = AccountsListResult(
            success = true,
            accounts = accounts,
            count = accounts.size,
            note = if (accounts.isEmpty()) {
                "The grant check passed and AccountManager returned no account, so this phone really " +
                    "has none matching the filter. That is the phone's state, not a refusal."
            } else {
                "Account names are visible to any app holding the Contacts permission group on this " +
                    "device, so treat them as known rather than private."
            },
        )
        return emitPayload(json, json.encodeToString(AccountsListResult.serializer(), r), r, r.success, r.error)
    }

    // ------------------------------------------------------------------
    // Provider helpers
    // ------------------------------------------------------------------

    private fun findContactIdByName(name: String): Long? {
        val cursor = runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID),
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} = ?",
                arrayOf(name),
                null,
            )
        }.getOrNull() ?: return null
        cursor.use { c ->
            if (c.moveToFirst()) {
                val index = c.getColumnIndex(ContactsContract.Contacts._ID)
                return if (index >= 0) c.getLong(index) else null
            }
        }
        return null
    }

    private fun displayNameFor(contactId: Long): String? {
        val cursor = runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                "${ContactsContract.Contacts._ID} = ?",
                arrayOf(contactId.toString()),
                null,
            )
        }.getOrNull() ?: return null
        cursor.use { c ->
            if (c.moveToFirst()) {
                val index = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                return if (index >= 0) c.getString(index) else null
            }
        }
        return null
    }

    private fun rawContactIdFor(contactId: Long): Long? {
        val cursor = runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null,
            )
        }.getOrNull() ?: return null
        cursor.use { c ->
            if (c.moveToFirst()) {
                val index = c.getColumnIndex(ContactsContract.RawContacts._ID)
                return if (index >= 0) c.getLong(index) else null
            }
        }
        return null
    }

    private fun contactPhotoUri(contactId: Long): Uri = Uri.withAppendedPath(
        ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
        ContactsContract.Contacts.Photo.CONTENT_DIRECTORY,
    )

    /**
     * Whether the contact row declares a photo id, or null when the row is unreadable.
     *
     * THE THREE ANSWERS MATTER. True is a contact with a picture, false is a contact
     * genuinely without one, and null is a provider that did not answer. Reporting the
     * third as the second is the same defect as reading a denial as an empty list.
     */
    private fun contactDeclaresPhoto(contactId: Long): Boolean? {
        val cursor = runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.PHOTO_ID, ContactsContract.Contacts.PHOTO_FILE_ID),
                "${ContactsContract.Contacts._ID} = ?",
                arrayOf(contactId.toString()),
                null,
            )
        }.getOrNull() ?: return null

        return cursor.use { c ->
            if (!c.moveToFirst()) {
                false
            } else {
                val idIndex = c.getColumnIndex(ContactsContract.Contacts.PHOTO_ID)
                val fileIndex = c.getColumnIndex(ContactsContract.Contacts.PHOTO_FILE_ID)
                val hasId = idIndex >= 0 && !c.isNull(idIndex) && c.getLong(idIndex) > 0L
                val hasFile = fileIndex >= 0 && !c.isNull(fileIndex) && c.getLong(fileIndex) > 0L
                hasId || hasFile
            }
        }
    }

    /** What a photo read produced: the bytes served, and the bytes measured on disk. */
    private data class PhotoCopy(
        val byteCount: Long,
        val writtenTo: String?,
        val writtenByteCount: Long?,
    )

    /**
     * Read a contact's photo once, optionally writing it to [writeTo].
     *
     * Null means the stream could not be opened at all, which is the provider refusing
     * rather than the contact lacking a picture; the caller has already established a
     * photo is declared. The byte count is measured as the stream is consumed, because
     * a descriptor may legitimately report UNKNOWN_LENGTH and a length is not evidence
     * that bytes were served. The on-disk length afterwards is the proof the write
     * landed.
     */
    private fun copyPhoto(contactId: Long, writeTo: String?): PhotoCopy? {
        val input = runCatching {
            context.contentResolver.openInputStream(contactPhotoUri(contactId))
        }.getOrNull() ?: return null

        return input.use { stream ->
            if (writeTo == null) {
                PhotoCopy(byteCount = stream.drain(), writtenTo = null, writtenByteCount = null)
            } else {
                val target = java.io.File(writeTo)
                target.parentFile?.mkdirs()
                runCatching {
                    target.outputStream().use { output -> stream.copyTo(output) }
                    // Measured after the stream closed, which is what proves the bytes
                    // reached the file rather than only a buffer.
                    val onDisk = target.length()
                    PhotoCopy(byteCount = onDisk, writtenTo = writeTo, writtenByteCount = onDisk)
                }.getOrNull()
            }
        }
    }

    /** Consume a stream and report how many bytes it actually carried. */
    private fun java.io.InputStream.drain(): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
        }
        return total
    }

    /**
     * The lookup uri other apps resolve a contact by, or null when it cannot be read.
     *
     * ONE QUERY, TWO CONSUMERS. The create path returns it as a string in its payload
     * and the delete path needs the Uri itself to delete through, because the contacts
     * id can change under aggregation while the lookup key survives it. Both read the
     * same row, so the query is written once here rather than twice in two shapes.
     */
    private fun contactLookupUri(contactId: Long): Uri? {
        val cursor = runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
                "${ContactsContract.Contacts._ID} = ?",
                arrayOf(contactId.toString()),
                null,
            )
        }.getOrNull() ?: return null

        cursor.use { c ->
            if (c.moveToFirst()) {
                val index = c.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
                if (index >= 0) {
                    return ContactsContract.Contacts.getLookupUri(contactId, c.getString(index))
                }
            }
        }
        return null
    }

    private fun lookupUriFor(contactId: Long): String? =
        runCatching { contactLookupUri(contactId)?.toString() }.getOrNull()

    /**
     * Emit a result whose success flag comes from the payload rather than being forced.
     *
     * ToolExecutionResult.success(...) forces success = true whatever it is handed, so
     * wrapping a refusal in it reported a denial as a completed call. Taking the flag
     * from the payload makes that mistake unwritable by accident.
     */
    private fun <T : ToolResultData> emitPayload(
        json: Json,
        rawJson: String,
        resultData: T,
        success: Boolean,
        error: String?,
    ): ToolExecutionResult = ToolExecutionResult(
        rawJson = rawJson,
        resultData = resultData,
        resultObject = null,
        success = success,
        error = error,
    )

    /**
     * Emit a payload that already knows it failed, taking the error from the caller.
     *
     * THE ERROR IS PASSED IN, NOT RECOVERED BY CASTING. The earlier version read it
     * back out of the payload behind a chain of three `as?` casts, so a payload type
     * outside those three came back with `success = false` and `error = null`: a
     * failure with no reason, which reads to a model as a malformed success. Passing
     * the sentence alongside the payload makes that class of bug unwritable, and the
     * payload still carries it because every failure payload is built with the same
     * string in its own `error` field.
     */
    private fun <T : ToolResultData> failure(
        serializer: KSerializer<T>,
        payload: T,
        error: String,
    ): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(serializer, payload),
        resultData = payload,
        resultObject = null,
        success = false,
        error = error,
    )
}

/**
 * The personal-data domain's wiring: its definitions and its executor, in one file so
 * a tool's claim and its behaviour cannot be committed separately.
 */
object CallLogContactsDomainModule : DomainToolModule {
    override val id: String = "calllog_contacts"

    override val registrations: List<ToolSetRegistration> = listOf(CallLogContactsDomainDefinitions)

    override fun bindings(): Module = module {
        factory { CallLogContactsDomainExecutor(get()) }
    }
}
