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
