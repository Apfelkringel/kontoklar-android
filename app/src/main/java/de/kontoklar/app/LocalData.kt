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
    val customerId: String? = null,
    val customerAddress: String = "",
    val customerEmail: String = "",
    val date: String = LocalDate.now().toString(),
    val serviceDate: String = LocalDate.now().toString(),
    val dueDate: String = LocalDate.now().plusDays(14).toString(),
    val status: String = "Entwurf"
)

data class Offer(
    val id: String = UUID.randomUUID().toString(),
    val number: String = "",
    val customer: String,
    val description: String,
    val amountCents: Long,
    val customerId: String? = null,
    val customerAddress: String = "",
    val customerEmail: String = "",
    val date: String = LocalDate.now().toString(),
    val validUntil: String = LocalDate.now().plusDays(30).toString(),
    val status: String = "Entwurf",
    val convertedInvoiceId: String? = null
)

fun offerStatus(offer: Offer, today: LocalDate = LocalDate.now()): String =
    if (offer.status == "Versendet" && runCatching { LocalDate.parse(offer.validUntil).isBefore(today) }.getOrDefault(false)) "Abgelaufen" else offer.status

fun Offer.toInvoice(number: String, paymentTermsDays: Int): Invoice = Invoice(
    number = number,
    customer = customer,
    description = description,
    amountCents = amountCents,
    customerId = customerId,
    customerAddress = customerAddress,
    customerEmail = customerEmail,
    dueDate = LocalDate.now().plusDays(paymentTermsDays.coerceIn(1, 90).toLong()).toString()
)

data class Customer(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val email: String = "",
    val street: String = "",
    val postalCode: String = "",
    val city: String = "",
    val taxNumber: String = ""
) {
    val postalAddress: String get() = listOf(street, listOf(postalCode, city).filter(String::isNotBlank).joinToString(" "))
        .filter(String::isNotBlank).joinToString("\n")
}

data class Product(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val unitPriceCents: Long
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
    val vatRatePercent: Int = 19,
    val email: String = "",
    val contactName: String = "",
    val phone: String = "",
    val iban: String = ""
)

data class InvoiceAmountBreakdown(val netCents: Long, val vatCents: Long, val grossCents: Long)

fun invoiceAmountBreakdown(grossCents: Long, vatRatePercent: Int): InvoiceAmountBreakdown {
    require(grossCents >= 0) { "Der Bruttobetrag darf nicht negativ sein." }
    require(vatRatePercent in 0..100) { "Der Umsatzsteuersatz ist ungültig." }
    val divisor = 100L + vatRatePercent
    val quotient = grossCents / divisor
    val remainder = grossCents % divisor
    val net = quotient * 100L + (remainder * 100L + divisor / 2L) / divisor
    return InvoiceAmountBreakdown(net, grossCents - net, grossCents)
}

data class Expense(
    val id: String = UUID.randomUUID().toString(),
    val merchant: String,
    val category: String,
    val amountCents: Long,
    val date: String = LocalDate.now().toString(),
    val note: String = "",
    val receiptUri: String? = null,
    val inputVatCents: Long? = null
)

fun List<Expense>.upsertExpense(expense: Expense): List<Expense> =
    if (any { it.id == expense.id }) map { if (it.id == expense.id) expense else it } else listOf(expense) + this

fun List<Expense>.withoutExpense(id: String): List<Expense> = filterNot { it.id == id }

fun List<Invoice>.upsertInvoice(invoice: Invoice): List<Invoice> =
    if (any { it.id == invoice.id }) map { if (it.id == invoice.id) invoice else it } else listOf(invoice) + this

fun List<Invoice>.withoutInvoice(id: String): List<Invoice> = filterNot { it.id == id }

fun List<Product>.upsertProduct(product: Product): List<Product> =
    if (any { it.id == product.id }) map { if (it.id == product.id) product else it } else listOf(product) + this

fun List<Product>.withoutProduct(id: String): List<Product> = filterNot { it.id == id }

class LocalData(context: Context) {
    private val prefs = context.getSharedPreferences("kontoklar_data_v1", Context.MODE_PRIVATE)

    fun exportSnapshot(): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("businessProfile", businessProfileJson(businessProfile()))
        .put("invoices", storedArray("invoices"))
        .put("offers", storedArray("offers"))
        .put("expenses", storedArray("expenses"))
        .put("bankTransactions", storedArray("bank_transactions"))
        .put("customers", storedArray("customers"))
        .put("products", storedArray("products"))

