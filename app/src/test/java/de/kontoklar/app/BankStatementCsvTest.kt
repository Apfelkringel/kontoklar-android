package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class BankStatementCsvTest {
    @Test fun importsC24CsvWithQuotedSeparatorsAndGermanAmounts() {
        val csv = """Transaktionstyp;Buchungsdatum;Betrag;Zahlungsempfänger;IBAN;Verwendungszweck;Beschreibung
            SEPA-Überweisung;16.09.2026;-1.234,56;Lieferant GmbH;DE89370400440532013000;Rechnung 42;"Material; dringend"
            Gutschrift;2026-09-17;250,00;Kundin;DE02120300000000202051;RE-8;Überweisung
        """.trimIndent()

        val parsed = parseBankStatementCsv(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))

        assertEquals(2, parsed.transactions.size)
        assertEquals(listOf("DE89370400440532013000", "DE02120300000000202051"), parsed.accountIbans)
        assertEquals(-123456L, parsed.transactions[0].amountCents)
        assertEquals("2026-09-16", parsed.transactions[0].date)
        assertEquals("Lieferant GmbH", parsed.transactions[0].counterparty)
        assertTrue(parsed.transactions[0].description.contains("Material; dringend"))
        assertEquals(25_000L, parsed.transactions[1].amountCents)
    }

    @Test fun importsComdirectCsvWithBomAndSemicolonDecimal() {
        val csv = "\uFEFF\"Buchungstag\";\"Wertstellung (Valuta)\";\"Vorgang\";\"Buchungstext\";\"Umsatz in EUR\"\r\n" +
            "\"15.09.2026\";\"15.09.2026\";\"SEPA-Lastschrift\";\"Empfänger: Stadtwerke\";\"-87,42\"\r\n"

        val parsed = parseBankStatement(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))

        assertEquals(1, parsed.transactions.size)
        assertEquals(-8_742L, parsed.transactions.single().amountCents)
        assertEquals("2026-09-15", parsed.transactions.single().date)
        assertTrue(parsed.transactions.single().description.contains("SEPA-Lastschrift"))
        assertTrue(parsed.transactions.single().description.contains("Empfänger: Stadtwerke"))
    }

    @Test fun rejectsUnknownExportsAndRowsWithInvalidAmounts() {
        val unknown = "name;sum\nA;12,00"
        assertTrue(runCatching { parseBankStatementCsv(ByteArrayInputStream(unknown.toByteArray())) }.isFailure)
        val invalid = "Buchungstag;Vorgang;Umsatz in EUR\n16.09.2026;Überweisung;falsch"
        assertTrue(runCatching { parseBankStatementCsv(ByteArrayInputStream(invalid.toByteArray())) }.isFailure)
    }
}
