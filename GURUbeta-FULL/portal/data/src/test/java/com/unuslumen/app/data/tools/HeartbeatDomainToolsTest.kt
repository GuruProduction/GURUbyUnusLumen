// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.domain.model.BookingCombine
import com.unuslumen.app.domain.model.EntryState
import com.unuslumen.app.domain.model.HeartbeatBooking
import com.unuslumen.app.domain.model.HeartbeatEntry
import com.unuslumen.app.domain.model.RepeatKind
import com.unuslumen.app.domain.repository.HeartbeatBookingRepository
import com.unuslumen.app.domain.repository.HeartbeatPromptRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The heartbeat diary tools, and the distinction the whole domain rests on.
 *
 * A BOOKING IS A RULE; AN OCCURRENCE IS WHAT HAPPENED. Most of these tests exist because
 * collapsing those two produces a diary that lies in a specific direction: it reports a
 * scheduled half hour as work that ran, or reports a half hour nobody executed as quiet.
 * The order that asked for these tools says so explicitly, and it is right to.
 *
 * The rest is about refusal shape. A refusal here is a tool call that SUCCEEDED and
 * carries `success = false` with a sentence, never a thrown error and never an empty
 * result, because a model reading the reply has to be able to tell "that half hour is
 * already booked" from "the tool broke".
 */
class HeartbeatDomainToolsTest {

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    private class FakeBookings : HeartbeatBookingRepository {
        val rows = mutableListOf<HeartbeatBooking>()
        val removed = mutableListOf<String>()
        val enabledCalls = mutableListOf<Pair<String, Boolean>>()
        val rescheduled = mutableListOf<Pair<HeartbeatBooking, Long>>()

        /** Set to make the next write fail with this sentence, as the real one does. */
        var refuseWith: String? = null

        override fun observeBookings(): Flow<List<HeartbeatBooking>> = flowOf(rows.toList())
        override suspend fun bookings(): List<HeartbeatBooking> = rows.toList()
        override suspend fun booking(id: String): HeartbeatBooking? = rows.firstOrNull { it.id == id }

        override suspend fun book(booking: HeartbeatBooking): Result<Unit> {
            val reason = refuseWith
            if (reason != null) {
                refuseWith = null
                // The real repository refuses through `require`, which throws inside
                // runCatching, so the failure carries the sentence in its message.
                return Result.failure(IllegalArgumentException(reason))
            }
            rows.add(booking)
            return Result.success(Unit)
        }

        override suspend fun reschedule(booking: HeartbeatBooking, fromMillis: Long): Result<Unit> {
            val reason = refuseWith
            if (reason != null) {
                refuseWith = null
                return Result.failure(IllegalArgumentException(reason))
            }
            rescheduled += booking to fromMillis
            val index = rows.indexOfFirst { it.id == booking.id }
            if (index >= 0) rows[index] = booking else rows.add(booking)
            return Result.success(Unit)
        }

        override suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit> {
            enabledCalls += id to enabled
            val index = rows.indexOfFirst { it.id == id }
            if (index >= 0) rows[index] = rows[index].copy(enabled = enabled)
            return Result.success(Unit)
        }

        override suspend fun remove(id: String): Result<Unit> {
            removed += id
            rows.removeAll { it.id == id }
            return Result.success(Unit)
        }

        override fun observeDay(dayStart: Long, dayEnd: Long): Flow<List<HeartbeatEntry>> = flowOf(emptyList())
        override suspend fun day(dayStart: Long, dayEnd: Long): List<HeartbeatEntry> = emptyList()
        override fun observeSlotsBetween(fromMillis: Long, toMillis: Long): Flow<List<Long>> = flowOf(emptyList())
        override suspend fun entryAt(slotAt: Long): HeartbeatEntry? = null
        override suspend fun overrideInstruction(entryId: String, instruction: String?): Result<Unit> =
            Result.success(Unit)
        override suspend fun recordOutcome(
            entryId: String,
            state: EntryState,
            firedAt: Long,
            finishedAt: Long,
            outcome: String,
        ) = Unit
        override suspend fun reconcile(nowMillis: Long) = Unit
    }

