package de.kontoklar.app

import java.time.LocalDate

data class TaxYearReport(
    val year: Int,
    val issuedInvoiceCents: Long,
    val issuedInvoiceCount: Int,
    val documentedOutputVatCents: Long,
    val netInvoiceCentsWithVatSnapshot: Long,
    val invoicesWithoutVatRateCount: Int,
    val expenseCents: Long,
    val documentedInputVatCents: Long,
    val netExpenseCentsWithVatBreakdown: Long,
    val expensesWithoutVatBreakdownCount: Int,
    val recordedDifferenceCents: Long,
    val openInvoiceCents: Long,
    val overdueInvoiceCount: Int,
    val missingReceiptCount: Int,
    val expenseByCategory: List<ExpenseCategoryTotal>
)

data class ExpenseCategoryTotal(val category: String, val amountCents: Long, val count: Int)
data class FinancialMonthTotal(val month: Int, val issuedInvoiceCents: Long, val expenseCents: Long)
data class RecordedCashYearTotals(val paymentReceiptsCents: Long, val expensePaymentsCents: Long, val receiptCount: Int, val expensePaymentCount: Int)
data class CashExpenseCategoryTotal(val category: String, val paidCents: Long, val paymentCount: Int)

/** Groups recorded expense payment events by their event year, never by receipt/document date. */
fun cashExpenseYearByCategory(year: Int, expenses: List<Expense>, payments: List<ExpensePayment>): List<CashExpenseCategoryTotal> {
    val categories = expenses.associateBy(Expense::id)
    return payments.asSequence()
        .filter { payment -> runCatching { LocalDate.parse(payment.date).year == year }.getOrDefault(false) }
        .groupBy { payment ->
            categories[payment.expenseId]?.category?.trim()?.takeIf(String::isNotBlank) ?:
                if (payment.expenseId in categories) "Ohne Kategorie" else "Nicht zugeordnet"
        }
        .map { (category, records) -> CashExpenseCategoryTotal(category, records.sumOf(ExpensePayment::amountCents), records.size) }
        .sortedWith(compareByDescending<CashExpenseCategoryTotal> { it.paidCents }.thenBy { it.category.lowercase() })
}

/** Totals only explicitly dated payment events; it is not an EÜR and omits tax-specific corrections and asset treatment. */
fun recordedCashYearTotals(
    year: Int,
    invoicePayments: List<InvoicePayment>,
    expensePayments: List<ExpensePayment>
): RecordedCashYearTotals {
    val receipts = invoicePayments.filter { runCatching { LocalDate.parse(it.date).year == year }.getOrDefault(false) }
    val payments = expensePayments.filter { runCatching { LocalDate.parse(it.date).year == year }.getOrDefault(false) }
    return RecordedCashYearTotals(
        paymentReceiptsCents = receipts.sumOf(InvoicePayment::amountCents),
        expensePaymentsCents = payments.sumOf(ExpensePayment::amountCents),
        receiptCount = receipts.size,
        expensePaymentCount = payments.size
    )
}

/** Groups recorded gross invoice and expense amounts by document date; this is not a cash-flow or tax report. */
fun financialYearTrend(year: Int, invoices: List<Invoice>, expenses: List<Expense>): List<FinancialMonthTotal> {
    val invoiceTotals = LongArray(12)
    val expenseTotals = LongArray(12)
    invoices.asSequence().filter { it.status != "Entwurf" }.forEach { invoice ->
        runCatching { LocalDate.parse(invoice.date) }.getOrNull()?.takeIf { it.year == year }
            ?.let { invoiceTotals[it.monthValue - 1] += invoice.amountCents }
    }
    expenses.forEach { expense ->
        runCatching { LocalDate.parse(expense.date) }.getOrNull()?.takeIf { it.year == year }
            ?.let { expenseTotals[it.monthValue - 1] += expense.amountCents }
    }
    return (0 until 12).map { month -> FinancialMonthTotal(month + 1, invoiceTotals[month], expenseTotals[month]) }
}

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
    val expensesByCategory = yearExpenses.groupBy { it.category.trim().ifBlank { "Ohne Kategorie" } }
        .map { (category, records) -> ExpenseCategoryTotal(category, records.sumOf(Expense::amountCents), records.size) }
        .sortedWith(compareByDescending<ExpenseCategoryTotal> { it.amountCents }.thenBy { it.category.lowercase() })
    val invoiceAmountsWithVat = issuedInvoices.mapNotNull { invoice ->
        invoice.vatRatePercent?.let { invoiceTaxBreakdown(invoice, it) }
    }
    return TaxYearReport(
        year = year,
        issuedInvoiceCents = invoiceTotal,
        issuedInvoiceCount = issuedInvoices.size,
        documentedOutputVatCents = invoiceAmountsWithVat.sumOf { it.vatCents },
        netInvoiceCentsWithVatSnapshot = invoiceAmountsWithVat.sumOf { it.netCents },
        invoicesWithoutVatRateCount = issuedInvoices.count { it.vatRatePercent == null },
        expenseCents = expenseTotal,
        documentedInputVatCents = expensesWithVat.sumOf { it.inputVatCents ?: 0L },
        netExpenseCentsWithVatBreakdown = expensesWithVat.sumOf { it.amountCents - (it.inputVatCents ?: 0L) },
        expensesWithoutVatBreakdownCount = yearExpenses.count { it.inputVatCents == null },
        recordedDifferenceCents = invoiceTotal - expenseTotal,
        openInvoiceCents = openInvoices.sumOf(::invoiceOutstandingCents),
        overdueInvoiceCount = overdueInvoices.size,
        missingReceiptCount = yearExpenses.count { it.receiptUri.isNullOrBlank() },
        expenseByCategory = expensesByCategory
    )
}