    fun restoreSnapshot(snapshot: JSONObject) {
        require(snapshot.optInt("schemaVersion") == 1) { "Diese Sicherungsversion wird nicht unterstützt." }
        val importedInvoices = decodeArray(snapshot.getJSONArray("invoices"), ::invoiceFromJson)
        val importedOffers = decodeArray(snapshot.getJSONArray("offers"), ::offerFromJson)
        val importedExpenses = decodeArray(snapshot.getJSONArray("expenses"), ::expenseFromJson)
        val importedBankTransactions = decodeArray(snapshot.optJSONArray("bankTransactions") ?: JSONArray(), ::bankTransactionFromJson)
        val importedCustomers = decodeArray(snapshot.getJSONArray("customers"), ::customerFromJson)
        val importedProducts = decodeArray(snapshot.optJSONArray("products") ?: JSONArray(), ::productFromJson)
        val importedProfile = businessProfileFromJson(snapshot.getJSONObject("businessProfile"))
        require(importedInvoices.all { it.amountCents > 0 && it.customer.isNotBlank() && it.description.isNotBlank() }) { "Die Sicherung enthält ungültige Rechnungen." }
        require(importedOffers.all { it.amountCents > 0 && it.customer.isNotBlank() && it.description.isNotBlank() }) { "Die Sicherung enthält ungültige Angebote." }
        require(importedExpenses.all { it.amountCents > 0 && it.merchant.isNotBlank() && (it.inputVatCents == null || it.inputVatCents in 0..it.amountCents) }) { "Die Sicherung enthält ungültige Ausgaben oder Umsatzsteuerangaben." }
        require(importedBankTransactions.all { it.amountCents != 0L && validIsoDate(it.date) && it.id.matches(Regex("[a-f0-9]{64}")) }) { "Die Sicherung enthält ungültige Bankumsätze." }
        require(importedCustomers.all { it.name.isNotBlank() }) { "Die Sicherung enthält ungültige Kundendaten." }
        require(importedProducts.all { it.name.isNotBlank() && it.unitPriceCents > 0 }) { "Die Sicherung enthält ungültige Produkte oder Dienstleistungen." }
        require(importedInvoices.map { it.id }.distinct().size == importedInvoices.size && importedInvoices.all { validIsoDate(it.date) && validIsoDate(it.serviceDate) && validIsoDate(it.dueDate) }) { "Die Sicherung enthält doppelte Rechnungen oder ungültige Rechnungsdaten." }
        require(importedOffers.map { it.id }.distinct().size == importedOffers.size && importedOffers.all { validIsoDate(it.date) && validIsoDate(it.validUntil) }) { "Die Sicherung enthält doppelte Angebote oder ungültige Angebotsdaten." }
        require(importedExpenses.map { it.id }.distinct().size == importedExpenses.size && importedExpenses.all { validIsoDate(it.date) }) { "Die Sicherung enthält doppelte Ausgaben oder ungültige Ausgabedaten." }
        require(importedBankTransactions.map { it.id }.distinct().size == importedBankTransactions.size) { "Die Sicherung enthält doppelte Bankumsätze." }
        require(importedCustomers.map { it.id }.distinct().size == importedCustomers.size) { "Die Sicherung enthält doppelte Kunden." }
        require(importedProducts.map { it.id }.distinct().size == importedProducts.size) { "Die Sicherung enthält doppelte Produkte." }

        check(prefs.edit()
            .putString("invoices", snapshot.getJSONArray("invoices").toString())
            .putString("offers", snapshot.getJSONArray("offers").toString())
            .putString("expenses", snapshot.getJSONArray("expenses").toString())
            .putString("bank_transactions", (snapshot.optJSONArray("bankTransactions") ?: JSONArray()).toString())
            .putString("customers", snapshot.getJSONArray("customers").toString())
            .putString("products", (snapshot.optJSONArray("products") ?: JSONArray()).toString())
            .putString("business_profile", businessProfileJson(importedProfile).toString())
            .commit()) { "Die wiederhergestellten Daten konnten nicht dauerhaft gespeichert werden." }
    }

