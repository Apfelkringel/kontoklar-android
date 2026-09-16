package de.kontoklar.app

import java.time.LocalDate
import java.util.UUID

data class InvoicePayment(
    val id: String = UUID.randomUUID().toString(),
    val invoiceId: String,
    val amountCents: Long,
    val date: String,
    val source: String = "Manuell",
    val bankTransactionId: String? = null
)

data class RecordedInvoicePayment(val invoice: Invoice, val payment: InvoicePayment)

fun recordInvoicePayment(
    invoice: Invoice,
    amountCents: Long,
    date: LocalDate,
    source: String = "Manuell",
    bankTransactionId: String? = null
): RecordedInvoicePayment {
    require(source in setOf("Manuell", "Kontoauszug")) { "Die Zahlungsquelle ist ungültig." }
    require((source == "Kontoauszug") == (bankTransactionId != null)) { "Die Kontoauszugs-Zuordnung ist unvollständig." }
    val updated = applyInvoicePayment(invoice, amountCents)
    return RecordedInvoicePayment(updated, InvoicePayment(
        invoiceId = invoice.id,
        amountCents = amountCents,
        date = date.toString(),
        source = source,
        bankTransactionId = bankTransactionId
    ))
}

fun recoverMatchedBankPayments(
    invoices: List<Invoice>,
    payments: List<InvoicePayment>,
    transactions: List<BankTransaction>
): List<InvoicePayment> {
    val knownIds = payments.mapNotNullTo(mutableSetOf(), InvoicePayment::bankTransactionId)
    val remainingByInvoice = invoices.associate { invoice ->
        invoice.id to (invoice.paidCents - payments.filter { it.invoiceId == invoice.id }.sumOf(InvoicePayment::amountCents)).coerceAtLeast(0L)
    }.toMutableMap()
    val invoicesById = invoices.associateBy(Invoice::id)
    return transactions.asSequence()
        .filter { it.matchedInvoiceId != null && it.id !in knownIds && it.amountCents > 0 && runCatching { LocalDate.parse(it.date) }.isSuccess }
        .mapNotNull { transaction ->
            val invoiceId = transaction.matchedInvoiceId ?: return@mapNotNull null
            val invoice = invoicesById[invoiceId] ?: return@mapNotNull null
            val available = remainingByInvoice[invoiceId] ?: 0L
            if (transaction.amountCents > available) return@mapNotNull null
            remainingByInvoice[invoiceId] = available - transaction.amountCents
            InvoicePayment(invoiceId = invoice.id, amountCents = transaction.amountCents, date = transaction.date,
                source = "Kontoauszug", bankTransactionId = transaction.id)
        }.toList()
}
