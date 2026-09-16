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
    val status: String = "Entwurf",
    val vatRatePercent: Int? = null,
    val lines: List<InvoiceLine> = emptyList(),
    val paidCents: Long = 0L
)

fun invoiceOutstandingCents(invoice: Invoice): Long = if (invoice.status == "Bezahlt") 0L else (invoice.amountCents - invoice.paidCents.coerceAtLeast(0)).coerceAtLeast(0)

fun applyInvoicePayment(invoice: Invoice, paymentCents: Long): Invoice {
    require(invoice.status != "Entwurf") { "Ein Rechnungsentwurf kann keine Zahlung erhalten." }
    val outstanding = invoiceOutstandingCents(invoice)
    require(paymentCents > 0 && paymentCents <= outstanding) { "Der Zahlungseingang muss positiv sein und darf den offenen Restbetrag nicht überschreiten." }
    val paid = Math.addExact(invoice.paidCents.coerceAtLeast(0), paymentCents)
    return invoice.copy(paidCents = paid, status = if (paid == invoice.amountCents) "Bezahlt" else "Teilbezahlt")
}

data class InvoiceLine(val description: String, val amountCents: Long)

fun invoiceLines(invoice: Invoice): List<InvoiceLine> = invoice.lines.ifEmpty {
    listOf(InvoiceLine(invoice.description, invoice.amountCents))
}

fun invoiceTaxBreakdown(invoice: Invoice, vatRatePercent: Int): InvoiceAmountBreakdown {
    val lines = invoiceLines(invoice)
    if (invoice.lines.isEmpty()) return invoiceAmountBreakdown(invoice.amountCents, vatRatePercent)
    val totals = lines.fold(InvoiceAmountBreakdown(0, 0, 0)) { sum, line ->
        val amounts = invoiceAmountBreakdown(line.amountCents, vatRatePercent)
        InvoiceAmountBreakdown(
            netCents = Math.addExact(sum.netCents, amounts.netCents),
            vatCents = Math.addExact(sum.vatCents, amounts.vatCents),
            grossCents = Math.addExact(sum.grossCents, amounts.grossCents)
        )
    }
    require(totals.grossCents == invoice.amountCents) { "Die Positionssumme entspricht nicht dem Rechnungsbetrag." }
    return totals
}

fun invoiceVatRoundingIsConsistent(invoice: Invoice, vatRatePercent: Int): Boolean = runCatching {
    invoiceTaxBreakdown(invoice, vatRatePercent) == invoiceAmountBreakdown(invoice.amountCents, vatRatePercent)
}.getOrDefault(false)

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
    val convertedInvoiceId: String? = null,
    val lines: List<InvoiceLine> = emptyList()
)

fun offerLines(offer: Offer): List<InvoiceLine> = offer.lines.ifEmpty {
    listOf(InvoiceLine(offer.description, offer.amountCents))
}

fun offerStatus(offer: Offer, today: LocalDate = LocalDate.now()): String =
    if (offer.status == "Versendet" && runCatching { LocalDate.parse(offer.validUntil).isBefore(today) }.getOrDefault(false)) "Abgelaufen" else offer.status

