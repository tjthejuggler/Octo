package com.example.octo.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Canonical body-part id list shared with the LLM prompt (docs/ARCHITECTURE.md §5).
 * Kept as strings here so the llm package does not depend on the Room layer; the
 * pipeline maps them to [com.example.octo.data.local.BodyPart] via [BodyPart.fromId].
 */
object BodyPartIds {
    val CANONICAL_IDS: List<String> = listOf(
        "neck", "shoulders", "chest", "upper_back", "lower_back", "core", "obliques",
        "lats", "hips_glutes", "groin_adductors", "quads", "hamstrings", "calves",
        "ankles_feet", "wrists_forearms", "biceps_triceps",
    )
}

/**
 * Provider-agnostic OpenAI-compatible chat-completions client (docs/ARCHITECTURE.md §4.4).
 * Works with OpenAI, Groq, OpenRouter, LM Studio / ollama-compat — anything that accepts
 * `POST {baseUrl}/chat/completions` with `Authorization: Bearer <key>`.
 *
 * Body-part classification contract (§4d): `response_format {"type":"json_object"}`,
 * temperature 0.2, response validated with kotlinx.serialization; unknown/hallucinated
 * body-part ids are dropped ([parseAnalysis]).
 */
class LlmClient(private val okHttpClient: OkHttpClient) {

    /** Endpoint credentials from [com.example.octo.data.settings.OctoSettings]. */
    data class Endpoint(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
    )

    /** Validated model answer (§4d schema). */
    @Serializable
    data class BodyPartAnalysis(
        val bodyParts: List<String> = emptyList(),
        val confidence: Double = 0.0,
        val notes: String = "",
    )

    /** Thrown on HTTP/network/parse failure — the pipeline maps this to AnalysisState.FAILED. */
    class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Classifies [transcript] into body-part ids. Short input → one call; input over
     * [CHUNK_CHAR_LIMIT] → chunked calls, results unioned preserving first-seen order.
     *
     * @param isTitleDescriptionOnly true when [transcript] is title+description, not a
     *        real transcript — the system prompt is adjusted accordingly.
     */
    fun classifyBodyParts(
        endpoint: Endpoint,
        transcript: String,
        isTitleDescriptionOnly: Boolean,
    ): BodyPartAnalysis {
        require(endpoint.baseUrl.isNotBlank()) { "LLM base URL is blank" }
        require(endpoint.model.isNotBlank()) { "LLM model is blank" }

        val chunks = chunk(transcript, CHUNK_CHAR_LIMIT)
        if (chunks.size == 1) {
            return classifyChunk(endpoint, chunks[0], isTitleDescriptionOnly, chunkIndex = null, chunkCount = 1)
        }

        val merged = LinkedHashSet<String>()
        var confidenceSum = 0.0
        val notes = StringBuilder()
        chunks.forEachIndexed { index, chunkText ->
            val part = classifyChunk(endpoint, chunkText, isTitleDescriptionOnly, index, chunks.size)
            merged += part.bodyParts
            confidenceSum += part.confidence
            if (part.notes.isNotBlank()) {
                if (notes.isNotEmpty()) notes.append("; ")
                notes.append(part.notes)
            }
        }
        return BodyPartAnalysis(
            bodyParts = merged.toList(),
            confidence = confidenceSum / chunks.size,
            notes = notes.toString(),
        )
    }

    /** Tiny round-trip used by Settings "Test LLM" — returns the raw assistant text. */
    fun complete(endpoint: Endpoint, userMessage: String): String {
        val body = buildChatBody(
            endpoint = endpoint,
            system = "You are a helpful assistant. Reply with exactly: OK",
            user = userMessage,
        )
        val content = extractMessageContent(postChatCompletions(endpoint, body))
        return content?.takeIf { it.isNotBlank() } ?: throw LlmException("Empty completion response")
    }

    // ─────────────────────────────────────────────────────────────────────────────

    private fun classifyChunk(
        endpoint: Endpoint,
        chunkText: String,
        isTitleDescriptionOnly: Boolean,
        chunkIndex: Int?,
        chunkCount: Int,
    ): BodyPartAnalysis {
        val system = buildSystemPrompt(isTitleDescriptionOnly, chunkIndex, chunkCount)
        val user = buildString {
            if (isTitleDescriptionOnly) {
                append("Video title, description and tags:\n")
            } else {
                append("Video transcript:\n")
            }
            append(chunkText)
        }
        val responseContent = extractMessageContent(
            postChatCompletions(endpoint, buildChatBody(endpoint, system, user)),
        )
        return parseAnalysis(responseContent)
    }

