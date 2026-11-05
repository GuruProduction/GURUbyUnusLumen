// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.heartbeat

import com.unuslumen.app.domain.model.BookingCombine
import com.unuslumen.app.domain.model.EntryState
import com.unuslumen.app.domain.model.HeartbeatBooking
import com.unuslumen.app.domain.model.HeartbeatSlot
import com.unuslumen.app.domain.model.RepeatKind
import com.unuslumen.app.domain.repository.HeartbeatPromptRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The diary's decisions, proved on the JVM.
 *
 * WHAT THIS DOES NOT TEST AND WHY. It does not test the drawing, because a Compose tree
 * asserts almost nothing worth asserting: a card in the wrong place still renders. What it
 * tests is every judgement the screen is built from — which slots exist, which half hour a
 * booking owns, what the sheet refuses, and what a beat would send if the sheet were saved.
 *
 * THE REPOSITORY IS A REAL FAKE, not a mock. MockK unwraps Result, so a stubbed
 * `Result.success(Unit)` is handed back as `Unit` and the view model's fold throws a
 * ClassCastException. The fake answers what the interface says and holds state, so the
 * assertions here are about what the diary became rather than which calls were made.
 *
 * THE CLOCK IS PINNED so the calendar is the same on every machine, and the dispatcher is
 * a test one so a launched coroutine cannot outlive the assertion that checks it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeartbeatDiaryViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val london = TimeZone.getTimeZone("Europe/London")

    private lateinit var repository: FakeHeartbeatBookingRepository
    private lateinit var promptRepository: HeartbeatPromptRepository

    /** Thursday 5 November 2026, 09:14 local. A beat that woke would be in the 09:00 slot. */
    private val now: Long = Calendar.getInstance(london).apply {
        clear()
        set(2026, 10, 5, 9, 14, 0)
    }.timeInMillis

    private fun dayStart(): Long = HeartbeatSlot.startOfDay(now, london)

    private fun at(hour: Int, minute: Int): Long = Calendar.getInstance(london).apply {
        clear()
        set(2026, 10, 5, hour, minute, 0)
    }.timeInMillis

    private fun otherDay(hour: Int, minute: Int, dayOfMonth: Int): Long =
        Calendar.getInstance(london).apply {
            clear()
            set(2026, 10, dayOfMonth, hour, minute, 0)
        }.timeInMillis

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeHeartbeatBookingRepository(now = { now })
        promptRepository = mockk(relaxed = true)
        coEvery { promptRepository.current() } returns "Look at the day."
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = HeartbeatDiaryViewModel(
        bookingRepository = repository,
        promptRepository = promptRepository,
        nowMillis = { now },
    )

    /** Opens the diary and settles every launched coroutine. */
    private fun started(): HeartbeatDiaryViewModel {
        val model = viewModel()
        return model
    }

    // ---------- the day ----------

    @Test
    fun `the day opens on today and draws all forty-eight half hours`() = runTest(dispatcher) {
        val model = started()
        advanceUntilIdle()

        val state = model.state.value
        assertEquals(dayStart(), state.selectedDayStart)
        assertEquals(48, state.slots.size)
        assertTrue("every slot is on a half hour", state.slots.all { HeartbeatSlot.isOnSlot(it.slotAt) })
        assertTrue("an empty diary has nothing booked", state.slots.none { it.isBooked })
    }

    @Test
    fun `the diary reconciles the calendar when it opens`() = runTest(dispatcher) {
        val model = started()
        advanceUntilIdle()

        assertEquals(1, repository.reconcileCount)
        assertNotNull(model.state.value)
    }

    @Test
    fun `a booked half hour draws as a card and its neighbours stay open`() = runTest(dispatcher) {
        repository.book(
            HeartbeatBooking(
                id = "b",
                name = "Inbox",
                instruction = "Read mail.",
                repeat = RepeatKind.DAILY,
                startsAt = at(9, 0),
            )
        )

        val model = started()
        advanceUntilIdle()
        model.onEvent(DiaryEvent.SelectDay(dayStart()))
        advanceUntilIdle()

        val slots = model.state.value.slots
        assertEquals("Inbox", slots.first { it.slotAt == at(9, 0) }.booking?.name)
        assertFalse("the half hour after it is open", slots.first { it.slotAt == at(9, 30) }.isBooked)
        assertFalse("and the one before it", slots.first { it.slotAt == at(8, 30) }.isBooked)
    }

    @Test
    fun `an entry's real state reaches the slot that owns it`() = runTest(dispatcher) {
        repository.book(
            HeartbeatBooking(
                id = "b",
                name = "Inbox",
                instruction = "Read mail.",
                repeat = RepeatKind.DAILY,
                startsAt = at(9, 0),
            )
        )
        val entry = repository.entries.first { it.slotAt == at(9, 0) }
        repository.recordOutcome(entry.id, EntryState.RAN, at(9, 1), at(9, 4), "")

        val model = started()
        advanceUntilIdle()
        model.onEvent(DiaryEvent.SelectDay(dayStart()))
        advanceUntilIdle()

        val slot = model.state.value.slots.first { it.slotAt == at(9, 0) }
        assertEquals(EntryState.RAN, slot.entry?.state)
        assertEquals(at(9, 1), slot.entry?.firedAt)
    }

    @Test
    fun `a past half hour with no beat reads as missed, not as still booked`() = runTest(dispatcher) {
        // A booking whose occurrence was yesterday, which nothing could have run.
        repository.book(
            HeartbeatBooking(
                id = "b",
                name = "Yesterday",
                instruction = "Something.",
                repeat = RepeatKind.ONCE,
                startsAt = otherDay(9, 0, 4),
            )
        )

        val model = started()
        advanceUntilIdle()
        model.onEvent(DiaryEvent.SelectDay(HeartbeatSlot.startOfDay(otherDay(12, 0, 4), london)))
        advanceUntilIdle()

        val slot = model.state.value.slots.first { it.slotAt == otherDay(9, 0, 4) }
        assertEquals(EntryState.MISSED, slot.entry?.state)
    }

    @Test
    fun `moving a week lands on the same weekday seven days later`() = runTest(dispatcher) {
        val model = started()
        advanceUntilIdle()

        model.onEvent(DiaryEvent.ShiftWeek(1))
        advanceUntilIdle()

        val moved = model.state.value.selectedDayStart
        assertEquals(dayStart() + 7 * DiaryState.DAY_MILLIS, moved)
        assertEquals(
            HeartbeatSlot.weekdayIndex(dayStart(), london),
            HeartbeatSlot.weekdayIndex(moved, london),
        )
    }

    @Test
    fun `the week strip marks only the days that have something on them`() = runTest(dispatcher) {
        repository.book(
            HeartbeatBooking(
                id = "b",
                name = "Monday only",
                instruction = "Something.",
                repeat = RepeatKind.CHOSEN,
                chosenDays = setOf(0),
                startsAt = otherDay(8, 0, 2),
            )
        )

        val model = started()
        advanceUntilIdle()
        model.onEvent(DiaryEvent.SelectDay(dayStart()))
        advanceUntilIdle()

        val marked = model.state.value.daysWithBookings
        assertEquals("only the Monday is marked", 1, marked.size)
        assertEquals(0, HeartbeatSlot.weekdayIndex(marked.first(), london))
    }

    // ---------- the sheet ----------

    @Test
    fun `opening an empty half hour gives a new sheet at that exact slot`() = runTest(dispatcher) {
        val model = started()
        advanceUntilIdle()

        model.onEvent(DiaryEvent.OpenSheet(slotAt = at(10, 0)))
        val sheet = model.state.value.sheet

        assertNotNull(sheet)
        assertEquals(at(10, 0), sheet!!.slotAt)
        assertFalse("a new booking is not an edit", sheet.isEdit)
    }

    @Test
    fun `pressing an occupied half hour edits that booking instead of making a second one`() =
        runTest(dispatcher) {
            repository.book(
                HeartbeatBooking(
                    id = "b",
                    name = "Inbox",
                    instruction = "Read mail.",
                    repeat = RepeatKind.DAILY,
                    startsAt = at(9, 0),
                )
            )

            val model = started()
            advanceUntilIdle()
            model.onEvent(DiaryEvent.SelectDay(dayStart()))
            advanceUntilIdle()
            model.onEvent(DiaryEvent.OpenSheet(slotAt = at(9, 0)))
            advanceUntilIdle()

            val sheet = model.state.value.sheet
            assertNotNull(sheet)
            assertTrue("it is an edit, not a new booking", sheet!!.isEdit)
            assertEquals("Inbox", sheet.name)
            assertEquals("Read mail.", sheet.instruction)
        }

    @Test
    fun `a booking cannot be saved without a name and an instruction`() = runTest(dispatcher) {
        val model = started()
        advanceUntilIdle()
        model.onEvent(DiaryEvent.OpenSheet(slotAt = at(10, 0)))
        model.onEvent(DiaryEvent.SaveBooking)
        advanceUntilIdle()

        val sheet = model.state.value.sheet
        assertNotNull("the sheet stays open so nothing is lost", sheet)
        assertNotNull("and it says why", sheet!!.error)
        assertTrue("nothing reached the diary", repository.bookings.isEmpty())
    }

    @Test
    fun `a chosen-days booking with no days is refused rather than saved`() = runTest(dispatcher) {
        val model = started()
        advanceUntilIdle()
        model.onEvent(DiaryEvent.OpenSheet(slotAt = at(10, 0)))
        model.onEvent(DiaryEvent.EditName("Nothing"))
        model.onEvent(DiaryEvent.EditInstruction("Do nothing."))
        model.onEvent(DiaryEvent.SetRepeat(RepeatKind.CHOSEN))

        // Choosing days seeds the weekdays, so the empty state is reached by turning off
        // whichever days are currently on rather than by pressing all seven blindly: a
        // blind seven would switch the seeded ones back on.
        val seeded = model.state.value.sheet!!.chosenDays.toList()
        seeded.forEach { model.onEvent(DiaryEvent.ToggleDay(it)) }
        assertTrue(
            "the sheet is genuinely empty of days: ${model.state.value.sheet!!.chosenDays}",
            model.state.value.sheet!!.chosenDays.isEmpty(),
        )

        model.onEvent(DiaryEvent.SaveBooking)
        advanceUntilIdle()

        val sheet = model.state.value.sheet
        assertNotNull(sheet)
        assertTrue(
            "the refusal says what is wrong: ${sheet!!.error}",
            sheet.error!!.contains("at least one day"),
        )
        assertTrue("nothing reached the diary", repository.bookings.isEmpty())
    }

    @Test
    fun `a saved booking carries the half hour, the rule and the combine choice`() = runTest(dispatcher) {
        val model = started()
        advanceUntilIdle()
        model.onEvent(DiaryEvent.OpenSheet(slotAt = at(10, 0)))
        model.onEvent(DiaryEvent.EditName("Morning mail"))
        model.onEvent(DiaryEvent.EditInstruction("Check the inbox."))
        model.onEvent(DiaryEvent.SetRepeat(RepeatKind.WEEKDAYS))
        model.onEvent(DiaryEvent.SetCombine(BookingCombine.INSTEAD))
        model.onEvent(DiaryEvent.SaveBooking)
        advanceUntilIdle()

        val booking = repository.bookings.single()
        assertEquals("Morning mail", booking.name)
        assertEquals("Check the inbox.", booking.instruction)
        assertEquals(RepeatKind.WEEKDAYS, booking.repeat)
        assertEquals(BookingCombine.INSTEAD, booking.combine)
        assertEquals("a booking starts at the slot it was made on", at(10, 0), booking.startsAt)
        assertNull("the sheet closes on success", model.state.value.sheet)
    }

    @Test
    fun `a saved booking's occurrences are dated and real`() = runTest(dispatcher) {
        val model = started()
        advanceUntilIdle()
        model.onEvent(DiaryEvent.OpenSheet(slotAt = at(10, 0)))
        model.onEvent(DiaryEvent.EditName("Weekdays"))
        model.onEvent(DiaryEvent.EditInstruction("Check the inbox."))
        model.onEvent(DiaryEvent.SetRepeat(RepeatKind.WEEKDAYS))
        model.onEvent(DiaryEvent.SaveBooking)
        advanceUntilIdle()

        val mine = repository.entries.filter { it.bookingId == repository.bookings.single().id }
        assertTrue("a year of weekdays is a lot of real rows", mine.size > 200)
        assertTrue("every occurrence is on a half hour", mine.all { HeartbeatSlot.isOnSlot(it.slotAt) })
        assertTrue(
            "and none of them falls at the weekend",
            mine.none { HeartbeatSlot.weekdayIndex(it.slotAt, london) >= 5 },
        )
    }

    @Test
    fun `a refused booking keeps the sheet open and shows the repository's own reason`() =
        runTest(dispatcher) {
            repository.book(
                HeartbeatBooking(
                    id = "a",
                    name = "Commute",
                    instruction = "Check roads.",
                    repeat = RepeatKind.WEEKDAYS,
                    startsAt = at(10, 0),
                )
            )

            val model = started()
            advanceUntilIdle()
            model.onEvent(DiaryEvent.SelectDay(dayStart()))
            advanceUntilIdle()

            /*
             * A DIFFERENT HALF HOUR ON A DAY THAT ALREADY HAS A BOOKING MUST BE ALLOWED.
             *
             * This is the assertion that caught a real defect: the sheet's collision check
             * asked whether the other booking fired on that DATE rather than at that SLOT,
             * so any day with one booking refused every other half hour in it.
             */
            model.onEvent(DiaryEvent.OpenSheet(slotAt = at(10, 30)))
            advanceUntilIdle()
            model.onEvent(DiaryEvent.EditName("Second"))
            model.onEvent(DiaryEvent.EditInstruction("Something else."))
            model.onEvent(DiaryEvent.SetRepeat(RepeatKind.WEEKDAYS))
            model.onEvent(DiaryEvent.SaveBooking)
            advanceUntilIdle()

            assertEquals("a different half hour is allowed", 2, repository.bookings.size)
            assertNull(model.state.value.sheet)

            // And now the real refusal: the repository declines and the sheet has to say
            // so rather than swallow it.
            repository.refuseWith =
                "That half hour is already booked as \"Commute\". Two bookings cannot share one half hour."
            model.onEvent(DiaryEvent.OpenSheet(slotAt = at(11, 0)))
            model.onEvent(DiaryEvent.EditName("Third"))
            model.onEvent(DiaryEvent.EditInstruction("Something else again."))
            model.onEvent(DiaryEvent.SaveBooking)
            advanceUntilIdle()

            val sheet = model.state.value.sheet
            assertNotNull("the sheet must stay open", sheet)
            assertTrue(
                "the refusal names the booking already there: ${sheet!!.error}",
                sheet.error!!.contains("Commute"),
            )
        }

    @Test
    fun `an edit reschedules from now rather than rewriting the past`() = runTest(dispatcher) {
        repository.book(
            HeartbeatBooking(
                id = "b",
                name = "Inbox",
                instruction = "Read mail.",
                repeat = RepeatKind.DAILY,
                startsAt = at(9, 0),
            )
        )

        val model = started()
        advanceUntilIdle()
        model.onEvent(DiaryEvent.SelectDay(dayStart()))
        advanceUntilIdle()
        model.onEvent(DiaryEvent.OpenSheet(slotAt = at(9, 0), editingId = "b"))
        model.onEvent(DiaryEvent.EditInstruction("Read only the bank."))
        model.onEvent(DiaryEvent.SaveBooking)
        advanceUntilIdle()

        assertEquals(
            "the rule now says the new thing",
            "Read only the bank.",
            repository.bookings.single().instruction,
        )
        assertTrue(
            "nothing before now was touched",
            repository.entries.none { it.slotAt < now && it.state == EntryState.PENDING },
        )
        assertTrue(
            "and the future was re-derived",
            repository.entries.any { it.slotAt > now },
        )
    }

    @Test
    fun `the sheet preview shows the composed text a beat would send`() = runTest(dispatcher) {
        val model = started()
        advanceUntilIdle()
        model.onEvent(DiaryEvent.OpenSheet(slotAt = at(10, 0)))
        model.onEvent(DiaryEvent.EditName("Morning mail"))
        model.onEvent(DiaryEvent.EditInstruction("Check the inbox."))

        val alongside = model.previewFor(model.state.value.sheet!!)
        assertTrue("the master prompt is included: $alongside", alongside.contains("Look at the day."))
        assertTrue("and the booking: $alongside", alongside.contains("Check the inbox."))
        assertTrue("and it is named: $alongside", alongside.contains("Morning mail"))

        model.onEvent(DiaryEvent.SetCombine(BookingCombine.INSTEAD))
        val instead = model.previewFor(model.state.value.sheet!!)
        assertEquals("Check the inbox.", instead)
        assertFalse(
            "instead means instead, and the master prompt must not leak in: $instead",
            instead.contains("Look at the day."),
        )
    }

    @Test
    fun `a preview updates as the instruction is typed`() = runTest(dispatcher) {
        val model = started()
        advanceUntilIdle()
        model.onEvent(DiaryEvent.OpenSheet(slotAt = at(10, 0)))
        model.onEvent(DiaryEvent.EditInstruction("First draft."))
        assertTrue(model.previewFor(model.state.value.sheet!!).contains("First draft."))

        model.onEvent(DiaryEvent.EditInstruction("Second draft."))
        val second = model.previewFor(model.state.value.sheet!!)
        assertTrue(second.contains("Second draft."))
        assertFalse("the old draft is gone: $second", second.contains("First draft."))
    }

    @Test
    fun `deleting a booking removes it and closes the sheet`() = runTest(dispatcher) {
        repository.book(
            HeartbeatBooking(
                id = "b",
                name = "Inbox",
                instruction = "Read mail.",
                repeat = RepeatKind.DAILY,
                startsAt = at(9, 0),
            )
        )

        val model = started()
        advanceUntilIdle()
        model.onEvent(DiaryEvent.SelectDay(dayStart()))
        advanceUntilIdle()
        model.onEvent(DiaryEvent.OpenSheet(slotAt = at(9, 0), editingId = "b"))
        model.onEvent(DiaryEvent.DeleteBooking("b"))
        advanceUntilIdle()

        assertNull(model.state.value.sheet)
        assertTrue("the booking is gone", repository.bookings.isEmpty())
    }

    @Test
    fun `turning a booking off clears what has not happened and keeps what has`() = runTest(dispatcher) {
        repository.book(
            HeartbeatBooking(
                id = "b",
                name = "Inbox",
                instruction = "Read mail.",
                repeat = RepeatKind.DAILY,
                startsAt = at(9, 0),
            )
        )
        val pastEntry = repository.entries.first { it.slotAt == at(9, 0) }
        repository.recordOutcome(pastEntry.id, EntryState.RAN, at(9, 1), at(9, 5), "")

        val model = started()
        advanceUntilIdle()
        model.onEvent(DiaryEvent.SetBookingEnabled("b", false))
        advanceUntilIdle()

        assertFalse("the rule is off", repository.bookings.single().enabled)
        assertTrue(
            "what already ran is kept",
            repository.entries.any { it.id == pastEntry.id },
        )
        assertTrue(
            "what had not happened is cleared",
            repository.entries.none { it.state == EntryState.PENDING },
        )
    }

    @Test
    fun `turning a booking back on re-derives its future from the rule`() = runTest(dispatcher) {
        repository.book(
            HeartbeatBooking(
                id = "b",
                name = "Inbox",
                instruction = "Read mail.",
                repeat = RepeatKind.DAILY,
                startsAt = otherDay(9, 0, 6),
            )
        )
        repository.setEnabled("b", false)
        val afterOff = repository.entries.size

        val model = started()
        advanceUntilIdle()
        model.onEvent(DiaryEvent.SetBookingEnabled("b", true))
        advanceUntilIdle()

        assertTrue("the diary is populated again", repository.entries.isNotEmpty())
        assertTrue(
            "and it is a real calendar rather than a single row: had $afterOff",
            repository.entries.size > afterOff,
        )
    }

    @Test
    fun `toggling a day turns the repeat into chosen days`() = runTest(dispatcher) {
        val model = started()
        advanceUntilIdle()
        model.onEvent(DiaryEvent.OpenSheet(slotAt = at(10, 0)))
        model.onEvent(DiaryEvent.SetRepeat(RepeatKind.WEEKDAYS))

        // Saturday, which weekdays does not cover.
        model.onEvent(DiaryEvent.ToggleDay(5))
        val sheet = model.state.value.sheet!!

        assertEquals(RepeatKind.CHOSEN, sheet.repeat)
        assertTrue("the picked day is on", 5 in sheet.chosenDays)
    }

    @Test
    fun `every half hour of the day is drawn, not only the booked ones`() = runTest(dispatcher) {
        repository.book(
            HeartbeatBooking(
                id = "b",
                name = "Inbox",
                instruction = "Read mail.",
                repeat = RepeatKind.DAILY,
                startsAt = at(9, 0),
            )
        )

        val model = started()
        advanceUntilIdle()
        model.onEvent(DiaryEvent.SelectDay(dayStart()))
        advanceUntilIdle()

        val slots = model.state.value.slots
        assertEquals(48, slots.size)
        assertEquals("exactly one booking", 1, slots.count { it.isBooked })
        assertTrue(
            "the open ones are still there to press",
            slots.count { !it.isBooked } == 47,
        )
    }
}
