package de.kontoklar.app

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Read-only mapping for the official comdirect REST API.
 *
 * The access token is deliberately supplied by the caller and is never persisted by this class.
 * There are no order, transfer or payment methods in this client.
 */
class ComdirectReadOnlyClient(
    private val accessToken: String,
    private val sessionIdentifier: String,
    private val apiBaseUrl: String = "https://api.comdirect.de"
) {
    init {
        require(accessToken.isNotBlank()) { "Für den comdirect-Abruf fehlt ein Zugriffstoken." }
        require(sessionIdentifier.isNotBlank()) { "Für den comdirect-Abruf fehlt eine aktive Session." }
        val base = URL(apiBaseUrl)
        require(base.protocol == "https" && base.host == "api.comdirect.de" && base.userInfo == null && base.query == null && base.ref == null) {
            "Der comdirect-Client akzeptiert ausschließlich https://api.comdirect.de."
        }
    }

    /** Loads balances, booked transactions and depot positions without any write-capable endpoint. */
    fun loadSnapshot(): ComdirectSnapshot {
        val accountRows = getCollection("/api/banking/clients/user/v2/accounts/balances")
        val accounts = accountRows.map(::parseComdirectAccount)
        val transactions = accountRows.flatMap { row ->
            val accountId = row.optString("accountId").ifBlank { row.optString("accountNumber") }
            val iban = row.optString("iban")
            getCollection("/api/banking/v1/accounts/${accountId.pathSegment()}/transactions")
                .map { parseComdirectTransaction(it, iban) }
        }
        val securities = getCollection("/api/brokerage/clients/user/v3/depots")
            .flatMap { depot ->
                val depotId = depot.optString("depotId").ifBlank { depot.optString("id") }
                require(depotId.isNotBlank()) { "comdirect lieferte ein Depot ohne Kennung." }
                getCollection("/api/brokerage/v3/depots/${depotId.pathSegment()}/positions")
                    .map { parseComdirectPosition(it, depotId) }
            }
        return ComdirectSnapshot(accounts, transactions, securities)
    }

    private fun getCollection(path: String): List<JSONObject> {
        val url = URL(apiBaseUrl.trimEnd('/') + path + "?paging-first=0&paging-count=1000")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty(
                "x-http-request-info",
                JSONObject().put("clientRequestId", JSONObject().put("sessionId", sessionIdentifier).put("requestId", requestId())).toString()
            )
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) error("comdirect-Abruf fehlgeschlagen (HTTP $status).")
            val json = JSONObject(body)
            val values = json.optJSONArray("values") ?: json.optJSONArray("data")
                ?: error("comdirect-Antwort enthält keine Datensammlung.")
            List(values.length()) { values.getJSONObject(it) }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        fun String.pathSegment() = java.net.URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
        fun requestId(): String = java.util.UUID.randomUUID().toString().replace("-", "")
    }
}

data class ComdirectSnapshot(
    val accounts: List<BankAccountSummary>,
    val transactions: List<BankTransaction>,
    val securities: List<BankSecurityPosition>
)

internal fun parseComdirectAccount(json: JSONObject): BankAccountSummary {
    val id = json.optString("accountId").ifBlank { json.optString("accountNumber") }
    require(id.isNotBlank()) { "comdirect-Konto ohne Kennung." }
    val balance = json.optJSONObject("balance") ?: json.optJSONObject("accountBalance")
    val currency = balance?.optString("unit").orEmpty().ifBlank { balance?.optString("currency").orEmpty() }.ifBlank { "EUR" }
    return BankAccountSummary(
        id = "comdirect:$id",
        connectionId = "comdirect",
        name = json.optString("accountDisplayId").ifBlank { "comdirect-Konto" },
        type = "CHECKING",
        currency = currency,
        balanceMinor = balance?.let { parseMinor(it.optString("value")) },
        asOfDate = json.optString("balanceDate").take(10)
    )
}

internal fun parseComdirectTransaction(json: JSONObject, iban: String): BankTransaction {
    val id = json.optString("transactionId").ifBlank { json.optString("bookingId") }
    require(id.isNotBlank()) { "comdirect-Buchung ohne Kennung." }
    val date = json.optString("bookingDate").ifBlank { json.optString("valueDate") }.take(10)
    require(runCatching { LocalDate.parse(date) }.isSuccess) { "comdirect-Buchung enthält kein gültiges Datum." }
    val amount = json.optJSONObject("amount") ?: error("comdirect-Buchung ohne Betrag.")
    val cents = parseMinor(amount.optString("value"))
    val signed = when (json.optString("creditDebitIndicator").uppercase()) {
        "CREDIT", "CRDT" -> cents
        "DEBIT", "DBIT" -> -cents
        else -> error("comdirect-Buchung ohne Soll/Haben-Kennzeichnung.")
    }
    val names = json.optJSONArray("names")?.let { array ->
        List(array.length()) { index -> array.optString(index) }.filter(String::isNotBlank).joinToString(" · ")
    } ?: json.optString("counterparty")
    val description = listOf(json.optString("remittanceInfo"), json.optString("bookingText"))
        .filter(String::isNotBlank).distinct().joinToString(" · ").take(800)
    return BankTransaction("comdirect:$id", iban, date, names.ifBlank { "Unbekannter Zahlungspartner" }, description, signed, json.optString("endToEndReference"))
}

internal fun parseComdirectPosition(json: JSONObject, depotId: String): BankSecurityPosition {
    val id = json.optString("positionId").ifBlank { json.optString("isin") }
    require(id.isNotBlank()) { "comdirect-Depotposition ohne Kennung." }
    val quantity = json.optString("quantity").toDoubleOrNull()
        ?: json.optJSONObject("quantity")?.let { it.optString("value").toDoubleOrNull() }
    val marketValue = json.optJSONObject("marketValue")
    return BankSecurityPosition(
        id = "comdirect:$id", accountId = "comdirect:$depotId", connectionId = "comdirect",
        name = json.optString("securityName").ifBlank { json.optString("name") }.ifBlank { "Wertpapier" }, isin = json.optString("isin"),
        wkn = json.optString("wkn"), quantityNominal = quantity, quantityType = "Stück",
        quoteType = "", quoteMinor = null, quoteCurrency = "",
        marketValueMinor = marketValue?.let { parseMinor(it.optString("value")) },
        marketValueCurrency = marketValue?.optString("unit").orEmpty().ifBlank { "EUR" },
        profitOrLossMinor = null, quoteDate = json.optString("valuationDate").take(10)
    )
}

private fun parseMinor(value: String): Long {
    require(value.isNotBlank()) { "Betrag fehlt." }
    return BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
}
