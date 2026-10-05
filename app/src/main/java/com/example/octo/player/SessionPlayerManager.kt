package com.example.octo.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.example.octo.data.local.VideoEntity
import java.io.File
import okhttp3.OkHttpClient

/** Result of resolving a [VideoEntity] to something ExoPlayer can play. */
sealed interface PlaybackResolution {
    /** Local media source ready to hand to `ExoPlayer.setMediaSource`. */
    data class Ready(val source: MediaSource) : PlaybackResolution

    /** No usable local file (FAILED / STREAM_ONLY / deleted) — offline-first, no streaming. */
    data object MissingFiles : PlaybackResolution
}

/**
 * Media3 playback plumbing (docs/ARCHITECTURE.md §4.2 download strategy + §6 player pkg).
 *
 * Local-first resolution for a downloaded video:
 *  1. Muxed progressive MP4 (`videos/<id>.mp4`) → single [ProgressiveMediaSource].
 *  2. Dual-track download (`videos/<id>.video` + sibling `<id>.audio`, paths per
 *     [VideoDownloadManager]) → [MergingMediaSource] (video + side-loaded audio).
 *  3. Neither present → [PlaybackResolution.MissingFiles]; the UI shows a friendly
 *     error + retry-download. Streaming the canonical YouTube URL is NOT supported
 *     by plain ExoPlayer, so offline-first is the only playback path.
 */
class SessionPlayerManager(context: Context, okHttpClient: OkHttpClient) {

    private val appContext: Context = context.applicationContext

    /** DefaultDataSource wraps OkHttp — file:// URIs go through FileDataSource, http through OkHttp. */
    private val dataSourceFactory = DefaultDataSource.Factory(
        appContext,
        OkHttpDataSource.Factory(okHttpClient).setUserAgent(USER_AGENT),
    )

    /** Resolves the best local playback source for [video]. Pure function, main-thread safe. */
    fun resolve(video: VideoEntity): PlaybackResolution {
        val videosDir = File(appContext.filesDir, "videos")

        // 1) Muxed progressive MP4 — preferred (Phase 3 primary strategy).
        val muxed = File(videosDir, "${video.id}.mp4")
        if (muxed.exists() && muxed.length() > 0) {
            return PlaybackResolution.Ready(progressive(muxed))
        }

        // 2) Dual download: localFilePath points at the video-only track; audio is derivable.
        val primary = video.localFilePath?.let(::File)
        if (primary != null && primary.exists() && primary.length() > 0) {
            val audio = File(primary.parentFile, primary.nameWithoutExtension + AUDIO_SUFFIX)
            return if (audio.exists() && audio.length() > 0) {
                PlaybackResolution.Ready(MergingMediaSource(progressive(primary), progressive(audio)))
            } else {
                // Video-only file without audio — still playable (silent), better than nothing.
                PlaybackResolution.Ready(progressive(primary))
            }
        }

        return PlaybackResolution.MissingFiles
    }

    /**
     * Builds the player. Audio focus is handled by Media3 (`handleAudioFocus = true`),
     * so pausing on call/other-app audio is automatic. The caller owns the listener.
     */
    fun createPlayer(listener: Player.Listener): ExoPlayer = ExoPlayer.Builder(appContext).build().apply {
        setAudioAttributes(AUDIO_ATTRIBUTES, /* handleAudioFocus = */ true)
        addListener(listener)
    }

    private fun progressive(file: File): ProgressiveMediaSource =
        ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(Uri.fromFile(file)))

    private companion object {
        const val AUDIO_SUFFIX = ".audio"
        const val USER_AGENT = "Octo/1.0 (Media3)"

        val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
    }
}
