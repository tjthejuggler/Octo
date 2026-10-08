package com.example.octo.data.local

/**
 * Detailed muscle taxonomy layered UNDER the 16 [BodyPart] categories.
 *
 * The 16 body parts remain the app's tagging/dashboard/stats system (library
 * filters, region coloring, session crediting). Muscles are a finer detail
 * layer produced by the analyzer (transcript + video-frame vision analysis);
 * each muscle rolls up to exactly one parent [BodyPart] so any muscle result
 * can be folded into the existing body-part system losslessly.
 *
 * Same contract rules as [BodyPart]: [id] is a stable snake_case string shared
 * verbatim by the LLM muscle prompt, [VideoMuscleCrossRef.muscleId] in Room,
 * and the muscle listing UI. Do not rename ids.
 */
enum class Muscle(val id: String, val displayName: String, val parent: BodyPart) {
    // ── Neck ──
    STERNOCLEIDOMASTOID("sternocleidomastoid", "Sternocleidomastoid", BodyPart.NECK),
    UPPER_TRAPEZIUS("upper_trapezius", "Upper Trapezius", BodyPart.NECK),
    LEVATOR_SCAPULAE("levator_scapulae", "Levator Scapulae", BodyPart.NECK),
    DEEP_NECK_FLEXORS("deep_neck_flexors", "Deep Neck Flexors", BodyPart.NECK),
    SCALENES("scalenes", "Scalenes", BodyPart.NECK),

    // ── Shoulders ──
    ANTERIOR_DELTOID("anterior_deltoid", "Anterior Deltoid", BodyPart.SHOULDERS),
    LATERAL_DELTOID("lateral_deltoid", "Lateral Deltoid", BodyPart.SHOULDERS),
    POSTERIOR_DELTOID("posterior_deltoid", "Posterior Deltoid", BodyPart.SHOULDERS),
    ROTATOR_CUFF("rotator_cuff", "Rotator Cuff (SITS)", BodyPart.SHOULDERS),

    // ── Chest ──
    PECTORALIS_MAJOR("pectoralis_major", "Pectoralis Major", BodyPart.CHEST),
    PECTORALIS_MINOR("pectoralis_minor", "Pectoralis Minor", BodyPart.CHEST),

    // ── Upper back ──
    RHOMBOIDS("rhomboids", "Rhomboids", BodyPart.UPPER_BACK),
    MIDDLE_TRAPEZIUS("middle_trapezius", "Middle Trapezius", BodyPart.UPPER_BACK),
    LOWER_TRAPEZIUS("lower_trapezius", "Lower Trapezius", BodyPart.UPPER_BACK),

    // ── Lower back ──
    ERECTOR_SPINAE("erector_spinae", "Erector Spinae", BodyPart.LOWER_BACK),
    QUADRATUS_LUMBORUM("quadratus_lumborum", "Quadratus Lumborum", BodyPart.LOWER_BACK),

    // ── Core / abs ──
    RECTUS_ABDOMINIS("rectus_abdominis", "Rectus Abdominis", BodyPart.CORE),
    TRANSVERSE_ABDOMINIS("transverse_abdominis", "Transverse Abdominis", BodyPart.CORE),

    // ── Obliques ──
    INTERNAL_OBLIQUES("internal_obliques", "Internal Obliques", BodyPart.OBLIQUES),
    EXTERNAL_OBLIQUES("external_obliques", "External Obliques", BodyPart.OBLIQUES),

    // ── Lats ──
    LATISSIMUS_DORSI("latissimus_dorsi", "Latissimus Dorsi", BodyPart.LATS),
    TERES_MAJOR("teres_major", "Teres Major", BodyPart.LATS),

