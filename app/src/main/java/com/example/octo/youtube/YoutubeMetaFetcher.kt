package com.example.octo.youtube

import android.util.LruCache
import com.example.octo.youtube.YoutubeUrlParser.canonicalWatchUrl
import java.io.IOException
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Metadata + stream extraction via NewPipeExtractor (docs/ARCHITECTURE.md §4.2).
 *
 * `StreamInfo.getInfo(service, url)` validates through YoutubeStreamLinkHandlerFactory and
 * runs YoutubeStreamExtractor. The calls are blocking — every public function here must be
 * invoked from [kotlinx.coroutines.Dispatchers.IO].
 *
 * [org.schabi.newpipe.extractor.NewPipe.init] must have been called once before first use
 * (done in `OctoApplication.AppContainer`).
 */
class YoutubeMetaFetcher(private val service: StreamingService = ServiceList.YouTube) {

    /** Immutable snapshot of what the library UI needs from a stream page. */
    data class VideoMeta(
        val videoId: String,
        val title: String,
        val channel: String,
        val durationSeconds: Int,
        val viewCount: Long,
        val thumbnailUrl: String?, // best i.ytimg.com URL exposed by the extractor
        val canonicalUrl: String,
    )

    /** [VideoMeta] plus the raw extractor result (stream lists) — used by the downloader. */
    data class FullInfo(val meta: VideoMeta, val streamInfo: StreamInfo)

    private val cache = LruCache<String, VideoMeta>(32)

    /**
     * Fetches metadata for [videoId].
     *
     * @throws IOException on network failure
     * @throws ExtractionException on YouTube layout/bot-check breakage → caller maps to FAILED
     */
    @Synchronized
    @Throws(IOException::class, ExtractionException::class)
    fun fetch(videoId: String): VideoMeta = fetchFull(videoId).meta

    /**
     * Single watch-page fetch yielding both [VideoMeta] and the available streams.
     * Must be called from Dispatchers.IO.
     */
    @Synchronized
    @Throws(IOException::class, ExtractionException::class)
    fun fetchFull(videoId: String): FullInfo {
        val url = canonicalWatchUrl(videoId)
        val info: StreamInfo = StreamInfo.getInfo(service, url)

        val meta = VideoMeta(
            videoId = videoId,
            title = info.name.orEmpty().ifBlank { videoId },
            channel = info.uploaderName.orEmpty(),
            // NewPipeExtractor's StreamInfo.getDuration() is already in SECONDS.
            durationSeconds = info.duration.toInt().coerceAtLeast(0),
            viewCount = info.viewCount,
            thumbnailUrl = info.thumbnails
                .maxByOrNull { it.width.toLong() * it.height }
                ?.url,
            canonicalUrl = url,
        )
        cache.put(videoId, meta)
        return FullInfo(meta, info)
    }
}
