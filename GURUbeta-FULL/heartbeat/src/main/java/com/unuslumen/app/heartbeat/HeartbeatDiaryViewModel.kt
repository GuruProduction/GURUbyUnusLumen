// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.heartbeat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.model.BookingCombine
import com.unuslumen.app.domain.model.HeartbeatBooking
import com.unuslumen.app.domain.model.HeartbeatEntry
import com.unuslumen.app.domain.model.HeartbeatExpansion
import com.unuslumen.app.domain.model.HeartbeatSlot
import com.unuslumen.app.domain.model.RepeatKind
import com.unuslumen.app.domain.model.composeBeatInstruction
import com.unuslumen.app.domain.repository.HeartbeatBookingRepository
import com.unuslumen.app.domain.repository.HeartbeatPromptRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone
import kotlin.uuid.Uuid

/**
 * One half hour as the diary draws it.
 *
 * THE SAME TYPE FOR A BOOKED SLOT AND AN EMPTY ONE. The timeline is forty-eight rows and
 * almost all of them are empty, so the empty ones are a row with no booking rather than a
 * second type the screen has to branch on everywhere.
 */
data class DiarySlot(
    /** The absolute half hour. */
    val slotAt: Long,
    /** What is booked here, or null when the half hour is open. */
    val booking: HeartbeatBooking? = null,
    /** The dated occurrence, when there is one. */
    val entry: HeartbeatEntry? = null,
) {
    val isBooked: Boolean get() = booking != null
}

/** The booking sheet, as an object rather than eight loose fields. */
data class BookingSheet(
    /** The half hour being booked or edited. */
    val slotAt: Long,
    /** The booking being edited, or null when this is a new one. */
    val editingId: String? = null,
    val name: String = "",
    val instruction: String = "",
    val repeat: RepeatKind = RepeatKind.WEEKDAYS,
    val chosenDays: Set<Int> = setOf(0, 1, 2, 3, 4),
    val combine: BookingCombine = BookingCombine.ALONGSIDE,
    val saving: Boolean = false,
    val error: String? = null,
) {
    /** A booking needs a name and something to do. */
    val canSave: Boolean get() = name.isNotBlank() && instruction.isNotBlank() && !saving

    /** A chosen-days booking needs at least one day, or it would never fire. */
    val validDays: Boolean get() = repeat != RepeatKind.CHOSEN || chosenDays.isNotEmpty()

    val isEdit: Boolean get() = editingId != null
}

/**
 * The diary tab's state.
 *
 * WHAT MAKES THIS A CALENDAR AND NOT A LIST. It holds a selected day, the week that day
 * sits in, and the occurrences for the day itself. Everything the timeline draws is here,
 * including the empty half hours, because a diary a person can read has to show the space
 * between bookings as well as the bookings.
 */
data class DiaryState(
    val isLoading: Boolean = true,
    /** Midnight of the day being looked at. */
    val selectedDayStart: Long = 0L,
    val bookings: List<HeartbeatBooking> = emptyList(),
    val slots: List<DiarySlot> = emptyList(),
    /** Which days in the shown week have anything on them, for the strip's marks. */
    val daysWithBookings: Set<Long> = emptySet(),
    /** The sheet, when one is open. */
    val sheet: BookingSheet? = null,
    val notice: String? = null,
    val error: String? = null,
    /** The master prompt, so the sheet can show exactly what a beat would inject. */
    val masterPrompt: String = "",
) {
    /** The seven midnights of the shown week, Monday first. */
    val weekDays: List<Long> get() = mondayOf(selectedDayStart).let { monday ->
        (0..6).map { monday + it * DAY_MILLIS }
    }

    /** How many of the shown day's half hours are booked. */
    val bookedCount: Int get() = slots.count { it.isBooked }

    /** True when any booking in the shown day is working right now. */
    val runningCount: Int get() = slots.count { it.entry?.state?.name == "RUNNING" }

    companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L

        /** Midnight of the Monday of the week an instant falls in. */
        fun mondayOf(instantMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long {
            val start = HeartbeatSlot.startOfDay(instantMillis, zone)
            val weekday = HeartbeatSlot.weekdayIndex(start, zone)
            return start - weekday * DAY_MILLIS
        }
    }
}