    private fun buildSystemPrompt(
        isTitleDescriptionOnly: Boolean,
        chunkIndex: Int?,
        chunkCount: Int,
    ): String = buildString {
        append("You are a physiotherapy assistant. ")
        if (isTitleDescriptionOnly) {
            append("No transcript is available — only the video title, description and tags. ")
            append("Recall matters more than precision here: list EVERY body part the video ")
            append("plausibly stretches, moves or exercises. Infer the typical body parts ")
            append("for the drill type named in the title (e.g. 'pelvic tilt' involves hips ")
            append("and lower back; 'unlock shoulders' involves shoulders and chest). Include ")
            append("a part even if you are only moderately sure it is covered. ")
        } else {
            append("Given the transcript of a yoga/stretching/mobility video, list EVERY ")
            append("body part that is stretched, moved or exercised AT ALL — including ")
            append("partial or brief stretches. Include a part if any drill targets it even ")
            append("for a few seconds; do not omit parts that are mentioned or shown. ")
        }
        append("\n\nRules:\n")
        append("- Output ONLY body-part ids from this canonical list:\n")
        append("  ")
        append(BodyPartIds.CANONICAL_IDS.joinToString(", "))
        append("\n- Ignore pleasantries, breath cues, chit-chat — but never drop an ")
        append("actual body-part mention while doing so.\n")
        if (chunkIndex != null) {
            append("- This is part ${chunkIndex + 1} of $chunkCount of a longer transcript — ")
            append("classify this part on its own; other chunks are merged elsewhere.\n")
        }
        append("- Respond with JSON only, matching this schema:\n")
        append("""  {"body_parts": ["id", ...], "confidence": 0.0-1.0, "notes": "brief optional rationale"}""")
    }

    private fun buildChatBody(endpoint: Endpoint, system: String, user: String): String {
        val payload = buildJsonObject {
            put("model", endpoint.model)
            put("temperature", 0.2)
            putJsonObject("response_format") { put("type", "json_object") }
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", system)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", user)
                })
            })
        }
        return payload.toString()
    }

    private fun postChatCompletions(endpoint: Endpoint, body: String): String {
        val url = endpoint.baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        // LLM calls must not inherit the downloader's unbounded call timeout.
        val client = okHttpClient.newBuilder()
            .callTimeout(LLM_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw LlmException("LLM HTTP ${response.code}: ${responseBody.take(300)}")
                }
                return responseBody
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            throw LlmException("LLM request failed: ${e.message}", e)
        }
    }

    /** Pulls `choices[0].message.content` out of the raw completion JSON. */
    private fun extractMessageContent(rawResponseBody: String): String? = try {
        val root = json.parseToJsonElement(rawResponseBody).jsonObject
        val first = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        first["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }

    /** Decodes + validates the model's JSON answer; unknown ids are dropped here (§4d). */
    private fun parseAnalysis(content: String?): BodyPartAnalysis {
        if (content.isNullOrBlank()) throw LlmException("LLM returned no content")
        val cleaned = content
            .trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val raw = try {
            json.parseToJsonElement(cleaned).jsonObject
        } catch (e: Exception) {
            throw LlmException("LLM response is not valid JSON: ${content.take(200)}", e)
        }
        val ids = raw["body_parts"]?.jsonArray
            ?.mapNotNull { element ->
                runCatching { element.jsonPrimitive.content }.getOrNull()
            }
            .orEmpty()
            .filter { it in BodyPartIds.CANONICAL_IDS } // drop hallucinated ids
            .distinct()
        val confidence = raw["confidence"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 1.0) ?: 0.0
        val notes = raw["notes"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return BodyPartAnalysis(bodyParts = ids, confidence = confidence, notes = notes)
    }

    /** Splits into paragraph-aware chunks no longer than [limit] chars. */
    internal fun chunk(text: String, limit: Int = CHUNK_CHAR_LIMIT): List<String> {
        if (text.length <= limit) return listOf(text)
        val chunks = ArrayList<String>()
        var cursor = 0
        while (cursor < text.length) {
            var end = minOf(cursor + limit, text.length)
            if (end < text.length) {
                // Prefer breaking on a paragraph, then a line, then any whitespace.
                val lastParagraph = text.lastIndexOf("\n\n", end)
                val lastLine = text.lastIndexOf('\n', end)
                val lastSpace = text.lastIndexOf(' ', end)
                end = when {
                    lastParagraph > cursor + limit / 2 -> lastParagraph + 2
                    lastLine > cursor + limit / 2 -> lastLine + 1
                    lastSpace > cursor + limit / 2 -> lastSpace + 1
                    else -> end
                }
            }
            chunks += text.substring(cursor, end).trim()
            cursor = end
        }
        return chunks.filter { it.isNotEmpty() }.ifEmpty { listOf(text.take(limit)) }
    }

    companion object {
        const val CHUNK_CHAR_LIMIT = 12_000
        const val LLM_CALL_TIMEOUT_SECONDS = 60L
    }
}
