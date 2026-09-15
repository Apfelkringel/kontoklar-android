package de.kontoklar.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

data class Invoice(
    val id: String = UUID.randomUUID().toString(),
    val number: String = "",
    val customer: String,
    val description: String,
    val amountCents: Long,
    val date: String = LocalDate.now().toString(),
    val dueDate: String = LocalDate.now().plusDays(14).toString(),
    val status: String = "Entwurf"
)

data class BusinessProfile(
    val businessName: String = "",
    val street: String = "",
    val postalCode: String = "",
    val city: String = "",
    val taxNumber: String = "",
    val vatId: String = "",
    val invoicePrefix: String = "RE",
    val paymentTermsDays: Int = 14,
    val vatRatePercent: Int = 19
)

data class Expense(
    val id: String = UUID.randomUUID().toString(),
    val merchant: String,
    val category: String,
    val amountCents: Long,
    val date: String = LocalDate.now().toString(),
    val note: String = "",
    val receiptUri: String? = null
)

class LocalData(context: Context) {
    private val prefs = context.getSharedPreferences("kontoklar_data_v1", Context.MODE_PRIVATE)

    fun invoices(): List<Invoice> = read("invoices") { Invoice(id = it.optString("id", UUID.randomUUID().toString()), number = it.optString("number", ""), customer = it.optString("customer", ""), description = it.optString("description", ""), amountCents = it.optLong("amountCents", 0), date = it.optString("date", ""), dueDate = it.optString("dueDate", ""), status = it.optString("status", "Entwurf")) }

    fun expenses(): List<Expense> = read("expenses") { Expense(id = it.optString("id", UUID.randomUUID().toString()), merchant = it.optString("merchant", ""), category = it.optString("category", "Sonstiges"), amountCents = it.optLong("amountCents", 0), date = it.optString("date", ""), note = it.optString("note", ""), receiptUri = it.optString("receiptUri").takeIf(String::isNotBlank)) }

    fun saveInvoices(values: List<Invoice>) = write("invoices", values.map { JSONObject().put("id", it.id).put("number", it.number).put("customer", it.customer).put("description", it.description).put("amountCents", it.amountCents).put("date", it.date).put("dueDate", it.dueDate).put("status", it.status) })
    fun saveExpenses(values: List<Expense>) = write("expenses", values.map { JSONObject().put("id", it.id).put("merchant", it.merchant).put("category", it.category).put("amountCents", it.amountCents).put("date", it.date).put("note", it.note).put("receiptUri", it.receiptUri) })

    fun businessProfile(): BusinessProfile {
        val json = prefs.getString("business_profile", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return BusinessProfile()
        return BusinessProfile(
            businessName = json.optString("businessName"), street = json.optString("street"),
            postalCode = json.optString("postalCode"), city = json.optString("city"),
            taxNumber = json.optString("taxNumber"), vatId = json.optString("vatId"),
            invoicePrefix = json.optString("invoicePrefix", "RE").ifBlank { "RE" },
            paymentTermsDays = json.optInt("paymentTermsDays", 14).coerceIn(1, 90),
            vatRatePercent = json.optInt("vatRatePercent", 19).coerceIn(0, 27)
        )
    }

    fun saveBusinessProfile(profile: BusinessProfile) {
        prefs.edit().putString("business_profile", JSONObject()
            .put("businessName", profile.businessName).put("street", profile.street)
            .put("postalCode", profile.postalCode).put("city", profile.city)
            .put("taxNumber", profile.taxNumber).put("vatId", profile.vatId)
            .put("invoicePrefix", profile.invoicePrefix).put("paymentTermsDays", profile.paymentTermsDays)
            .put("vatRatePercent", profile.vatRatePercent).toString()).apply()
    }

    fun nextInvoiceNumber(): String {
        val year = LocalDate.now().year
        return nextInvoiceNumber(year, invoices().map { it.number }, businessProfile().invoicePrefix)
    }

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

fun nextInvoiceNumber(year: Int, existingNumbers: List<String>, invoicePrefix: String = "RE"): String {
    val prefix = "${invoicePrefix.ifBlank { "RE" }}-$year-"
    val next = existingNumbers.mapNotNull { number -> number.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull() }.maxOrNull()?.plus(1) ?: 1
    return "$prefix${next.toString().padStart(4, '0')}"
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
