package com.example.octo.downloader

import android.content.Context
import com.example.octo.youtube.YoutubeMetaFetcher
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Downloads a YouTube video to app-private storage (docs/ARCHITECTURE.md §4.2).
 *
 * Strategy:
 *  1. **Muxed first** — progressive MP4 (itag 22 720p / itag 18 360p preferred, anything
 *     MP4 ≤ 1080p accepted) → `filesDir/videos/<id>.mp4`, plays directly in Media3.
 *  2. **Dual fallback** — best video-only MP4 ≤ 1080p → `<id>.video`, best m4a →
 *     `<id>.audio`; merged at playback with Media3 MergingMediaSource (Phase 4).
 *  3. Thumbnail → `filesDir/thumbs/<id>.jpg`; failure here is non-fatal (UI falls back
 *     to the remote i.ytimg.com URL).
 *
 * Audio path is derivable from the video path: `videos/<id>.video` → `videos/<id>.audio`
 * (see [audioPathFor]).
 *
 * All functions must run off the main thread; every network read is wrapped in Dispatchers.IO.
 * Downloads write to `<name>.part` and atomically rename on success, so a killed process can
 * never leave a half-written file that looks complete.
 */
class VideoDownloadManager(
    context: Context,
    private val okHttpClient: OkHttpClient,
    private val metaFetcher: YoutubeMetaFetcher,
) {
    private val videosDir = File(context.filesDir, "videos").apply { mkdirs() }
    private val thumbsDir = File(context.filesDir, "thumbs").apply { mkdirs() }

    sealed interface Outcome {
        /** Single progressive MP4 — [file] is the playable file. */
        data class Muxed(val file: File, val thumbnailFile: File?) : Outcome

        /** Video-only track + side-loaded audio; merged at playback time. */
        data class Dual(
            val videoFile: File,
            val audioFile: File,
            val thumbnailFile: File?,
        ) : Outcome
    }

    /** Download artifacts plus the metadata fetched on the way (worker persists both). */
    data class Result(val outcome: Outcome, val meta: YoutubeMetaFetcher.VideoMeta)

    /** Byte-level progress; `contentLength` may be -1 when the server omits it. */
    fun interface ProgressListener {
        fun onProgress(bytesRead: Long, contentLength: Long)
    }

    /** Derivable audio path for a [VideoEntity.localFilePath] pointing at the video track. */
    fun audioPathFor(videoPath: String): String =
        videoPath.replaceAfterLast('.', "audio")

    fun thumbnailPathFor(videoId: String): File = File(thumbsDir, "$videoId.jpg")

    fun muxedPathFor(videoId: String): File = File(videosDir, "$videoId.mp4")

    /**
     * Full pipeline: fetch streams → pick best → download video (+audio) → thumbnail.
     * The video track is downloaded first so a mid-flight cancellation leaves the more
     * expensive file in the `.part` temp state, never as a completed path.
     */
    @Throws(IOException::class)
    suspend fun download(
        videoId: String,
        progress: ProgressListener? = null,
    ): Result = withContext(Dispatchers.IO) {
        val full = metaFetcher.fetchFull(videoId)
        val info = full.streamInfo

        val muxed = pickMuxedStream(info)
        val outcome = if (muxed != null) {
            val file = File(videosDir, "$videoId.mp4")
            downloadTo(file, muxed.content, progress)
            Outcome.Muxed(file, thumbnailFile = null)
        } else {
            val videoOnly = pickVideoOnlyStream(info)
                ?: throw IOException("No usable MP4 video stream (muxed or video-only) for $videoId")
            val audio = pickAudioStream(info)
                ?: throw IOException("No m4a audio stream for dual-track download of $videoId")
            val videoFile = File(videosDir, "$videoId.video")
            val audioFile = File(videosDir, "$videoId.audio")
            downloadTo(videoFile, videoOnly.content, progress)
            downloadTo(audioFile, audio.content, progress)
            Outcome.Dual(videoFile, audioFile, thumbnailFile = null)
        }

        val thumb = downloadThumbnail(videoId, full.meta.thumbnailUrl)
        val withThumb = when (outcome) {
            is Outcome.Muxed -> outcome.copy(thumbnailFile = thumb)
            is Outcome.Dual -> outcome.copy(thumbnailFile = thumb)
        }
        Result(withThumb, full.meta)
    }

    /** Best muxed MP4: itag 22 → itag 18 → tallest MP4 ≤ 1080p. */
    private fun pickMuxedStream(info: StreamInfo): VideoStream? {
        val mp4 = info.videoStreams.filter { it.format == MediaFormat.MPEG_4 && !it.content.isNullOrBlank() }
        return mp4.firstOrNull { it.itag == 22 }
            ?: mp4.firstOrNull { it.itag == 18 }
            ?: mp4.filter { it.height in 1..MAX_HEIGHT }
                .maxByOrNull { it.height }
    }

    /** Tallest video-only MP4 ≤ 1080p. */
    private fun pickVideoOnlyStream(info: StreamInfo): VideoStream? =
        info.videoOnlyStreams
            .filter { it.format == MediaFormat.MPEG_4 && !it.content.isNullOrBlank() && it.height <= MAX_HEIGHT }
            .maxByOrNull { it.height }

    /** Highest-bitrate m4a. */
    private fun pickAudioStream(info: StreamInfo): AudioStream? =
        info.audioStreams
            .filter { it.format == MediaFormat.M4A && !it.content.isNullOrBlank() }
            .maxByOrNull { it.averageBitrate }

    /** Streams `url` into [target] via OkHttp; `.part` temp + atomic rename. */
    private suspend fun downloadTo(target: File, url: String, progress: ProgressListener?) =
        withContext(Dispatchers.IO) {
            val temp = File(target.parentFile, target.name + PART_SUFFIX)
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Download HTTP ${response.code} for ${target.name}")
                    }
                    val body = response.body ?: throw IOException("Empty body for ${target.name}")
                    val contentLength = body.contentLength()

                    body.byteStream().use { input ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            var total = 0L
                            while (true) {
                                coroutineContext.ensureActive() // cooperative cancellation
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                total += read
                                progress?.onProgress(total, contentLength)
                            }
                            output.flush()
                        }
                    }
                }

                if (target.exists() && !target.delete()) {
                    throw IOException("Could not replace existing ${target.name}")
                }
                if (!temp.renameTo(target)) {
                    throw IOException("Rename ${temp.name} → ${target.name} failed")
                }
            } finally {
                temp.delete() // no-op on success (renamed away); cleans partial on failure
            }
        }

    /** Fetches the thumbnail; returns null (never throws) so thumbnails can't fail a download. */
    private suspend fun downloadThumbnail(videoId: String, remoteUrl: String?): File? =
        withContext(Dispatchers.IO) {
            val target = thumbnailPathFor(videoId)
            val candidates = listOfNotNull(
                remoteUrl,
                "https://i.ytimg.com/vi/$videoId/hqdefault.jpg", // stable fallback (§4.2)
            )
            for (url in candidates) {
                try {
                    val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use
                        val bytes = response.body?.bytes() ?: return@use
                        if (bytes.isEmpty()) return@use
                        if (!target.exists() || target.delete()) {
                            target.writeBytes(bytes)
                            return@withContext target
                        }
                    }
                } catch (_: IOException) {
                    // try next candidate
                }
            }
            null
        }

    /** Removes every local artifact for [videoId] (files + DB row cleanup is the repo's job). */
    fun deleteFiles(videoId: String) {
        listOf(
            File(videosDir, "$videoId.mp4"),
            File(videosDir, "$videoId.video"),
            File(videosDir, "$videoId.audio"),
            thumbnailPathFor(videoId),
        ).forEach { it.delete() }
    }

    private companion object {
        const val MAX_HEIGHT = 1080
        const val PART_SUFFIX = ".part"
        const val BUFFER_BYTES = 64 * 1024
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
