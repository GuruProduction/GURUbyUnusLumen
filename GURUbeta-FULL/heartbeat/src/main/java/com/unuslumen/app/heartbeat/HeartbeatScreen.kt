// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.heartbeat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.unuslumen.app.domain.model.BookingCombine
import com.unuslumen.app.domain.model.EntryState
import com.unuslumen.app.domain.model.RepeatKind
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisBody
import com.unuslumen.app.ui.theme.MemphisSquareMark
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The heartbeat room: the prompt GURU beats with, and the diary of booked half hours.
 *
 * WHAT THIS REPLACED. The prompt shipped inside the APK. Changing a word of what
 * GURU says to itself every thirty minutes meant a developer, a commit and a
 * release, on an app whose whole premise is that its owner is in charge. This screen
 * is the fix: read it, edit it, save it, and the next beat uses the new text.
 *
 * TWO TABS, ONE SWITCH. The switch governs the whole room and sits above both tabs,
 * because "is it running" is the first question either tab answers. The prompt tab is
 * what every beat sends. The diary tab is what a booked half hour does, in addition to
 * or instead of that prompt.
 *
 * TWO FIELDS, NOT ONE, on the prompt tab. The editor shows the text as written, tokens
 * and all, and the preview shows exactly what will be injected after substitution.
 * Without the second one a person cannot tell what `${human}` turns into, and a prompt
 * that looks right and reads wrong is the worst of both.
 *
 * SHAPE. Fixed head, scrolling body, fixed action foot, the same as the agent creator
 * and the detail sheets: the commit button is always on screen, so a long prompt or a
 * long day can never push the only way to commit it off the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeartbeatScreen(
    navController: NavHostController,
    viewModel: HeartbeatViewModel = koinViewModel(),
    diaryViewModel: HeartbeatDiaryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val diary by diaryViewModel.state.collectAsStateWithLifecycle()

    /*
     * WHICH TAB IS OPEN, HELD HERE RATHER THAN IN A VIEW MODEL.
     *
     * A selected tab is not app state: it does not outlive the room and nobody expects
     * it to. rememberSaveable keeps it across a rotation, which is the only lifetime it
     * needs. In a view model it would mean a reopened room always landed on whichever tab
     * was last used, which is a small thing that reads as wrong.
     */
    var tab by rememberSaveable { mutableIntStateOf(0) }

    /*
     * OPEN THE PORTAL WHEN A BEAT IS FIRED BY HAND.
     *
     * The view model hands the prompt to the portal's shared holder and reports
     * success; the navigation belongs here because this composable is the one holding
     * the NavHostController. Navigation stays out of the DI module on purpose: a module
     * that routes would need to know the app's whole screen graph, and the room is
     * meant to be a room rather than a second router.
     *
     * The prompt is already waiting by the time this runs, and the portal consumes it
     * on entry, so the beat arrives as a real streaming turn.
     */
    LaunchedEffect(state.firedAt) {
        if (state.firedAt > 0L) {
            navController.navigate(com.unuslumen.app.ui.navigation.Screen.PortalScreen) {
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            guruAppBar(
                title = "Heartbeat",
                // The stamp states the switch, because that is the first thing a
                // person needs to know about a chain that is silent by design.
                stamp = if (state.enabled) "on" else "off",
                stampColor = if (state.enabled) Memphis.Yellow else Memphis.Lilac,
            )
        },
    ) { paddingValues ->
        Box(Modifier.fillMaxSize().padding(paddingValues)) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Reading the prompt…", style = captionStyle(10f, Memphis.Ink.copy(alpha = 0.6f)))
                }

                else -> Column(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 18.dp, top = 12.dp),
                    ) {
                        /*
                         * THE SWITCH COMES FIRST, ABOVE BOTH TABS.
                         *
                         * It decides whether anything below means anything at all, and a
                         * person opening this room has one question before any other: is
                         * it running. Putting the tabs above it would bury the control
                         * that governs the whole screen.
                         */
                        ChainSwitch(
                            enabled = state.enabled,
                            toggling = state.toggling,
                            canEnable = state.canEnable,
                            promptRequired = state.promptRequired,
                            onToggle = { viewModel.onEvent(HeartbeatEvent.SetEnabled(it)) },
                        )

                        /*
                         * THE TAB STRIP.
                         *
                         * Two printed cells, the active one filled with ink, because that
                         * is the only selected-state vocabulary this design language has.
                         * A Material tab row draws an indicator and a ripple, both of
                         * which would be the one soft object on a printed page.
                         */
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            PrintedTab(
                                label = "Prompt",
                                selected = tab == 0,
                                modifier = Modifier.weight(1f),
                                onClick = { tab = 0 },
                            )
                            PrintedTab(
                                label = "Diary",
                                selected = tab == 1,
                                modifier = Modifier.weight(1f),
                                onClick = { tab = 1 },
                            )
                        }
                    }

                    if (tab == 0) {
                        PromptTabBody(state = state, viewModel = viewModel)
                    } else {
                        DiaryTabBody(diary = diary, diaryViewModel = diaryViewModel)
                    }
                }
            }
        }
    }
}

