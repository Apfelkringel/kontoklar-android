package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeRepublicStatementPdfTest {
    @Test fun importsC24StatementRowsAndContinuationLines() {
        val text = """
            C24 Bank GmbH
            C24 Smartkonto
            IBAN: DE89370400440532013000
            Kontoauszug 05/2026 Kontostand 1.973,24 €
            Transaktionsübersicht
            Buchung Valuta Transaktionsinformation Betrag
            29.05. 29.05. Online-Kartenzahlung -91,27 €
            Supermarkt Beispiel
            28.05. 28.05. Überweisung +7.522,77 €
            Kundin GmbH
            IBAN: DE02120300000000202051 / BIC: TESTDEFFXXX
            Zusammenfassung
            Startsaldo 1.000,00 €
            Endsaldo 8.431,50 €
            C24 Bank GmbH Seite 1 von 1
        """.trimIndent()

        val parsed = parseC24StatementText(text)

        assertEquals(2, parsed.transactions.size)
        assertEquals(-9_127L, parsed.transactions[0].amountCents)
        assertEquals("2026-05-29", parsed.transactions[0].date)
        assertTrue(parsed.transactions[0].description.contains("Supermarkt Beispiel"))
        assertEquals(752_277L, parsed.transactions[1].amountCents)
        assertEquals("Kundin GmbH", parsed.transactions[1].counterparty)
    }

    @Test fun importsCurrentStatementRowsWithIncomingOutgoingAndBalances() {
        val text = """
            TRADE REPUBLIC BANK GMBH
            ACCOUNT STATEMENT
            ACCOUNT TRANSACTIONS
            DATE TYPE DESCRIPTION MONEY IN MONEY OUT BALANCE
            01 Apr 2026 Transfer Incoming transfer from MAIN BANK €800.00 €800.00
            02 Apr 2026 Card Transaction Supermarket €42.90 €757.10
            03 Apr 2026 Interest Interest payment €3.34 €760.44
            04 Apr 2026 Interest Interest payment €1,234.56 €1,995.00
        """.trimIndent()

        val parsed = parseTradeRepublicStatementText(text)

        assertEquals(4, parsed.transactions.size)
        assertEquals("2026-04-01", parsed.transactions[0].date)
        assertEquals(80_000L, parsed.transactions[0].amountCents)
        assertEquals(-4_290L, parsed.transactions[1].amountCents)
        assertEquals(334L, parsed.transactions[2].amountCents)
        assertEquals(123_456L, parsed.transactions[3].amountCents)
    }

    @Test fun importsLegacyGermanStatementAndExtractsIban() {
        val text = """
            TRADE REPUBLIC BANK GMBH
            KONTOAUSZUG zum 2020-03-31
            Aufstellung über die Buchungen in Deinem Verrechnungskonto DE02120300000000202051.
            BUCHUNGEN
            BUCHUNGSTAG / BUCHUNGSTEXT BETRAG IN EUR
            03.01.2020 Accepted PayIn: Absender to Empfänger 100,00
            02.01.2020
            08.01.2020 Ausführung Handel Direktkauf Aktie -1.478,00
        """.trimIndent()

        val parsed = parseTradeRepublicStatementText(text)

        assertEquals(listOf("DE02120300000000202051"), parsed.accountIbans)
        assertEquals(2, parsed.transactions.size)
        assertEquals(10_000L, parsed.transactions[0].amountCents)
        assertEquals(-147_800L, parsed.transactions[1].amountCents)
        assertEquals("2020-01-08", parsed.transactions[1].date)
    }

    @Test fun rejectsOtherPdfsAndAmbiguousDirectionInsteadOfGuessing() {
        assertTrue(runCatching { parseTradeRepublicStatementText("An unrelated PDF") }.isFailure)
        val ambiguous = """
            TRADE REPUBLIC BANK GMBH
            ACCOUNT TRANSACTIONS
            DATE TYPE DESCRIPTION MONEY IN MONEY OUT BALANCE
            01 Apr 2026 Transfer Internal transfer €10.00 €90.00
        """.trimIndent()
        assertTrue(runCatching { parseTradeRepublicStatementText(ambiguous) }.isFailure)
    }
}
