package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ComdirectStatementPdfTest {
    @Test
    fun parsesSignedFinanzreportRows() {
        val parsed = parseComdirectStatementText("""
            comdirect
            Finanzreport
            IBAN: DE12 3456 7890 1234 5678 90
            Buchungsdatum Buchungstext Umsatz in EUR
            04.02.2026 Empfänger: Beispiel GmbH -12,50
            05.02.2026 Gutschrift: Kunde +1.250,00
        """.trimIndent())

        assertEquals("DE12345678901234567890", parsed.accountIbans.single())
        assertEquals(listOf("2026-02-04", "2026-02-05"), parsed.transactions.map { it.date })
        assertEquals(listOf(-1250L, 125000L), parsed.transactions.map { it.amountCents })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsignedRowsInsteadOfGuessingDirection() {
        parseComdirectStatementText("""
            comdirect Finanzreport Umsatz in EUR
            04.02.2026 Buchung 12,50
        """.trimIndent())
    }
}