/**
 * The prompt tab: the chain's pulse, the editor, the tokens and the preview.
 *
 * IT KEEPS ITS OWN FOOT. Save and Reset belong to the prompt, so they scroll with it
 * rather than sitting under the diary, where they would look like they saved a booking.
 */
@Composable
private fun PromptTabBody(state: HeartbeatState, viewModel: HeartbeatViewModel) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 16.dp),
        ) {
            /*
             * THE PROMPT PANEL.
             *
             * A dismissible sheet that tells the owner a heartbeat master prompt has to be
             * written before the chain can be switched on. It sits in the list flow with
             * its own padding so the printed drop stays inside its own cell, which a bar
             * pinned under the switch did not: that one drew its shadow onto the sentence
             * below it.
             */
            if (state.promptRequired) {
                item(key = "prompt-required") {
                    PromptRequiredPanel(
                        onDismiss = {
                            viewModel.onEvent(HeartbeatEvent.DismissPromptRequired)
                        },
                    )
                }
            }

            item(key = "intro") {
                Text(
                    when {
                        state.enabled ->
                            "GURU wakes every thirty minutes and sends itself the " +
                                "prompt below. Change it and the next beat uses your words."
                        state.draft.isBlank() ->
                            "A prompt has to exist before the heartbeat can be switched " +
                                "on, because that prompt is what every beat sends."
                        else ->
                            "GURU is not waking. Switch the heartbeat on above and it " +
                                "will fire every thirty minutes with the prompt below."
                    },
                    style = bodyText(),
                )
            }

            // THE CHAIN'S PULSE, BEFORE THE PROMPT.
            //
            // A heartbeat that has stopped is the fault a person notices,
            // and until this block existed the room could only show the
            // prompt, which looked perfectly healthy whether or not anything
            // ever fired. Both numbers are printed because neither is enough
            // alone: a next booking with no last fire is a chain written and
            // never delivered, which is exactly what a dead heartbeat looks
            // like from the inside.
            item(key = "chain") { ChainState(state, viewModel) }

            state.notice?.let { notice ->
                item(key = "notice") { Notice(notice, Memphis.Teal) }
            }
            state.error?.let { error ->
                item(key = "error") { Notice(error, Memphis.Coral) }
            }

            item(key = "editor-band") {
                Band("The prompt", if (state.draft.isBlank()) null else "as written")
            }
            item(key = "editor") {
                PromptField(
                    value = state.draft,
                    onChange = { viewModel.onEvent(HeartbeatEvent.Edit(it)) },
                )
            }

            item(key = "tokens") {
                Spacer(Modifier.height(12.dp))
                Text(
                    "These get filled in at beat time. The preview below shows the result.",
                    style = captionStyle(9f, Memphis.Ink.copy(alpha = 0.6f)),
                )
                Spacer(Modifier.height(7.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TokenChip("\${human}", "your name")
                    TokenChip("\${time}", "the clock")
                }
            }

            item(key = "preview-band") {
                Band("What a beat injects", if (state.preview.isBlank()) null else "now")
            }
            item(key = "preview") {
                MemphisSurface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Memphis.Paper,
                    borderWidth = 2.5.dp,
                    shadowOffset = 4.dp,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    ) {
                        if (state.preview.isBlank()) {
                            Text(
                                "Nothing yet. Write a prompt above.",
                                style = captionStyle(9.5f, Memphis.Ink.copy(alpha = 0.45f)),
                            )
                        } else {
                            Text(state.preview, style = bodyText())
                        }
                    }
                }
            }
        }

        // THE FOOT. Save is the commit. Reset clears the prompt, because
        // there is no shipped one to go back to.
        Box(Modifier.fillMaxWidth().height(3.dp).background(Memphis.Ink))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PrintedButton(
                label = "Reset",
                modifier = Modifier.weight(1f),
                filled = false,
                enabled = state.edited && !state.saving,
                onClick = { viewModel.onEvent(HeartbeatEvent.Reset) },
            )
            PrintedButton(
                label = when {
                    state.saving -> "Saving…"
                    else -> "Save"
                },
                modifier = Modifier.weight(1f),
                filled = true,
                enabled = state.canSave && !state.saving,
                onClick = { viewModel.onEvent(HeartbeatEvent.Save) },
            )
        }
    }
}

