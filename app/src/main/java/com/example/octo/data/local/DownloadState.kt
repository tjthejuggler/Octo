package com.example.octo.data.local

/**
 * Lifecycle of a video's local copy.
 *
 * PENDING      — queued, worker has not picked it up yet
 * DOWNLOADING  — worker actively fetching bytes
 * COMPLETED    — localFilePath is playable offline
 * FAILED       — last attempt failed; UI offers retry
 * STREAM_ONLY  — never downloaded on purpose (URL-only fallback mode, §4.2)
 *
 * Persisted by Room via the built-in enum→name() String conversion (no TypeConverter needed).
 */
enum class DownloadState {
    PENDING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    STREAM_ONLY,
}