    private fun storedArray(key: String): JSONArray = prefs.getString(key, null)?.let(::JSONArray) ?: JSONArray()

    private fun <T> decodeArray(array: JSONArray, decode: (JSONObject) -> T): List<T> =
        List(array.length()) { decode(array.getJSONObject(it)) }

    private fun invoiceFromJson(it: JSONObject) = Invoice(
        id = it.optString("id", UUID.randomUUID().toString()), number = it.optString("number", ""),
        customer = it.optString("customer", ""), description = it.optString("description", ""),
        amountCents = it.optLong("amountCents", 0), customerId = it.optString("customerId").takeIf(String::isNotBlank),
        customerAddress = it.optString("customerAddress"), customerEmail = it.optString("customerEmail"),
        date = it.optString("date"), serviceDate = it.optString("serviceDate", it.optString("date")), dueDate = it.optString("dueDate"), status = it.optString("status", "Entwurf")
    )

    private fun offerFromJson(it: JSONObject) = Offer(
        id = it.optString("id", UUID.randomUUID().toString()), number = it.optString("number"),
        customer = it.optString("customer"), description = it.optString("description"), amountCents = it.optLong("amountCents"),
        customerId = it.optString("customerId").takeIf(String::isNotBlank), customerAddress = it.optString("customerAddress"),
        customerEmail = it.optString("customerEmail"), date = it.optString("date"), validUntil = it.optString("validUntil"),
        status = it.optString("status", "Entwurf"), convertedInvoiceId = it.optString("convertedInvoiceId").takeIf(String::isNotBlank)
    )

    private fun expenseFromJson(it: JSONObject) = Expense(
        id = it.optString("id", UUID.randomUUID().toString()), merchant = it.optString("merchant", ""),
        category = it.optString("category", "Sonstiges"), amountCents = it.optLong("amountCents", 0),
        date = it.optString("date"), note = it.optString("note"), receiptUri = it.optString("receiptUri").takeIf(String::isNotBlank),
        inputVatCents = it.takeUnless { json -> json.isNull("inputVatCents") }?.optLong("inputVatCents")
    )

    private fun bankTransactionFromJson(it: JSONObject) = BankTransaction(
        id = it.optString("id"), accountIban = it.optString("accountIban"), date = it.optString("date"),
        counterparty = it.optString("counterparty"), description = it.optString("description"),
        amountCents = it.optLong("amountCents"), reference = it.optString("reference"),
        matchedInvoiceId = it.optString("matchedInvoiceId").takeIf(String::isNotBlank)
    )

    private fun customerFromJson(it: JSONObject) = Customer(
        id = it.optString("id", UUID.randomUUID().toString()), name = it.optString("name"), email = it.optString("email"),
        street = it.optString("street"), postalCode = it.optString("postalCode"), city = it.optString("city"), taxNumber = it.optString("taxNumber")
    )

    private fun productFromJson(it: JSONObject) = Product(
        id = it.optString("id", UUID.randomUUID().toString()), name = it.optString("name"),
        description = it.optString("description"), unitPriceCents = it.optLong("unitPriceCents")
    )

    fun invoices(): List<Invoice> = read("invoices") { Invoice(id = it.optString("id", UUID.randomUUID().toString()), number = it.optString("number", ""), customer = it.optString("customer", ""), description = it.optString("description", ""), amountCents = it.optLong("amountCents", 0), customerId = it.optString("customerId").takeIf(String::isNotBlank), customerAddress = it.optString("customerAddress"), customerEmail = it.optString("customerEmail"), date = it.optString("date", ""), serviceDate = it.optString("serviceDate", it.optString("date", "")), dueDate = it.optString("dueDate", ""), status = it.optString("status", "Entwurf")) }

    fun offers(): List<Offer> = read("offers") { Offer(id = it.optString("id", UUID.randomUUID().toString()), number = it.optString("number"), customer = it.optString("customer"), description = it.optString("description"), amountCents = it.optLong("amountCents"), customerId = it.optString("customerId").takeIf(String::isNotBlank), customerAddress = it.optString("customerAddress"), customerEmail = it.optString("customerEmail"), date = it.optString("date"), validUntil = it.optString("validUntil"), status = it.optString("status", "Entwurf"), convertedInvoiceId = it.optString("convertedInvoiceId").takeIf(String::isNotBlank)) }

