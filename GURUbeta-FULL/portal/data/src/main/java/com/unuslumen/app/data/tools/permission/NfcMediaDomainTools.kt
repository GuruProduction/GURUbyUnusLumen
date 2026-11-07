// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.provider.MediaStore
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.reflect.KClass

/**
 * The NFC and media domain: reading a tag, writing one, and querying the phone's media
 * library.
 *
 * THE TWO DIFFERENT KINDS OF LIMIT IN ONE FILE. NFC has a physical limit that no grant can
 * cross: reading a tag needs a tag present and a foreground reader session, so a background
 * tool cannot summon one. The media library has a permission limit with a subtlety: on
 * Android 14 a person can grant access to selected photos only, and a tool unaware of that
 * reports "you have twelve photos" about a library of thousands. Both are reported as what
 * they are.
 *
 * WHY A TAG RECORD EXISTS. Because there is no way to ask the platform "what tags have you
 * seen". The reader delivers a tag into the app at the moment it is presented, and after
 * that the only record is one the app keeps. TagTrace below is that record, and every result
 * built from it says so rather than implying the platform can be queried.
 */

@Serializable
data class MediaEntry(
    val id: Long,
    val displayName: String? = null,
    val uri: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val dateAddedSeconds: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMillis: Long? = null,
    val artist: String? = null,
    val album: String? = null,
    val bucketName: String? = null,
)