/**
 * The diary tab: a week, a day, and every half hour in it.
 *
 * WHY EVERY HALF HOUR IS DRAWN, not only the booked ones. The empty slot is the thing a
 * person presses to book, so hiding it would mean the only way to make a booking was a
 * button floating somewhere else. Forty-eight rows is a scroll, and the scroll is the
 * calendar.
 *
 * THE STATUS WORD IS WHAT HAPPENED. A card's right-hand word is never "booked" for a time
 * that has gone: it is ran, running, missed, skipped, or cut short, all of which are
 * written by the beat that actually reached that half hour.
 */
@Composable
private fun DiaryTabBody(diary: DiaryState, diaryViewModel: HeartbeatDiaryViewModel) {
    val clock = remember { SimpleDateFormat("HH:mm", Locale.UK) }
    val dayFormat = remember { SimpleDateFormat("EEE d MMM", Locale.UK) }
    val weekdayFormat = remember { SimpleDateFormat("EEE", Locale.UK) }
    val dayNumber = remember { SimpleDateFormat("d", Locale.UK) }
    val listState = rememberLazyListState()

    /*
     * OPEN ON THE CURRENT HALF HOUR, NOT ON MIDNIGHT.
     *
     * A diary that opens at 00:00 makes a person scroll past eleven empty hours every single
     * time to reach the part of the day they are actually in. The scroll happens once when
     * the tab opens and once whenever the day changes; it is not re-run on every state
     * change, because that would drag the list back under somebody's finger while they were
     * reading it.
     *
     * THE INDEX IS COMPUTED FROM THE SAME STATE THE LIST IS BUILT FROM. The header rows are a
     * fixed set with two optional notices, so counting them is exact rather than a guess,
     * and it stays exact if a notice appears later because the count comes from the same
     * values the list is composed with.
     */
    LaunchedEffect(diary.selectedDayStart, diary.isLoading) {
        if (diary.isLoading || diary.slots.isEmpty()) return@LaunchedEffect
        val nowMinute = minuteOfDayNow()
        val slotIndex = diary.slots.indexOfFirst { slot ->
            val at = Calendar.getInstance().apply { timeInMillis = slot.slotAt }
            at.get(Calendar.HOUR_OF_DAY) * 60 + at.get(Calendar.MINUTE) >= nowMinute
        }.let { if (it < 0) diary.slots.lastIndex else it }

        // One band, the week strip, the day line, and a notice each for a message and an
        // error. Counted rather than hardcoded so an appearing notice cannot shift the aim.
        val headerCount = 3 +
            (if (diary.notice != null) 1 else 0) +
            (if (diary.error != null) 1 else 0)
        listState.scrollToItem(headerCount + slotIndex)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 16.dp),
            ) {
                item(key = "week-band") {
                    Band(
                        "Bookings",
                        if (diary.bookedCount == 0) "nothing today" else "${diary.bookedCount} today",
                    )
                }

                item(key = "week") {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        diary.weekDays.forEach { day ->
                            DayCell(
                                label = weekdayFormat.format(Date(day)),
                                number = dayNumber.format(Date(day)),
                                selected = day == diary.selectedDayStart,
                                marked = day in diary.daysWithBookings,
                                modifier = Modifier.weight(1f),
                                onClick = { diaryViewModel.onEvent(DiaryEvent.SelectDay(day)) },
                            )
                        }
                    }
                }

                item(key = "day-line") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WeekArrow(
                            forward = false,
                            onClick = { diaryViewModel.onEvent(DiaryEvent.ShiftWeek(-1)) },
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                dayFormat.format(Date(diary.selectedDayStart)).uppercase(),
                                style = posterStyle(13f),
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                when {
                                    diary.runningCount > 0 -> "one running now"
                                    diary.bookedCount == 0 -> "nothing booked"
                                    else -> "the day"
                                },
                                style = captionStyle(7.5f, Memphis.Ink.copy(alpha = 0.55f)),
                            )
                        }
                        WeekArrow(
                            forward = true,
                            onClick = { diaryViewModel.onEvent(DiaryEvent.ShiftWeek(1)) },
                        )
                    }
                }

                diary.notice?.let { notice ->
                    item(key = "diary-notice") { Notice(notice, Memphis.Teal) }
                }
                diary.error?.let { error ->
                    item(key = "diary-error") { Notice(error, Memphis.Coral) }
                }

                items(
                    count = diary.slots.size,
                    key = { index -> "slot-${diary.slots[index].slotAt}" },
                ) { index ->
                    val slot = diary.slots[index]
                    SlotRow(
                        slot = slot,
                        time = clock.format(Date(slot.slotAt)),
                        onOpen = {
                            diaryViewModel.onEvent(
                                DiaryEvent.OpenSheet(
                                    slotAt = slot.slotAt,
                                    editingId = slot.booking?.id,
                                )
                            )
                        },
                    )
                }
            }

            Box(Modifier.fillMaxWidth().height(3.dp).background(Memphis.Ink))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                PrintedButton(
                    label = "Book a slot",
                    modifier = Modifier.weight(1f),
                    filled = true,
                    enabled = true,
                    onClick = {
                        // The first open half hour of the shown day, so the sheet opens on
                        // something real rather than on an invented time.
                        val nextOpen = diary.slots.firstOrNull { !it.isBooked }?.slotAt
                            ?: diary.selectedDayStart
                        diaryViewModel.onEvent(DiaryEvent.OpenSheet(slotAt = nextOpen))
                    },
                )
            }
        }

        /*
         * THE BOOKING SHEET, ON TOP OF THE DAY.
         *
         * A scrim and a printed sheet, the same geometry as the Ottio detail sheet: an ink
         * wash at low alpha, a heavier border and a bigger drop than anything behind it, a
         * fixed masthead and foot, and a body that scrolls. It covers the day rather than
         * replacing it, so the half hour being booked stays visible behind.
         */
        diary.sheet?.let { sheet ->
            BookingSheetOverlay(
                sheet = sheet,
                preview = diaryViewModel.previewFor(sheet),
                onEvent = diaryViewModel::onEvent,
            )
        }
    }
}

