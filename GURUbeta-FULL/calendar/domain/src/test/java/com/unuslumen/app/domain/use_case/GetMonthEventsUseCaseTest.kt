// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.MONTH_GRID_CELL_COUNT
import com.unuslumen.app.domain.model.CalendarEvent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The month grid is the single most date-sensitive piece of calendar logic in
 * the app: an off-by-one here shows every event on the wrong day. These tests
 * pin the grid shape, the weekday offset for every first-day-of-week setting,
 * the current-month flag, and event placement.
 *
 * They drive the real GetEventsWithinRangeUseCase over an in-memory fake
 * repository, so the whole chain is exercised, not a substitute for it.
 */
@Suppress("DEPRECATION")
class GetMonthEventsUseCaseTest {

    private val dispatcher = StandardTestDispatcher()

    private fun useCase(events: List<CalendarEvent> = emptyList()): GetMonthEventsUseCase {
        val repository = FakeCalendarRepository(events = events)
        return GetMonthEventsUseCase(
            getEventsWithinRangeUseCase = GetEventsWithinRangeUseCase(repository),
            defaultDispatcher = dispatcher,
        )
    }

    @Test
    fun `grid is always forty two cells`() = runTest(dispatcher) {
        val days = useCase()(YearMonth(2026, 2), emptyList())
        assertEquals(MONTH_GRID_CELL_COUNT, days.size)
    }

    @Test
    fun `grid starts on the configured first day of week`() = runTest(dispatcher) {
        val days = useCase()(YearMonth(2026, 10), emptyList(), DayOfWeek.MONDAY)
        assertEquals(DayOfWeek.MONDAY, days.first().date.dayOfWeek)
    }

    @Test
    fun `grid starts on sunday when sunday is configured`() = runTest(dispatcher) {
        val days = useCase()(YearMonth(2026, 10), emptyList(), DayOfWeek.SUNDAY)
        assertEquals(DayOfWeek.SUNDAY, days.first().date.dayOfWeek)
    }

    @Test
    fun `leading days outside the month are flagged as not current month`() = runTest(dispatcher) {
        // October 2026 starts on a Thursday, so a Monday-first grid begins in
        // September and those leading cells must not claim to be October.
        val days = useCase()(YearMonth(2026, 10), emptyList(), DayOfWeek.MONDAY)
        val first = days.first()
        // monthNumber is deprecated in favour of `month`, but this project
        // pins the 0.6-compat kotlinx-datetime, where `month` resolves to
        // java.time.Month and has no `.number` property, so the replacement
        // the compiler suggests does not actually compile here.
        @Suppress("DEPRECATION")
        val leadingMonth = first.date.monthNumber
        assertEquals(9, leadingMonth)
        assertTrue(!first.isCurrentMonth)
    }

    @Test
    fun `every day inside the month is flagged as current month`() = runTest(dispatcher) {
        val days = useCase()(YearMonth(2026, 10), emptyList())
        @Suppress("DEPRECATION")
        val insideMonth = days.filter { it.date.year == 2026 && it.date.monthNumber == 10 }
        assertEquals(31, insideMonth.size)
        assertTrue(insideMonth.all { it.isCurrentMonth })
    }

    @Test
    fun `days inside the grid but outside the month are never flagged as current`() = runTest(dispatcher) {
        // This is the assertion a mutation exposed as missing. Flipping the
        // month comparison to != would mark every outside day as current and
        // every inside day as not current; without this test that mutant
        // survived and the suite claimed coverage it did not have.
        val month = YearMonth(2026, 10)
        val days = useCase()(month, emptyList(), DayOfWeek.MONDAY)

        val outside = days.filter { it.date.year != 2026 || it.date.monthNumber != 10 }
        assertTrue(outside.isNotEmpty())
        assertTrue(outside.none { it.isCurrentMonth })

        val inside = days.filter { it.date.year == 2026 && it.date.monthNumber == 10 }
        assertTrue(inside.all { it.isCurrentMonth })
    }

