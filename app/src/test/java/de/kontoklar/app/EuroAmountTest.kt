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

    @Test fun acceptsOnlyValidSha256Digests() {
        assertEquals(true, isValidSha256("a".repeat(64)))
        assertEquals(true, isValidSha256("ABCDEF0123456789".repeat(4)))
        assertEquals(false, isValidSha256("sha256:" + "a".repeat(64)))
        assertEquals(false, isValidSha256("a".repeat(63)))
        assertEquals(false, isValidSha256("g".repeat(64)))
    }

    @Test fun csvExportUsesGermanDecimalsEscapesCellsAndNeutralizesFormulas() {
        assertEquals("\"a;b\"", csvField("a;b"))
        assertEquals("\"a \"\"quote\"\"\"", csvField("a \"quote\""))
        assertEquals("\"'=1+1\"", csvField("=1+1"))
        assertEquals("1234,56", centsAsGermanDecimal(123456))
    }

    @Test fun invoiceNumbersAreSequentialPerYear() {
        assertEquals("RE-2026-0003", nextInvoiceNumber(2026, listOf("RE-2026-0001", "RE-2025-0100", "RE-2026-0002")))
        assertEquals("RE-2027-0001", nextInvoiceNumber(2027, listOf("RE-2026-0015")))
        assertEquals("INV-2026-0002", nextInvoiceNumber(2026, listOf("INV-2026-0001", "RE-2026-0009"), "INV"))
    }
}
