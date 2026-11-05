// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

import java.util.Calendar
import java.util.TimeZone

/**
 * A half hour on the clock, and the arithmetic the whole diary is built on.
 *
 * WHY THIS IS A TYPE AND NOT TWO NUMBERS. The heartbeat fires on absolute wall-clock
 * half hours: 09:00, 09:30, 10:00. A booking claims one of them, so every booking, every
 * occurrence and every beat has to agree on what "the half hour I am in" means. Written
 * out at each call site, that arithmetic drifts, and the first place it drifts is the
 * one that books an alarm for 09:30 because a run ended at 09:00 and thirty minutes got
 * added to the wrong thing.
 *
 * EVERYTHING HERE IS ANDROID-FREE AND PURE. It takes the clock as an argument, so a JVM
 * test can drive a whole year of occurrences in milliseconds and assert what it produced.
 */
object HeartbeatSlot {

    /** The cadence. The founder set thirty minutes and nothing in this feature changes it. */
    const val SLOT_MINUTES = 30L

    const val SLOT_MILLIS = SLOT_MINUTES * 60_000L

    /** Hours in a day, and the slots in one: forty-eight. */
    const val SLOTS_PER_DAY = 48

    /**
     * The half hour an instant falls in, floored.
     *
     * A beat that wakes at 09:29:59 belongs to the 09:00 slot, not the 09:30 one, because
     * the slot is the half hour the clock is in and the alarm is what delivers it. An
     * instant exactly on a boundary belongs to that boundary.
     */
    fun floor(instantMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = instantMillis }
        val minute = calendar.get(Calendar.MINUTE)
        calendar.set(Calendar.MINUTE, if (minute < 30) 0 else 30)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /** The next half hour after an instant. */
    fun next(instantMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        floor(instantMillis, zone) + SLOT_MILLIS

    /**
     * The first half hour strictly after [afterMillis] on the given wall clock time.
     *
     * THIS IS HOW A RULE REPEATS AT THE SAME TIME OF DAY. A booking at 09:00 repeats at
     * 09:00 every qualifying day, not every twenty-four hours from whenever it was made,
     * so the time of day is read from the rule's own first occurrence and applied to each
     * date. That is the difference between a diary and a stopwatch.
     */
    fun atTimeOfDay(
        dayMillis: Long,
        timeOfDayMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): Long {
        val day = Calendar.getInstance(zone).apply { timeInMillis = dayMillis }
        val time = Calendar.getInstance(zone).apply { timeInMillis = timeOfDayMillis }
        day.set(Calendar.HOUR_OF_DAY, time.get(Calendar.HOUR_OF_DAY))
        day.set(Calendar.MINUTE, time.get(Calendar.MINUTE))
        day.set(Calendar.SECOND, 0)
        day.set(Calendar.MILLISECOND, 0)
        return day.timeInMillis
    }

    /** Midnight at the start of the day an instant falls in. */
    fun startOfDay(instantMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = instantMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** Midnight at the end of that day, exclusive. */
    fun endOfDay(instantMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val start = Calendar.getInstance(zone).apply {
            timeInMillis = instantMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        start.add(Calendar.DAY_OF_MONTH, 1)
        return start.timeInMillis
    }

    /** Minutes past midnight for an instant, so a timeline can place it. */
    fun minuteOfDay(instantMillis: Long, zone: TimeZone = TimeZone.getDefault()): Int =
        Calendar.getInstance(zone).apply { timeInMillis = instantMillis }.let {
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
        }

    /** Monday-based weekday index, 0 through 6, matching [RepeatRule.days]. */
    fun weekdayIndex(instantMillis: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val calendarDay = Calendar.getInstance(zone).apply { timeInMillis = instantMillis }
            .get(Calendar.DAY_OF_WEEK)
        // Calendar.SUNDAY is 1 and MONDAY is 2, so Sunday has to wrap to 6 rather than -1.
        return (calendarDay + 5) % 7
    }

    /** True when the instant is exactly on a half hour with nothing below it. */
    fun isOnSlot(instantMillis: Long, zone: TimeZone = TimeZone.getDefault()): Boolean {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = instantMillis }
        return (calendar.get(Calendar.MINUTE) == 0 || calendar.get(Calendar.MINUTE) == 30) &&
            calendar.get(Calendar.SECOND) == 0 &&
            calendar.get(Calendar.MILLISECOND) == 0
    }
}

/**
 * How a booking repeats.
 *
 * ONCE, DAILY, WEEKDAYS and CHOSEN. Four spellings rather than a general recurrence
 * language, because a diary is read by a person: "every weekday at nine" is a sentence
 * somebody says, and an RRULE with BYDAY and INTERVAL is a sentence nobody says. The
 * expansion is a pure function over this and a date range, so a fifth kind can be added
 * without touching any caller.
 */
enum class RepeatKind(
    /** What the sheet prints on the button. */
    val label: String,
    /** One line saying what it means, under the button when chosen. */
    val explanation: String,
) {
    ONCE("Once", "This day only."),
    DAILY("Daily", "Every day."),
    WEEKDAYS("Weekdays", "Monday to Friday."),
    CHOSEN("Days", "Only the days you pick.");

    companion object {
        fun fromStored(value: String): RepeatKind =
            entries.firstOrNull { it.name == value } ?: ONCE

        /** The days a kind covers, Monday first. CHOSEN has none of its own. */
        fun daysOf(kind: RepeatKind): List<Int> = when (kind) {
            ONCE, DAILY -> listOf(0, 1, 2, 3, 4, 5, 6)
            WEEKDAYS -> listOf(0, 1, 2, 3, 4)
            CHOSEN -> emptyList()
        }
    }
}

/**
 * How a booking sits beside the master prompt.
 *
 * ALONGSIDE is the default because the master prompt is the owner's standing instruction
 * to itself and a booking is an addition, not a replacement. INSTEAD exists because some
 * half hours want silence on everything else: deep work, sleep, a meeting. Both are the
 * owner's call per booking, and neither is a mode the app chooses for them.
 */
enum class BookingCombine(val label: String) {
    ALONGSIDE("Alongside master"),
    INSTEAD("Instead of master");

    companion object {
        fun fromStored(value: String): BookingCombine =
            entries.firstOrNull { it.name == value } ?: ALONGSIDE
    }
}

/** What actually happened in one half hour. Never what was asked for. */
enum class EntryState(val label: String) {
    /** Booked and still in the future. */
    PENDING("Booked"),
    /** A beat is running in this slot right now. */
    RUNNING("Running"),
    /** The beat reached the model and finished its turn. */
    RAN("Ran"),
    /**
     * The beat started and was cut off at the ten-minute budget.
     *
     * ITS OWN STATE, because the other three are all untrue of it. It is not RAN, since
     * GURU never finished what it was told to do. It is not MISSED, since it plainly
     * started and something happened. It is not SKIPPED, since nothing was declined. A
     * diary that called this either of those would be telling its owner the wrong story
     * about their own half hour, and there is no reason to invent a comfortable answer
     * when the honest one has a name.
     */
    CUT_SHORT("Cut short"),
    /** The half hour passed without a beat. The phone was asleep or the alarm was dropped. */
    MISSED("Missed"),
    /** A beat arrived and declined to run, because the previous one was still going. */
    SKIPPED("Skipped"),
    /** The owner removed the booking before this occurrence happened. */
    CANCELLED("Cancelled");

    companion object {
        fun fromStored(value: String): EntryState =
            entries.firstOrNull { it.name == value } ?: PENDING
    }
}

/**
 * A booking as the diary and the beat both see it.
 *
 * ONE MODEL FOR BOTH READERS. The room draws these and the beat resolves one of these for
 * the half hour it woke in. Two models would be two definitions of what a booking is, and
 * the one the beat read would eventually disagree with the one on screen.
 */
data class HeartbeatBooking(
    val id: String,
    val name: String,
    /** What GURU is told to do in this half hour. */
    val instruction: String,
    val repeat: RepeatKind,
    /** Monday-first set of chosen days. Ignored unless [repeat] is CHOSEN. */
    val chosenDays: Set<Int> = emptySet(),
    val combine: BookingCombine = BookingCombine.ALONGSIDE,
    /** Index into the palette rotation, so a booking keeps one colour for its life. */
    val colourIndex: Int = 0,
    val enabled: Boolean = true,
    /** The first occurrence, carrying both the first date and the time of day. */
    val startsAt: Long,
    /** Last date an occurrence may fall on, or 0 for none. */
    val endsAt: Long = 0L,
) {
    /** The half hour this booking repeats at, as minutes past midnight. */
    fun minuteOfDay(zone: TimeZone = TimeZone.getDefault()): Int =
        HeartbeatSlot.minuteOfDay(startsAt, zone)

    /**
     * Which weekdays this booking repeats on, Monday first.
     *
     * A ONCE BOOKING HAS NO WEEKDAYS, and that is deliberate. It used to answer the weekday
     * of its own start, which made it fire every week on that day: an off-by-one that no
     * screen would ever show, because a weekly repeat looks exactly like a once-off until
     * the second week. "Once" is a property of a DATE, not of a weekday, so it is answered
     * by [slotOn] and never by this.
     */
    fun days(zone: TimeZone = TimeZone.getDefault()): Set<Int> = when (repeat) {
        RepeatKind.CHOSEN -> chosenDays
        RepeatKind.ONCE -> emptySet()
        else -> RepeatKind.daysOf(repeat).toSet()
    }

    /**
     * The slot this booking produces on a given day, or null when it produces none.
     *
     * THE ONLY PLACE THAT DECIDES IT. Both [occursOn] and the expansion engine call this,
     * so "does it fire on this day" has one answer rather than two that can drift. A ONCE
     * booking is compared by its whole date rather than its weekday, which is what stops it
     * repeating every week forever.
     */
    fun slotOn(dayMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
        if (!enabled) return null
        val slot = HeartbeatSlot.atTimeOfDay(dayMillis, startsAt, zone)
        if (slot < startsAt) return null
        if (endsAt > 0L && slot > endsAt) return null
        if (!HeartbeatSlot.isOnSlot(slot, zone)) return null

        val matches = when (repeat) {
            // A once-off is one date, so the whole day has to line up, not just the weekday.
            RepeatKind.ONCE -> HeartbeatSlot.startOfDay(slot, zone) ==
                HeartbeatSlot.startOfDay(startsAt, zone)
            else -> HeartbeatSlot.weekdayIndex(slot, zone) in days(zone)
        }
        return if (matches) slot else null
    }

    /** True when this booking produces an occurrence on the given day. */
    fun occursOn(dayMillis: Long, zone: TimeZone = TimeZone.getDefault()): Boolean =
        slotOn(dayMillis, zone) != null
}

/** One dated occurrence, exactly as the diary holds it. */
data class HeartbeatEntry(
    val id: String,
    val bookingId: String,
    /** The absolute half hour this occurrence is. */
    val slotAt: Long,
    /** A per-occurrence rewrite of the instruction, or null to use the booking's. */
    val overrideInstruction: String? = null,
    val state: EntryState = EntryState.PENDING,
    val firedAt: Long = 0L,
    val finishedAt: Long = 0L,
    val outcome: String = "",
) {
    /** The instruction this occurrence actually sends. */
    fun instructionFor(booking: HeartbeatBooking?): String =
        overrideInstruction?.takeIf { it.isNotBlank() } ?: booking?.instruction.orEmpty()

    /** True when a beat may still pick this occurrence up. */
    val isOpen: Boolean get() = state == EntryState.PENDING
}

/**
 * The instruction a beat actually injects, built from the booking and the master prompt.
 *
 * THE ONE PLACE THAT DECIDES THE TEXT. The room's preview and the beat's real injection
 * both call this, so what a person is shown is what is sent. A second copy of this rule
 * in the run service would be a second contract, and the first time a booking was edited
 * the two would disagree.
 *
 * ALONGSIDE: the master prompt first, then the booking, because the master prompt is the
 * standing instruction and the booking is what is different about this half hour. The
 * booking is marked with its own name so a model reading two instructions can tell them
 * apart rather than treating them as one paragraph.
 *
 * INSTEAD: the booking alone. Deliberately the booking alone and nothing else, because
 * that is what the owner asked for by choosing it, and quietly appending the master
 * prompt would make the control a lie.
 *
 * NO MASTER PROMPT AND INSTEAD: the booking alone is still a valid beat.
 * NO MASTER PROMPT AND ALONGSIDE: the booking alone, because there is nothing to sit
 * alongside and a beat with something to do is better than a beat with nothing.
 */
fun composeBeatInstruction(
    masterPrompt: String,
    booking: HeartbeatBooking?,
    entry: HeartbeatEntry?,
): String {
    val master = masterPrompt.trim()
    /*
     * THE BOOKING'S OWN INSTRUCTION IS THE FALLBACK WHEN THERE IS NO ENTRY.
     *
     * This used to read `entry?.instructionFor(booking)`, which answers empty whenever the
     * occurrence is absent. The room's preview composes from the rule alone — a person is
     * editing a rule, not a dated row — so that form silently produced the master prompt
     * and nothing else, and the sheet's preview was wrong for every booking. The real beat
     * always has an entry, which is exactly why the mistake would never have shown up on
     * the device: it was only ever wrong where somebody was looking at it.
     */
    val booked = (entry?.instructionFor(booking) ?: booking?.instruction.orEmpty()).trim()
    val bookingName = booking?.name?.trim().orEmpty()

    if (booked.isEmpty()) return master
    if (master.isEmpty() || booking?.combine == BookingCombine.INSTEAD) return booked

    val header = if (bookingName.isEmpty()) {
        "This half hour is booked in your diary. Do this as well:"
    } else {
        "This half hour is booked in your diary as \"$bookingName\". Do this as well:"
    }
    return "$master\n\n$header\n$booked"
}
