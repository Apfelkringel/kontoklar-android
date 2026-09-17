package de.kontoklar.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComdirectReadOnlyAndroidTest {
    @Test fun parsesOfficialStyleAccountBalance() {
        val account = parseComdirectAccount(JSONObject("""
            {"accountId":"123456789","accountDisplayId":"123456789",
             "balance":{"value":"1234.56","unit":"EUR"},
             "balanceDate":"2026-09-17T10:15:00+02:00"}
        """))
        assertEquals("comdirect:123456789", account.id)
        assertEquals("CHECKING", account.type)
        assertEquals("EUR", account.currency)
        assertEquals(123456L, account.balanceMinor)
        assertEquals("2026-09-17", account.asOfDate)
    }

    @Test fun parsesCreditAndDebitTransactions() {
        val credit = parseComdirectTransaction(JSONObject("""
            {"transactionId":"credit-1","bookingDate":"2026-09-16",
             "creditDebitIndicator":"CRDT","amount":{"value":"250.00","unit":"EUR"},
             "names":["Kunde GmbH"],"remittanceInfo":"Rechnung 42","endToEndReference":"E2E-42"}
        """), "DE123")
        val debit = parseComdirectTransaction(JSONObject("""
            {"bookingId":"debit-1","valueDate":"2026-09-17",
             "creditDebitIndicator":"DBIT","amount":{"value":"19.99","unit":"EUR"},
             "counterparty":"Supermarkt","bookingText":"Kartenzahlung"}
        """), "DE123")
        assertEquals(25000L, credit.amountCents)
        assertEquals("Kunde GmbH", credit.counterparty)
        assertEquals("E2E-42", credit.reference)
        assertEquals(-1999L, debit.amountCents)
        assertEquals("Supermarkt", debit.counterparty)
        assertEquals("2026-09-17", debit.date)
    }

    @Test fun parsesDepotQuantityObjectAndMarketValue() {
        val position = parseComdirectPosition(JSONObject("""
            {"positionId":"pos-1","securityName":"Global ETF","isin":"IE00TEST",
             "quantity":{"value":"2.5","unit":" Stück"},
             "marketValue":{"value":"321.09","unit":"EUR"},"valuationDate":"2026-09-17"}
        """), "depot-1")
        assertEquals("comdirect:pos-1", position.id)
        assertEquals(2.5, position.quantityNominal!!, 0.000001)
        assertEquals(32109L, position.marketValueMinor)
        assertEquals("EUR", position.marketValueCurrency)
        assertEquals("2026-09-17", position.quoteDate)
        assertNull(position.profitOrLossMinor)
    }
}