    fun expenses(): List<Expense> = read("expenses", ::expenseFromJson)
    fun bankTransactions(): List<BankTransaction> = read("bank_transactions", ::bankTransactionFromJson)

    fun saveInvoices(values: List<Invoice>) = write("invoices", values.map { JSONObject().put("id", it.id).put("number", it.number).put("customer", it.customer).put("customerId", it.customerId).put("customerAddress", it.customerAddress).put("customerEmail", it.customerEmail).put("description", it.description).put("amountCents", it.amountCents).put("date", it.date).put("serviceDate", it.serviceDate).put("dueDate", it.dueDate).put("status", it.status) })
    fun saveOffers(values: List<Offer>) = write("offers", values.map { JSONObject().put("id", it.id).put("number", it.number).put("customer", it.customer).put("customerId", it.customerId).put("customerAddress", it.customerAddress).put("customerEmail", it.customerEmail).put("description", it.description).put("amountCents", it.amountCents).put("date", it.date).put("validUntil", it.validUntil).put("status", it.status).put("convertedInvoiceId", it.convertedInvoiceId) })
    fun saveExpenses(values: List<Expense>) = write("expenses", values.map { JSONObject().put("id", it.id).put("merchant", it.merchant).put("category", it.category).put("amountCents", it.amountCents).put("date", it.date).put("note", it.note).put("receiptUri", it.receiptUri).put("inputVatCents", it.inputVatCents ?: JSONObject.NULL) })
    fun saveBankTransactions(values: List<BankTransaction>) = write("bank_transactions", values.map { JSONObject().put("id", it.id).put("accountIban", it.accountIban).put("date", it.date).put("counterparty", it.counterparty).put("description", it.description).put("amountCents", it.amountCents).put("reference", it.reference).put("matchedInvoiceId", it.matchedInvoiceId) })
    fun customers(): List<Customer> = read("customers") { Customer(id = it.optString("id", UUID.randomUUID().toString()), name = it.optString("name"), email = it.optString("email"), street = it.optString("street"), postalCode = it.optString("postalCode"), city = it.optString("city"), taxNumber = it.optString("taxNumber")) }
    fun saveCustomers(values: List<Customer>) = write("customers", values.map { JSONObject().put("id", it.id).put("name", it.name).put("email", it.email).put("street", it.street).put("postalCode", it.postalCode).put("city", it.city).put("taxNumber", it.taxNumber) })
    fun products(): List<Product> = read("products", ::productFromJson)
    fun saveProducts(values: List<Product>) = write("products", values.map { JSONObject().put("id", it.id).put("name", it.name).put("description", it.description).put("unitPriceCents", it.unitPriceCents) })

