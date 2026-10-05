package com.example.octo.network

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Shared OkHttp client (docs/ARCHITECTURE.md §6 `network/`).
 *
 * Used by: NewPipeExtractor bridge, [com.example.octo.downloader.VideoDownloadManager],
 * thumbnails. Long reads are expected (video downloads) — no overall call timeout.
 */
fun octoOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .callTimeout(0, TimeUnit.SECONDS) // no overall cap: downloads can run for minutes
    .retryOnConnectionFailure(true)
    .build()
