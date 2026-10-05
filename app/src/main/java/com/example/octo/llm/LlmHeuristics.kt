package com.example.octo.llm

/**
 * Keyword-based body-part classification used when no LLM API key is configured
 * (docs/ARCHITECTURE.md §4.4 fallback). Runs over title + transcript (+ tags) text.
 *
 * Recall-oriented (NOTES §23): the matching threshold is adaptive — real transcripts
 * (≥ [LONG_TEXT_CHARS]) require [MIN_HITS_PER_PART] distinct cue hits per part, while
 * thin title/description-only text for a short video needs just one hit, because a
 * 30-second short legitimately mentions each body part only once.
 *
 * Results are always marked heuristic in the notes so the UI can show
 * "(heuristic)" next to chips derived this way.
 */
object LlmHeuristics {

    /** Cue phrases per canonical id. Matched as substrings, case-insensitive. */
    private val cuePhrases: Map<String, List<String>> = mapOf(
        "neck" to listOf(
            "neck", "cervical", "chin tuck", "head tilt", "trap stretch",
        ),
        "shoulders" to listOf(
            "shoulder", "deltoid", "rotator cuff", "shoulder blade", "traps",
            "slouch",
        ),
        "chest" to listOf(
            "chest", "pec ", "pecs", "pectoral", "heart opener", "cobra",
            "open up your chest",
        ),
        "upper_back" to listOf(
            "upper back", "thoracic", "rhomboid", "mid back", "mid-back",
            "shoulder blade", "cat cow",
        ),
        "lower_back" to listOf(
            "lower back", "lumbar", "spine", "erector", "cat cow",
            "child's pose", "child pose", "pelvic tilt", "anterior tilt",
        ),
        "core" to listOf(
            "core", "abs", "abdominal", "plank", "navel", "belly", "hollow",
            "lower belly", "stomach",
        ),
        "obliques" to listOf(
            "oblique", "side plank", "twist", "spinal twist",
            "rotate the torso", "side bend",
        ),
        "lats" to listOf(
            "lat ", "lats", "latissimus", "wide grip", "side body",
        ),
        "hips_glutes" to listOf(
            "hip", "hips", "glute", "butt", "piriformis", "pigeon", "lunge",
            "hip flexor", "figure four", "squat", "pelvic tilt", "anterior tilt",
            "pelvis",
        ),
        "groin_adductors" to listOf(
            "adductor", "groin", "inner thigh", "butterfly", "frog",
            "straddle", "wide-legged",
        ),
        "quads" to listOf(
            "quad", "quads", "quadriceps", "thigh", "front of the thigh",
            "warrior",
        ),
        "hamstrings" to listOf(
            "hamstring", "hamstrings", "back of the thigh", "back of the leg",
            "forward fold", "fold forward", "downward dog",
        ),
        "calves" to listOf(
            "calf", "calves", "gastroc", "soleus", "downward dog",
        ),
        "ankles_feet" to listOf(
            "ankle", "ankles", "foot", "feet", "toe", "toes", "plantar",
        ),
        "wrists_forearms" to listOf(
            "wrist", "wrists", "forearm", "forearms", "palm", "palms",
        ),
        "biceps_triceps" to listOf(
            "bicep", "triceps", "arm", "arms",
        ),
    )

    /** Multi-word drill phrases that imply several body parts at once. */
    private val phraseImplications: List<Pair<String, List<String>>> = listOf(
        "full body" to listOf(
            "neck", "shoulders", "chest", "core", "hips_glutes",
            "hamstrings", "quads",
        ),
        "full-body" to listOf(
            "neck", "shoulders", "chest", "core", "hips_glutes",
            "hamstrings", "quads",
        ),
        "full stretch" to listOf(
            "neck", "shoulders", "chest", "core", "hips_glutes",
            "hamstrings", "quads",
        ),
        "morning stretch" to listOf(
            "neck", "shoulders", "chest", "core", "hips_glutes", "hamstrings",
        ),
        "sun salutation" to listOf(
            "chest", "core", "hamstrings", "shoulders", "calves",
        ),
        "pelvic tilt" to listOf("hips_glutes", "lower_back", "core"),
    )

    const val MIN_HITS_PER_PART = 2

    /** Text at or below this length is treated as thin (title/description-only). */
    const val LONG_TEXT_CHARS = 1_000

    /** Union result shape matching [LlmClient.BodyPartAnalysis]. */
    fun classify(text: String): LlmClient.BodyPartAnalysis {
        val lower = text.lowercase()
        val thinText = text.length < LONG_TEXT_CHARS
        val minHits = if (thinText) 1 else MIN_HITS_PER_PART
        val matched = LinkedHashSet<String>()

        for ((id, phrases) in cuePhrases) {
            var hits = 0
            for (phrase in phrases) {
                hits += countOccurrences(lower, phrase)
                if (hits >= minHits) break
            }
            if (hits >= minHits) matched += id
        }

        for ((phrase, implied) in phraseImplications) {
            if (lower.contains(phrase)) matched += implied
        }

        return LlmClient.BodyPartAnalysis(
            bodyParts = matched.toList(),
            confidence = when {
                matched.isEmpty() -> 0.0
                thinText -> 0.3 // title/description-only — weaker evidence
                else -> 0.4
            },
            notes = if (thinText) {
                "heuristic keyword match on thin text (title/description only; no LLM key configured)"
            } else {
                "heuristic keyword match (no LLM key configured)"
            },
        )
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isBlank()) return 0
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }
}