    // ── Hips & glutes ──
    GLUTEUS_MAXIMUS("gluteus_maximus", "Gluteus Maximus", BodyPart.HIPS_GLUTES),
    GLUTEUS_MEDIUS("gluteus_medius", "Gluteus Medius", BodyPart.HIPS_GLUTES),
    GLUTEUS_MINIMUS("gluteus_minimus", "Gluteus Minimus", BodyPart.HIPS_GLUTES),
    PIRIFORMIS("piriformis", "Piriformis", BodyPart.HIPS_GLUTES),
    HIP_FLEXORS("hip_flexors", "Hip Flexors (Iliopsoas)", BodyPart.HIPS_GLUTES),
    TFL("tensor_fasciae_latae", "Tensor Fasciae Latae", BodyPart.HIPS_GLUTES),

    // ── Groin / adductors ──
    ADDUCTOR_LONGUS("adductor_longus", "Adductor Longus", BodyPart.GROIN_ADDUCTORS),
    ADDUCTOR_MAGNUS("adductor_magnus", "Adductor Magnus", BodyPart.GROIN_ADDUCTORS),
    GRACILIS("gracilis", "Gracilis", BodyPart.GROIN_ADDUCTORS),
    PECTINEUS("pectineus", "Pectineus", BodyPart.GROIN_ADDUCTORS),

    // ── Quads ──
    RECTUS_FEMORIS("rectus_femoris", "Rectus Femoris", BodyPart.QUADS),
    VASTUS_LATERALIS("vastus_lateralis", "Vastus Lateralis", BodyPart.QUADS),
    VASTUS_MEDIALIS("vastus_medialis", "Vastus Medialis", BodyPart.QUADS),
    VASTUS_INTERMEDIUS("vastus_intermedius", "Vastus Intermedius", BodyPart.QUADS),

    // ── Hamstrings ──
    BICEPS_FEMORIS("biceps_femoris", "Biceps Femoris", BodyPart.HAMSTRINGS),
    SEMITENDINOSUS("semitendinosus", "Semitendinosus", BodyPart.HAMSTRINGS),
    SEMIMEMBRANOSUS("semimembranosus", "Semimembranosus", BodyPart.HAMSTRINGS),

    // ── Calves ──
    GASTROCNEMIUS("gastrocnemius", "Gastrocnemius", BodyPart.CALVES),
    SOLEUS("soleus", "Soleus", BodyPart.CALVES),

    // ── Ankles & feet ──
    TIBIALIS_ANTERIOR("tibialis_anterior", "Tibialis Anterior", BodyPart.ANKLES_FEET),
    TIBIALIS_POSTERIOR("tibialis_posterior", "Tibialis Posterior", BodyPart.ANKLES_FEET),
    PLANTAR_FASCIA("plantar_fascia", "Plantar Fascia", BodyPart.ANKLES_FEET),
    PERONEALS("peroneals", "Peroneals", BodyPart.ANKLES_FEET),

    // ── Wrists & forearms ──
    WRIST_FLEXORS("wrist_flexors", "Wrist Flexors", BodyPart.WRISTS_FOREARMS),
    WRIST_EXTENSORS("wrist_extensors", "Wrist Extensors", BodyPart.WRISTS_FOREARMS),
    BRACHIORADIALIS("brachioradialis", "Brachioradialis", BodyPart.WRISTS_FOREARMS),

    // ── Biceps & triceps ──
    BICEPS_BRACHII("biceps_brachii", "Biceps Brachii", BodyPart.BICEPS_TRICEPS),
    TRICEPS_BRACHII("triceps_brachii", "Triceps Brachii", BodyPart.BICEPS_TRICEPS),
    ;

    companion object {
        private val byId: Map<String, Muscle> = entries.associateBy { it.id }

        /** Returns null for unknown/LLM-hallucinated ids — callers must drop those. */
        fun fromId(id: String): Muscle? = byId[id]

        /** All 16 body parts must have at least one muscle beneath them. */
        fun musclesUnder(part: BodyPart): List<Muscle> = entries.filter { it.parent == part }
    }
}
