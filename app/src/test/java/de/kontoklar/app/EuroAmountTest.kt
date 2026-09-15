package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EuroAmountTest {
    @Test fun parsesGermanThousandsAndDecimalSeparators() {
        assertEquals(123456L, parseEuroCents("1.234,56 €"))
    }

    @Test fun parsesDotDecimalInput() {
        assertEquals(123456L, parseEuroCents("1234.56"))
    }

    @Test fun rejectsInvalidOrZeroAmounts() {
        assertNull(parseEuroCents("abc"))
        assertNull(parseEuroCents("0,00"))
        assertNull(parseEuroCents("1,234"))
    }

    @Test fun comparesSemanticVersions() {
        assertEquals(true, isNewerVersion("v0.3.0", "0.2.0"))
        assertEquals(false, isNewerVersion("0.2.0", "0.2.0"))
        assertEquals(false, isNewerVersion("0.1.9", "0.2.0"))
    }
}