/** One printed tab. The active one is filled with ink, exactly as a chosen cell is. */
@Composable
private fun PrintedTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    MemphisSurface(
        modifier = modifier.clickable { onClick() },
        color = if (selected) Memphis.Ink else Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label.uppercase(),
                style = captionStyle(9.5f, if (selected) Memphis.Paper else Memphis.Ink),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * One day in the week strip.
 *
 * THE MARK IS NOT DECORATION. A day with something on it carries a rule under its number
 * so a person can see at a glance which days are worth opening. The selected day fills
 * with ink, which is the only selected-state shape this language has.
 */
@Composable
private fun DayCell(
    label: String,
    number: String,
    selected: Boolean,
    marked: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .background(if (selected) Memphis.Ink else Memphis.Paper)
            .border(2.5.dp, Memphis.Ink)
            .clickable { onClick() }
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label.uppercase(),
                style = captionStyle(7.5f, if (selected) Memphis.Paper.copy(alpha = 0.7f) else Memphis.Ink.copy(alpha = 0.55f)),
            )
            Text(
                number,
                style = posterStyle(15f, if (selected) Memphis.Paper else Memphis.Ink),
            )
            Box(
                Modifier
                    .padding(top = 3.dp)
                    .width(7.dp)
                    .height(2.5.dp)
                    .background(
                        when {
                            !marked -> Color.Transparent
                            selected -> Memphis.Yellow
                            else -> Memphis.Coral
                        }
                    ),
            )
        }
    }
}

/**
 * A week arrow, printed as its own small cell.
 *
 * A CHEVRON IN A BOX RATHER THAN A TEXT BUTTON. It is the only way to move the week, so
 * it has to look pressable on a page where nothing else glides or signals; a bare glyph
 * would read as decoration. One composable for both directions so the two cannot drift
 * apart in size or hit area.
 */
@Composable
private fun WeekArrow(forward: Boolean, onClick: () -> Unit) {
    MemphisSurface(
        modifier = Modifier.clickable { onClick() },
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 3.dp,
    ) {
        Box(
            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (forward) "›" else "‹",
                style = posterStyle(13f),
            )
        }
    }
}

/**
 * One half hour: a booking card, or a hairline saying the slot is open.
 *
 * THE EMPTY SLOT IS A ROW, NOT A BUTTON. Forty-eight buttons in a column would read as a
 * control panel; a hairline that names itself reads as a diary with space in it, and the
 * whole row is the press target either way.
 */
@Composable
private fun SlotRow(
    slot: DiarySlot,
    time: String,
    onOpen: () -> Unit,
) {
    val booking = slot.booking
    Row(
        Modifier.fillMaxWidth().padding(bottom = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            time,
            style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
            modifier = Modifier.width(40.dp).padding(top = 11.dp),
        )

        if (booking == null) {
            Box(
                Modifier
                    .weight(1f)
                    .height(27.dp)
                    .border(1.5.dp, Memphis.Ink.copy(alpha = 0.22f))
                    .clickable { onOpen() }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    "Open. Tap to book.",
                    style = captionStyle(7.5f, Memphis.Ink.copy(alpha = 0.32f)),
                )
            }
            return@Row
        }

        val entry = slot.entry
        val state = entry?.state ?: EntryState.PENDING

        MemphisSurface(
            modifier = Modifier.weight(1f).clickable { onOpen() },
            color = Memphis.Paper,
            borderWidth = 2.5.dp,
            shadowOffset = 3.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MemphisSquareMark(
                        color = Memphis.inkFor(booking.colourIndex),
                        side = 9.dp,
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(booking.name.uppercase(), style = posterStyle(13.5f))
                }
                Spacer(Modifier.height(7.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        repeatLabel(booking.repeat, booking.chosenDays, booking.combine),
                        style = captionStyle(7.5f, Memphis.Ink.copy(alpha = 0.55f)),
                    )
                    EntryStateWord(state = state, entry = entry, time = time)
                }
            }
        }
    }
}