    fun businessProfile(): BusinessProfile {
        val json = prefs.getString("business_profile", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return BusinessProfile()
        return businessProfileFromJson(json)
    }

    fun saveBusinessProfile(profile: BusinessProfile) {
        prefs.edit().putString("business_profile", businessProfileJson(profile).toString()).apply()
    }

    private fun businessProfileJson(profile: BusinessProfile): JSONObject = JSONObject()
            .put("businessName", profile.businessName).put("street", profile.street)
            .put("postalCode", profile.postalCode).put("city", profile.city)
            .put("taxNumber", profile.taxNumber).put("vatId", profile.vatId)
            .put("invoicePrefix", profile.invoicePrefix).put("paymentTermsDays", profile.paymentTermsDays)
            .put("vatRatePercent", profile.vatRatePercent).put("email", profile.email)
            .put("contactName", profile.contactName).put("phone", profile.phone).put("iban", profile.iban)

    private fun businessProfileFromJson(json: JSONObject) = BusinessProfile(
        businessName = json.optString("businessName"), street = json.optString("street"),
        postalCode = json.optString("postalCode"), city = json.optString("city"),
        taxNumber = json.optString("taxNumber"), vatId = json.optString("vatId"),
        invoicePrefix = json.optString("invoicePrefix", "RE").ifBlank { "RE" },
        paymentTermsDays = json.optInt("paymentTermsDays", 14).coerceIn(1, 90),
        vatRatePercent = json.optInt("vatRatePercent", 19).coerceIn(0, 27),
        email = json.optString("email"), contactName = json.optString("contactName"),
        phone = json.optString("phone"), iban = json.optString("iban")
    )

    fun nextInvoiceNumber(): String {
        val year = LocalDate.now().year
        return nextInvoiceNumber(year, invoices().map { it.number }, businessProfile().invoicePrefix)
    }

    fun nextOfferNumber(): String = nextOfferNumber(LocalDate.now().year, offers().map { it.number })

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

private fun validIsoDate(value: String): Boolean = runCatching { LocalDate.parse(value) }.isSuccess

fun nextInvoiceNumber(year: Int, existingNumbers: List<String>, invoicePrefix: String = "RE"): String {
    val prefix = "${invoicePrefix.ifBlank { "RE" }}-$year-"
    val next = existingNumbers.mapNotNull { number -> number.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull() }.maxOrNull()?.plus(1) ?: 1
    return "$prefix${next.toString().padStart(4, '0')}"
}

fun nextOfferNumber(year: Int, existingNumbers: List<String>): String {
    val prefix = "ANG-$year-"
    val next = existingNumbers.mapNotNull { number -> number.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull() }.maxOrNull()?.plus(1) ?: 1
    return "$prefix${next.toString().padStart(4, '0')}"
}

fun parseEuroCents(raw: String): Long? {
    return parseEuroCentsAllowZero(raw)?.takeIf { it > 0 }
}

fun parseOptionalEuroCents(raw: String): Long? {
    if (raw.isBlank()) return null
    return parseEuroCentsAllowZero(raw)
}

private fun parseEuroCentsAllowZero(raw: String): Long? {
    val cleaned = raw.trim().replace("€", "").replace(" ", "")
    if (cleaned.count { it == ',' } > 1) return null
    val normalized = if (',' in cleaned) cleaned.replace(".", "").replace(',', '.') else cleaned
    val amount = normalized.toBigDecimalOrNull() ?: return null
    if (amount < java.math.BigDecimal.ZERO || amount.scale() > 2) return null
    return runCatching { amount.movePointRight(2).longValueExact() }.getOrNull()
}

fun formatEuro(cents: Long): String {
    val euros = cents / 100
    val remainder = kotlin.math.abs(cents % 100)
    return "${String.format(java.util.Locale.GERMANY, "%,d", euros)},${String.format(java.util.Locale.GERMANY, "%02d", remainder)} €"
}

data class ReceiptScan(
    val merchant: String?,
    val date: String?,
    val amountCents: Long?,
    val text: String
)

fun parseReceiptText(text: String): ReceiptScan {
    val lines = text.lines().map(String::trim).filter(String::isNotBlank)
    val dateRegex = Regex("\\b([0-3]?\\d)[./-]([01]?\\d)[./-](20\\d{2}|\\d{2})\\b")
    val date = lines.asSequence().mapNotNull { line ->
        dateRegex.find(line)?.let { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@let null
            val month = match.groupValues[2].toIntOrNull() ?: return@let null
            val yearRaw = match.groupValues[3].toIntOrNull() ?: return@let null
            val year = if (yearRaw < 100) 2000 + yearRaw else yearRaw
            runCatching { LocalDate.of(year, month, day).toString() }.getOrNull()
        }
    }.firstOrNull()
    val amountRegex = Regex("(?<!\\d)(\\d{1,3}(?:[ .]\\d{3})*(?:[,.]\\d{2})|\\d+[,.]\\d{2})(?!\\d)")
    val totalHints = Regex("(?i)gesamt|summe|total|brutto|zu zahlen|zahlbetrag|endbetrag|rechnungsbetrag")
    val candidates = lines.filterNot(dateRegex::containsMatchIn).flatMap { line ->
        amountRegex.findAll(line).mapNotNull { match -> parseEuroCents(match.value) }
            .map { cents -> cents to totalHints.containsMatchIn(line) }.toList()
    }
    val amount = candidates.filter { it.second }.maxOfOrNull { it.first }
        ?: candidates.maxOfOrNull { it.first }
    val merchant = lines.firstOrNull { line ->
        line.any(Char::isLetter) && line.count(Char::isLetter) >= 3 &&
            !dateRegex.containsMatchIn(line) && !totalHints.containsMatchIn(line) &&
            !Regex("(?i)rechnung|kassenbon|quittung|beleg|datum|uhrzeit|tel\\.?|www\\.|http").containsMatchIn(line)
    }?.take(80)
    return ReceiptScan(merchant, date, amount, text)
}