sealed interface DiaryEvent {
    data object Load : DiaryEvent
    /** Look at a different day. */
    data class SelectDay(val dayStart: Long) : DiaryEvent
    /** Move the week back or forward, keeping the same weekday selected. */
    data class ShiftWeek(val weeks: Int) : DiaryEvent
    /** Open the sheet for an empty half hour, or for an existing booking's own slot. */
    data class OpenSheet(val slotAt: Long, val editingId: String? = null) : DiaryEvent
    data class EditName(val text: String) : DiaryEvent
    data class EditInstruction(val text: String) : DiaryEvent
    data class SetRepeat(val repeat: RepeatKind) : DiaryEvent
    data class ToggleDay(val dayIndex: Int) : DiaryEvent
    data class SetCombine(val combine: BookingCombine) : DiaryEvent
    data object SaveBooking : DiaryEvent
    data object CloseSheet : DiaryEvent
    /** Remove a booking. Its past stays; only what has not happened goes. */
    data class DeleteBooking(val id: String) : DiaryEvent
    data class SetBookingEnabled(val id: String, val enabled: Boolean) : DiaryEvent
    data object ClearNotice : DiaryEvent
}

/**
 * The diary: the bookings, the half hours, and the sheet that makes one.
 *
 * WHY THIS IS ITS OWN VIEW MODEL. The prompt tab and the diary tab are two different
 * jobs sharing a switch. The prompt's state is a draft and a preview; the diary's is a
 * calendar with a selected day, a week and a sheet. Folding them into one object would
 * mean every event had to be filtered against every field, and the room would carry the
 * union of both states at all times.
 *
 * THE CLOCK IS AN ARGUMENT, NOT A CALL. Every place that needs "now" takes it from
 * [nowMillis], so a JVM test can drive a whole year of a diary without waiting for one.
 *
 * ONE DAY IS FORTY-EIGHT SLOTS AND ALL OF THEM ARE DRAWN. Not just the booked ones,
 * because the empty half hour is the thing you press to book. The list is built here
 * rather than in the composable so it can be asserted.
 */
