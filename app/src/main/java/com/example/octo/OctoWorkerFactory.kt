package com.example.octo

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.example.octo.work.DownloadVideoWorker
import com.example.octo.work.ReanalyzeLibraryWorker

/**
 * Manual-DI bridge so [CoroutineWorker]s receive dependencies from [OctoApplication.AppContainer]
 * (WorkManager's default reflective factory has no constructor to call).
 */
class OctoWorkerFactory(private val container: OctoApplication.AppContainer) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        DownloadVideoWorker::class.java.name ->
            DownloadVideoWorker(appContext, workerParameters)

        ReanalyzeLibraryWorker::class.java.name ->
            ReanalyzeLibraryWorker(appContext, workerParameters, container.bodyPartAnalysisPipeline)

        else -> null // fall back to default reflective instantiation
    }
}
