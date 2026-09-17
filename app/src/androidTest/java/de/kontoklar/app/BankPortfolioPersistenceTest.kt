package de.kontoklar.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BankPortfolioPersistenceTest {
    @Test fun mapsInitialBankSnapshotIntoLocalTransactionAccountsAndPositions() {
        val snapshot = parseLiveBankSnapshot(JSONObject("""
            {
              "id":"42","bankName":"C24","status":"READY",
              "transactions":[{"id":"77:900","accountIban":"DE02120300000000202051","date":"2026-09-16","counterparty":"Stadtwerke","description":"Abschlag","amountCents":-8742,"reference":"ref-1"}],
              "accounts":[{"id":"77","name":"Girokonto","type":"Checking","currency":"EUR","balanceMinor":125050,"asOfDate":"2026-09-16"},{"id":"88","name":"Depot","type":"Security","currency":"EUR","balanceMinor":50000,"asOfDate":"2026-09-16"}],
              "securities":[{"id":"900","accountId":"88","name":"ETF Muster","isin":"IE00TEST1234","wkn":"TST123","quantityNominal":2.5,"quantityType":"PIECE","quoteType":"ACTUAL","quoteMinor":10025,"quoteCurrency":"EUR","marketValueMinor":25063,"marketValueCurrency":"EUR","profitOrLossMinor":1230,"quoteDate":"2026-09-15"}]
            }
        """.trimIndent()))

        assertEquals(LiveBankConnection("42", "C24", "READY"), snapshot.connection)
        assertEquals(BankTransaction("live:77:900", "DE02120300000000202051", "2026-09-16", "Stadtwerke", "Abschlag", -8742, "ref-1"), snapshot.transactions.single())
        assertEquals(BankAccountSummary("live:77", "42", "Girokonto", "Checking", "EUR", 125050, "2026-09-16"), snapshot.accounts.first())
        assertEquals("live:88", snapshot.securities.single().accountId)
        assertEquals(25063L, snapshot.securities.single().marketValueMinor)
        assertEquals("IE00TEST1234", snapshot.securities.single().isin)
    }

    @Test fun balancesAndPositionsSurviveEncryptedLocalBackupRestore() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val isolatedPreferencesName = "kontoklar_bank_portfolio_instrumentation_test"
        val context = object : ContextWrapper(instrumentation.context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                targetContext.getSharedPreferences(isolatedPreferencesName, mode)
        }
        val store = LocalData(context)
        val accounts = listOf(
            BankAccountSummary("live:account-1", "connection-1", "Test Giro", "Checking", "EUR", 123456, "2026-09-16"),
            BankAccountSummary("live:account-2", "connection-1", "Test Depot", "Security", "EUR", 987654, "")
        )
        val positions = listOf(
            BankSecurityPosition(
                id = "position-1", accountId = "live:account-2", connectionId = "connection-1",
                name = "Test ETF Secret", isin = "IE00TEST1234", wkn = "TST123", quantityNominal = 2.5,
                quantityType = "UNIT", quoteType = "ACTU", quoteMinor = 10025, quoteCurrency = "EUR",
                marketValueMinor = 25063, marketValueCurrency = "EUR", profitOrLossMinor = 1230,
                quoteDate = "2026-09-15"
            )
        )

        try {
            store.saveBankAccounts(accounts)
            store.saveBankSecurities(positions)

            val rawPreferences = context.getSharedPreferences("kontoklar_data_v1", Context.MODE_PRIVATE)
            assertTrue(rawPreferences.getString("bank_accounts", "")!!.startsWith("KKENC1:"))
            assertTrue(rawPreferences.getString("bank_securities", "")!!.startsWith("KKENC1:"))
            assertFalse(rawPreferences.getString("bank_securities", "")!!.contains("Test ETF Secret"))

            val snapshot = JSONObject(store.exportSnapshot().toString())
            assertEquals(2, snapshot.getJSONArray("bankAccounts").length())
            assertEquals(1, snapshot.getJSONArray("bankSecurities").length())

            store.saveBankAccounts(emptyList())
            store.saveBankSecurities(emptyList())
            store.restoreSnapshot(snapshot)

            assertEquals(accounts, store.bankAccounts())
            assertEquals(positions, store.bankSecurities())

            val invalidSnapshot = JSONObject(snapshot.toString())
            val duplicatedPositions = JSONArray(invalidSnapshot.getJSONArray("bankSecurities").toString())
                .put(JSONObject(invalidSnapshot.getJSONArray("bankSecurities").getJSONObject(0).toString()))
            invalidSnapshot.put("bankSecurities", duplicatedPositions)
            assertThrows(IllegalArgumentException::class.java) { store.restoreSnapshot(invalidSnapshot) }
            assertEquals(accounts, store.bankAccounts())
            assertEquals(positions, store.bankSecurities())
        } finally {
            targetContext.deleteSharedPreferences(isolatedPreferencesName)
        }
    }
}