/** What a booking repeats, and how it combines, in one small line. */
private fun repeatLabel(
    repeat: RepeatKind,
    chosenDays: Set<Int>,
    combine: BookingCombine,
): String {
    val base = when (repeat) {
        RepeatKind.ONCE -> "ONCE"
        RepeatKind.DAILY -> "DAILY"
        RepeatKind.WEEKDAYS -> "WEEKDAYS"
        RepeatKind.CHOSEN -> if (chosenDays.isEmpty()) {
            "NO DAYS"
        } else {
            chosenDays.sorted().joinToString(" ") { DAY_LETTERS[it] }
        }
    }
    return when (combine) {
        BookingCombine.ALONGSIDE -> base
        BookingCombine.INSTEAD -> "$base · INSTEAD OF MASTER"
    }
}

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")

/**
 * The one word that says what happened here.
 *
 * IT IS THE POINT OF THE DIARY. A calendar that only shows a plan is a wish list. This
 * prints the real ending: ran at a time, running now, missed while the phone was asleep,
 * skipped because the previous beat was still working, or cut short at the ten-minute
 * budget. Every one of those is a different fault a person can act on.
 */
@Composable
private fun EntryStateWord(state: EntryState, entry: com.unuslumen.app.domain.model.HeartbeatEntry?, time: String) {
    val clock = remember { SimpleDateFormat("HH:mm", Locale.UK) }
    val (text, ink, fill) = when (state) {
        EntryState.RAN -> Triple(
            if (entry != null && entry.firedAt > 0L) "RAN ${clock.format(Date(entry.firedAt))}" else "RAN",
            Memphis.Teal,
            Color.Transparent,
        )
        EntryState.RUNNING -> Triple("RUNNING", Memphis.Ink, Memphis.Yellow)
        EntryState.MISSED -> Triple("MISSED", Memphis.Coral, Color.Transparent)
        EntryState.SKIPPED -> Triple("SKIPPED", Memphis.Purple, Color.Transparent)
        EntryState.CUT_SHORT -> Triple("CUT SHORT", Memphis.Sun, Color.Transparent)
        EntryState.CANCELLED -> Triple("CANCELLED", Memphis.Ink.copy(alpha = 0.4f), Color.Transparent)
        EntryState.PENDING -> Triple("BOOKED", Memphis.Ink.copy(alpha = 0.55f), Color.Transparent)
    }
    Text(
        text,
        style = captionStyle(7.5f, ink),
        modifier = if (fill == Color.Transparent) {
            Modifier
        } else {
            Modifier.background(fill).padding(horizontal = 5.dp, vertical = 1.dp)
        },
    )
}

/**
 * The booking sheet.
 *
 * THE SAME GEOMETRY AS THE AGENTS DETAIL SHEET: an ink wash at low alpha, a heavier border
 * and a bigger drop than anything behind it, a fixed masthead and foot, and a body that
 * scrolls. That sheet already solved "a long body must not push the way out past the
 * fold", so this one uses its shape rather than inventing a second answer to the same
 * problem.
 *
 * EVERY CHOICE IS A PRINTED CELL that fills with ink. No switch, no chip cloud, no
 * dropdown: those are three more selected-state vocabularies for a page that already has
 * one.
 */