    private class FakePrompt(private var content: String = "") : HeartbeatPromptRepository {
        override suspend fun current(): String = content
        override suspend fun isEdited(): Boolean = content.isNotBlank()
        override suspend fun save(content: String): Result<Unit> {
            if (content.isBlank()) {
                return Result.failure(IllegalArgumentException("A heartbeat prompt cannot be empty"))
            }
            this.content = content
            return Result.success(Unit)
        }
        override suspend fun reset() { content = "" }
    }

    /**
     * Build the executor with a stand-in context.
     *
     * THE CONTEXT IS USED BY EXACTLY ONE PATH, the manual fire, and it needs a real
     * service start to answer anything. Everything else in this file lives on the
     * database. A MockK relaxed context answers the calls that path makes, which lets the
     * fire test assert on what the tool reports without claiming a beat ran.
     */
    private fun executor(
        bookings: FakeBookings = FakeBookings(),
        prompt: FakePrompt = FakePrompt(),
    ) = HeartbeatToolExecutor(
        bookings,
        prompt,
        io.mockk.mockk(relaxed = true),
    )

    private fun json(result: com.unuslumen.app.data.tools.registry.ToolExecutionResult): JsonObject =
        Json.parseToJsonElement(result.rawJson) as JsonObject

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: error("'$key' missing")

