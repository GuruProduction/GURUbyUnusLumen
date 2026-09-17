package com.unuslumen.app.guru.presentation.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.repository.CalendarRepository
import com.unuslumen.app.domain.repository.LuxifyRepository
import com.unuslumen.app.domain.repository.ProjectRepository
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
 *
 * Two counts ride repository Flows and update live as data changes.
 * Today's calendar count is a ranged query re-run when the Lobby is entered.
 * No hardcoded numbers anywhere.
 */
@KoinViewModel
class LobbyViewModel(
    private val projectRepository: ProjectRepository,
    private val calendarRepository: CalendarRepository,
    private val luxifyRepository: LuxifyRepository,
) : ViewModel() {

    data class LobbyCounts(
        val projects: Int = 0,
        val calendarToday: Int = 0,
        val skills: Int = 0,
    )

    /** Today's calendar count lives outside the combine because it is a
     *  snapshot query, not a flow. Merged into the public state below. */
    private val calendarToday = MutableStateFlow(0)

    private val flowCounts = combine(
        projectRepository.getActiveProjects().map { it.size },
        luxifyRepository.getAllSkillsFlow().map { it.size },
    ) { projects, skills ->
        LobbyCounts(
            projects = projects,
            skills = skills,
        )
    }

    val counts: StateFlow<LobbyCounts> = combine(flowCounts, calendarToday) { base, today ->
        base.copy(calendarToday = today)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LobbyCounts())

    private val todayRange: Pair<Long, Long> by lazy {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.now().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        start to end
    }

    init {
        refreshTodayEvents()
    }

    /** Re-queries today's calendar range. Call whenever the Lobby composes. */
    fun refreshTodayEvents() {
        viewModelScope.launch {
            val (start, end) = todayRange
            calendarToday.value = calendarRepository.getEvents(start, end).size
        }
    }
}