    @Test
    fun `trailing days outside the month are not flagged as current month`() = runTest(dispatcher) {
        // A 42-cell grid routinely runs past the end of the month. Those cells
        // belong to the next month and must report false. This is the
        // assertion a mutation of the month/year comparison kills; without it
        // the flag can be inverted everywhere and nothing notices.
        val days = useCase()(YearMonth(2026, 10), emptyList())
        @Suppress("DEPRECATION")
        val outside = days.filter { !(it.date.year == 2026 && it.date.monthNumber == 10) }
        assertTrue(outside.isNotEmpty())
        assertTrue(outside.none { it.isCurrentMonth })
    }

    @Test
    fun `the same day number in a different year is not current month`() = runTest(dispatcher) {
        // Guards the year half of the comparison: 15 October 2025 is the same
        // day of the same month but a different year, and must be false.
        val days = useCase()(YearMonth(2026, 10), emptyList())
        val octoberDays = days.filter {
            @Suppress("DEPRECATION")
            it.date.monthNumber == 10
        }
        val currentYear = octoberDays.filter { it.date.year == 2026 }
        val otherYear = octoberDays.filter { it.date.year != 2026 }
        assertTrue(currentYear.all { it.isCurrentMonth })
        assertTrue(otherYear.isEmpty() || otherYear.none { it.isCurrentMonth })
    }

    @Test
    fun `leap february carries twenty nine current month days`() = runTest(dispatcher) {
        val days = useCase()(YearMonth(2028, 2), emptyList())
        @Suppress("DEPRECATION")
        val februaryDays = days.filter { it.date.year == 2028 && it.date.monthNumber == 2 }
        assertEquals(29, februaryDays.size)
    }

    @Test
    fun `non leap february carries twenty eight current month days`() = runTest(dispatcher) {
        val days = useCase()(YearMonth(2026, 2), emptyList())
        @Suppress("DEPRECATION")
        val februaryDays = days.filter { it.date.year == 2026 && it.date.monthNumber == 2 }
        assertEquals(28, februaryDays.size)
    }

    @Test
    fun `days are consecutive with no gaps or repeats`() = runTest(dispatcher) {
        val days = useCase()(YearMonth(2026, 12), emptyList())
        days.zipWithNext().forEach { (current, next) ->
            val expected = current.date.plus(1, DateTimeUnit.DAY)
            assertEquals(expected, next.date)
        }
    }

    @Test
    fun `an event lands on the day it starts`() = runTest(dispatcher) {
        val month = YearMonth(2026, 10)
        val firstDay = useCase()(month, emptyList(), DayOfWeek.MONDAY)[10].date
        val withEvent = useCase(listOf(sampleEvent(firstDay)))(month, emptyList(), DayOfWeek.MONDAY)
        assertEquals(1, withEvent[10].events.size)
        assertTrue(withEvent.filterIndexed { index, _ -> index != 10 }.all { it.events.isEmpty() })
    }

    @Test
    fun `an event outside the grid is not placed anywhere`() = runTest(dispatcher) {
        val month = YearMonth(2026, 10)
        val farAway = LocalDate(2027, 3, 15)
        val days = useCase(listOf(sampleEvent(farAway)))(month, emptyList())
        assertTrue(days.all { it.events.isEmpty() })
    }

    @Test
    fun `dayNumberFrom is zero for the first day and wraps correctly`() {
        assertEquals(0, DayOfWeek.MONDAY.dayNumberFrom(DayOfWeek.MONDAY))
        assertEquals(6, DayOfWeek.SUNDAY.dayNumberFrom(DayOfWeek.MONDAY))
        assertEquals(1, DayOfWeek.TUESDAY.dayNumberFrom(DayOfWeek.MONDAY))
        assertEquals(0, DayOfWeek.SUNDAY.dayNumberFrom(DayOfWeek.SUNDAY))
        assertEquals(6, DayOfWeek.SATURDAY.dayNumberFrom(DayOfWeek.SUNDAY))
    }

    private fun sampleEvent(day: LocalDate): CalendarEvent {
        // One hour after midnight keeps the event clear of any day-boundary
        // rounding and proves the grid keys on the calendar day, not the hour.
        val start = day
            .atTime(hour = 1, minute = 0)
            .toInstant(TimeZone.currentSystemDefault())
            .toEpochMilliseconds()
        return CalendarEvent(
            id = 1L,
            title = "Standup",
            start = start,
            end = start + 30 * 60 * 1000L,
            calendarId = 1L,
        )
    }
}