    private fun JsonObject.flag(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: error("'$key' missing")

    private fun JsonObject.num(key: String): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: error("'$key' missing")

    private fun booking(id: String, name: String = "Morning") = HeartbeatBooking(
        id = id,
        name = name,
        instruction = "read the news",
        repeat = RepeatKind.DAILY,
        combine = BookingCombine.ALONGSIDE,
        enabled = true,
        startsAt = 1_800_000_000_000L,
    )

    // ------------------------------------------------------------------
    // The prompt: absence is an answer, not an empty string
    // ------------------------------------------------------------------

    /**
     * A fresh install has no prompt, and the reply says so rather than reporting "".
     *
     * THE DISTINCTION IS THE WHOLE POINT. An empty string is what a cleared prompt also
     * reads as, and the two states lead a caller somewhere different: one means "ask the
     * owner to write one", the other means "the owner removed theirs".
     */
    @Test
    fun readingAnUnwrittenPromptSaysSoRatherThanReturningAnEmptyString() = runBlocking {
        val body = json(executor().execute(HeartbeatToolDefinitions.GET_HEARTBEAT_PROMPT, emptyMap()))
        assertFalse("no prompt has been written", body.flag("written"))
        assertEquals("", body.str("content"))
        assertTrue(
            "the message must say a prompt still needs writing: ${body.str("message")}",
            body.str("message").contains("No master prompt is written"),
        )
    }

    @Test
    fun readingAWrittenPromptReturnsIt() = runBlocking {
        val body = json(
            executor(prompt = FakePrompt("keep the diary honest"))
                .execute(HeartbeatToolDefinitions.GET_HEARTBEAT_PROMPT, emptyMap()),
        )
        assertTrue(body.flag("written"))
        assertEquals("keep the diary honest", body.str("content"))
    }

    /** An empty save is refused with a reason, because an empty prompt looks like a failure. */
    @Test
    fun savingAnEmptyPromptIsRefused() = runBlocking {
        val body = json(
            executor().execute(HeartbeatToolDefinitions.SAVE_HEARTBEAT_PROMPT, mapOf("content" to "   ")),
        )
        assertFalse(body.flag("success"))
        assertTrue(
            "the refusal must explain itself: ${body.str("message")}",
            body.str("message").contains("empty"),
        )
    }

    @Test
    fun savingAPromptWritesIt() = runBlocking {
        val prompt = FakePrompt()
        val body = json(
            executor(prompt = prompt).execute(
                HeartbeatToolDefinitions.SAVE_HEARTBEAT_PROMPT,
                mapOf("content" to "think about the day"),
            ),
        )
        assertTrue(body.flag("success"))
        assertTrue(body.flag("written"))
        assertEquals("think about the day", prompt.current())
    }

    // ------------------------------------------------------------------
    // Bookings: refusals carry sentences
    // ------------------------------------------------------------------

    /** A half hour another booking owns is refused with that booking named. */
    @Test
    fun aCollidingBookingIsRefusedWithTheOwnersName() = runBlocking {
        val bookings = FakeBookings()
        bookings.refuseWith = "That half hour is already booked as \"Morning\"."
        val body = json(
            executor(bookings).execute(
                HeartbeatToolDefinitions.BOOK_HEARTBEAT,
                mapOf(
                    "name" to "Second",
                    "instruction" to "something else",
                    "startAt" to "2026-11-20T09:00",
                ),
            ),
        )
        assertFalse(body.flag("success"))
        assertTrue(
            "the refusal must name the booking already there: ${body.str("error")}",
            body.str("error").contains("Morning"),
        )
    }

    /** A start time that is not a date is refused rather than silently becoming now. */
    @Test
    fun anUnreadableStartTimeIsRefused() = runBlocking {
        val body = json(
            executor().execute(
                HeartbeatToolDefinitions.BOOK_HEARTBEAT,
                mapOf("name" to "X", "instruction" to "Y", "startAt" to "next tuesday-ish"),
            ),
        )
        assertFalse(body.flag("success"))
        assertTrue(body.str("error").contains("not a date"))
    }

    /** A missing start time is refused with the argument named. */
    @Test
    fun aMissingStartTimeIsRefused() = runBlocking {
        val body = json(
            executor().execute(
                HeartbeatToolDefinitions.BOOK_HEARTBEAT,
                mapOf("name" to "X", "instruction" to "Y"),
            ),
        )
        assertFalse(body.flag("success"))
        assertTrue(body.str("error").contains("startAt"))
    }

    /** Booking on the half hour reports the slot it actually got. */
    @Test
    fun bookingOnAHalfHourReportsThatSlot() = runBlocking {
        val bookings = FakeBookings()
        val body = json(
            executor(bookings).execute(
                HeartbeatToolDefinitions.BOOK_HEARTBEAT,
                mapOf(
                    "name" to "Morning",
                    "instruction" to "read the news",
                    "startAt" to "2026-11-20T09:00",
                ),
            ),
        )
        assertTrue(body.flag("success"))
        assertEquals("one booking stored", 1, bookings.rows.size)
        assertTrue("the slot is described in words: ${body.str("slot")}", body.str("slot").contains("09:00"))
    }

    /** A time between half hours is snapped, and the reply says it was snapped. */
    @Test
    fun bookingBetweenHalfHoursSnapsAndSaysItDid() = runBlocking {
        val body = json(
            executor().execute(
                HeartbeatToolDefinitions.BOOK_HEARTBEAT,
                mapOf(
                    "name" to "Morning",
                    "instruction" to "read the news",
                    "startAt" to "2026-11-20T14:17",
                ),
            ),
        )
        assertTrue(body.flag("success"))
        assertTrue("the slot is on a half hour: ${body.str("slot")}", body.str("slot").contains("14:00"))
        assertTrue(
            "the snap must be stated rather than silent: ${body.str("note")}",
            body.str("note").contains("snapped") || body.str("note").contains("half hours only"),
        )
    }

    /**
     * The manual fire reports acceptance, never that work happened.
     *
     * THE TOOL CANNOT KNOW WHETHER THE BEAT DID ANYTHING. It knows Android took the
     * request. The occurrence's state and outcome are written by the beat itself, which is
     * why the message tells a caller to read the day. A reply claiming the work ran would
     * be the same lie as reporting a scheduled booking as executed.
     */
    @Test
    fun firingByHandReportsAcceptanceAndPointsAtTheDiaryForTheResult() = runBlocking {
        val body = json(executor().execute(HeartbeatToolDefinitions.FIRE_HEARTBEAT_NOW, emptyMap()))
        // A relaxed Context accepts startForegroundService, so the request is accepted.
        assertTrue("the request was accepted", body.flag("accepted"))
        assertTrue(
            "the reply must send the caller to the diary for the real result: ${body.str("message")}",
            body.str("message").contains("read the day") || body.str("message").contains("readHeartbeatDay"),
        )
    }

    /**
     * Turning a booking off disables and does not delete.
     *
     * A CANCELLED BOOKING IS A RECORD. The diary has to keep it, because the occurrences
     * it already produced are history and the rule explains what produced them.
     */
    @Test
    fun turningABookingOffDisablesRatherThanDeleting() = runBlocking {
        val bookings = FakeBookings()
        bookings.rows.add(booking("b1"))
        val body = json(
            executor(bookings).execute(
                HeartbeatToolDefinitions.SET_HEARTBEAT_ENABLED,
                mapOf("id" to "b1", "enabled" to false),
            ),
        )
        assertTrue(body.flag("success"))
        assertEquals("the booking is still there", 1, bookings.rows.size)
        assertFalse("and it is switched off", bookings.rows.single().enabled)
        assertTrue("removal was never called", bookings.removed.isEmpty())
        assertEquals(listOf("b1" to false), bookings.enabledCalls)
    }

    /** An id that does not exist is refused by name rather than silently doing nothing. */
    @Test
    fun anUnknownBookingIdIsRefused() = runBlocking {
        val body = json(
            executor().execute(
                HeartbeatToolDefinitions.SET_HEARTBEAT_ENABLED,
                mapOf("id" to "nope", "enabled" to true),
            ),
        )
        assertFalse(body.flag("success"))
        assertTrue(body.str("error").contains("nope"))
    }

    /** A listing with nothing in it reports zero rather than failing. */
    @Test
    fun anEmptyDiaryListsZeroBookings() = runBlocking {
        val body = json(executor().execute(HeartbeatToolDefinitions.LIST_BOOKINGS, emptyMap()))
        assertEquals(0, body.num("total"))
    }

    /** A listing carries the rule, so a caller sees what was booked rather than only a name. */
    @Test
    fun aListingCarriesTheRepeatRuleAndTheSlot() = runBlocking {
        val bookings = FakeBookings()
        bookings.rows.add(booking("b1"))
        val body = json(executor(bookings).execute(HeartbeatToolDefinitions.LIST_BOOKINGS, emptyMap()))
        assertEquals(1, body.num("total"))
        val entry = body.arr("bookings").single().jsonObject
        assertEquals("DAILY", entry.str("repeatKind"))
        assertEquals("Morning", entry.str("name"))
        assertTrue("the slot is described in words", entry.str("startsAt").isNotBlank())
    }

    private fun JsonObject.arr(key: String): JsonArray =
        (this[key] as? JsonArray) ?: error("'$key' missing")

    // ------------------------------------------------------------------
    // The tools exist and are described
    // ------------------------------------------------------------------

    /** Every verb the order names is declared, because a name missing here is a missing capability. */
    @Test
    fun everyHeartbeatVerbIsDeclared() {
        val names = HeartbeatToolDefinitions.definitions.map { it.name }.toSet()
        for (expected in listOf(
            HeartbeatToolDefinitions.GET_HEARTBEAT_PROMPT,
            HeartbeatToolDefinitions.SAVE_HEARTBEAT_PROMPT,
            HeartbeatToolDefinitions.LIST_BOOKINGS,
            HeartbeatToolDefinitions.BOOK_HEARTBEAT,
            HeartbeatToolDefinitions.UPDATE_HEARTBEAT,
            HeartbeatToolDefinitions.SET_HEARTBEAT_ENABLED,
            HeartbeatToolDefinitions.READ_HEARTBEAT_DAY,
            HeartbeatToolDefinitions.REMOVE_HEARTBEAT,
            HeartbeatToolDefinitions.FIRE_HEARTBEAT_NOW,
        )) {
            assertTrue("'$expected' is not declared", expected in names)
        }
    }

    /** Each definition describes what it does, because that text is the model's only instruction. */
    @Test
    fun everyHeartbeatVerbIsDescribedAndCategorised() {
        for (definition in HeartbeatToolDefinitions.definitions) {
            assertTrue("'${definition.name}' has no description", definition.description.isNotBlank())
            assertEquals("'${definition.name}' belongs in heartbeat", "heartbeat", definition.category)
        }
    }

    /** No heartbeat tool declares an Android permission: the diary is the app's own database. */
    @Test
    fun noHeartbeatToolDeclaresAPermission() {
        for (definition in HeartbeatToolDefinitions.definitions) {
            assertTrue(
                "'${definition.name}' declares ${definition.permissions}, but this domain needs none",
                definition.permissions.isEmpty(),
            )
        }
    }
}
