package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.MONTH_GRID_CELL_COUNT
import com.unuslumen.app.domain.model.CalendarDay
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named

private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L

@Factory
class GetMonthEventsUseCase(
    private val getEventsWithinRangeUseCase: GetEventsWithinRangeUseCase,
    @Named("defaultDispatcher") private val defaultDispatcher: CoroutineDispatcher
) {
    suspend operator fun invoke(
        month: YearMonth,
        excludedCalendars: List<Int>,
        firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY
    ): List<CalendarDay> {
        return withContext(defaultDispatcher) {
            val firstOfMonth = month.firstDay
            val startOffset = firstOfMonth.dayOfWeek.dayNumberFrom(firstDayOfWeek)
            val startDate = firstOfMonth.minus(startOffset, DateTimeUnit.DAY)

            val gridDays = MONTH_GRID_CELL_COUNT.toLong()
            val endDate = startDate.plus(gridDays, DateTimeUnit.DAY)

            val startMillis = startDate.atTime(hour = 0, minute = 0).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
            val endMillis = endDate.atTime(hour = 23, minute = 59).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()

            val events = getEventsWithinRangeUseCase(startMillis, endMillis, excludedCalendars)

            val eventsByDayIndex = events.groupBy { event ->
                ((event.start - startMillis) / MILLIS_PER_DAY).toInt()
            }

            (0 until gridDays.toInt()).map { index ->
                val dayDate = startDate.plus(index, DateTimeUnit.DAY)
                CalendarDay(
                    date = dayDate,
                    isCurrentMonth = dayDate.month == month.month && dayDate.year == month.year,
                    events = eventsByDayIndex[index].orEmpty()
                )
            }
        }
    }
}

fun DayOfWeek.dayNumberFrom(firstDayOfWeek: DayOfWeek): Int {
    val thisDayOrdinal = this.ordinal
    val firstDayOrdinal = firstDayOfWeek.ordinal
    return (thisDayOrdinal - firstDayOrdinal + 7) % 7
}

