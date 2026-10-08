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
 * Canonical muscle id list for the fine-grained detail layer. Mirrors
 * [com.example.octo.data.local.Muscle] (same no-Room-dependency rule as
 * [BodyPartIds]); the pipeline maps ids via [com.example.octo.data.local.Muscle.fromId].
 */
object MuscleIds {
    val CANONICAL_IDS: List<String> = listOf(
        // neck
        "sternocleidomastoid", "upper_trapezius", "levator_scapulae", "deep_neck_flexors", "scalenes",
        // shoulders
        "anterior_deltoid", "lateral_deltoid", "posterior_deltoid", "rotator_cuff",
        // chest
        "pectoralis_major", "pectoralis_minor",
        // upper back
        "rhomboids", "middle_trapezius", "lower_trapezius",
        // lower back
        "erector_spinae", "quadratus_lumborum",
        // core
        "rectus_abdominis", "transverse_abdominis",
        // obliques
        "internal_obliques", "external_obliques",
        // lats
        "latissimus_dorsi", "teres_major",
        // hips & glutes
        "gluteus_maximus", "gluteus_medius", "gluteus_minimus", "piriformis", "hip_flexors", "tensor_fasciae_latae",
        // groin / adductors
        "adductor_longus", "adductor_magnus", "gracilis", "pectineus",
        // quads
        "rectus_femoris", "vastus_lateralis", "vastus_medialis", "vastus_intermedius",
        // hamstrings
        "biceps_femoris", "semitendinosus", "semimembranosus",
        // calves
        "gastrocnemius", "soleus",
        // ankles & feet
        "tibialis_anterior", "tibialis_posterior", "plantar_fascia", "peroneals",
        // wrists & forearms
        "wrist_flexors", "wrist_extensors", "brachioradialis",
        // biceps & triceps
        "biceps_brachii", "triceps_brachii",
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
 *
 * Muscle classification (vision layer): same contract, but the user message carries
 * base64 JPEG frames as OpenAI-style `image_url` content parts — requires a
 * vision-capable model on the endpoint.
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

    /** Validated muscle-detail answer — same validation rules as [BodyPartAnalysis]. */
    @Serializable
    data class MuscleAnalysis(
        val muscles: List<String> = emptyList(),
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

    /**
     * Muscle-detail classification. Primary signal is [framesBase64] (ordered video
     * stills, base64 JPEG, no data-uri prefix) sent as vision content parts; the
     * [transcript] (nullable) rides along as extra context. With no frames the call
     * degrades to text-only muscle extraction from the transcript.
     *
     * One call, one frame batch — callers keep frame counts modest (~10) to stay
     * within typical multimodal request limits.
     */
    fun classifyMuscles(
        endpoint: Endpoint,
        transcript: String?,
        framesBase64: List<String>,
    ): MuscleAnalysis {
        require(endpoint.baseUrl.isNotBlank()) { "LLM base URL is blank" }
        require(endpoint.model.isNotBlank()) { "LLM model is blank" }

        val system = buildString {
            append("You are a physiotherapy assistant analyzing a stretching / yoga / ")
            append("mobility video to identify the EXACT muscles that are stretched.\n\n")
            append("Method: first silently describe each frame's pose (joint positions, ")
            append("spine angle, which limb is lengthening), then infer which muscles are ")
            append("under stretch or load in those positions. Do not output the pose ")
            append("descriptions — only the final muscle list.\n\n")
            append("Rules:\n")
            append("- Output ONLY muscle ids from this canonical list:\n  ")
            append(MuscleIds.CANONICAL_IDS.joinToString(", "))
            append("\n- Include every muscle meaningfully stretched in ANY frame or ")
            append("mentioned in the transcript — brief holds still count.\n")
            append("- The video may show several different drills; union across all of them.\n")
            append("- Respond with JSON only, matching this schema:\n")
            append("""  {"muscles": ["id", ...], "confidence": 0.0-1.0, "notes": "brief optional rationale"}""")
        }

        val userContent = buildJsonArray {
            if (!transcript.isNullOrBlank()) {
                add(buildJsonObject {
                    put("type", "text")
                    put(
                        "text",
                        "Video transcript (additional context — the frames are the primary signal):\n" +
                            transcript.take(CHUNK_CHAR_LIMIT),
                    )
                })
            }
            framesBase64.forEachIndexed { index, b64 ->
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "Frame ${index + 1}/${framesBase64.size}:")
                })
                add(buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") {
                        put("url", "data:image/jpeg;base64,$b64")
                    }
                })
            }
            if (transcript.isNullOrBlank() && framesBase64.isEmpty()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "No frames and no transcript available.")
                })
            }
        }

        val body = buildChatBody(endpoint, system, userContent)
        val responseContent = extractMessageContent(postChatCompletions(endpoint, body))
        return parseMuscleAnalysis(responseContent)
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
        val userContent = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", user)
            })
        }
        return buildChatBody(endpoint, system, userContent)
    }

    /** Body with a pre-built (possibly multimodal) user content array. */
    private fun buildChatBody(endpoint: Endpoint, system: String, userContent: kotlinx.serialization.json.JsonArray): String {
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
                    put("content", userContent)
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
            .callTimeout(VISION_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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
        val raw = parseJsonContent(content)
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

    /** Same validation pipeline for the muscle answer; unknown ids dropped. */
    private fun parseMuscleAnalysis(content: String?): MuscleAnalysis {
        if (content.isNullOrBlank()) throw LlmException("LLM returned no content")
        val raw = parseJsonContent(content)
        val ids = raw["muscles"]?.jsonArray
            ?.mapNotNull { element ->
                runCatching { element.jsonPrimitive.content }.getOrNull()
            }
            .orEmpty()
            .filter { it in MuscleIds.CANONICAL_IDS } // drop hallucinated ids
            .distinct()
        val confidence = raw["confidence"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 1.0) ?: 0.0
        val notes = raw["notes"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return MuscleAnalysis(muscles = ids, confidence = confidence, notes = notes)
    }

    private fun parseJsonContent(content: String): kotlinx.serialization.json.JsonObject {
        val cleaned = content
            .trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        return try {
            json.parseToJsonElement(cleaned).jsonObject
        } catch (e: Exception) {
            throw LlmException("LLM response is not valid JSON: ${content.take(200)}", e)
        }
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

        /** Multimodal calls with ~10 frames run longer than plain text calls. */
        const val VISION_CALL_TIMEOUT_SECONDS = 180L
    }
}