@Composable
private fun BookingSheetOverlay(
    sheet: BookingSheet,
    preview: String,
    onEvent: (DiaryEvent) -> Unit,
) {
    val clock = remember { SimpleDateFormat("HH:mm", Locale.UK) }
    val dayFormat = remember { SimpleDateFormat("EEE d MMM", Locale.UK) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Memphis.Ink.copy(alpha = 0.34f)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
        ) {
            MemphisSurface(
                modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
                color = Memphis.Paper,
                borderWidth = 3.dp,
                shadowOffset = 8.dp,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    // --- fixed head ---
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 15.dp)) {
                        Text(
                            when {
                                sheet.isEdit -> "Edit booking"
                                else -> "Book ${clock.format(Date(sheet.slotAt))}"
                            }.uppercase(),
                            style = posterStyle(24f),
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            dayFormat.format(Date(sheet.slotAt)).uppercase() + " · HALF HOUR",
                            style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 7.dp)
                                .height(3.dp)
                                .background(Memphis.Ink),
                        )
                    }

                    // --- the body scrolls ---
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    ) {
                        FieldLabel("Name it")
                        SheetField(
                            value = sheet.name,
                            placeholder = "Morning mail",
                            onChange = { onEvent(DiaryEvent.EditName(it)) },
                        )

                        FieldLabel("What GURU does")
                        SheetField(
                            value = sheet.instruction,
                            placeholder = "Check the two inboxes and tell me only what needs a reply before noon.",
                            onChange = { onEvent(DiaryEvent.EditInstruction(it)) },
                            minHeight = 74.dp,
                        )

                        FieldLabel("Repeat")
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            RepeatKind.entries.forEach { kind ->
                                ChoiceCell(
                                    label = kind.label,
                                    selected = sheet.repeat == kind,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onEvent(DiaryEvent.SetRepeat(kind)) },
                                )
                            }
                        }

                        /*
                         * THE DAYS ARE ALWAYS SHOWN, not only when CHOSEN is active.
                         *
                         * A person editing a weekday booking needs to see which days it
                         * covers, and pressing one is itself a statement that they want to
                         * choose days. Hiding the grid until a separate control was pressed
                         * would mean two presses to change Thursday.
                         */
                        FieldLabel("On these days")
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            val shownDays = sheet.displayDays()
                            DAY_LETTERS.forEachIndexed { index, letter ->
                                ChoiceCell(
                                    label = letter,
                                    // THE GRID SHOWS WHAT THE BOOKING COVERS, not what has
                                    // been ticked. A once-off on a Thursday lights Thursday
                                    // alone, weekdays lights Monday to Friday, and only a
                                    // chosen-days booking reflects arbitrary ticks. Reading
                                    // the raw tick set made a fresh sheet arrive with five
                                    // cells filled for a booking that was one date.
                                    selected = index in shownDays,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onEvent(DiaryEvent.ToggleDay(index)) },
                                )
                            }
                        }

                        FieldLabel("How it combines")
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            BookingCombine.entries.forEach { combine ->
                                ChoiceCell(
                                    label = combine.label,
                                    selected = sheet.combine == combine,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onEvent(DiaryEvent.SetCombine(combine)) },
                                )
                            }
                        }

                        /*
                         * THE PREVIEW IS NOT AN EXTRA. A booking beside a master prompt
                         * sends both, and "instead of master" sends only one. Without
                         * showing the composed text, a person cannot tell which of those
                         * their choice produces, and the control becomes a guess.
                         */
                        FieldLabel("What a beat would send")
                        MemphisSurface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Memphis.Paper,
                            borderWidth = 2.5.dp,
                            shadowOffset = 3.dp,
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 160.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(11.dp),
                            ) {
                                Text(
                                    preview.ifBlank { "Nothing yet. Write what GURU does." },
                                    style = if (preview.isBlank()) {
                                        bodyText().copy(color = Memphis.Ink.copy(alpha = 0.4f))
                                    } else {
                                        bodyText()
                                    },
                                )
                            }
                        }

                        sheet.error?.let { error ->
                            Notice(error, Memphis.Coral)
                        }

                        Spacer(Modifier.height(14.dp))
                    }

                    // --- the fixed foot: the way out and the commit, always visible ---
                    Box(Modifier.fillMaxWidth().height(3.dp).background(Memphis.Ink))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PrintedButton(
                            label = "Cancel",
                            modifier = Modifier.weight(1f),
                            filled = false,
                            enabled = !sheet.saving,
                            onClick = { onEvent(DiaryEvent.CloseSheet) },
                        )
                        PrintedButton(
                            label = when {
                                sheet.saving -> "Saving…"
                                sheet.isEdit -> "Save it"
                                else -> "Book it"
                            },
                            modifier = Modifier.weight(1f),
                            filled = true,
                            enabled = sheet.canSave && sheet.validDays,
                            onClick = { onEvent(DiaryEvent.SaveBooking) },
                        )
                    }
                }
            }
        }
    }
}

/** A small printed label above a field. */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text.uppercase(),
        style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.55f)),
        modifier = Modifier.padding(top = 12.dp, bottom = 5.dp),
    )
}

