package com.example.octo.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the recall-expanded heuristic classifier (NOTES §23).
 * The ASR transcript sample is the verbatim caption text of the short
 * https://youtube.com/shorts/BOoYU6keLL4 used as the Phase-19 test case.
 */
class LlmHeuristicsTest {

    /** Verbatim (deduped) ASR transcript of short BOoYU6keLL4. */
    private val shortAsrTranscript = """
        Do this 20 times every day and it will get rid of your protruding lower belly forever.
        Do this for 60 seconds every day and it will relax your tight hamstrings. Do
        this 20 times every day and it will correct pelvic tilt and relieve tension
        in your lower back.
        Do this 20 times every day and it will open up your chest area. Do this 40
        times every day and it will open up your shoulders and get rid of slouching.
    """.trimIndent()

    @Test
    fun `short asr transcript yields all six ground-truth parts`() {
        val result = LlmHeuristics.classify(shortAsrTranscript)
        val expected = setOf(
            "shoulders", "chest", "core", "hamstrings", "hips_glutes", "lower_back",
        )
        assertEquals(expected, result.bodyParts.toSet())
    }

    @Test
    fun `thin title-only text still captures hip and shoulder signal`() {
        // The exact 81-char tier-3 text the pipeline stored before the fix.
        val title = "5 Exercises To Unlock Shoulders & Fix Pelvic Tilt FAST!  #mobility #hip #shoulder"
        val result = LlmHeuristics.classify(title)
        assertTrue(result.bodyParts.contains("shoulders"))
        assertTrue(result.bodyParts.contains("hips_glutes"))
        assertTrue(result.bodyParts.contains("lower_back")) // via pelvic-tilt implication
    }

    @Test
    fun `meditation text stays precise - no stretch over-tagging`() {
        // Meditation-style transcript excerpt: breath cues, posture words, no drills.
        val meditation = """
            Welcome to this ten minute meditation. Find a comfortable seated position.
            Gently close your eyes. Take a deep breath in through your nose,
            and slowly exhale through your mouth. Notice the sensations in your body.
            If your mind wanders, gently bring your attention back to your breath.
            Continue breathing deeply, letting each exhale release tension.
            When you are ready, gently open your eyes.
        """.trimIndent()
        val result = LlmHeuristics.classify(meditation)
        // Breath/relaxation vocabulary must not tag stretch body parts.
        assertEquals(emptySet<String>(), result.bodyParts.toSet())
    }

    @Test
    fun `single mention in long transcript does not tag - threshold guard`() {
        // Long filler text (≥ LONG_TEXT_CHARS) with one incidental "shoulder" mention.
        val filler = ("This is a long relaxing instrumental audio track with no talking. " ).repeat(20)
        val long = filler + " Relax your shoulder as you listen. " + filler
        val result = LlmHeuristics.classify(long)
        assertTrue(long.length >= LlmHeuristics.LONG_TEXT_CHARS)
        assertTrue(!result.bodyParts.contains("shoulders"))
    }

    @Test
    fun `synonyms map to canonical ids`() {
        val text = "loosen your hamstrings, glutes and inner thigh, stretch the calves " +
            "and ankles, open the pecs and lats, engage the abs and obliques"
        // Thin text → single-hit threshold applies.
        val result = LlmHeuristics.classify(text)
        val expected = setOf(
            "hamstrings", "hips_glutes", "groin_adductors", "calves",
            "ankles_feet", "chest", "lats", "core", "obliques", "quads", // "inner thigh" also cues quads
        )
        assertEquals(expected, result.bodyParts.toSet())
    }

    @Test
    fun `full body phrase implies several common parts`() {
        val result = LlmHeuristics.classify("10 minute full body morning stretch routine")
        assertTrue(result.bodyParts.containsAll(listOf("neck", "shoulders", "chest", "core", "hamstrings")))
    }

    @Test
    fun `thin text is flagged with lower confidence`() {
        val thin = LlmHeuristics.classify("quick hip mobility drill")
        val long = LlmHeuristics.classify(
            "hip mobility drill. " .repeat(200) + " hip openers and lunges throughout.",
        )
        assertTrue(thin.confidence < long.confidence)
        assertTrue(thin.notes.contains("thin text"))
    }
}
