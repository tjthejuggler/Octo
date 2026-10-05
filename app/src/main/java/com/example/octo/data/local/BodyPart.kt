package com.example.octo.data.local

/**
 * Canonical body-part taxonomy — docs/ARCHITECTURE.md §5.
 *
 * [id] is the stable snake_case string shared verbatim by:
 *  - the LLM muscle-extraction prompt (§4.4)
 *  - [VideoBodyPartCrossRef.bodyPartId] / [BodyPartStretchEntity.bodyPartId] in Room
 *  - the body-map overlay regions (Phase 6)
 *
 * Do not rename ids — they cross the LLM boundary and are persisted in the DB.
 */
enum class BodyPart(val id: String, val displayName: String) {
    NECK("neck", "Neck"),
    SHOULDERS("shoulders", "Shoulders"),
    CHEST("chest", "Chest"),
    UPPER_BACK("upper_back", "Upper Back"),
    LOWER_BACK("lower_back", "Lower Back"),
    CORE("core", "Core / Abs"),
    OBLIQUES("obliques", "Obliques"),
    LATS("lats", "Lats"),
    HIPS_GLUTES("hips_glutes", "Hips & Glutes"),
    GROIN_ADDUCTORS("groin_adductors", "Groin / Adductors"),
    QUADS("quads", "Quads"),
    HAMSTRINGS("hamstrings", "Hamstrings"),
    CALVES("calves", "Calves"),
    ANKLES_FEET("ankles_feet", "Ankles & Feet"),
    WRISTS_FOREARMS("wrists_forearms", "Wrists & Forearms"),
    BICEPS_TRICEPS("biceps_triceps", "Biceps & Triceps");

    companion object {
        private val byId: Map<String, BodyPart> = entries.associateBy { it.id }

        /** Returns null for unknown/LLM-hallucinated ids — callers must drop those (§4.4). */
        fun fromId(id: String): BodyPart? = byId[id]
    }
}
