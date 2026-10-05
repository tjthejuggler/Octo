package com.example.octo.youtube

import java.net.URI
import java.net.URISyntaxException

/**
 * YouTube URL parsing — regex + video-id extraction rules are the ones documented in
 * docs/ARCHITECTURE.md §4.1 (reused verbatim from Wags' proven `YOUTUBE_URL_REGEX`).
 *
 * Supported shapes:
 *  - youtube.com/watch?v=<ID>            (also music.youtube.com/watch?v=<ID>)
 *  - youtu.be/<ID>
 *  - youtube.com/shorts/<ID>
 *  - youtube.com/embed/<ID>
 *  - youtube.com/live/<ID>
 *
 * [parse] tolerates extra text around the URL (YouTube's share sheet and users both add some).
 */
object YoutubeUrlParser {

    private val YOUTUBE_URL_REGEX = Regex(
        pattern =
            """https?://(?:[a-z0-9-]+\.)*(?:youtube\.com/(?:watch\?\S+|shorts/\S+|embed/\S+|live/\S+)|youtu\.be/\S+|music\.youtube\.com/watch\?\S+)""",
        options = setOf(RegexOption.IGNORE_CASE),
    )

    private val VIDEO_ID_REGEX = Regex("[A-Za-z0-9_-]{6,20}")

    private val PATH_ID_HOSTS = setOf("shorts", "embed", "live")

    /** A successfully parsed YouTube video. */
    data class Parsed(val videoId: String, val canonicalUrl: String)

    /** Finds the first YouTube video URL in [text] (may contain surrounding prose). */
    fun parse(text: String): Parsed? =
        YOUTUBE_URL_REGEX
            .findAll(text)
            .map { it.value.trimEnd(',', '.', ';', ')', ']', '!', '?') }
            .firstNotNullOfOrNull(::parseUrl)

    /** Parses a single URL; returns null when it is not a YouTube video URL. */
    fun parseUrl(url: String): Parsed? =
        extractVideoId(url)?.let { videoId -> Parsed(videoId, canonicalWatchUrl(videoId)) }

    /** Canonical watch URL — the library's restore key (§4.2: always persisted). */
    fun canonicalWatchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

    private fun extractVideoId(url: String): String? = try {
        val uri = URI(url)
        val host = uri.host?.lowercase() ?: return null
        when {
            host.endsWith("youtu.be") -> {
                val segment = uri.path?.trim('/')?.substringBefore('/')
                segment?.takeIf(::isValidVideoId)
            }

            host.endsWith("youtube.com") -> {
                val path = uri.path.orEmpty()
                if (path.startsWith("/watch")) {
                    uri.rawQuery
                        ?.split('&')
                        ?.firstNotNullOfOrNull { param ->
                            if (param.startsWith("v=")) param.substring(2).takeIf(::isValidVideoId) else null
                        }
                } else {
                    val segments = path.split('/').filter { it.isNotBlank() }
                    if (segments.size >= 2 && segments[0] in PATH_ID_HOSTS && isValidVideoId(segments[1])) {
                        segments[1]
                    } else {
                        null
                    }
                }
            }

            else -> null
        }
    } catch (_: URISyntaxException) {
        null
    }

    private fun isValidVideoId(candidate: String): Boolean = candidate.matches(VIDEO_ID_REGEX)
}