/** A field the owner types into, in the same surface as every other object. */
@Composable
private fun SheetField(
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    minHeight: androidx.compose.ui.unit.Dp = 46.dp,
) {
    MemphisSurface(
        modifier = Modifier.fillMaxWidth(),
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 3.dp,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .padding(11.dp),
        ) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = bodyText().copy(color = Memphis.Ink.copy(alpha = 0.4f)),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = bodyText(),
                cursorBrush = SolidColor(Memphis.Ink),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One printed choice. Selected fills with ink; nothing glides. */
@Composable
private fun ChoiceCell(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .background(if (selected) Memphis.Ink else Memphis.Paper)
            .border(2.5.dp, Memphis.Ink)
            .clickable { onClick() }
            .padding(vertical = 9.dp, horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            style = captionStyle(7.5f, if (selected) Memphis.Paper else Memphis.Ink),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Whether the chain is alive, said in plain numbers and one plain sentence.
 *
 * WHY THIS IS ON THE SCREEN AT ALL. The beat is silent by design, which means its
 * failure is silent too. A person can only tell that something stopped from outside:
 * GURU stops saying anything unprompted. That is a bad way to learn the chain is
 * dead, and it is unfixable by reading code, so the room states the two facts that
 * distinguish the cases and offers the one action that separates them.
 */
@Composable
private fun ChainState(state: HeartbeatState, viewModel: HeartbeatViewModel) {
    val clock = remember { SimpleDateFormat("HH:mm", Locale.UK) }
    val day = remember { SimpleDateFormat("d MMM", Locale.UK) }

    fun stamp(millis: Long): String {
        if (millis <= 0L) return "never"
        val at = Date(millis)
        return "${day.format(at)} ${clock.format(at)}"
    }

    Band("The chain", if (state.neverFired) "no beat yet" else "alive")

    MemphisSurface(
        modifier = Modifier.fillMaxWidth(),
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            ChainLine("LAST FIRED", stamp(state.lastFiredAt))
            Spacer(Modifier.height(6.dp))
            ChainLine("NEXT DUE", stamp(state.nextBookedAt))

            if (state.lastOutcome.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    state.lastOutcome,
                    style = captionStyle(8.5f, Memphis.Coral),
                )
            }

            Spacer(Modifier.height(11.dp))
            MemphisSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (state.firing) Modifier
                        else Modifier.clickable { viewModel.onEvent(HeartbeatEvent.FireNow) }
                    ),
                color = if (state.firing) Memphis.Paper else Memphis.Ink,
                borderWidth = 2.5.dp,
                shadowOffset = 3.dp,
            ) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            state.firing -> "STARTING A BEAT…"
                            else -> "FIRE ONE NOW"
                        },
                        style = captionStyle(9f, if (state.firing) Memphis.Ink.copy(alpha = 0.4f) else Memphis.Paper),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "If that beat reaches the model, the chain above is the fault rather than the beat. " +
                    "If it reports an outcome instead, that sentence is the fault.",
                style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.55f)),
            )
        }
    }
}

/** One label and its value, printed as a line rather than a card. */
@Composable
private fun ChainLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)))
        Text(value.uppercase(), style = posterStyle(13f))
    }
}

@Composable
private fun PromptField(value: String, onChange: (String) -> Unit) {
    MemphisSurface(
        modifier = Modifier.fillMaxWidth(),
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            if (value.isEmpty()) {
                Text(
                    "Write what GURU should say to itself when it wakes.",
                    style = bodyText().copy(color = Memphis.Ink.copy(alpha = 0.4f)),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = bodyText(),
                cursorBrush = SolidColor(Memphis.Ink),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A token as it is written, with what it becomes underneath. */
@Composable
private fun RowScope.TokenChip(token: String, means: String) {
    Box(
        Modifier
            .weight(1f)
            .background(Memphis.Paper)
            .border(2.5.dp, Memphis.Ink)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(token, style = posterStyle(12f))
            Text(means.uppercase(), style = captionStyle(7f, Memphis.Ink.copy(alpha = 0.55f)))
        }
    }
}

/** The black band that separates one part of the page from the next. */
@Composable
private fun Band(label: String, note: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 9.dp)
            .background(Memphis.Ink)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = captionStyle(9.5f, Memphis.Paper))
        if (note != null) {
            Text(
                note.uppercase(),
                style = captionStyle(8f, Memphis.Ink),
                modifier = Modifier
                    .background(Memphis.Paper)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * The switch, printed rather than slid.
 *
 * WHY A PRESSED BLOCK AND NOT A TOGGLE. The Memphis language in this app is printed
 * blocks with hard ink outlines: nothing here glides, springs or fades, so a Material
 * switch would be the one soft object on a poster. It reads as one control with two
 * stated states, and the state is a word rather than a knob position.
 *
 * THE THIRD LINE IS THE REFUSAL, AND IT LIVES HERE. When there is no prompt the block
 * says so in its own caption rather than in a warning bar underneath, because the
 * reason the switch will not move is a property of the switch. Pressing it while
 * there is no prompt sets [HeartbeatState.promptRequired] on the view model, which
 * lights this line up and draws the border in coral; the switch itself never moves.
 *
 * IT CANNOT BE PRESSED WHILE A WRITE IS IN FLIGHT, which is the same guard the rest of
 * the room uses for Save. A double tap on a switch that books an alarm is not a thing
 * to leave possible.
 */
@Composable
private fun ChainSwitch(
    enabled: Boolean,
    toggling: Boolean,
    canEnable: Boolean,
    promptRequired: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    // A refused press is stated in the block itself: coral ink so the refusal is
    // unmistakable, ink otherwise so a normal off state stays quiet.
    val refused = promptRequired && !canEnable && !enabled
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        MemphisSurface(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (toggling) Modifier else Modifier.clickable { onToggle(!enabled) }
                ),
            color = when {
                enabled -> Memphis.Yellow
                refused -> Memphis.Coral.copy(alpha = 0.18f)
                else -> Memphis.Paper
            },
            borderWidth = 2.5.dp,
            shadowOffset = 4.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    if (enabled) "HEARTBEAT ON" else "HEARTBEAT OFF",
                    style = posterStyle(15f),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    when {
                        toggling -> "SAVING…"
                        enabled -> "FIRES EVERY 30 MINUTES. TAP TO STOP IT."
                        refused -> "NEEDS A PROMPT FIRST. WRITE ONE BELOW AND SAVE IT."
                        !canEnable -> "WRITE AND SAVE A PROMPT, THEN THIS TURNS ON."
                        else -> "NOTHING IS SCHEDULED. TAP TO START IT."
                    },
                    style = captionStyle(
                        8.5f,
                        if (refused) Memphis.Coral else Memphis.Ink.copy(alpha = 0.65f),
                    ),
                )
            }
        }
    }
}

