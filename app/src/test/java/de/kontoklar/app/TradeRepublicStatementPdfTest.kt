package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeRepublicStatementPdfTest {
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
