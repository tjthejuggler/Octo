package com.example.octo.youtube

import com.example.octo.data.local.TranscriptSource
import com.example.octo.network.octoOkHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.SubtitlesStream

/**
 * Transcript acquisition (docs/ARCHITECTURE.md §4.3) — best-effort tier chain:
 *
 *  1. PRIMARY — scrape `ytInitialPlayerResponse` off the watch page, read
 *     `captions.playerCaptionsTracklistRenderer.captionTracks[]`, prefer manual EN,
 *     then ASR EN, then any track; GET `<baseUrl>&fmt=json3` and flatten `events[].segs[].utf8`.
 *     ASR rolling-window repeats are deduped (cue N's leading words matching cue N−1's tail).
 *  1b. INNERTUBE — POST `youtubei/v1/player` with the ANDROID client and read the same
 *     `captionTracks[]`. The desktop watch-page scrape frequently yields caption baseUrls
 *     WITHOUT signature params, which the timedtext endpoint answers with HTTP 200 and an
 *     EMPTY body (NOTES §23) — the innertube client returns SIGNED baseUrls that work,
 *     recovering real captions (especially ASR on Shorts) that the scrape misses.
 *  2. FALLBACK — NewPipeExtractor `StreamInfo.getSubtitles()` (same caption data via the
 *     library; the extractor's content URL is fetched and parsed the same way).
 *  3. LAST RESORT — `title + description + tags`, flagged [TranscriptSource.TITLE_DESCRIPTION]
 *     so the LLM prompt knows it is not a real transcript. Tags/keywords come from the
 *     innertube response (videoDetails.keywords) or NewPipeExtractor — Shorts often have an
 *     empty description, so tags carry most of the remaining signal.
 *
 * Returns null only when all tiers fail ("No transcript available" in the UI).
 *
 * All public functions are blocking — invoke from [kotlinx.coroutines.Dispatchers.IO].
 * Timeouts come from the shared client (20 s connect / 60 s read, no call cap) —
 * this fetcher never touches multi-minute downloads, but reuses [octoOkHttpClient].
 */
