package de.kontoklar.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

data class Invoice(
    val id: String = UUID.randomUUID().toString(),
    val customer: String,
    val description: String,
    val amountCents: Long,
    val date: String = LocalDate.now().toString(),
    val dueDate: String = LocalDate.now().plusDays(14).toString(),
    val status: String = "Entwurf"
)

data class Expense(
    val id: String = UUID.randomUUID().toString(),
    val merchant: String,
    val category: String,
    val amountCents: Long,
    val date: String = LocalDate.now().toString(),
    val note: String = ""
)

class LocalData(context: Context) {
    private val prefs = context.getSharedPreferences("kontoklar_data_v1", Context.MODE_PRIVATE)

    fun invoices(): List<Invoice> = read("invoices") { Invoice(it.optString("id", UUID.randomUUID().toString()), it.optString("customer", ""), it.optString("description", ""), it.optLong("amountCents", 0), it.optString("date", ""), it.optString("dueDate", ""), it.optString("status", "Entwurf")) }

    fun expenses(): List<Expense> = read("expenses") { Expense(it.optString("id", UUID.randomUUID().toString()), it.optString("merchant", ""), it.optString("category", "Sonstiges"), it.optLong("amountCents", 0), it.optString("date", ""), it.optString("note", "")) }

    fun saveInvoices(values: List<Invoice>) = write("invoices", values.map { JSONObject().put("id", it.id).put("customer", it.customer).put("description", it.description).put("amountCents", it.amountCents).put("date", it.date).put("dueDate", it.dueDate).put("status", it.status) })
    fun saveExpenses(values: List<Expense>) = write("expenses", values.map { JSONObject().put("id", it.id).put("merchant", it.merchant).put("category", it.category).put("amountCents", it.amountCents).put("date", it.date).put("note", it.note) })

    private fun <T> read(key: String, decode: (JSONObject) -> T): List<T> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            List(json.length()) { decode(json.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun write(key: String, values: List<JSONObject>) {
        prefs.edit().putString(key, JSONArray(values).toString()).apply()
    }
}

fun parseEuroCents(raw: String): Long? {
    val cleaned = raw.trim().replace("€", "").replace(" ", "")
    if (cleaned.count { it == ',' } > 1) return null
    val normalized = if (',' in cleaned) cleaned.replace(".", "").replace(',', '.') else cleaned
    val amount = normalized.toBigDecimalOrNull() ?: return null
    if (amount <= java.math.BigDecimal.ZERO || amount.scale() > 2) return null
    return runCatching { amount.movePointRight(2).longValueExact() }.getOrNull()
}

fun formatEuro(cents: Long): String {
    val euros = cents / 100
    val remainder = kotlin.math.abs(cents % 100)
    return "${String.format(java.util.Locale.GERMANY, "%,d", euros)},${String.format(java.util.Locale.GERMANY, "%02d", remainder)} €"
}
