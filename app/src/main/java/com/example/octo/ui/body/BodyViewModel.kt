package com.example.octo.ui.body

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.octo.OctoApplication
import com.example.octo.data.local.BodyPart
import com.example.octo.data.local.BodyPartDao
import com.example.octo.data.local.BodyPartStretchEntity
import com.example.octo.data.local.SessionDao
import com.example.octo.data.local.SessionWithTitle
import com.example.octo.data.repository.SessionRepository
import com.example.octo.data.settings.SettingsRepository
import com.example.octo.ipc.TailIntegrationClient
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Dashboard presentation state (Phase 6): per-region minutes for the selected date,
 * the % ring value, totals, and the stretch streak.
 *
 * Phase 22: also carries PENDING (played, unconfirmed) sessions for the home banner
 * + review sheet, and the CONFIRMED sessions of the selected day for the undo sheet.
 * Streak/percent/totals are computed from BodyPartStretchEntity — which only
 * [SessionRepository.confirmSession] ever writes — so unconfirmed playback is
 * invisible here by construction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BodyViewModel(
    private val bodyPartDao: BodyPartDao,
    private val sessionDao: SessionDao,
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val tailClient: TailIntegrationClient,
) : ViewModel() {

    /** Selected date for the (read-only) history navigation; defaults to today. */
    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    private val dayRows: Flow<List<BodyPartStretchEntity>> =
        _selectedDate.flatMapLatest { date -> bodyPartDao.forDay(date.iso()) }

    private val streakWindow: Flow<List<BodyPartStretchEntity>> =
        bodyPartDao.forRange(
            fromDate = LocalDate.now().minusDays(STREAK_WINDOW_DAYS - 1L).iso(),
            toDate = LocalDate.now().iso(),
        )

    /** Phase 22: pending sessions (any day) — the home review banner. */
    val pendingSessions: StateFlow<List<SessionWithTitle>> =
        sessionDao.pendingWithTitles()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Phase 22: confirmed sessions on the SELECTED day — the undo sheet. */
    val confirmedForSelectedDay: StateFlow<List<SessionWithTitle>> =
        _selectedDate.flatMapLatest { date ->
            val zone = ZoneId.systemDefault()
            sessionDao.confirmedForDay(
                dayStartMillis = date.atStartOfDay(zone).toInstant().toEpochMilli(),
                dayEndMillis = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    data class Dashboard(
        val selectedDate: LocalDate = LocalDate.now(),
        val isToday: Boolean = true,
        val minutesByPart: Map<BodyPart, Int> = emptyMap(),
        val totalMinutes: Int = 0,
        val sessions: Int = 0,
        val percent: Int = 0,
        val streakDays: Int = 0,
    )

    val dashboard: StateFlow<Dashboard> =
        combine(_selectedDate, dayRows, streakWindow) { date, rows, window ->
            val minutesByPart: Map<BodyPart, Int> = rows
                .mapNotNull { row -> BodyPart.fromId(row.bodyPartId)?.to(row.minutes) }
                .toMap()
            Dashboard(
                selectedDate = date,
                isToday = date == LocalDate.now(),
                minutesByPart = minutesByPart,
                totalMinutes = rows.sumOf { it.minutes },
                sessions = rows.sumOf { it.sessions },
                percent = ((minutesByPart.size * 100.0) / BodyPart.entries.size).toInt(),
                streakDays = computeStreak(window),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Dashboard())

    // ── Phase 22 actions ──

    /** "Log it" (review sheet): confirm + single Tail broadcast with the credited minutes. */
    fun confirmPending(sessionRowId: Long) {
        viewModelScope.launch {
            val minutes = sessionRepository.confirmSession(sessionRowId) ?: return@launch
            sendToTail(minutes)
        }
    }

    fun confirmAllPending() {
        viewModelScope.launch {
            for (row in pendingSessions.value) {
                val minutes = sessionRepository.confirmSession(row.autoId) ?: continue
                sendToTail(minutes)
            }
        }
    }

    fun discardPending(sessionRowId: Long) {
        viewModelScope.launch { sessionRepository.discardSession(sessionRowId) }
    }

    /** Undo a logged session: exact aggregate reversal via the credited snapshot. */
    fun undoConfirmed(sessionRowId: Long) {
        viewModelScope.launch { sessionRepository.undoConfirmedSession(sessionRowId) }
    }

    private suspend fun sendToTail(minutes: Int) {
        if (minutes <= 0) return
        val habit = settingsRepository.settings.firstOrNull()?.tailHabitName
            ?.takeIf { it.isNotBlank() }
            ?: SettingsRepository.DEFAULT_TAIL_HABIT_NAME
        tailClient.sendIncrement(habitName = habit, minutes = minutes, sessions = 1)
    }

    fun selectPreviousDay() {
        _selectedDate.value = _selectedDate.value.minusDays(1)
    }

    fun selectNextDay() {
        val next = _selectedDate.value.plusDays(1)
        if (!next.isAfter(LocalDate.now())) _selectedDate.value = next
    }

    fun backToToday() {
        _selectedDate.value = LocalDate.now()
    }

    private fun computeStreak(window: List<BodyPartStretchEntity>): Int {
        val days = window.map { it.date }.toSet()
        var cursor = LocalDate.now()
        // Today not yet stretched → streak still alive if yesterday chain holds.
        if (cursor.iso() !in days) cursor = cursor.minusDays(1)
        var streak = 0
        while (cursor.iso() in days) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    private fun LocalDate.iso(): String = format(DateTimeFormatter.ISO_LOCAL_DATE)

    companion object {
        private const val STREAK_WINDOW_DAYS = 60L

        /** Factory pattern consistent with the other screens (manual DI, no Hilt). */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OctoApplication
                val container = app.appContainer
                BodyViewModel(
                    bodyPartDao = container.database.bodyPartDao(),
                    sessionDao = container.database.sessionDao(),
                    sessionRepository = container.sessionRepository,
                    settingsRepository = container.settingsRepository,
                    tailClient = TailIntegrationClient(app),
                )
            }
        }
    }
}