class TranscriptFetcher(
    private val okHttpClient: OkHttpClient,
    private val metaFetcher: YoutubeMetaFetcher,
) {

    /** What the pipeline persists after a fetch. */
    data class Result(
        val text: String,
        val source: TranscriptSource,
    )

    /** Sentinel thrown internally when a tier fails, so the chain can fall through. */
    private class TierFailed(cause: Throwable? = null) : Exception(cause)

    fun fetch(videoId: String): Result? {
        // ── Tier 1: raw timedtext scrape ──
        try {
            val fromCaptions = fetchViaWatchPage(videoId)
            if (!fromCaptions.isNullOrBlank()) {
                return Result(fromCaptions, TranscriptSource.CAPTIONS)
            }
        } catch (_: Exception) {
            // fall through to tier 1b
        }

        // ── Tier 1b: innertube ANDROID player API (signed caption baseUrls) ──
        var innertube: InnertubeData? = null
        try {
            innertube = fetchViaInnertube(videoId)
            val fromInnertube = innertube?.let { captionsFromTracks(it.captionTracks) }
            if (!fromInnertube.isNullOrBlank()) {
                return Result(fromInnertube, TranscriptSource.CAPTIONS)
            }
        } catch (_: Exception) {
            // fall through to tier 2
        }

        // ── Tier 2: NewPipeExtractor subtitles ──
        try {
            val fromNewPipe = fetchViaNewPipeSubtitles(videoId)
            if (!fromNewPipe.isNullOrBlank()) {
                return Result(fromNewPipe, TranscriptSource.NEWPIPE_SUBTITLES)
            }
        } catch (_: Exception) {
            // fall through to tier 3
        }

        // ── Tier 3: title + description + tags ──
        try {
            val text = buildTitleDescriptionText(videoId, innertube)
            if (text.isNotBlank() && text != videoId) {
                return Result(text, TranscriptSource.TITLE_DESCRIPTION)
            }
        } catch (_: Exception) {
            // total failure below
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Tier 1 — watch page scrape
    // ─────────────────────────────────────────────────────────────────────────────

    private fun fetchViaWatchPage(videoId: String): String? {
        val watchUrl = YoutubeUrlParser.canonicalWatchUrl(videoId)
        val pageBody = httpGet(
            Request.Builder()
                .url(watchUrl)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "CONSENT=YES+cb")
                .build(),
        ) ?: throw TierFailed()

        val playerResponseJson = extractYtInitialPlayerResponse(pageBody)
            ?: throw TierFailed() // consent wall / bot check / layout change
        val captionTracks = captionTracks(playerResponseJson)
        if (captionTracks.isEmpty()) return null // genuinely no captions → next tier

        val track = pickTrack(captionTracks) ?: return null
        val baseUrl = track[BASE_URL_KEY]?.jsonPrimitive?.content ?: return null

        val json3Body = httpGet(
            Request.Builder()
                .url(baseUrl.trim() + "&fmt=json3")
                .header("User-Agent", DESKTOP_USER_AGENT)
                .build(),
        ) ?: throw TierFailed()
        return parseCaptionBody(json3Body, asr = isAsrTrack(track))
    }

    /**
     * Robust `ytInitialPlayerResponse` extraction: finds the assignment keyword, then
     * brace-matches the JSON object so curly braces inside string literals don't end
     * the slice early. Handles both `var ytInitialPlayerResponse = {...};` and
     * `window["ytInitialPlayerResponse"] = {...}` shapes.
     */
    private fun extractYtInitialPlayerResponse(page: String): JsonObject? {
        val keyIndex = page.indexOf("ytInitialPlayerResponse")
        if (keyIndex < 0) return null
        var braceStart = page.indexOf('{', keyIndex)
        if (braceStart < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        var end = -1
        for (i in braceStart until page.length) {
            val c = page[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                inString && c == '"' -> inString = false
                !inString && c == '"' -> inString = true
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        if (end < 0) return null
        return try {
            json.parseToJsonElement(page.substring(braceStart, end + 1)).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun captionTracks(playerResponse: JsonObject): List<JsonObject> = try {
        playerResponse["captions"]!!
            .jsonObject["playerCaptionsTracklistRenderer"]!!
            .jsonObject["captionTracks"]!!
            .jsonArray
            .map { it.jsonObject }
    } catch (_: Exception) {
        emptyList()
    }

    /** §4.3 step 3: manual EN → ASR EN → first track of any language. */
    private fun pickTrack(tracks: List<JsonObject>): JsonObject? {
        val language = { t: JsonObject -> t["languageCode"]?.jsonPrimitive?.content.orEmpty() }
        val kind = { t: JsonObject -> t["kind"]?.jsonPrimitive?.content.orEmpty() }
        return tracks.firstOrNull { language(it).startsWith("en") && kind(it) != ASR_KIND }
            ?: tracks.firstOrNull { language(it).startsWith("en") }
            ?: tracks.firstOrNull()
    }

    private fun isAsrTrack(track: JsonObject): Boolean =
        track["kind"]?.jsonPrimitive?.content == ASR_KIND

    // ─────────────────────────────────────────────────────────────────────────────
    // Tier 1b — innertube ANDROID player API
    // ─────────────────────────────────────────────────────────────────────────────

    /** Everything the pipeline may want from one innertube player response. */
    private class InnertubeData(
        val captionTracks: List<JsonObject>,
        val keywords: List<String>,
        val description: String,
        val title: String,
    )

    /**
     * POST `youtubei/v1/player` with the ANDROID client. Returns signed caption
     * baseUrls plus `videoDetails` (title / description / keywords) — used both for
     * caption recovery (tier 1b) and for tier-3 metadata enrichment.
     */
    private fun fetchViaInnertube(videoId: String): InnertubeData? {
        val payload = buildJsonObject {
            put("videoId", videoId)
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "ANDROID")
                    put("clientVersion", INNERTUBE_CLIENT_VERSION)
                    put("androidSdkVersion", 30)
                    put("hl", "en")
                }
            }
        }
        val request = Request.Builder()
            .url(INNERTUBE_PLAYER_URL)
            .header("User-Agent", INNERTUBE_USER_AGENT)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val body = httpGet(request) ?: return null
        val root = json.parseToJsonElement(body).jsonObject
        val videoDetails = root["videoDetails"]?.jsonObject
        return InnertubeData(
            captionTracks = root["captions"]
                ?.jsonObject?.get("playerCaptionsTracklistRenderer")
                ?.jsonObject?.get("captionTracks")
                ?.jsonArray?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                .orEmpty(),
            keywords = videoDetails?.get("keywords")?.jsonArray
                ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                .orEmpty(),
            description = videoDetails?.get("shortDescription")?.jsonPrimitive?.content.orEmpty(),
            title = videoDetails?.get("title")?.jsonPrimitive?.content.orEmpty(),
        )
    }

    /** Tier 1b caption path: pick a track, fetch its (signed) baseUrl, parse either format. */
    private fun captionsFromTracks(tracks: List<JsonObject>): String? {
        if (tracks.isEmpty()) return null
        val track = pickTrack(tracks) ?: return null
        val baseUrl = track[BASE_URL_KEY]?.jsonPrimitive?.content ?: return null
        val body = httpGet(
            Request.Builder()
                .url(baseUrl.trim() + "&fmt=json3")
                .header("User-Agent", INNERTUBE_USER_AGENT)
                .build(),
        ) ?: return null
        return parseCaptionBody(body, asr = isAsrTrack(track))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Tier 2 — NewPipeExtractor subtitles
    // ─────────────────────────────────────────────────────────────────────────────

    private fun fetchViaNewPipeSubtitles(videoId: String): String? {
        val info: StreamInfo = metaFetcher.fetchFull(videoId).streamInfo
        val subtitles: List<SubtitlesStream> = info.subtitles
        if (subtitles.isEmpty()) return null

        val prefer = { s: SubtitlesStream -> s.languageTag.startsWith("en") }
        val track = subtitles.firstOrNull { prefer(it) && !it.isAutoGenerated }
            ?: subtitles.firstOrNull(prefer)
            ?: subtitles.firstOrNull()

        val url = track?.content ?: return null
        val body = httpGet(
            Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .build(),
        ) ?: return null
        // Extractor serves the same timedtext endpoint → json3 parse; XML srv3 handled too.
        return parseCaptionBody(body, asr = track.isAutoGenerated)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Transcript formats
    // ─────────────────────────────────────────────────────────────────────────────

    /** Dispatches on body shape — json3 starts with `{`, srv3 XML with `<` (NOTES §23). */
    private fun parseCaptionBody(body: String, asr: Boolean): String =
        if (body.trimStart().startsWith("{")) {
            parseJson3(body, asr = asr)
        } else {
            parseXmlTranscript(body)
        }

    /** Flattens json3 `events[].segs[].utf8` into plain text; [asr] enables word dedupe. */
    private fun parseJson3(body: String, asr: Boolean): String {
        val root = json.parseToJsonElement(body).jsonObject
        val events = root["events"]?.jsonArray ?: return ""
        val lines = ArrayList<String>(events.size)
        for (event in events) {
            val obj = event.jsonObject
            // Skip the styling hint events (no segs / no text).
            val segs = obj["segs"]?.jsonArray ?: continue
            val line = buildString {
                for (seg in segs) {
                    val utf8 = seg.jsonObject["utf8"]?.jsonPrimitive?.content ?: continue
                    append(utf8)
                }
            }.replace("\n", " ").trim()
            if (line.isNotEmpty() && line != "​") {
                lines += line
            }
        }
        return if (asr) dedupeAsrRepeats(lines).joinToString("\n") else lines.joinToString("\n")
    }

    /** srv3 XML fallback (`<text start=... >token</text>`), used by the NewPipe tier. */
    private fun parseXmlTranscript(body: String): String {
        // srv3 word lists arrive as <p t=..><s>word</s>...</p> — <s> tags join with spaces.
        val paragraphRegex = Regex("<p\\b[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
        val textRegex = Regex("<text[^>]*>(.*?)</text>", RegexOption.DOT_MATCHES_ALL)
        val tags = Regex("<[^>]+>")
        // HTML entity unescape (concatenated so no entity literals appear in source).
        val amp = "&" + "amp;"
        val lt = "&" + "lt;"
        val gt = "&" + "gt;"
        val quot = "&" + "quot;"
        val clean: (String) -> String? = { raw ->
            raw
                .replace(lt, "<")
                .replace(gt, ">")
                .replace(quot, "\"")
                .replace(amp, "&")
                .replace("\n", " ")
                .trim()
                .takeIf { it.isNotEmpty() }
        }
        val lines = paragraphRegex.findAll(body).mapNotNull { m ->
            clean(m.groupValues[1].replace("</s>\\s*<s>".toRegex(), " ").replace(tags, " "))
                ?.replace(WHITESPACE, " ")
        }.toList()
        if (lines.isNotEmpty()) {
            return dedupeAsrRepeats(lines).joinToString("\n")
        }
        val textLines = textRegex.findAll(body).mapNotNull { m ->
            clean(m.groupValues[1].replace(tags, " "))?.replace(WHITESPACE, " ")
        }.toList()
        // ASR xml tracks roll the same way — dedupe unconditionally (cheap, safe on manual too).
        return dedupeAsrRepeats(textLines).joinToString("\n")
    }

    /**
     * ASR rolling-window dedupe (§4.3 step 4 / NOTES §4): strip cue N's leading words that
     * repeat cue N−1's trailing words (longest overlap first, ≥1 word).
     */
    private fun dedupeAsrRepeats(lines: List<String>): List<String> {
        if (lines.isEmpty()) return lines
        val out = ArrayList<String>(lines.size)
        var previous: String? = null
        for (line in lines) {
            val prev = previous
            val cleaned = if (prev.isNullOrBlank()) {
                line
            } else {
                val prevWords = prev.split(WHITESPACE).filter { it.isNotBlank() }
                val curWords = line.split(WHITESPACE).filter { it.isNotBlank() }
                var maxOverlap = 0
                val maxCheck = minOf(prevWords.size, curWords.size)
                for (n in maxCheck downTo 1) {
                    val prevTail = prevWords.takeLast(n)
                    val curHead = curWords.take(n)
                    if (normalizeWords(prevTail) == normalizeWords(curHead)) {
                        maxOverlap = n
                        break
                    }
                }
                if (maxOverlap == 0 || maxOverlap == curWords.size) {
                    line
                } else {
                    curWords.drop(maxOverlap).joinToString(" ")
                }
            }
            if (cleaned.isNotBlank()) {
                out += cleaned
                previous = line // compare against the raw previous line, mirroring the rolling window
            }
        }
        return out
    }

    private fun normalizeWords(words: List<String>): List<String> =
        words.map { it.lowercase().trim('.', ',', '!', '?', '​') }

    // ─────────────────────────────────────────────────────────────────────────────
    // Tier 3 — title + description + tags enrichment
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Builds the last-resort text from ALL available metadata: title, full description,
     * and video tags/keywords. Shorts often have an empty description, in which case
     * tags carry most of the remaining signal (NOTES §23). Prefers already-fetched
     * innertube data (no extra HTTP round-trip); falls back to NewPipeExtractor.
     */
    private fun buildTitleDescriptionText(videoId: String, innertube: InnertubeData?): String {
        var title = innertube?.title.orEmpty()
        var description = innertube?.description.orEmpty()
        var tags = innertube?.keywords.orEmpty()
        if (title.isBlank() && description.isBlank()) {
            val full = metaFetcher.fetchFull(videoId)
            title = full.meta.title
            description = full.streamInfo.description?.content.orEmpty()
            tags = full.streamInfo.tags.orEmpty()
        }
        return buildString {
            append(title.trim())
            if (description.isNotBlank()) {
                append("\n\n")
                append(description.trim())
            }
            if (tags.isNotEmpty()) {
                append("\n\nTags: ")
                append(tags.joinToString(", "))
            }
        }.trim()
    }

    // ─────────────────────────────────────────────────────────────────────────────

    private fun httpGet(request: Request): String? = try {
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        const val BASE_URL_KEY = "baseUrl"
        const val ASR_KIND = "asr"
        // NOTE: This is NOT a project secret. It is Google's own public InnerTube
        // API key that ships hardcoded inside every copy of YouTube's web player
        // (the ANDROID innertube client key). It is publicly documented, not tied
        // to any Google Cloud project we own, and cannot be rotated or revoked by
        // us. Secret scanners flag it as a false positive.
        const val INNERTUBE_PLAYER_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        const val INNERTUBE_PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?key=$INNERTUBE_PLAYER_KEY"
        const val INNERTUBE_CLIENT_VERSION = "20.10.38"
        const val INNERTUBE_USER_AGENT =
            "com.google.android.youtube/$INNERTUBE_CLIENT_VERSION (Linux; U; Android 11) gzip"

        val WHITESPACE = Regex("\\s+")
        val json = Json { ignoreUnknownKeys = true }
    }
}
