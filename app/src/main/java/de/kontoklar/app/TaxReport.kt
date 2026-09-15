package de.kontoklar.app

import java.time.LocalDate

data class TaxYearReport(
    val year: Int,
    val issuedInvoiceCents: Long,
    val issuedInvoiceCount: Int,
    val expenseCents: Long,
    val documentedInputVatCents: Long,
    val netExpenseCentsWithVatBreakdown: Long,
    val expensesWithoutVatBreakdownCount: Int,
    val recordedDifferenceCents: Long,
    val openInvoiceCents: Long,
    val overdueInvoiceCount: Int,
    val missingReceiptCount: Int
)

fun taxYearReport(
    year: Int,
    invoices: List<Invoice>,
    expenses: List<Expense>,
    today: LocalDate = LocalDate.now()
): TaxYearReport {
    val issuedInvoices = invoices.filter { invoice ->
        invoice.status != "Entwurf" && runCatching { LocalDate.parse(invoice.date).year == year }.getOrDefault(false)
    }
    val yearExpenses = expenses.filter { expense ->
        runCatching { LocalDate.parse(expense.date).year == year }.getOrDefault(false)
    }
    val openInvoices = issuedInvoices.filter { it.status != "Bezahlt" }
    val overdueInvoices = openInvoices.filter { invoice ->
        runCatching { LocalDate.parse(invoice.dueDate).isBefore(today) }.getOrDefault(false)
    }
    val invoiceTotal = issuedInvoices.sumOf { it.amountCents }
    val expenseTotal = yearExpenses.sumOf { it.amountCents }
    val expensesWithVat = yearExpenses.filter { it.inputVatCents != null }
    return TaxYearReport(
        year = year,
        issuedInvoiceCents = invoiceTotal,
        issuedInvoiceCount = issuedInvoices.size,
        expenseCents = expenseTotal,
        documentedInputVatCents = expensesWithVat.sumOf { it.inputVatCents ?: 0L },
        netExpenseCentsWithVatBreakdown = expensesWithVat.sumOf { it.amountCents - (it.inputVatCents ?: 0L) },
        expensesWithoutVatBreakdownCount = yearExpenses.count { it.inputVatCents == null },
        recordedDifferenceCents = invoiceTotal - expenseTotal,
        openInvoiceCents = openInvoices.sumOf { it.amountCents },
        overdueInvoiceCount = overdueInvoices.size,
        missingReceiptCount = yearExpenses.count { it.receiptUri.isNullOrBlank() }
    )
}