@Serializable
data class MediaLibraryResult(
    val success: Boolean,
    val kind: String,
    val items: List<MediaEntry> = emptyList(),
    val returnedCount: Int = 0,
    /** The real total in the store for this selection, measured rather than guessed. */
    val totalMatching: Int? = null,
    /** True when Android granted only the selected photos rather than the library. */
    val partialAccessGranted: Boolean = false,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class NfcSighting(
    val tagIdHex: String,
    val techList: List<String> = emptyList(),
    /** A plain description of the NDEF content when the tag carried any. */
    val ndefSummary: String? = null,
    val seenAtMillis: Long,
)

@Serializable
data class NfcReadResult(
    val success: Boolean,
    val hardwarePresent: Boolean = false,
    val enabled: Boolean = false,
    val sessionArmed: Boolean = false,
    val sightings: List<NfcSighting> = emptyList(),
    val sightingCount: Int = 0,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class NfcWriteResult(
    val success: Boolean,
    val preparedBytes: Int? = null,
    val hardwarePresent: Boolean = false,
    val enabled: Boolean = false,
    val sessionArmed: Boolean = false,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

/**
 * The tag sightings this process has seen, in arrival order.
 *
 * THE PLATFORM CANNOT BE ASKED. A tag arrives as a callback while a reader session is armed,
 * and there is no API that lists what the app has seen. This record is therefore the only
 * source of that history, it dies with the process, and every result built from it says so.
 */
object TagTrace {
    private const val CAPACITY = 50
    private val lock = Any()
    private val sightings = ArrayDeque<NfcSighting>()

    fun record(sighting: NfcSighting) {
        synchronized(lock) {
            sightings.addLast(sighting)
            while (sightings.size > CAPACITY) sightings.removeFirst()
        }
    }

    /**
     * Record a tag the platform just delivered.
     *
     * Called by whatever component holds the reader session. Kept here so the summary format
     * is written once, in the same file that reads it back.
     */
    fun recordTag(tag: Tag) {
        val id = tag.id
        val hex = id?.joinToString("") { byte -> "%02X".format(byte) }.orEmpty()
        val summary = runCatching {
            @Suppress("DEPRECATION")
            val raw = tag.techList
            if (raw.isNullOrEmpty()) null else raw.joinToString(", ")
        }.getOrNull()

        record(
            NfcSighting(
                tagIdHex = hex,
                techList = runCatching { tag.techList?.toList().orEmpty() }.getOrDefault(emptyList()),
                ndefSummary = summary,
                seenAtMillis = System.currentTimeMillis(),
            )
        )
    }

    fun recent(limit: Int): List<NfcSighting> = synchronized(lock) {
        sightings.toList().takeLast(limit.coerceIn(1, CAPACITY))
    }

    fun size(): Int = synchronized(lock) { sightings.size }
}

object NfcMediaDomainDefinitions : ToolSetRegistration {
    const val MEDIA_LIBRARY = "mediaLibrary"
    const val NFC_READ = "nfcRead"
    const val NFC_WRITE = "nfcWrite"
    const val NFC_TRANSACTIONS = "nfcTransactions"

    override val definitions = listOf(
        ToolDefinition(
            name = MEDIA_LIBRARY,
            description = "Find photos, videos or audio on the phone and return each item's name, " +
                "MediaStore uri, size, dimensions and duration. It reports BOTH the number returned " +
                "and the real total matching, because on Android 14 a person can grant access to " +
                "selected photos only: when that partial grant is in force the store returns just " +
                "those items, and a tool that reported its own count as the total would tell you " +
                "that twelve pictures is your whole library. Which grant is in force is stated in " +
                "the result rather than left to be discovered.",
            category = "media_library",
            parameters = listOf(
                ToolParameter(
                    "kind", ToolParameterType.Enum, true, "Which kind of media to query.",
                    listOf("IMAGE", "VIDEO", "AUDIO"),
                ),
                ToolParameter("limit", ToolParameterType.Integer, false, "1 to 500. Default 50."),
                ToolParameter(
                    "sinceMillis", ToolParameterType.Long, false,
                    "Only items added at or after this epoch millisecond. Default 0."
                ),
                ToolParameter(
                    "minSizeBytes", ToolParameterType.Long, false,
                    "Only items at least this large. Default 0."
                ),
                ToolParameter(
                    "sortBy", ToolParameterType.Enum, false,
                    "Default DATE_DESC.",
                    listOf("DATE_DESC", "DATE_ASC", "SIZE_DESC", "NAME_ASC"),
                ),
            ),
            // Reads are gated by the media grants. READ_MEDIA_IMAGES is declared as the
            // representative because the dispatcher refuses when ANY declared permission is
            // missing, and demanding all three would refuse a person who granted photos only.
            permissions = listOf(TeamThreePermissions.READ_MEDIA_IMAGES.permission),
        ),
        ToolDefinition(
            name = NFC_READ,
            description = "Report whether this phone has an NFC radio, whether it is switched on, and " +
                "what tags the app has seen since it started. This CANNOT summon a tag: reading one " +
                "needs a physical tag held to the phone while a foreground reader session is armed, " +
                "which a background tool cannot create. So the honest answer when nothing has been " +
                "presented is exactly that, not an empty list that looks like a failed read.",
            category = "nfc",
            parameters = listOf(
                ToolParameter(
                    "limit", ToolParameterType.Integer, false,
                    "How many recent sightings to return, 1 to 50. Default 10."
                ),
            ),
            permissions = listOf(TeamThreePermissions.NFC.permission),
        ),
        ToolDefinition(
            name = NFC_WRITE,
            description = "Prepare an NDEF text record for writing to a tag, report its byte size, and " +
                "report whether a tag session is armed to receive it. Like reading, this cannot " +
                "create the physical moment: a write needs a tag present and a live reader session. " +
                "So it never reports a write as done, because no write can happen without a tag, and " +
                "saying the message is prepared and waiting is the true answer.",
            category = "nfc",
            parameters = listOf(
                ToolParameter("text", ToolParameterType.String, true, "The text to encode in the NDEF record."),
            ),
            permissions = listOf(TeamThreePermissions.NFC.permission),
        ),
        ToolDefinition(
            name = NFC_TRANSACTIONS,
            description = "Report the tag sightings this process has seen, and state plainly that the " +
                "raw contactless transaction stream is not readable without a system privilege this " +
                "build does not hold. The reader-mode path covers the practical case and IS real " +
                "data, so this returns it rather than refusing: an empty list here means no tag has " +
                "been presented since the process started, never that the read was blocked.",
            category = "nfc",
            parameters = listOf(
                ToolParameter(
                    "limit", ToolParameterType.Integer, false,
                    "How many recent sightings to return, 1 to 50. Default 50."
                ),
            ),
            permissions = listOf(TeamThreePermissions.NFC.permission),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = NfcMediaDomainExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

class NfcMediaDomainExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            NfcMediaDomainDefinitions.MEDIA_LIBRARY -> mediaLibrary(args)
            NfcMediaDomainDefinitions.NFC_READ -> nfcRead(args)
            NfcMediaDomainDefinitions.NFC_WRITE -> nfcWrite(args)
            NfcMediaDomainDefinitions.NFC_TRANSACTIONS -> nfcTransactions(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    // ------------------------------------------------------------------
    // Media
    // ------------------------------------------------------------------

    private suspend fun mediaLibrary(args: Map<String, Any?>): ToolExecutionResult {
        val kind = ((args["kind"] as? String) ?: "").uppercase()
        if (kind != "IMAGE" && kind != "VIDEO" && kind != "AUDIO") {
            return failure(
                MediaLibraryResult.serializer(),
                MediaLibraryResult(
                    success = false,
                    kind = kind,
                    error = "The kind must be IMAGE, VIDEO or AUDIO, and '$kind' is none of them.",
                ),
            )
        }

        // Guard ONLY the grant that matches the requested kind. Demanding all three would
        // refuse a person who granted photos and not audio, which is the mistake that makes a
        // working tool look broken.
        val required = when (kind) {
            "IMAGE" -> TeamThreePermissions.READ_MEDIA_IMAGES
            "VIDEO" -> TeamThreePermissions.READ_MEDIA_VIDEO
            else -> TeamThreePermissions.READ_MEDIA_AUDIO
        }
        PermissionGrants.guard(context, required)?.let { return it }

        val limit = ((args["limit"] as? Number)?.toInt() ?: 50).coerceIn(1, 500)
        val sinceMillis = (args["sinceMillis"] as? Number)?.toLong() ?: 0L
        val minSize = (args["minSizeBytes"] as? Number)?.toLong() ?: 0L
        val sortBy = ((args["sortBy"] as? String) ?: "DATE_DESC").uppercase()

        val collection: Uri = when (kind) {
            "IMAGE" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "VIDEO" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val sortOrder = when (sortBy) {
            "DATE_ASC" -> "${MediaStore.MediaColumns.DATE_ADDED} ASC"
            "SIZE_DESC" -> "${MediaStore.MediaColumns.SIZE} DESC"
            "NAME_ASC" -> "${MediaStore.MediaColumns.DISPLAY_NAME} ASC"
            else -> "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        }

        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()
        if (sinceMillis > 0L) {
            // MediaStore stores seconds, not millis. Dividing here rather than comparing a
            // second value against a millisecond bound is the difference between a working
            // filter and one that matches everything.
            selectionParts.add("${MediaStore.MediaColumns.DATE_ADDED} >= ?")
            selectionArgs.add((sinceMillis / 1000L).toString())
        }
        if (minSize > 0L) {
            selectionParts.add("${MediaStore.MediaColumns.SIZE} >= ?")
            selectionArgs.add(minSize.toString())
        }
        val selection = selectionParts.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        val argsArray = selectionArgs.takeIf { it.isNotEmpty() }?.toTypedArray()

        // Whether Android granted only the selected photos. This is the subtlest lie in the
        // whole media surface: with partial access the store returns a short list that looks
        // like a small library.
        val partialAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            PermissionGrants.check(context, TeamThreePermissions.READ_MEDIA_VISUAL_USER_SELECTED)
                .allowed &&
            !PermissionGrants.check(context, required).allowed

        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            if (kind != "AUDIO") {
                add(MediaStore.MediaColumns.WIDTH)
                add(MediaStore.MediaColumns.HEIGHT)
                add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            } else {
                add(MediaStore.Audio.Media.DURATION)
                add(MediaStore.Audio.Media.ARTIST)
                add(MediaStore.Audio.Media.ALBUM)
            }
        }.toTypedArray()

        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val items = mutableListOf<MediaEntry>()
                val cursor = context.contentResolver.query(
                    collection, projection, selection, argsArray, sortOrder,
                )
                if (cursor == null) {
                    null
                } else {
                    cursor.use { c ->
                        while (c.moveToNext() && items.size < limit) {
                            val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                            fun text(column: String): String? {
                                val index = c.getColumnIndex(column)
                                return if (index >= 0 && !c.isNull(index)) c.getString(index) else null
                            }
                            fun number(column: String): Long? {
                                val index = c.getColumnIndex(column)
                                return if (index >= 0 && !c.isNull(index)) c.getLong(index) else null
                            }
                            fun integer(column: String): Int? {
                                val index = c.getColumnIndex(column)
                                return if (index >= 0 && !c.isNull(index)) c.getInt(index) else null
                            }

                            items.add(
                                MediaEntry(
                                    id = id,
                                    displayName = text(MediaStore.MediaColumns.DISPLAY_NAME),
                                    uri = ContentUris.withAppendedId(collection, id).toString(),
                                    mimeType = text(MediaStore.MediaColumns.MIME_TYPE),
                                    sizeBytes = number(MediaStore.MediaColumns.SIZE),
                                    dateAddedSeconds = number(MediaStore.MediaColumns.DATE_ADDED),
                                    width = if (kind != "AUDIO") integer(MediaStore.MediaColumns.WIDTH) else null,
                                    height = if (kind != "AUDIO") integer(MediaStore.MediaColumns.HEIGHT) else null,
                                    durationMillis = if (kind == "AUDIO") number(MediaStore.Audio.Media.DURATION) else null,
                                    artist = if (kind == "AUDIO") text(MediaStore.Audio.Media.ARTIST) else null,
                                    album = if (kind == "AUDIO") text(MediaStore.Audio.Media.ALBUM) else null,
                                    bucketName = if (kind != "AUDIO") {
                                        text(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                                    } else null,
                                )
                            )
                        }
                    }
                    items
                }
            }.getOrNull()
        }

        if (outcome == null) {
            return failure(
                MediaLibraryResult.serializer(),
                MediaLibraryResult(
                    success = false,
                    kind = kind,
                    partialAccessGranted = partialAccess,
                    error = "The media store did not answer the query at all, which is a provider that " +
                        "refused rather than an empty library.",
                ),
            )
        }

        // The real total, counted with the same selection. Without this the returned count is
        // ambiguous between "all there is" and "as many as you asked for".
        val total = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    selection,
                    argsArray,
                    null,
                )?.use { c -> c.count }
            }.getOrNull()
        }

        val r = MediaLibraryResult(
            success = true,
            kind = kind,
            items = outcome,
            returnedCount = outcome.size,
            totalMatching = total,
            partialAccessGranted = partialAccess,
            note = buildString {
                append("Returned ${outcome.size}")
                total?.let { append(" of $it") }
                append(" $kind item(s) from the device media store. ")
                if (partialAccess) {
                    append("Android has granted only the photos you selected rather than the whole ")
                    append("library, so a small list here is that grant and not your real total. ")
                }
                append("Each uri is a MediaStore content uri, which is what other tools accept.")
            }.trim(),
        )
        return emit(json, MediaLibraryResult.serializer(), r)
    }

    // ------------------------------------------------------------------
    // NFC
    // ------------------------------------------------------------------

    private fun nfcState(): Triple<Boolean, Boolean, Boolean> {
        // Hardware presence first, because no grant fixes a phone with no radio.
        val present = PermissionGrants.hasFeature(context, PackageManager.FEATURE_NFC)
        if (!present) return Triple(false, false, false)

        val adapter = runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull()
        if (adapter == null) return Triple(true, false, false)

        val enabled = runCatching { adapter.isEnabled }.getOrDefault(false)

        // THERE IS NO WAY TO ASK whether a reader session is armed. The platform offers no
        // getter, so the only evidence is whether a tag has actually been delivered. An earlier
        // draft wrote `TagTrace.size() >= 0`, which is true by construction and would have put a
        // fabricated yes in front of a person while looking like a measurement. So this is false
        // until a tag has genuinely arrived, and every result says that the true state is unknown
        // rather than claiming it either way.
        val sawATag = TagTrace.size() > 0
        return Triple(true, enabled, sawATag)
    }

    private suspend fun nfcRead(args: Map<String, Any?>): ToolExecutionResult {
        val (present, enabled, armed) = nfcState()

        if (!present) {
            val refusal = PermissionGrants.guardHardware(
                present = false,
                label = "NFC radio",
                why = "GURU could read a tag when you hold the phone to one",
            )
            return refusal ?: failure(
                NfcReadResult.serializer(),
                NfcReadResult(success = false, error = "This device has no NFC radio."),
            )
        }

        PermissionGrants.guard(context, TeamThreePermissions.NFC)?.let { return it }

        val limit = ((args["limit"] as? Number)?.toInt() ?: 10).coerceIn(1, 50)
        val sightings = TagTrace.recent(limit)

        val r = NfcReadResult(
            success = true,
            hardwarePresent = true,
            enabled = enabled,
            sessionArmed = armed,
            sightings = sightings,
            sightingCount = sightings.size,
            note = buildString {
                if (!enabled) {
                    append("NFC is switched off in Settings, so no tag can be read until it is turned ")
                    append("on. That is a phone setting rather than a permission. ")
                }
                append("Reading a tag needs a physical tag held to the phone while a foreground reader ")
                append("session is armed, which a background tool cannot create. The platform also ")
                append("offers no way to ask whether a session is currently armed, so sessionArmed ")
                append("means only that a tag HAS been delivered since this process started. ")
                if (sightings.isEmpty()) {
                    append("No tag has been presented to this app since it started, which is the true ")
                    append("answer rather than a failed read.")
                } else {
                    append("These are the tags this app has actually seen since it started, from its ")
                    append("own record: the platform offers no way to ask what has been seen.")
                }
            }.trim(),
        )
        return emit(json, NfcReadResult.serializer(), r)
    }

    private suspend fun nfcWrite(args: Map<String, Any?>): ToolExecutionResult {
        val text = args["text"] as? String
        if (text.isNullOrBlank()) {
            return failure(
                NfcWriteResult.serializer(),
                NfcWriteResult(success = false, error = "This call needs 'text' to encode."),
            )
        }

        val (present, enabled, armed) = nfcState()

        if (!present) {
            val refusal = PermissionGrants.guardHardware(
                present = false,
                label = "NFC radio",
                why = "GURU could write a tag when you hold the phone to one",
            )
            return refusal ?: failure(
                NfcWriteResult.serializer(),
                NfcWriteResult(success = false, error = "This device has no NFC radio."),
            )
        }

        PermissionGrants.guard(context, TeamThreePermissions.NFC)?.let { return it }

        val message = runCatching {
            NdefMessage(arrayOf(NdefRecord.createTextRecord("en", text)))
        }.getOrNull()

        if (message == null) {
            return failure(
                NfcWriteResult.serializer(),
                NfcWriteResult(
                    success = false,
                    hardwarePresent = true,
                    enabled = enabled,
                    error = "The NDEF record could not be built from that text.",
                ),
            )
        }

        val r = NfcWriteResult(
            success = true,
            preparedBytes = message.toByteArray().size,
            hardwarePresent = true,
            enabled = enabled,
            sessionArmed = armed,
            note = buildString {
                append("The NDEF text record is built and ready, ${message.toByteArray().size} bytes. ")
                append("It was NOT written to anything: a write needs a physical tag present and a live ")
                append("reader session, and this call can create neither. sessionArmed reports only ")
                append("that a tag has been delivered since this process started, because the platform ")
                append("exposes no way to ask whether a session is armed. ")
                if (!enabled) {
                    append("NFC is also switched off, so a tag could not be written until it is on. ")
                }
                append("Nothing was written, and this result never claims otherwise.")
            }.trim(),
        )
        return emit(json, NfcWriteResult.serializer(), r)
    }

    private suspend fun nfcTransactions(args: Map<String, Any?>): ToolExecutionResult {
        val (present, enabled, armed) = nfcState()

        if (!present) {
            val refusal = PermissionGrants.guardHardware(
                present = false,
                label = "NFC radio",
                why = "GURU could report contactless activity when you hold the phone to a tag",
            )
            return refusal ?: failure(
                NfcReadResult.serializer(),
                NfcReadResult(success = false, error = "This device has no NFC radio."),
            )
        }

        PermissionGrants.guard(context, TeamThreePermissions.NFC)?.let { return it }

        val limit = ((args["limit"] as? Number)?.toInt() ?: 50).coerceIn(1, 50)
        val sightings = TagTrace.recent(limit)

        val r = NfcReadResult(
            success = true,
            hardwarePresent = true,
            enabled = enabled,
            sessionArmed = armed,
            sightings = sightings,
            sightingCount = sightings.size,
            note = buildString {
                append("These are the tags this app has seen since it started, which is real data from ")
                append("the reader path. ")
                append("The raw contactless transaction stream needs a system privilege this build does ")
                append("not hold, and no grant you can give changes that. ")
                if (sightings.isEmpty()) {
                    append("The record is empty because no tag has been presented since the process ")
                    append("started, not because the read was blocked.")
                }
            }.trim(),
        )
        return emit(json, NfcReadResult.serializer(), r)
    }

    // ------------------------------------------------------------------
    // Emit helpers
    // ------------------------------------------------------------------

    private fun <T : ToolResultData> emit(
        json: Json,
        serializer: kotlinx.serialization.KSerializer<T>,
        payload: T,
    ): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(serializer, payload),
        resultData = payload,
        resultObject = null,
        success = when (payload) {
            is MediaLibraryResult -> payload.success
            is NfcReadResult -> payload.success
            is NfcWriteResult -> payload.success
            else -> false
        },
        error = when (payload) {
            is MediaLibraryResult -> payload.error
            is NfcReadResult -> payload.error
            is NfcWriteResult -> payload.error
            else -> null
        },
    )

    private fun <T : ToolResultData> failure(
        serializer: kotlinx.serialization.KSerializer<T>,
        payload: T,
    ): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(serializer, payload),
        resultData = payload,
        resultObject = null,
        success = false,
        error = when (payload) {
            is MediaLibraryResult -> payload.error
            is NfcReadResult -> payload.error
            is NfcWriteResult -> payload.error
            else -> null
        },
    )
}

object NfcMediaDomainModule : DomainToolModule {
    override val id: String = "nfc_media"

    override val registrations: List<ToolSetRegistration> = listOf(NfcMediaDomainDefinitions)

    override fun bindings(): Module = module {
        factory { NfcMediaDomainExecutor(get()) }
    }
}
