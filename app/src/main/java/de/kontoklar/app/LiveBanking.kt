package de.kontoklar.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Base64

data class BankingInstitution(val id: String, val name: String)
data class LiveBankConnection(val id: String, val bankName: String, val status: String)
data class BankLinkSession(val id: String, val authorizationUrl: String)
data class LiveBankSnapshot(val connection: LiveBankConnection, val transactions: List<BankTransaction>)
data class BankSyncResult(val status: String, val authorizationUrl: String? = null, val snapshot: LiveBankSnapshot? = null)

/** Client for KontoKlar's own server-side Open Banking adapter. Provider secrets never belong in the APK. */
class LiveBankingClient(context: Context, private val baseUrl: String = BuildConfig.BANKING_API_BASE_URL) {
    private val preferences = SecureLocalPreferences(context.applicationContext)
    private var installationToken = preferences.getString(TOKEN_KEY) ?: createInstallationToken().also {
        check(preferences.putString(TOKEN_KEY, it, commit = true)) { "Sicherer Banking-Schlüssel konnte nicht gespeichert werden." }
    }

    val isConfigured: Boolean get() = runCatching {
        URL(baseUrl).let { it.protocol == "https" && it.host.isNotBlank() && it.userInfo == null && it.query == null && it.ref == null }
    }.getOrDefault(false)

    fun institutions(): List<BankingInstitution> = request("GET", "/v1/banks").getJSONArray("banks").asList { row ->
        BankingInstitution(row.getString("id"), row.getString("name"))
    }

    fun connections(): List<LiveBankConnection> = request("GET", "/v1/connections").getJSONArray("connections").asList(::connectionFromJson)

    fun connect(bankId: String): BankLinkSession {
        val result = request("POST", "/v1/connections", JSONObject().put("bankId", bankId))
        val url = URL(result.getString("authorizationUrl"))
        require(url.protocol == "https" && url.host in setOf("webform-sandbox.finapi.io", "webform-live.finapi.io")) {
            "Der Bankanbieter hat eine nicht erlaubte Freigabe-URL geliefert."
        }
        return BankLinkSession(result.getString("id"), url.toString())
    }

    fun sync(connection: LiveBankConnection): BankSyncResult {
        val result = request("POST", "/v1/connections/${connection.id.pathSegment()}/sync", JSONObject().put("deviceOs", "Android"))
        val status = result.optString("status", "IN_PROGRESS")
        val authorizationUrl = result.optString("authorizationUrl").takeIf(String::isNotBlank)?.also { value ->
            val url = URL(value)
            require(url.protocol == "https" && url.host in setOf("webform-sandbox.finapi.io", "webform-live.finapi.io")) {
                "Der Bankanbieter hat eine nicht erlaubte Freigabe-URL geliefert."
            }
        }
        if (status != "COMPLETED") return BankSyncResult(status, authorizationUrl)
        val snapshotJson = result.getJSONObject("snapshot")
        val transactions = snapshotJson.optJSONArray("transactions").orEmpty().asList { row ->
            val amount = row.getLong("amountCents")
            BankTransaction(
                id = "live:${row.getString("id")}",
                accountIban = row.optString("accountIban"),
                date = row.getString("date"),
                counterparty = row.optString("counterparty", "Unbekannter Zahlungspartner"),
                description = row.optString("description"),
                amountCents = amount,
                reference = row.optString("reference")
            )
        }
        val state = connectionFromJson(snapshotJson)
        return BankSyncResult(status, snapshot = LiveBankSnapshot(state, transactions))
    }

    fun delete(connectionId: String) {
        request("DELETE", "/v1/connections/${connectionId.pathSegment()}")
    }

    fun deleteProviderProfile() {
        request("DELETE", "/v1/profile")
        val replacement = createInstallationToken()
        check(preferences.putString(TOKEN_KEY, replacement, commit = true)) { "Neuer sicherer Banking-Schlüssel konnte nicht gespeichert werden." }
        installationToken = replacement
    }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        check(isConfigured) { "Live-Banking-Backend ist noch nicht konfiguriert." }
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $installationToken")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(response).optString("error") }.getOrNull().orEmpty()
                error(message.ifBlank { "Bankdienst nicht erreichbar (HTTP $status)." })
            }
            if (status == HttpURLConnection.HTTP_NO_CONTENT || response.isBlank()) JSONObject() else JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun connectionFromJson(row: JSONObject) = LiveBankConnection(
        id = row.getString("id"), bankName = row.getString("bankName"), status = row.getString("status")
    )

    private companion object {
        const val TOKEN_KEY = "live_banking_installation_token_v1"
        fun createInstallationToken(): String = ByteArray(32).also(SecureRandom()::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
        fun String.pathSegment(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
        fun <T> JSONArray.asList(map: (JSONObject) -> T): List<T> = List(length()) { index -> map(getJSONObject(index)) }
        fun JSONArray?.orEmpty() = this ?: JSONArray()
    }
}