/**
 * The prompt the owner must now write, and the way to close it.
 *
 * WHY A PANEL AND NOT A BAR. A warning printed as a permanent strip under the switch
 * covered the sentence beneath it and could not be put away. This is a printed sheet
 * that says what is required, offers one action that closes it, and leaves the rest of
 * the page alone.
 *
 * IT DOES NOT WRITE ANYTHING. Dismissing closes the sheet. The switch has not moved and
 * the prompt is still missing, which is the honest state: the owner has been told what
 * is needed and decides when to do it.
 */
@Composable
private fun PromptRequiredPanel(onDismiss: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        MemphisSurface(
            modifier = Modifier.fillMaxWidth(),
            color = Memphis.Sun,
            borderWidth = 2.5.dp,
            shadowOffset = 5.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text("A MASTER PROMPT IS NEEDED", style = posterStyle(15f))

                Spacer(Modifier.height(7.dp))
                Text(
                    "The heartbeat cannot be switched on without one. A master prompt is " +
                        "what GURU sends itself every thirty minutes, so with nothing " +
                        "written there would be nothing to run.",
                    style = bodyText(),
                )

                Spacer(Modifier.height(9.dp))
                Text(
                    "Write one in the editor below, save it, then the switch will turn on.",
                    style = captionStyle(9f, Memphis.Ink.copy(alpha = 0.75f)),
                )

                Spacer(Modifier.height(13.dp))
                MemphisSurface(
                    modifier = Modifier.fillMaxWidth().clickable { onDismiss() },
                    color = Memphis.Ink,
                    borderWidth = 2.5.dp,
                    shadowOffset = 3.dp,
                ) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("GOT IT", style = captionStyle(9.5f, Memphis.Paper))
                    }
                }
            }
        }
    }
}

@Composable
private fun Notice(message: String, ink: Color, label: Color = Memphis.Paper) {
    MemphisSurface(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        color = ink,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Text(
            message,
            style = captionStyle(9f, label),
            modifier = Modifier.fillMaxWidth().padding(11.dp),
        )
    }
}

@Composable
private fun PrintedButton(
    label: String,
    modifier: Modifier = Modifier,
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    MemphisSurface(
        modifier = modifier.then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        color = if (filled && enabled) Memphis.Ink else Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label.uppercase(),
                style = captionStyle(
                    9.5f,
                    when {
                        !enabled -> Memphis.Ink.copy(alpha = 0.35f)
                        filled -> Memphis.Paper
                        else -> Memphis.Ink
                    },
                ),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The body face at a readable size, in the house voice. */
private fun bodyText() = androidx.compose.ui.text.TextStyle(
    fontFamily = MemphisBody,
    fontSize = 12.5.sp,
    lineHeight = 17.sp,
    color = Memphis.Ink,
)

/**
 * Minutes past midnight right now, so the diary can open on the half hour a person is in.
 *
 * READ ONCE PER SCROLL rather than kept in state. A clock held in state would be a second
 * definition of "now" that drifts from the one the bookings use, and this only needs to be
 * right at the moment the list is positioned.
 */
private fun minuteOfDayNow(): Int = Calendar.getInstance().let {
    it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
}
