package com.example.octo.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Muscle taxonomy invariants: unique stable ids, every body-part category has
 * at least one muscle beneath it (so the roll-up is total), and fromId drops
 * unknown/hallucinated ids exactly like [BodyPart.fromId] does.
 */
class MuscleTest {

    @Test
    fun `ids are unique`() {
        val ids = Muscle.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every body part has muscles under it`() {
        BodyPart.entries.forEach { part ->
            assert(Muscle.musclesUnder(part).isNotEmpty()) { "no muscles under ${part.id}" }
        }
    }

    @Test
    fun `fromId resolves known and drops unknown`() {
        assertEquals(Muscle.PIRIFORMIS, Muscle.fromId("piriformis"))
        assertNotNull(Muscle.fromId("gluteus_maximus"))
        assertNull(Muscle.fromId("gluteus maximus")) // no spaces — hallucination dropped
        assertNull(Muscle.fromId("hammies"))
        assertNull(Muscle.fromId(""))
    }

    @Test
    fun `roll-up maps each muscle to its declared parent`() {
        assertEquals(BodyPart.HIPS_GLUTES, Muscle.HIP_FLEXORS.parent)
        assertEquals(BodyPart.HAMSTRINGS, Muscle.SEMIMEMBRANOSUS.parent)
    }
}
