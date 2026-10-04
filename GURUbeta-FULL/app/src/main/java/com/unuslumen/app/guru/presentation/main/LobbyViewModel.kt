// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.presentation.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.repository.CalendarRepository
import com.unuslumen.app.domain.repository.LuxifyRepository
import com.unuslumen.app.domain.repository.ProjectRepository
import com.unuslumen.app.guru.media.MediaLibraryRepository
import com.unuslumen.app.thoughts.domain.repository.ThoughtCycleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel
import java.time.LocalDate
import java.time.ZoneId

/**
 * Feeds the Lobby screen its live data.
 *
 * Every badge on a Lobby tile is a real count from a real repository:
 * - projects = currently active AI workspace projects
 * - calendar = events scheduled for today
 * - skills   = installed Luxify skills
 * - thoughts = unacknowledged insights waiting from background cycles
 * - media    = items ingested into the on-device media library
 *
 * Counts ride repository Flows and update live as data changes.
 * Today's calendar and media counts are snapshot queries re-run when the
 * Lobby screen is entered (refreshTodayEvents / refreshMediaCount).
 * No hardcoded numbers anywhere.
 */
@KoinViewModel
class LobbyViewModel(
    private val projectRepository: ProjectRepository,
    private val calendarRepository: CalendarRepository,
    private val luxifyRepository: LuxifyRepository,
    private val thoughtCycleRepository: ThoughtCycleRepository,
    private val mediaLibraryRepository: MediaLibraryRepository,
) : ViewModel() {

    data class LobbyCounts(
        val projects: Int = 0,
        val calendarToday: Int = 0,
        val skills: Int = 0,
        val thoughts: Int = 0,
        val media: Int = 0,
    )

    /** Today's calendar count lives outside the combine because it is a
     *  snapshot query, not a flow. Merged into the public state below. */
    private val calendarToday = MutableStateFlow(0)

    /** Media count rides a manual snapshot like the calendar count: the
     *  media library has no flow repository, it is a counted library, so
     *  the value refreshes on Lobby entry through refreshMediaCount(). */
    private val mediaCount = MutableStateFlow(0)

    private val flowCounts = combine(
        projectRepository.getActiveProjects().map { it.size },
        luxifyRepository.getAllSkillsFlow().map { it.size },
        thoughtCycleRepository.getAllInsightsFlow().map { insights ->
            insights.count { it.acknowledgedAt == null && it.dismissedAt == null }
        },
    ) { projects, skills, thoughts ->
        LobbyCounts(
            projects = projects,
            skills = skills,
            thoughts = thoughts,
        )
    }

    val counts: StateFlow<LobbyCounts> = combine(flowCounts, calendarToday, mediaCount) { base, today, media ->
        base.copy(calendarToday = today, media = media)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LobbyCounts())

    private val todayRange: Pair<Long, Long> by lazy {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.now().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        start to end
    }

    init {
        refreshTodayEvents()
        refreshMediaCount()
    }

    /** Re-queries today's calendar range. Call whenever the Lobby composes. */
    fun refreshTodayEvents() {
        viewModelScope.launch {
            val (start, end) = todayRange
            calendarToday.value = calendarRepository.getEvents(start, end).size
        }
    }

    /** Re-reads the real COUNT(*) of the media library. Call whenever the Lobby composes. */
    fun refreshMediaCount() {
        viewModelScope.launch {
            mediaCount.value = mediaLibraryRepository.count()
        }
    }
}