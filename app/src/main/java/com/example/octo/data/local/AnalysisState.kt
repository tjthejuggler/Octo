package com.example.octo.data.local

/**
 * Lifecycle of the transcript + LLM body-part extraction pipeline (Phase 5).
 *
 * NONE      — never analyzed (fresh row, or analysis not yet triggered)
 * RUNNING   — pipeline actively fetching transcript / calling the LLM
 * DONE      — analysis finished; [VideoBodyPartCrossRef] rows reflect the result
 *             (an empty result set is a valid DONE — e.g. a meditation video)
 * FAILED    — transcript unobtainable ("No transcript available") or the LLM call
 *             failed; manual "Analyze" or the next play trigger will retry
 *
 * Persisted by Room via the built-in enum↔name() String conversion (no TypeConverter).
 */
enum class AnalysisState {
    NONE,
    RUNNING,
    DONE,
    FAILED,
}

/**
 * Where [VideoEntity.transcriptText] came from (docs/ARCHITECTURE.md §4.3 fallback chain).
 * Drives the LLM prompt: TITLE_DESCRIPTION text is flagged so the model knows it is
 * classifying from title + description only, not a real transcript.
 */
enum class TranscriptSource {
    /** Primary: YouTube timedtext captions (json3) scraped from the watch page. */
    CAPTIONS,

    /** Fallback: NewPipeExtractor subtitles (same caption data via the library). */
    NEWPIPE_SUBTITLES,

    /** Last resort before failure: title + description only. */
    TITLE_DESCRIPTION,
}

/**
 * Phase 24 (feedback round 5): HOW the current [VideoBodyPartCrossRef] links for a
 * video were produced — surfaced in the UI as "AI-analyzed" / "Quick scan" /
 * "Manually edited". Persisted on [VideoEntity.partSource] (migration v3→v4).
 *
 * NULL      — never analyzed (or pre-v4 rows whose links came from the old pipeline)
 * HEURISTIC — [com.example.octo.llm.LlmHeuristics] keyword match (no API key set)
 * LLM       — z.ai/OpenAI-compatible LLM classification (API key configured)
 * MANUAL    — the user picked the parts via the "Edit parts" dialog. A later
 *             RE-ANALYSIS (Library card menu, force=true) overwrites this — the
 *             Library shows a confirm dialog in that case.
 *
 * Undo semantics (Phase 22 preserved): confirmed sessions carry a per-session
 * `creditedBodyPartsJson` snapshot that stays authoritative for undo. Editing a
 * video's parts NEVER retroactively rewrites past credits — it only changes what
 * FUTURE confirmed sessions will credit.
 */
enum class PartSource {
    HEURISTIC,
    LLM,
    MANUAL,
}