@org.koin.android.annotation.KoinViewModel
class HeartbeatDiaryViewModel(
    private val bookingRepository: HeartbeatBookingRepository,
    private val promptRepository: HeartbeatPromptRepository,
    /** The clock, injected so the calendar can be driven in a test. */
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    private val _state = MutableStateFlow(DiaryState())
    val state: StateFlow<DiaryState> = _state.asStateFlow()

    private var dayJob: Job? = null

    /** Slots already claimed by a booking, so the sheet can refuse a second one. */
    private var claimedSlots: Set<Long> = emptySet()

    init {
        onEvent(DiaryEvent.Load)
    }

    fun onEvent(event: DiaryEvent) {
        when (event) {
            DiaryEvent.Load -> load()
            is DiaryEvent.SelectDay -> selectDay(event.dayStart)
            is DiaryEvent.ShiftWeek -> shiftWeek(event.weeks)
            is DiaryEvent.OpenSheet -> openSheet(event.slotAt, event.editingId)
            is DiaryEvent.EditName -> updateSheet { it.copy(name = event.text, error = null) }
            is DiaryEvent.EditInstruction ->
                updateSheet { it.copy(instruction = event.text, error = null) }
            is DiaryEvent.SetRepeat -> updateSheet { sheet ->
                // Choosing days seeds the mask with the weekdays, so a person pressing
                // "Days" sees a sensible week rather than seven empty cells.
                sheet.copy(
                    repeat = event.repeat,
                    chosenDays = when {
                        event.repeat == RepeatKind.CHOSEN && sheet.chosenDays.isEmpty() ->
                            setOf(0, 1, 2, 3, 4)
                        else -> sheet.chosenDays
                    },
                    error = null,
                )
            }
            is DiaryEvent.ToggleDay -> updateSheet { sheet ->
                val days = if (event.dayIndex in sheet.chosenDays) {
                    sheet.chosenDays - event.dayIndex
                } else {
                    sheet.chosenDays + event.dayIndex
                }
                sheet.copy(
                    chosenDays = days,
                    // Picking days is itself a statement that the repeat is CHOSEN.
                    repeat = RepeatKind.CHOSEN,
                    error = null,
                )
            }
            is DiaryEvent.SetCombine -> updateSheet { it.copy(combine = event.combine, error = null) }
            DiaryEvent.SaveBooking -> save()
            DiaryEvent.CloseSheet -> _state.update { it.copy(sheet = null) }
            is DiaryEvent.DeleteBooking -> delete(event.id)
            is DiaryEvent.SetBookingEnabled -> setEnabled(event.id, event.enabled)
            DiaryEvent.ClearNotice -> _state.update { it.copy(notice = null, error = null) }
        }
    }

    // ---------- loading ----------

    private fun load() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            // The calendar is topped up and yesterday's gaps are named before it is drawn,
            // so what the room shows is the reconciled diary rather than a hopeful plan.
            runCatching { bookingRepository.reconcile(nowMillis()) }

            val master = runCatching { promptRepository.current() }.getOrDefault("")
            val today = HeartbeatSlot.startOfDay(nowMillis())
            _state.update {
                it.copy(
                    isLoading = false,
                    selectedDayStart = if (it.selectedDayStart == 0L) today else it.selectedDayStart,
                    masterPrompt = master,
                )
            }
            refreshBookings()
            observeDay(_state.value.selectedDayStart)
        }
    }

    private fun refreshBookings() {
        viewModelScope.launch {
            val bookings = runCatching { bookingRepository.bookings() }.getOrDefault(emptyList())
            _state.update { it.copy(bookings = bookings) }
            refreshWeekMarks()
        }
    }

    /**
     * Recompute which days in the shown week carry anything.
     *
     * A MARK PER DAY, not the bookings themselves, because the strip only needs to say
     * whether a day has something on it. Computing it from the rules rather than from the
     * materialised rows keeps it correct even for a day whose rows have not been written
     * yet.
     */
    private fun refreshWeekMarks() {
        val state = _state.value
        val week = state.weekDays
        if (week.isEmpty()) return
        val from = week.first()
        val to = week.last() + DiaryState.DAY_MILLIS
        val marked = week.filter { day ->
            state.bookings.any { it.occursOn(day) }
        }.toSet()
        // The window bounds are used here for the strip's own range, so a day outside the
        // shown week cannot pick up a mark from a neighbouring one.
        _state.update {
            it.copy(daysWithBookings = marked.filter { day -> day in from until to }.toSet())
        }
    }

    private fun selectDay(dayStart: Long) {
        val day = HeartbeatSlot.startOfDay(dayStart)
        _state.update { it.copy(selectedDayStart = day) }
        refreshWeekMarks()
        observeDay(day)
    }

    private fun shiftWeek(weeks: Int) {
        val target = _state.value.selectedDayStart + weeks * 7 * DiaryState.DAY_MILLIS
        selectDay(target)
    }

    private fun observeDay(dayStart: Long) {
        dayJob?.cancel()
        dayJob = viewModelScope.launch {
            val dayEnd = HeartbeatSlot.endOfDay(dayStart)
            val bookings = _state.value.bookings
            val entries = runCatching { bookingRepository.observeDay(dayStart, dayEnd) }
                .getOrDefault(kotlinx.coroutines.flow.flowOf(emptyList()))

            entries.collect { dayEntries ->
                // THE RULE DECIDES WHERE A CARD SITS, THE ENTRY DECIDES WHAT IT SAYS.
                //
                // A booking whose occurrence row has not been written yet must still draw:
                // the owner booked it, and a diary that hides a booking until a
                // materialiser catches up is a diary that looks broken. So the slots are
                // derived from the rules first, and the dated entry is attached where one
                // exists.
                val bySlot = dayEntries.associateBy { it.slotAt }
                val owners = HeartbeatExpansion.slotOwners(bookings, dayStart, dayEnd)
                val slots = (0 until HeartbeatSlot.SLOTS_PER_DAY).map { index ->
                    val slotAt = dayStart + index * HeartbeatSlot.SLOT_MILLIS
                    val booking = owners[slotAt]
                    DiarySlot(
                        slotAt = slotAt,
                        booking = booking,
                        entry = bySlot[slotAt] ?: if (booking != null) {
                            // No row yet: the booking is real, so the card is drawn as
                            // pending rather than omitted.
                            HeartbeatEntry(
                                id = "",
                                bookingId = booking.id,
                                slotAt = slotAt,
                            )
                        } else {
                            null
                        },
                    )
                }
                claimedSlots = owners.keys
                _state.update { it.copy(slots = slots) }
            }
        }
    }

    // ---------- the sheet ----------

    private fun openSheet(slotAt: Long, editingId: String?) {
        val floor = HeartbeatSlot.floor(slotAt)
        val state = _state.value

        val existing = when {
            editingId != null -> state.bookings.firstOrNull { it.id == editingId }
            else -> state.slots.firstOrNull { it.slotAt == floor }?.booking
        }

        if (existing != null) {
            _state.update {
                it.copy(
                    sheet = BookingSheet(
                        slotAt = floor,
                        editingId = existing.id,
                        name = existing.name,
                        instruction = existing.instruction,
                        repeat = existing.repeat,
                        chosenDays = existing.chosenDays,
                        combine = existing.combine,
                    )
                )
            }
            return
        }

        /*
         * A HALF HOUR THAT BELONGS TO ANOTHER BOOKING CANNOT TAKE A SECOND ONE.
         *
         * THE CHECK IS AGAINST THE EXACT SLOT, NOT THE DAY. `occursOn` answers "does this
         * booking fire on that date", which is true for every half hour of a day that has
         * a booking anywhere in it. Using it here refused the second booking on any day
         * that already had one, so a diary could hold exactly one booking per day and the
         * rest of the day's forty-seven half hours looked open and refused on press.
         */
        val owner = state.bookings.firstOrNull { it.slotOn(floor) == floor }
        if (owner != null) {
            _state.update {
                it.copy(
                    error = "That half hour is already booked as \"${owner.name}\". " +
                        "Two bookings cannot share one half hour.",
                )
            }
            return
        }

        _state.update {
            it.copy(
                sheet = BookingSheet(slotAt = floor),
                error = null,
            )
        }
    }

    private fun updateSheet(change: (BookingSheet) -> BookingSheet) {
        _state.update { current ->
            current.sheet?.let { current.copy(sheet = change(it)) } ?: current
        }
    }

    /**
     * Save the sheet.
     *
     * A NEW BOOKING IS REFUSED IF ANOTHER RULE WOULD OWN THE SAME HALF HOUR, and the
     * refusal comes from the repository so the check exists in one place rather than in
     * the room and the repository both.
     *
     * AN EDIT KEEPS THE PAST. Rescheduling re-expands from the next half hour, so a rule
     * changed today does not rewrite what already happened last week.
     */
    private fun save() {
        val sheet = _state.value.sheet ?: return
        if (!sheet.canSave) {
            updateSheet { it.copy(error = "A booking needs a name and something for GURU to do.") }
            return
        }
        if (!sheet.validDays) {
            updateSheet { it.copy(error = "Pick at least one day, or the booking never fires.") }
            return
        }

        updateSheet { it.copy(saving = true, error = null) }

        viewModelScope.launch {
            val now = nowMillis()
            val existing = sheet.editingId?.let { id ->
                _state.value.bookings.firstOrNull { it.id == id }
            }

            val booking = HeartbeatBooking(
                id = sheet.editingId ?: Uuid.random().toString(),
                name = sheet.name.trim(),
                instruction = sheet.instruction.trim(),
                repeat = sheet.repeat,
                chosenDays = sheet.chosenDays,
                combine = sheet.combine,
                // The colour is the booking's position in the rotation, so a diary never
                // arrives at the same colour twice in a row by chance.
                colourIndex = existing?.colourIndex ?: _state.value.bookings.size,
                enabled = true,
                startsAt = existing?.startsAt ?: sheet.slotAt,
                endsAt = existing?.endsAt ?: 0L,
            )

            val result = if (existing == null) {
                bookingRepository.book(booking)
            } else {
                bookingRepository.reschedule(booking, fromMillis = now)
            }

            result.fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            sheet = null,
                            notice = if (existing == null) {
                                "\"${booking.name}\" is booked. GURU will do that at " +
                                    "${clockLabel(booking.startsAt)}."
                            } else {
                                "\"${booking.name}\" is changed from now on. What already ran " +
                                    "is unchanged."
                            },
                        )
                    }
                    refreshBookings()
                    observeDay(_state.value.selectedDayStart)
                },
                onFailure = { failure ->
                    updateSheet {
                        it.copy(
                            saving = false,
                            error = failure.message ?: "The booking could not be saved.",
                        )
                    }
                },
            )
        }
    }

    private fun delete(id: String) {
        viewModelScope.launch {
            val name = _state.value.bookings.firstOrNull { it.id == id }?.name ?: "The booking"
            bookingRepository.remove(id).fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            sheet = null,
                            notice = "$name is removed. What already ran is kept.",
                        )
                    }
                    refreshBookings()
                    observeDay(_state.value.selectedDayStart)
                },
                onFailure = { failure ->
                    _state.update { it.copy(error = failure.message ?: "That could not be removed.") }
                },
            )
        }
    }

    private fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            bookingRepository.setEnabled(id, enabled).fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            notice = if (enabled) {
                                "The booking is on again, from its next half hour."
                            } else {
                                "The booking is off. What has not happened yet is cleared; " +
                                    "what already ran stays."
                            },
                        )
                    }
                    refreshBookings()
                    observeDay(_state.value.selectedDayStart)
                },
                onFailure = { failure ->
                    _state.update { it.copy(error = failure.message ?: "That could not be changed.") }
                },
            )
        }
    }

    // ---------- previews the sheet needs ----------

    /**
     * What a beat would inject for the sheet as it currently stands.
     *
     * THE SAME FUNCTION THE BEAT CALLS. A preview with its own composition rules would be
     * a second contract, and the two would disagree the first time a booking was edited.
     */
    fun previewFor(sheet: BookingSheet): String = composeBeatInstruction(
        masterPrompt = _state.value.masterPrompt,
        booking = HeartbeatBooking(
            id = sheet.editingId ?: "preview",
            name = sheet.name.ifBlank { "Untitled" },
            instruction = sheet.instruction,
            repeat = sheet.repeat,
            chosenDays = sheet.chosenDays,
            combine = sheet.combine,
            startsAt = sheet.slotAt,
        ),
        entry = null,
    )

    /** "09:30", for a notice or a card. */
    private fun clockLabel(slotAt: Long): String {
        val at = Calendar.getInstance().apply { timeInMillis = slotAt }
        val hour = at.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val minute = at.get(Calendar.MINUTE).toString().padStart(2, '0')
        return "$hour:$minute"
    }
}
