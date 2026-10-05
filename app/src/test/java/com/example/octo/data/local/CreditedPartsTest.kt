package com.example.octo.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

/** Phase 22 — pure-JVM tests for the credited-snapshot codec + undo-clamp math. */
class CreditedPartsTest {

    @Test
    fun encodeDecode_roundTrip() {
        val parts = mapOf("neck" to 5, "core" to 5, "hamstrings" to 12)
        assertEquals(parts, CreditedParts.decode(CreditedParts.encode(parts)))
    }

    @Test
    fun encode_dropsZeroMinutes() {
        assertEquals("neck:3", CreditedParts.encode(mapOf("neck" to 3, "core" to 0)))
    }

    @Test
    fun decode_nullOrBlankOrMalformed_isEmptyOrSafe() {
        assertEquals(emptyMap<String, Int>(), CreditedParts.decode(null))
        assertEquals(emptyMap<String, Int>(), CreditedParts.decode(""))
        assertEquals(mapOf("neck" to 5), CreditedParts.decode("neck:5,bad,core:,x:NaN"))
    }

    @Test
    fun undoTotals_subtractsAndClampsAtZero() {
        // Normal case: two 5-min sessions confirmed → undo one.
        assertEquals(5 to 1, CreditedParts.undoTotals(storedMinutes = 10, storedSessions = 2, creditedMinutes = 5))
        // Clamp: aggregate already reduced (manual reset) — never negative.
        assertEquals(0 to 0, CreditedParts.undoTotals(storedMinutes = 3, storedSessions = 1, creditedMinutes = 5))
        // Session count clamps independently.
        assertEquals(2 to 0, CreditedParts.undoTotals(storedMinutes = 7, storedSessions = 1, creditedMinutes = 5))
    }
}
