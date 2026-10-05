package com.example.octo.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Immutable snapshot of user settings — exposed as a Flow by [SettingsRepository]. */
data class OctoSettings(
    val llmBaseUrl: String,
    val llmApiKey: String,
    val llmModel: String,
    val tailHabitName: String,
)

/**
 * DataStore-backed settings (docs/ARCHITECTURE.md §4.4): provider-agnostic
 * OpenAI-compatible LLM endpoint (defaults to z.ai, Phase 15) + the Tail habit
 * receiving session broadcasts (§2.7).
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val LLM_BASE_URL = stringPreferencesKey("llm_base_url")
        val LLM_API_KEY = stringPreferencesKey("llm_api_key")
        val LLM_MODEL = stringPreferencesKey("llm_model")
        val TAIL_HABIT_NAME = stringPreferencesKey("tail_habit_name")
    }

    /**
     * Phase 15: one-time migration of pre-z.ai defaults. DataStore defaults only
     * apply on fresh installs — a device that ran an older build persists the
     * OpenAI defaults as literal values (or has them unset-but-read). On first
     * read we overwrite values that match the OLD defaults EXACTLY; anything the
     * user customized (different base URL / model) is left untouched.
     */
    private suspend fun migrateLegacyDefaultsIfNeeded() {
        val prefs = dataStore.data.first()
        val storedBaseUrl = prefs[Keys.LLM_BASE_URL]
        val storedModel = prefs[Keys.LLM_MODEL]

        val baseUrlNeedsMigration = storedBaseUrl == null || storedBaseUrl == LEGACY_LLM_BASE_URL
        val modelNeedsMigration = storedModel == null || storedModel == LEGACY_LLM_MODEL
        if (!baseUrlNeedsMigration && !modelNeedsMigration) return

        dataStore.edit { mutable ->
            if (baseUrlNeedsMigration) mutable[Keys.LLM_BASE_URL] = DEFAULT_LLM_BASE_URL
            if (modelNeedsMigration) mutable[Keys.LLM_MODEL] = DEFAULT_LLM_MODEL
        }
    }

    val settings: Flow<OctoSettings> = dataStore.data.map { prefs ->
        OctoSettings(
            llmBaseUrl = prefs[Keys.LLM_BASE_URL] ?: DEFAULT_LLM_BASE_URL,
            llmApiKey = prefs[Keys.LLM_API_KEY] ?: "",
            llmModel = prefs[Keys.LLM_MODEL] ?: DEFAULT_LLM_MODEL,
            tailHabitName = prefs[Keys.TAIL_HABIT_NAME] ?: DEFAULT_TAIL_HABIT_NAME,
        )
    }

    val llmBaseUrl: Flow<String> = settings.map { it.llmBaseUrl }
    val llmApiKey: Flow<String> = settings.map { it.llmApiKey }
    val llmModel: Flow<String> = settings.map { it.llmModel }
    val tailHabitName: Flow<String> = settings.map { it.tailHabitName }

    suspend fun setLlmBaseUrl(value: String) = dataStore.edit { it[Keys.LLM_BASE_URL] = value }
    suspend fun setLlmApiKey(value: String) = dataStore.edit { it[Keys.LLM_API_KEY] = value }
    suspend fun setLlmModel(value: String) = dataStore.edit { it[Keys.LLM_MODEL] = value }
    suspend fun setTailHabitName(value: String) = dataStore.edit { it[Keys.TAIL_HABIT_NAME] = value }

    /**
     * Runs the legacy-defaults migration exactly once per process (idempotent —
     * an already-migrated store matches nothing). Called from [OctoApplication]-owned
     * init; see docs/NOTES.md Phase 15.
     */
    suspend fun runLegacyDefaultsMigration() = migrateLegacyDefaultsIfNeeded()

    companion object {
        const val DEFAULT_LLM_BASE_URL = "https://api.z.ai/api/paas/v4"
        const val DEFAULT_LLM_MODEL = "glm-4.6"
        const val DEFAULT_TAIL_HABIT_NAME = "Yoga & Stretch"

        /** Pre-Phase-15 defaults — migrated away from on first read (exact match only). */
        const val LEGACY_LLM_BASE_URL = "https://api.openai.com/v1"
        const val LEGACY_LLM_MODEL = "gpt-4o-mini"
    }
}
