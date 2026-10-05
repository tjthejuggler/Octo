package com.example.octo

import android.app.Application
import com.example.octo.data.repository.SessionRepository
import com.example.octo.ipc.TailIntegrationClient
import com.example.octo.player.SessionPlayerManager
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Configuration
import com.example.octo.data.local.DatabaseProvider
import com.example.octo.data.local.OctoDatabase
import com.example.octo.data.repository.VideoRepository
import com.example.octo.data.settings.SettingsRepository
import com.example.octo.downloader.VideoDownloadManager
import com.example.octo.llm.BodyPartAnalysisPipeline
import com.example.octo.llm.LlmClient
import com.example.octo.network.OkHttpDownloaderBridge
import com.example.octo.network.octoOkHttpClient
import com.example.octo.youtube.TranscriptFetcher
import com.example.octo.youtube.YoutubeMetaFetcher
import com.example.octo.youtube.YoutubeUrlParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe

private val Context.octoDataStore by preferencesDataStore(name = "octo_settings")

/**
 * Manual dependency graph (no Hilt — docs/NOTES.md §6).
 * Activities and workers reach dependencies via `application.appContainer`.
 */
class OctoApplication : Application(), Configuration.Provider {

    lateinit var appContainer: AppContainer
        private set

    /** Application-wide scope for fire-and-forget work (outlives any screen). */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
        // Phase 8: after process death, rows can be stranded in PENDING/DOWNLOADING with no
        // live worker, and the player can leave a zero-second session row open. Unique-KEEP
        // enqueue makes re-kicks idempotent; abandoned empty session rows are deleted.
        applicationScope.launch { recoverAfterProcessDeath() }
        // Phase 15: migrate stored pre-z.ai LLM defaults (api.openai.com/gpt-4o-mini or
        // unset) to the z.ai defaults once, before the first settings read. Exact-match
        // only — user-customized values are never clobbered.
        applicationScope.launch {
            runCatching { appContainer.settingsRepository.runLegacyDefaultsMigration() }
        }
    }

    private suspend fun recoverAfterProcessDeath() {
        runCatching { appContainer.videoRepository.resumePendingDownloads() }
        runCatching { appContainer.sessionRepository.deleteEmptySessions() }
    }

    /** WorkManager needs on-demand initialization to see [appContainer] (worker DI). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(OctoWorkerFactory(appContainer))
            .build()

    /** Simple hand-rolled DI container (Wags-style). */
    class AppContainer(context: Context) {
        val okHttpClient = octoOkHttpClient()

        init {
            // NewPipeExtractor global init — once per process, before first extractor use.
            NewPipe.init(
                OkHttpDownloaderBridge(
                    OkHttpDownloaderBridge.newExtractorClient(octoOkHttpClient()),
                ),
            )
        }

        val database: OctoDatabase = DatabaseProvider.get(context)
        val settingsRepository = SettingsRepository(context.octoDataStore)
        val youtubeUrlParser = YoutubeUrlParser
        val youtubeMetaFetcher = YoutubeMetaFetcher()
        val videoDownloadManager = VideoDownloadManager(
            context = context.applicationContext,
            okHttpClient = okHttpClient,
            metaFetcher = youtubeMetaFetcher,
        )
        val videoRepository = VideoRepository(
            context = context.applicationContext,
            videoDao = database.videoDao(),
            videoBodyPartDao = database.videoBodyPartDao(),
            metaFetcher = youtubeMetaFetcher,
            downloadManager = videoDownloadManager,
        )

        // Phase 5: transcript + LLM body-part extraction.
        val transcriptFetcher = TranscriptFetcher(
            okHttpClient = okHttpClient,
            metaFetcher = youtubeMetaFetcher,
        )
        val llmClient = LlmClient(okHttpClient)
        val bodyPartAnalysisPipeline = BodyPartAnalysisPipeline(
            db = database,
            transcriptFetcher = transcriptFetcher,
            llmClient = llmClient,
            settingsRepository = settingsRepository,
        )
        init {
            videoRepository.attachAnalysisPipeline(bodyPartAnalysisPipeline)
        }

        // Phase 4: player + session/Tail integration.
        val tailIntegrationClient = TailIntegrationClient(context.applicationContext)
        val sessionRepository = SessionRepository(database)
        val sessionPlayerManager = SessionPlayerManager(
            context = context.applicationContext,
            okHttpClient = okHttpClient,
        )
    }
}
