package de.kontoklar.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComdirectReadOnlyTest {
    @Test fun parsesOwnAccountAndBookedTransaction() {
        val account = parseComdirectAccount(JSONObject("""{
            "accountId":"4711", "accountDisplayId":"Konto 4711",
            "balance":{"value":"1234.56","unit":"EUR"}, "balanceDate":"2026-09-17"
        }"""))
        val transaction = parseComdirectTransaction(JSONObject("""{
            "transactionId":"tx-1", "bookingDate":"2026-09-16",
            "amount":{"value":"12.34","unit":"EUR"}, "creditDebitIndicator":"DEBIT",
            "names":["Stadtwerke"], "remittanceInfo":"Abschlag"
        }"""), "DE123")

        assertEquals("comdirect:4711", account.id)
        assertEquals(123456L, account.balanceMinor)
        assertEquals(-1234L, transaction.amountCents)
        assertEquals("Stadtwerke", transaction.counterparty)
    }

    @Test fun rejectsWriteLikeOrMalformedInputsByHavingNoWriteSurface() {
        assertTrue(ComdirectReadOnlyClient::class.java.methods.none { it.name in setOf("post", "put", "delete", "transfer", "order") })
        assertTrue(runCatching { parseComdirectTransaction(JSONObject("""{"transactionId":"x","bookingDate":"2026-09-16","amount":{"value":"1.00"}}"""), "DE123") }.isFailure)
    }
}