fun Offer.toInvoice(number: String, paymentTermsDays: Int, vatRatePercent: Int? = null): Invoice = Invoice(
    number = number,
    customer = customer,
    description = description,
    amountCents = amountCents,
    customerId = customerId,
    customerAddress = customerAddress,
    customerEmail = customerEmail,
    dueDate = LocalDate.now().plusDays(paymentTermsDays.coerceIn(1, 90).toLong()).toString(),
    vatRatePercent = vatRatePercent,
    lines = lines
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
    val iban: String = "",
    val activity: String = "",
    val legalForm: String = ""
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
    private val prefs = SecureLocalPreferences(context)

    fun exportSnapshot(): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("businessProfile", businessProfileJson(businessProfile()))
        .put("invoices", storedArray("invoices"))
        .put("invoicePayments", storedArray("invoice_payments"))
        .put("offers", storedArray("offers"))
        .put("expenses", storedArray("expenses"))
        .put("bankTransactions", storedArray("bank_transactions"))
        .put("customers", storedArray("customers"))
        .put("products", storedArray("products"))
        .put("taxDeadlines", storedArray("tax_deadlines"))
        .put("recurringInvoices", storedArray("recurring_invoices"))
        .put("recurringExpenses", storedArray("recurring_expenses"))

    fun restoreSnapshot(snapshot: JSONObject) {
        require(snapshot.optInt("schemaVersion") == 1) { "Diese Sicherungsversion wird nicht unterstützt." }
        val importedInvoices = decodeArray(snapshot.getJSONArray("invoices"), ::invoiceFromJson)
        val importedInvoicePayments = decodeArray(snapshot.optJSONArray("invoicePayments") ?: JSONArray(), ::invoicePaymentFromJson)
        val importedOffers = decodeArray(snapshot.getJSONArray("offers"), ::offerFromJson)
        val importedExpenses = decodeArray(snapshot.getJSONArray("expenses"), ::expenseFromJson)
        val importedBankTransactions = decodeArray(snapshot.optJSONArray("bankTransactions") ?: JSONArray(), ::bankTransactionFromJson)
        val importedCustomers = decodeArray(snapshot.getJSONArray("customers"), ::customerFromJson)
        val importedProducts = decodeArray(snapshot.optJSONArray("products") ?: JSONArray(), ::productFromJson)
        val importedTaxDeadlines = decodeArray(snapshot.optJSONArray("taxDeadlines") ?: JSONArray(), ::taxDeadlineFromJson)
        val importedRecurringPlans = decodeArray(snapshot.optJSONArray("recurringInvoices") ?: JSONArray(), ::recurringInvoicePlanFromJson)
        val importedRecurringExpenses = decodeArray(snapshot.optJSONArray("recurringExpenses") ?: JSONArray(), ::recurringExpensePlanFromJson)
        val importedProfile = businessProfileFromJson(snapshot.getJSONObject("businessProfile"))
        require(importedInvoices.all { it.amountCents > 0 && it.customer.isNotBlank() && it.description.isNotBlank() && it.paidCents in 0..it.amountCents }) { "Die Sicherung enthält ungültige Rechnungen oder Zahlungsstände." }
        require(importedInvoices.all { it.vatRatePercent == null || it.vatRatePercent in 0..27 }) { "Die Sicherung enthält einen ungültigen Umsatzsteuersatz für eine Rechnung." }
        require(importedInvoices.all { invoice -> invoice.lines.isEmpty() || (invoice.lines.size <= 20 && invoice.lines.all { it.description.isNotBlank() && it.amountCents > 0 } && runCatching { invoice.lines.fold(0L) { total, line -> Math.addExact(total, line.amountCents) } == invoice.amountCents }.getOrDefault(false)) }) { "Die Sicherung enthält ungültige Rechnungspositionen." }
        require(importedOffers.all { it.amountCents > 0 && it.customer.isNotBlank() && it.description.isNotBlank() }) { "Die Sicherung enthält ungültige Angebote." }
        require(importedOffers.all { offer -> offer.lines.isEmpty() || (offer.lines.size <= 20 && offer.lines.all { it.description.isNotBlank() && it.amountCents > 0 } && runCatching { offer.lines.fold(0L) { total, line -> Math.addExact(total, line.amountCents) } == offer.amountCents }.getOrDefault(false)) }) { "Die Sicherung enthält ungültige Angebotspositionen." }
        require(importedExpenses.all { it.amountCents > 0 && it.merchant.isNotBlank() && (it.inputVatCents == null || it.inputVatCents in 0..it.amountCents) }) { "Die Sicherung enthält ungültige Ausgaben oder Umsatzsteuerangaben." }
        require(importedBankTransactions.all { it.amountCents != 0L && validIsoDate(it.date) && it.id.matches(Regex("[a-f0-9]{64}")) }) { "Die Sicherung enthält ungültige Bankumsätze." }
        require(importedCustomers.all { it.name.isNotBlank() }) { "Die Sicherung enthält ungültige Kundendaten." }
        require(importedProducts.all { it.name.isNotBlank() && it.unitPriceCents > 0 }) { "Die Sicherung enthält ungültige Produkte oder Dienstleistungen." }
        require(importedTaxDeadlines.all { it.id.isNotBlank() && it.title.isNotBlank() && it.title.length <= 120 && it.note.length <= 500 && validIsoDate(it.dueDate) }) { "Die Sicherung enthält ungültige Steuertermine." }
        require(importedRecurringPlans.all { plan ->
            plan.id.isNotBlank() && plan.customer.isNotBlank() && plan.intervalMonths in setOf(1, 3, 12) &&
                plan.paymentTermsDays in 1..90 && plan.anchorDay in 1..31 && validIsoDate(plan.nextRunDate) &&
                plan.lines.size in 1..20 && plan.lines.all { it.description.isNotBlank() && it.amountCents > 0 } &&
                runCatching { plan.lines.fold(0L) { total, line -> Math.addExact(total, line.amountCents) } == plan.amountCents }.getOrDefault(false) &&
                (plan.vatRatePercent == null || plan.vatRatePercent in 0..27)
        }) { "Die Sicherung enthält ungültige wiederkehrende Rechnungen." }
        require(importedRecurringExpenses.all { plan ->
            plan.id.isNotBlank() && plan.merchant.isNotBlank() && plan.merchant.length <= 200 &&
                plan.category.isNotBlank() && plan.category.length <= 120 && plan.note.length <= 2000 &&
                plan.amountCents > 0 && (plan.inputVatCents == null || plan.inputVatCents in 0..plan.amountCents) &&
                plan.intervalMonths in setOf(1, 3, 12) && plan.anchorDay in 1..31 && validIsoDate(plan.nextRunDate)
        }) { "Die Sicherung enthält ungültige wiederkehrende Ausgaben." }
        require(importedInvoices.map { it.id }.distinct().size == importedInvoices.size && importedInvoices.all { validIsoDate(it.date) && validIsoDate(it.serviceDate) && validIsoDate(it.dueDate) }) { "Die Sicherung enthält doppelte Rechnungen oder ungültige Rechnungsdaten." }
        require(importedOffers.map { it.id }.distinct().size == importedOffers.size && importedOffers.all { validIsoDate(it.date) && validIsoDate(it.validUntil) }) { "Die Sicherung enthält doppelte Angebote oder ungültige Angebotsdaten." }
        require(importedExpenses.map { it.id }.distinct().size == importedExpenses.size && importedExpenses.all { validIsoDate(it.date) }) { "Die Sicherung enthält doppelte Ausgaben oder ungültige Ausgabedaten." }
        require(importedBankTransactions.map { it.id }.distinct().size == importedBankTransactions.size) { "Die Sicherung enthält doppelte Bankumsätze." }
        require(importedCustomers.map { it.id }.distinct().size == importedCustomers.size) { "Die Sicherung enthält doppelte Kunden." }
        require(importedProducts.map { it.id }.distinct().size == importedProducts.size) { "Die Sicherung enthält doppelte Produkte." }
        require(importedTaxDeadlines.map { it.id }.distinct().size == importedTaxDeadlines.size) { "Die Sicherung enthält doppelte Steuertermine." }
        require(importedRecurringPlans.map { it.id }.distinct().size == importedRecurringPlans.size) { "Die Sicherung enthält doppelte wiederkehrende Rechnungen." }
        require(importedRecurringExpenses.map { it.id }.distinct().size == importedRecurringExpenses.size) { "Die Sicherung enthält doppelte wiederkehrende Ausgaben." }
        require(importedInvoicePayments.all { payment ->
            payment.id.isNotBlank() && payment.invoiceId in importedInvoices.map(Invoice::id).toSet() &&
                payment.amountCents > 0 && validIsoDate(payment.date) && payment.source in setOf("Manuell", "Kontoauszug") &&
                ((payment.source == "Kontoauszug") == (payment.bankTransactionId != null))
        }) { "Die Sicherung enthält ungültige Zahlungseingänge." }
        require(importedInvoicePayments.map { it.id }.distinct().size == importedInvoicePayments.size &&
            importedInvoicePayments.mapNotNull { it.bankTransactionId }.distinct().size == importedInvoicePayments.count { it.bankTransactionId != null }) {
            "Die Sicherung enthält doppelte Zahlungseingänge oder Kontoauszugszuordnungen."
        }
        require(importedInvoicePayments.groupBy(InvoicePayment::invoiceId).all { (invoiceId, payments) ->
            val invoice = importedInvoices.first { it.id == invoiceId }
            runCatching { payments.fold(0L) { sum, payment -> Math.addExact(sum, payment.amountCents) } <= invoice.paidCents }.getOrDefault(false)
        }) { "Die Zahlungseingänge der Sicherung überschreiten den gespeicherten Rechnungszahlungsstand." }
        require(importedInvoicePayments.filter { it.source == "Kontoauszug" }.all { payment ->
            importedBankTransactions.any { transaction -> transaction.id == payment.bankTransactionId && transaction.matchedInvoiceId == payment.invoiceId && transaction.amountCents == payment.amountCents && transaction.date == payment.date }
        }) { "Eine Zahlungseingang-Zuordnung passt nicht zum importierten Kontoauszug." }

        check(prefs.putStrings(mapOf(
            "invoices" to snapshot.getJSONArray("invoices").toString(),
            "invoice_payments" to (snapshot.optJSONArray("invoicePayments") ?: JSONArray()).toString(),
            "offers" to snapshot.getJSONArray("offers").toString(),
            "expenses" to snapshot.getJSONArray("expenses").toString(),
            "bank_transactions" to (snapshot.optJSONArray("bankTransactions") ?: JSONArray()).toString(),
            "customers" to snapshot.getJSONArray("customers").toString(),
            "products" to (snapshot.optJSONArray("products") ?: JSONArray()).toString(),
            "tax_deadlines" to (snapshot.optJSONArray("taxDeadlines") ?: JSONArray()).toString(),
            "recurring_invoices" to (snapshot.optJSONArray("recurringInvoices") ?: JSONArray()).toString(),
            "recurring_expenses" to (snapshot.optJSONArray("recurringExpenses") ?: JSONArray()).toString(),
            "business_profile" to businessProfileJson(importedProfile).toString()
        ))) { "Die wiederhergestellten Daten konnten nicht dauerhaft gespeichert werden." }
    }

    private fun storedArray(key: String): JSONArray = prefs.getString(key, null)?.let(::JSONArray) ?: JSONArray()

    private fun <T> decodeArray(array: JSONArray, decode: (JSONObject) -> T): List<T> =
        List(array.length()) { decode(array.getJSONObject(it)) }

    private fun invoiceFromJson(it: JSONObject) = Invoice(
        id = it.optString("id", UUID.randomUUID().toString()), number = it.optString("number", ""),
        customer = it.optString("customer", ""), description = it.optString("description", ""),
        amountCents = it.optLong("amountCents", 0), customerId = it.optString("customerId").takeIf(String::isNotBlank),
        customerAddress = it.optString("customerAddress"), customerEmail = it.optString("customerEmail"),
        date = it.optString("date"), serviceDate = it.optString("serviceDate", it.optString("date")), dueDate = it.optString("dueDate"), status = it.optString("status", "Entwurf"),
        vatRatePercent = it.takeUnless { json -> json.isNull("vatRatePercent") }?.optInt("vatRatePercent"),
        lines = it.optJSONArray("lines")?.let { array -> List(array.length()) { index ->
            val line = array.getJSONObject(index)
            InvoiceLine(line.optString("description"), line.optLong("amountCents"))
        } }.orEmpty(),
        paidCents = it.optLong("paidCents", 0L)
    )

    private fun offerFromJson(it: JSONObject) = Offer(
        id = it.optString("id", UUID.randomUUID().toString()), number = it.optString("number"),
        customer = it.optString("customer"), description = it.optString("description"), amountCents = it.optLong("amountCents"),
        customerId = it.optString("customerId").takeIf(String::isNotBlank), customerAddress = it.optString("customerAddress"),
        customerEmail = it.optString("customerEmail"), date = it.optString("date"), validUntil = it.optString("validUntil"),
        status = it.optString("status", "Entwurf"), convertedInvoiceId = it.optString("convertedInvoiceId").takeIf(String::isNotBlank),
        lines = it.optJSONArray("lines")?.let { array -> List(array.length()) { index ->
            val line = array.getJSONObject(index)
            InvoiceLine(line.optString("description"), line.optLong("amountCents"))
        } }.orEmpty()
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
        matchedInvoiceId = it.optString("matchedInvoiceId").takeIf(String::isNotBlank),
        matchedExpenseId = it.optString("matchedExpenseId").takeIf(String::isNotBlank)
    )

    private fun customerFromJson(it: JSONObject) = Customer(
        id = it.optString("id", UUID.randomUUID().toString()), name = it.optString("name"), email = it.optString("email"),
        street = it.optString("street"), postalCode = it.optString("postalCode"), city = it.optString("city"), taxNumber = it.optString("taxNumber")
    )

    private fun productFromJson(it: JSONObject) = Product(
        id = it.optString("id", UUID.randomUUID().toString()), name = it.optString("name"),
        description = it.optString("description"), unitPriceCents = it.optLong("unitPriceCents")
    )

    private fun recurringInvoicePlanFromJson(it: JSONObject) = RecurringInvoicePlan(
        id = it.optString("id", UUID.randomUUID().toString()),
        templateInvoiceId = it.optString("templateInvoiceId"), customer = it.optString("customer"),
        customerId = it.optString("customerId").takeIf(String::isNotBlank),
        customerAddress = it.optString("customerAddress"), customerEmail = it.optString("customerEmail"),
        lines = it.optJSONArray("lines")?.let { array -> List(array.length()) { index ->
            val line = array.getJSONObject(index)
            InvoiceLine(line.optString("description"), line.optLong("amountCents"))
        } }.orEmpty(),
        intervalMonths = it.optInt("intervalMonths"), nextRunDate = it.optString("nextRunDate"),
        anchorDay = it.optInt("anchorDay"), paymentTermsDays = it.optInt("paymentTermsDays"),
        vatRatePercent = it.takeUnless { json -> json.isNull("vatRatePercent") }?.optInt("vatRatePercent"),
        active = it.optBoolean("active", true)
    )

    private fun recurringInvoicePlanToJson(plan: RecurringInvoicePlan) = JSONObject()
        .put("id", plan.id).put("templateInvoiceId", plan.templateInvoiceId)
        .put("customer", plan.customer).put("customerId", plan.customerId)
        .put("customerAddress", plan.customerAddress).put("customerEmail", plan.customerEmail)
        .put("lines", JSONArray().apply { plan.lines.forEach { put(JSONObject().put("description", it.description).put("amountCents", it.amountCents)) } })
        .put("intervalMonths", plan.intervalMonths).put("nextRunDate", plan.nextRunDate)
        .put("anchorDay", plan.anchorDay).put("paymentTermsDays", plan.paymentTermsDays)
        .put("vatRatePercent", plan.vatRatePercent ?: JSONObject.NULL).put("active", plan.active)

    private fun recurringExpensePlanFromJson(it: JSONObject) = RecurringExpensePlan(
        id = it.optString("id", UUID.randomUUID().toString()), merchant = it.optString("merchant"),
        category = it.optString("category"), amountCents = it.optLong("amountCents"),
        note = it.optString("note"), inputVatCents = it.takeUnless { json -> json.isNull("inputVatCents") }?.optLong("inputVatCents"),
        intervalMonths = it.optInt("intervalMonths"), nextRunDate = it.optString("nextRunDate"),
        anchorDay = it.optInt("anchorDay"), active = it.optBoolean("active", true)
    )

    private fun recurringExpensePlanToJson(plan: RecurringExpensePlan) = JSONObject()
        .put("id", plan.id).put("merchant", plan.merchant).put("category", plan.category)
        .put("amountCents", plan.amountCents).put("note", plan.note)
        .put("inputVatCents", plan.inputVatCents ?: JSONObject.NULL).put("intervalMonths", plan.intervalMonths)
        .put("nextRunDate", plan.nextRunDate).put("anchorDay", plan.anchorDay).put("active", plan.active)

    private fun invoicePaymentFromJson(json: JSONObject) = InvoicePayment(
        id = json.optString("id", UUID.randomUUID().toString()),
        invoiceId = json.optString("invoiceId"),
        amountCents = json.optLong("amountCents"),
        date = json.optString("date"),
        source = json.optString("source", "Manuell"),
        bankTransactionId = json.optString("bankTransactionId").takeIf(String::isNotBlank)
    )

    private fun invoicePaymentToJson(payment: InvoicePayment) = JSONObject()
        .put("id", payment.id).put("invoiceId", payment.invoiceId).put("amountCents", payment.amountCents)
        .put("date", payment.date).put("source", payment.source)
        .put("bankTransactionId", payment.bankTransactionId ?: JSONObject.NULL)

    private fun bankTransactionToJson(transaction: BankTransaction) = JSONObject()
        .put("id", transaction.id).put("accountIban", transaction.accountIban).put("date", transaction.date)
        .put("counterparty", transaction.counterparty).put("description", transaction.description)
        .put("amountCents", transaction.amountCents).put("reference", transaction.reference)
        .put("matchedInvoiceId", transaction.matchedInvoiceId).put("matchedExpenseId", transaction.matchedExpenseId)

    private fun expenseToJson(expense: Expense) = JSONObject()
        .put("id", expense.id).put("merchant", expense.merchant).put("category", expense.category)
        .put("amountCents", expense.amountCents).put("date", expense.date).put("note", expense.note)
        .put("receiptUri", expense.receiptUri).put("inputVatCents", expense.inputVatCents ?: JSONObject.NULL)

    private fun invoiceToJson(invoice: Invoice) = JSONObject()
        .put("id", invoice.id).put("number", invoice.number).put("customer", invoice.customer)
        .put("customerId", invoice.customerId).put("customerAddress", invoice.customerAddress)
        .put("customerEmail", invoice.customerEmail).put("description", invoice.description)
        .put("amountCents", invoice.amountCents).put("date", invoice.date)
        .put("serviceDate", invoice.serviceDate).put("dueDate", invoice.dueDate)
        .put("status", invoice.status).put("paidCents", invoice.paidCents)
        .put("vatRatePercent", invoice.vatRatePercent ?: JSONObject.NULL)
        .put("lines", JSONArray().apply { invoice.lines.forEach { put(JSONObject().put("description", it.description).put("amountCents", it.amountCents)) } })

    private fun taxDeadlineFromJson(it: JSONObject) = TaxDeadline(
        id = it.optString("id", UUID.randomUUID().toString()), title = it.optString("title"),
        dueDate = it.optString("dueDate"), note = it.optString("note"), completed = it.optBoolean("completed")
    )

    fun invoices(): List<Invoice> = read("invoices", ::invoiceFromJson)
    fun invoicePayments(): List<InvoicePayment> = read("invoice_payments", ::invoicePaymentFromJson)

    fun offers(): List<Offer> = read("offers", ::offerFromJson)

    fun expenses(): List<Expense> = read("expenses", ::expenseFromJson)
    fun bankTransactions(): List<BankTransaction> = read("bank_transactions", ::bankTransactionFromJson)

    fun saveInvoices(values: List<Invoice>) = write("invoices", values.map(::invoiceToJson))
    fun saveInvoicePayments(values: List<InvoicePayment>) = write("invoice_payments", values.map(::invoicePaymentToJson))
    fun saveOffers(values: List<Offer>) = write("offers", values.map { JSONObject().put("id", it.id).put("number", it.number).put("customer", it.customer).put("customerId", it.customerId).put("customerAddress", it.customerAddress).put("customerEmail", it.customerEmail).put("description", it.description).put("amountCents", it.amountCents).put("date", it.date).put("validUntil", it.validUntil).put("status", it.status).put("convertedInvoiceId", it.convertedInvoiceId).put("lines", JSONArray().apply { it.lines.forEach { line -> put(JSONObject().put("description", line.description).put("amountCents", line.amountCents)) } }) })
    fun saveExpenses(values: List<Expense>) = write("expenses", values.map(::expenseToJson))
    fun saveBankTransactions(values: List<BankTransaction>) = write("bank_transactions", values.map(::bankTransactionToJson))

    fun recordInvoicePayment(
        invoiceId: String,
        amountCents: Long,
        date: LocalDate = LocalDate.now(),
        source: String = "Manuell",
        bankTransactionId: String? = null
    ): Invoice {
        val currentInvoices = invoices()
        val invoice = currentInvoices.firstOrNull { it.id == invoiceId } ?: error("Die Rechnung ist nicht mehr vorhanden.")
        val currentPayments = invoicePayments()
        if (bankTransactionId != null) {
            require(currentPayments.none { it.bankTransactionId == bankTransactionId }) { "Dieser Kontoauszug wurde bereits als Zahlung erfasst." }
        }
        val recorded = de.kontoklar.app.recordInvoicePayment(invoice, amountCents, date, source, bankTransactionId)
        val updatedInvoices = currentInvoices.upsertInvoice(recorded.invoice)
        val values = mutableMapOf(
            "invoices" to JSONArray(updatedInvoices.map(::invoiceToJson)).toString(),
            "invoice_payments" to JSONArray(currentPayments + recorded.payment).toString()
        )
        if (bankTransactionId != null) {
            val transactions = bankTransactions()
            val transaction = transactions.firstOrNull { it.id == bankTransactionId } ?: error("Der Bankumsatz ist nicht mehr vorhanden.")
            require(transaction.amountCents == amountCents && transaction.amountCents > 0 && transaction.date == date.toString()) {
                "Der Bankumsatz passt nicht zum erfassten Zahlungseingang."
            }
            require(transaction.matchedExpenseId == null && (transaction.matchedInvoiceId == null || transaction.matchedInvoiceId == invoiceId)) {
                "Der Bankumsatz ist bereits einer anderen Buchung zugeordnet."
            }
            val updatedTransactions = transactions.map { if (it.id == bankTransactionId) it.copy(matchedInvoiceId = invoiceId) else it }
            values["bank_transactions"] = JSONArray(updatedTransactions.map(::bankTransactionToJson)).toString()
        }
        check(prefs.putStrings(values)) { "Rechnung und Zahlungseingang konnten nicht dauerhaft gespeichert werden." }
        return recorded.invoice
    }

    fun migrateMatchedInvoicePayments(): Int {
        val payments = invoicePayments()
        val migrated = recoverMatchedBankPayments(invoices(), payments, bankTransactions())
        if (migrated.isEmpty()) return 0
        check(prefs.putStrings(mapOf("invoice_payments" to JSONArray(payments + migrated).toString()))) {
            "Historische Bankzahlungen konnten nicht übernommen werden."
        }
        return migrated.size
    }
    fun customers(): List<Customer> = read("customers") { Customer(id = it.optString("id", UUID.randomUUID().toString()), name = it.optString("name"), email = it.optString("email"), street = it.optString("street"), postalCode = it.optString("postalCode"), city = it.optString("city"), taxNumber = it.optString("taxNumber")) }
    fun saveCustomers(values: List<Customer>) = write("customers", values.map { JSONObject().put("id", it.id).put("name", it.name).put("email", it.email).put("street", it.street).put("postalCode", it.postalCode).put("city", it.city).put("taxNumber", it.taxNumber) })
    fun products(): List<Product> = read("products", ::productFromJson)
    fun saveProducts(values: List<Product>) = write("products", values.map { JSONObject().put("id", it.id).put("name", it.name).put("description", it.description).put("unitPriceCents", it.unitPriceCents) })
    fun taxDeadlines(): List<TaxDeadline> = read("tax_deadlines", ::taxDeadlineFromJson)
    fun saveTaxDeadlines(values: List<TaxDeadline>) = write("tax_deadlines", values.map { JSONObject().put("id", it.id).put("title", it.title).put("dueDate", it.dueDate).put("note", it.note).put("completed", it.completed) })
    fun recurringInvoicePlans(): List<RecurringInvoicePlan> = read("recurring_invoices", ::recurringInvoicePlanFromJson)
    fun saveRecurringInvoicePlans(values: List<RecurringInvoicePlan>) = write("recurring_invoices", values.map(::recurringInvoicePlanToJson))
    fun recurringExpensePlans(): List<RecurringExpensePlan> = read("recurring_expenses", ::recurringExpensePlanFromJson)
    fun saveRecurringExpensePlans(values: List<RecurringExpensePlan>) = write("recurring_expenses", values.map(::recurringExpensePlanToJson))

    fun generateNextRecurringExpense(planId: String, today: LocalDate = LocalDate.now()): Expense {
        val currentPlans = recurringExpensePlans()
        val plan = currentPlans.firstOrNull { it.id == planId } ?: error("Die Ausgabevorlage ist nicht mehr vorhanden.")
        require(plan.active) { "Diese Ausgabevorlage ist pausiert." }
        val scheduledDate = LocalDate.parse(plan.nextRunDate)
        require(!scheduledDate.isAfter(today)) { "Diese wiederkehrende Ausgabe ist noch nicht fällig." }
        val expense = recurringExpenseFromPlan(plan, scheduledDate)
        val updatedPlans = currentPlans.map { if (it.id == planId) it.copy(nextRunDate = nextRecurringExpenseDate(plan).toString()) else it }
        val updatedExpenses = expenses().upsertExpense(expense)
        check(prefs.putStrings(mapOf(
            "expenses" to JSONArray(updatedExpenses.map(::expenseToJson)).toString(),
            "recurring_expenses" to JSONArray(updatedPlans.map(::recurringExpensePlanToJson)).toString()
        ))) { "Ausgabe und Folgetermin konnten nicht dauerhaft gespeichert werden." }
        return expense
    }

    fun generateNextRecurringInvoice(planId: String, today: LocalDate = LocalDate.now()): Invoice {
        val currentPlans = recurringInvoicePlans()
        val plan = currentPlans.firstOrNull { it.id == planId } ?: error("Die Rechnungsvorlage ist nicht mehr vorhanden.")
        require(plan.active) { "Diese Rechnungsvorlage ist pausiert." }
        val scheduledDate = LocalDate.parse(plan.nextRunDate)
        require(!scheduledDate.isAfter(today)) { "Diese wiederkehrende Rechnung ist noch nicht fällig." }
        val currentInvoices = invoices()
        val prefix = businessProfile().invoicePrefix
        val number = nextInvoiceNumber(scheduledDate.year, currentInvoices.map { it.number }, prefix)
        val invoice = recurringInvoiceDraft(plan, number, scheduledDate)
        val updatedPlan = plan.copy(nextRunDate = nextRecurringDate(scheduledDate, plan.intervalMonths, plan.anchorDay).toString())
        val updatedPlans = currentPlans.map { if (it.id == planId) updatedPlan else it }
        val updatedInvoices = currentInvoices.upsertInvoice(invoice)
        check(prefs.putStrings(mapOf(
            "invoices" to JSONArray(updatedInvoices.map(::invoiceToJson)).toString(),
            "recurring_invoices" to JSONArray(updatedPlans.map(::recurringInvoicePlanToJson)).toString()
        ))) { "Rechnungsentwurf und Folgetermin konnten nicht dauerhaft gespeichert werden." }
        return invoice
    }

    fun businessProfile(): BusinessProfile {
        val json = prefs.getString("business_profile", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return BusinessProfile()
        return businessProfileFromJson(json)
    }

    fun saveBusinessProfile(profile: BusinessProfile) {
        prefs.putString("business_profile", businessProfileJson(profile).toString())
    }

    private fun businessProfileJson(profile: BusinessProfile): JSONObject = JSONObject()
            .put("businessName", profile.businessName).put("street", profile.street)
            .put("postalCode", profile.postalCode).put("city", profile.city)
            .put("taxNumber", profile.taxNumber).put("vatId", profile.vatId)
            .put("invoicePrefix", profile.invoicePrefix).put("paymentTermsDays", profile.paymentTermsDays)
            .put("vatRatePercent", profile.vatRatePercent).put("email", profile.email)
        .put("contactName", profile.contactName).put("phone", profile.phone).put("iban", profile.iban)
        .put("activity", profile.activity).put("legalForm", profile.legalForm)

    private fun businessProfileFromJson(json: JSONObject) = BusinessProfile(
        businessName = json.optString("businessName"), street = json.optString("street"),
        postalCode = json.optString("postalCode"), city = json.optString("city"),
        taxNumber = json.optString("taxNumber"), vatId = json.optString("vatId"),
        invoicePrefix = json.optString("invoicePrefix", "RE").ifBlank { "RE" },
        paymentTermsDays = json.optInt("paymentTermsDays", 14).coerceIn(1, 90),
        vatRatePercent = json.optInt("vatRatePercent", 19).coerceIn(0, 27),
        email = json.optString("email"), contactName = json.optString("contactName"),
        phone = json.optString("phone"), iban = json.optString("iban"),
        activity = json.optString("activity"), legalForm = json.optString("legalForm")
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
        prefs.putString(key, JSONArray(values).toString())
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
    val suggestedCategory: String?,
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
    return ReceiptScan(merchant, date, amount, suggestExpenseCategory(merchant, text), text)
}
