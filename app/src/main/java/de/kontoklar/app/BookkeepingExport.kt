package de.kontoklar.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate

fun shareBookkeepingCsv(context: Context, invoices: List<Invoice>, expenses: List<Expense>, bankTransactions: List<BankTransaction> = emptyList()) {
    val directory = File(context.cacheDir, "exports").apply { check(isDirectory || mkdirs()) { "Exportordner ist nicht verfügbar." } }
    val file = File(directory, "KontoKlar-Buchungen-${LocalDate.now()}.csv")
    FileOutputStream(file).bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.write("\uFEFF")
        writer.appendLine(listOf("Typ", "Nummer", "Kunde/Händler", "Beschreibung/Kategorie", "Datum", "Fällig", "Betrag (EUR)", "Status", "Notiz", "Nettobetrag (EUR)", "Ausgewiesene USt./Vorsteuer (EUR)").joinToString(";") { csvField(it) })
        invoices.sortedBy(Invoice::date).forEach { invoice ->
            writer.appendLine(invoiceCsvFields(invoice).joinToString(";") { csvField(it) })
        }
        expenses.sortedBy(Expense::date).forEach { expense ->
            writer.appendLine(expenseCsvFields(expense).joinToString(";") { csvField(it) })
        }
        bankTransactions.sortedBy(BankTransaction::date).forEach { transaction ->
            val accountHint = transaction.accountIban.takeLast(4).takeIf(String::isNotBlank)?.let { "Konto ••••$it" }.orEmpty()
            writer.appendLine(listOf(
                "Bankumsatz", transaction.reference, transaction.counterparty, transaction.description,
                transaction.date, "", centsAsGermanDecimal(kotlin.math.abs(transaction.amountCents)),
                if (transaction.amountCents > 0) "Eingang" else "Ausgang",
                listOf(accountHint, transaction.matchedInvoiceId?.let { "Rechnungs-ID $it" }.orEmpty()).filter(String::isNotBlank).joinToString(" · "), "", ""
            ).joinToString(";") { csvField(it) })
        }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_SUBJECT, "KontoKlar-Buchungen ${LocalDate.now()}")
        putExtra(Intent.EXTRA_TEXT, "Lokaler Datenexport – kein Steuerbericht und keine Steuererklärung.")
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Buchungen teilen"))
}

internal fun invoiceCsvFields(invoice: Invoice): List<String> {
    val amounts = invoice.vatRatePercent?.let { invoiceTaxBreakdown(invoice, it) }
    return listOf(
        "Rechnung", invoice.number, invoice.customer, invoiceLines(invoice).joinToString(" | ") { it.description }, invoice.date, invoice.dueDate,
        centsAsGermanDecimal(invoice.amountCents), invoice.status, "",
        amounts?.netCents?.let(::centsAsGermanDecimal).orEmpty(), amounts?.vatCents?.let(::centsAsGermanDecimal).orEmpty()
    )
}

internal fun expenseCsvFields(expense: Expense): List<String> {
    val netAmount = expense.inputVatCents?.let { expense.amountCents - it }?.let(::centsAsGermanDecimal).orEmpty()
    val vatAmount = expense.inputVatCents?.let(::centsAsGermanDecimal).orEmpty()
    return listOf("Ausgabe", "", expense.merchant, expense.category, expense.date, "", centsAsGermanDecimal(expense.amountCents), "Erfasst", expense.note, netAmount, vatAmount)
}

internal fun csvField(value: String): String {
    val safe = if (value.trimStart().firstOrNull() in setOf('=', '+', '-', '@')) "'$value" else value
    return "\"${safe.replace("\"", "\"\"")}\""
}

internal fun centsAsGermanDecimal(cents: Long): String =
    "${cents / 100},${kotlin.math.abs(cents % 100).toString().padStart(2, '0')}"
