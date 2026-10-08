package com.example.octo.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.octo.OctoApplication
import com.example.octo.data.settings.SettingsRepository
import com.example.octo.ipc.TailIntegrationClient
import com.example.octo.llm.LlmClient
import com.example.octo.work.ReanalyzeLibraryWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings presentation state (Phase 5): editable LLM form, Test-LLM round-trip, and
 * Tail habit discovery via the provider query (Phase 4 [TailIntegrationClient]).
 */
class SettingsViewModel(
    application: Application,
    private val settingsRepository: SettingsRepository,
    private val llmClient: LlmClient,
    private val tailClient: TailIntegrationClient,
    private val bodyPartDao: com.example.octo.data.local.BodyPartDao,
    private val videoRepository: com.example.octo.data.repository.VideoRepository,
) : ViewModel() {

    /** App context for SAF stream access (export/import). */
    private val appContext: android.content.Context = application.applicationContext

    /** Result of the "Test LLM" round-trip. */
    sealed interface TestLlmState {
        data object Idle : TestLlmState
        data object Running : TestLlmState
        data class Success(val reply: String) : TestLlmState
        data class Failure(val message: String) : TestLlmState
    }

    /** Result of the Tail habit provider query. */
    sealed interface TailHabitsState {
        data object Loading : TailHabitsState

        /** Tail absent / provider unreachable / zero rows — graceful degraded UI. */
        data object Unavailable : TailHabitsState

        data class Found(val habits: List<String>) : TailHabitsState
    }

    // ── Form fields (pre-filled from DataStore once, then user-owned until Save) ──
    val baseUrl = MutableStateFlow(SettingsRepository.DEFAULT_LLM_BASE_URL)
    val apiKey = MutableStateFlow("")
    val model = MutableStateFlow(SettingsRepository.DEFAULT_LLM_MODEL)
    val habitName = MutableStateFlow(SettingsRepository.DEFAULT_TAIL_HABIT_NAME)

    private val _testLlm = MutableStateFlow<TestLlmState>(TestLlmState.Idle)
    val testLlm: StateFlow<TestLlmState> = _testLlm.asStateFlow()

    private val _tailHabits = MutableStateFlow<TailHabitsState>(TailHabitsState.Loading)
    val tailHabits: StateFlow<TailHabitsState> = _tailHabits.asStateFlow()

    /** Highlights the currently selected habit chip while loading. */
    val savedHabitName: StateFlow<String> = settingsRepository.tailHabitName
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.DEFAULT_TAIL_HABIT_NAME)

    val showPassword = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val current = settingsRepository.settings.first()
            baseUrl.value = current.llmBaseUrl
            apiKey.value = current.llmApiKey
            model.value = current.llmModel
            habitName.value = current.tailHabitName
        }
        loadTailHabits()
    }

    /** Persist all four fields to DataStore. */
    fun save(onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsRepository.setLlmBaseUrl(baseUrl.value.trim())
            settingsRepository.setLlmApiKey(apiKey.value.trim())
            settingsRepository.setLlmModel(model.value.trim())
            settingsRepository.setTailHabitName(habitName.value.trim())
            onSaved()
        }
    }

    /** Tiny "reply OK" round-trip against the endpoint as currently typed. */
    fun testLlm() {
        if (_testLlm.value == TestLlmState.Running) return
        if (apiKey.value.isBlank()) {
            _testLlm.value = TestLlmState.Failure(MESSAGE_NEEDS_KEY)
            return
        }
        _testLlm.value = TestLlmState.Running
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    llmClient.complete(
                        endpoint = LlmClient.Endpoint(
                            baseUrl = baseUrl.value.trim().ifBlank { SettingsRepository.DEFAULT_LLM_BASE_URL },
                            apiKey = apiKey.value.trim(),
                            model = model.value.trim().ifBlank { SettingsRepository.DEFAULT_LLM_MODEL },
                        ),
                        userMessage = "Say OK",
                    )
                }
            }
            _testLlm.value = result.fold(
                onSuccess = { TestLlmState.Success(it.take(60)) },
                onFailure = { TestLlmState.Failure(it.message ?: "Unknown error") },
            )
        }
    }

    /** Provider query — blocking binder call, so IO dispatcher. */
    fun loadTailHabits() {
        _tailHabits.value = TailHabitsState.Loading
        viewModelScope.launch {
            // queryHabits() is fail-safe: empty list covers both "Tail absent" and
            // "provider unreachable" (SecurityException etc. are caught inside).
            val habits = withContext(Dispatchers.IO) { tailClient.queryHabits() }
            _tailHabits.value = if (habits.isEmpty()) {
                TailHabitsState.Unavailable
            } else {
                TailHabitsState.Found(habits)
            }
        }
    }

    // ── Phase 8: library URL export / import (SAF) ──

    private val _backupStatus = MutableStateFlow<String?>(null)
    /** One-line result feedback ("Exported ✓" / "Imported 3 new…"), consumed by the UI. */
    val backupStatus: StateFlow<String?> = _backupStatus.asStateFlow()

    /** Writes the full library (URL rows) to [uri] via SAF. Never throws. */
    fun exportLibrary(uri: android.net.Uri, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val json = videoRepository.exportLibraryJson()
                    appContext.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("Could not open $uri for writing")
                    true
                }.getOrElse {
                    android.util.Log.w(TAG, "Library export failed", it)
                    _backupStatus.value = it.message ?: "export failed"
                    false
                }
            }
            if (ok) _backupStatus.value = STATUS_EXPORTED
            onDone(ok)
        }
    }

    /** Restores URL rows from [uri]; existing videoIds are skipped. Never throws. */
    fun importLibrary(uri: android.net.Uri, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val json = appContext.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    } ?: error("Could not read $uri")
                    val result = videoRepository.importLibraryJson(json)
                    _backupStatus.value = STATUS_IMPORT_FORMAT.format(result.imported, result.skippedExisting)
                    true
                }.getOrElse {
                    android.util.Log.w(TAG, "Library import failed", it)
                    _backupStatus.value = it.message ?: "import failed"
                    false
                }
            }
            if (!ok) Unit // status already carries the failure message
            onDone(ok)
        }
    }

    /** Tapping a habit chip selects AND persists it. */
    fun selectHabit(name: String) {
        habitName.value = name
        viewModelScope.launch { settingsRepository.setTailHabitName(name) }
    }

    // ── Muscle layer: "Reanalyze library" ──

    private val _reanalyzeStatus = MutableStateFlow<String?>(null)
    /** One-line feedback for the reanalyze button ("queued" / error). */
    val reanalyzeStatus: StateFlow<String?> = _reanalyzeStatus.asStateFlow()

    /**
     * Enqueues the unique [ReanalyzeLibraryWorker] — force re-runs body-part +
     * muscle (vision when frames exist) analysis for every library video in the
     * background. Requires a saved API key; use a vision-capable model in
     * Settings for the frame analysis (otherwise muscle extraction is
     * transcript-text-only).
     */
    fun reanalyzeLibrary() {
        viewModelScope.launch {
            val key = settingsRepository.settings.first().llmApiKey
            if (key.isBlank()) {
                _reanalyzeStatus.value = MESSAGE_NEEDS_KEY
                return@launch
            }
            val workManager = androidx.work.WorkManager.getInstance(appContext)
            val request = androidx.work.OneTimeWorkRequestBuilder<ReanalyzeLibraryWorker>().build()
            workManager.enqueueUniqueWork(
                ReanalyzeLibraryWorker.UNIQUE_NAME,
                androidx.work.ExistingWorkPolicy.KEEP,
                request,
            )
            _reanalyzeStatus.value = STATUS_REANALYZE_QUEUED
        }
    }

    /**
     * DEBUG-ONLY dev helper (Phase 6 task spec): seeds deterministic daily aggregates
     * so the body map / dashboard can be verified without playing a real session.
     * Builds 4 sessions spread over the last 3 days (today + 2) across a rotating
     * set of body parts, then increments the same aggregates the production
     * session pipeline writes. Harmless: only ADDS rows via BodyPartDao.increment.
     */
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.NONE)
    fun devSeedStretchData(onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val today = java.time.LocalDate.now()
            val days = listOf(today, today.minusDays(1), today.minusDays(2))
            val partGroups = listOf(
                listOf("hamstrings", "lower_back", "calves"),
                listOf("neck", "shoulders", "upper_back"),
                listOf("quads", "hips_glutes", "core"),
            )
            var increments = 0
            days.forEachIndexed { dayIndex, date ->
                val dateStr = date.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                partGroups[dayIndex % partGroups.size].forEachIndexed { partIdx, partId ->
                    bodyPartDao.increment(
                        date = dateStr,
                        bodyPartId = partId,
                        minutes = 4 + dayIndex * 2 + partIdx,
                        sessions = 1,
                    )
                    increments++
                }
            }
            onDone(increments)
        }
    }

    companion object {
        const val MESSAGE_NEEDS_KEY = "enter an API key first"
        const val STATUS_EXPORTED = "exported"
        const val STATUS_IMPORT_FORMAT = "imported:%d:%d"
        const val STATUS_REANALYZE_QUEUED = "queued"
        private const val TAG = "SettingsViewModel"

        /** Factory pattern consistent with [com.example.octo.ui.library.LibraryViewModel.Factory]. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OctoApplication
                val container = app.appContainer
                SettingsViewModel(
                    application = app,
                    settingsRepository = container.settingsRepository,
                    llmClient = container.llmClient,
                    tailClient = container.tailIntegrationClient,
                    bodyPartDao = container.database.bodyPartDao(),
                    videoRepository = container.videoRepository,
                )
            }
        }
    }
}
