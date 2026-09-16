package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TaxReportTest {
    @Test fun monthlyDashboardTrendUsesSelectedYearAndExcludesInvoiceDrafts() {
        val invoices = listOf(
            Invoice(customer = "A", description = "Work", amountCents = 10_000, date = "2026-01-12", status = "Versendet"),
            Invoice(customer = "B", description = "Work", amountCents = 7_000, date = "2026-01-18", status = "Entwurf"),
            Invoice(customer = "C", description = "Work", amountCents = 5_000, date = "2025-12-31", status = "Bezahlt")
        )
        val expenses = listOf(
            Expense(merchant = "Office", category = "Büro", amountCents = 2_500, date = "2026-01-15"),
            Expense(merchant = "Old", category = "Büro", amountCents = 9_000, date = "2025-12-31"),
            Expense(merchant = "Invalid", category = "Sonstiges", amountCents = 50, date = "invalid")
        )

        val trend = financialYearTrend(2026, invoices, expenses)

        assertEquals(12, trend.size)
        assertEquals(FinancialMonthTotal(1, 10_000, 2_500), trend.first())
        assertEquals(FinancialMonthTotal(12, 0, 0), trend.last())
    }

    @Test fun openInvoiceTotalUsesUnpaidRemainderAfterPartialPayment() {
        val partial = Invoice(number = "R-1", customer = "A", description = "Service", amountCents = 10_000, date = "2026-03-01", dueDate = "2026-03-02", status = "Teilbezahlt", paidCents = 2_500)
        val report = taxYearReport(2026, listOf(partial), emptyList(), LocalDate.parse("2026-03-05"))

        assertEquals(7_500L, report.openInvoiceCents)
        assertEquals(1, report.overdueInvoiceCount)
    }

    @Test fun yearlyOverviewExcludesDraftsAndSeparatesOpenAndOverdueInvoices() {
        val invoices = listOf(
            Invoice(number = "RE-1", customer = "A", description = "Work", amountCents = 10000, date = "2026-01-02", dueDate = "2026-02-02", status = "Bezahlt"),
            Invoice(number = "RE-2", customer = "B", description = "Work", amountCents = 5000, date = "2026-03-02", dueDate = "2026-03-20", status = "Versendet"),
            Invoice(number = "RE-3", customer = "C", description = "Work", amountCents = 7000, date = "2026-03-03", dueDate = "2026-04-01", status = "Entwurf"),
            Invoice(number = "RE-4", customer = "D", description = "Work", amountCents = 4000, date = "2025-12-31", dueDate = "2026-01-20", status = "Versendet")
        )
        val expenses = listOf(
            Expense(merchant = "Office", category = "Büro", amountCents = 2500, date = "2026-06-01"),
            Expense(merchant = "Travel", category = "Reise", amountCents = 1000, date = "2025-12-31")
        )

        val report = taxYearReport(2026, invoices, expenses, today = LocalDate.parse("2026-04-02"))

        assertEquals(15000L, report.issuedInvoiceCents)
        assertEquals(2, report.issuedInvoiceCount)
        assertEquals(2500L, report.expenseCents)
        assertEquals(12500L, report.recordedDifferenceCents)
        assertEquals(5000L, report.openInvoiceCents)
        assertEquals(1, report.overdueInvoiceCount)
        assertEquals(1, report.missingReceiptCount)
    }

    @Test fun invalidDatesDoNotCrashOrEnterYearTotals() {
        val invalidInvoice = Invoice(customer = "A", description = "Work", amountCents = 1000, date = "not-a-date")
        val invalidExpense = Expense(merchant = "B", category = "Sonstiges", amountCents = 200, date = "")

        val report = taxYearReport(2026, listOf(invalidInvoice), listOf(invalidExpense))

        assertEquals(0L, report.issuedInvoiceCents)
        assertEquals(0L, report.expenseCents)
        assertEquals(0, report.missingReceiptCount)
    }

    @Test fun reportsOnlyDocumentedInputVatAndCountsExpensesWithoutBreakdown() {
        val expenses = listOf(
            Expense(merchant = "Office", category = "Büro", amountCents = 11_900, date = "2026-06-01", inputVatCents = 1_900),
            Expense(merchant = "Cash", category = "Sonstiges", amountCents = 500, date = "2026-06-02")
        )

        val report = taxYearReport(2026, emptyList(), expenses)

        assertEquals(1_900L, report.documentedInputVatCents)
        assertEquals(10_000L, report.netExpenseCentsWithVatBreakdown)
        assertEquals(1, report.expensesWithoutVatBreakdownCount)
    }

    @Test fun reportsOutputVatOnlyForIssuedInvoicesWithSavedRate() {
        val invoices = listOf(
            Invoice(number = "RE-1", customer = "A", description = "Work", amountCents = 11_900, date = "2026-06-01", status = "Versendet", vatRatePercent = 19),
            Invoice(number = "RE-2", customer = "B", description = "Old work", amountCents = 11_900, date = "2026-06-02", status = "Bezahlt")
        )

        val report = taxYearReport(2026, invoices, emptyList())

        assertEquals(1_900L, report.documentedOutputVatCents)
        assertEquals(10_000L, report.netInvoiceCentsWithVatSnapshot)
        assertEquals(1, report.invoicesWithoutVatRateCount)
    }

    @Test fun sumsSavedTaxFromEachInvoiceLine() {
        val invoice = Invoice(
            number = "RE-3", customer = "A", description = "Beratung · Implementierung", amountCents = 35_700,
            date = "2026-06-01", status = "Versendet", vatRatePercent = 19,
            lines = listOf(InvoiceLine("Beratung", 11_900), InvoiceLine("Implementierung", 23_800))
        )

        val report = taxYearReport(2026, listOf(invoice), emptyList())

        assertEquals(5_700L, report.documentedOutputVatCents)
        assertEquals(30_000L, report.netInvoiceCentsWithVatSnapshot)
    }

    @Test fun groupsOnlySelectedYearExpensesByUserEnteredCategoryInDescendingValueOrder() {
        val expenses = listOf(
            Expense(merchant = "Server", category = "Software", amountCents = 7000, date = "2026-01-02"),
            Expense(merchant = "Cloud", category = "Software", amountCents = 3000, date = "2026-05-04"),
            Expense(merchant = "Train", category = "Reisekosten", amountCents = 8000, date = "2026-03-20"),
            Expense(merchant = "Other", category = " ", amountCents = 500, date = "2026-12-31"),
            Expense(merchant = "Old", category = "Software", amountCents = 50_000, date = "2025-12-31")
        )

        val totals = taxYearReport(2026, emptyList(), expenses).expenseByCategory

        assertEquals(listOf("Software", "Reisekosten", "Ohne Kategorie"), totals.map(ExpenseCategoryTotal::category))
        assertEquals(listOf(10_000L, 8000L, 500L), totals.map(ExpenseCategoryTotal::amountCents))
        assertEquals(listOf(2, 1, 1), totals.map(ExpenseCategoryTotal::count))
    }

    @Test fun groupsExpensePaymentEventsByPaymentYearAndRecordedCategory() {
        val expenses = listOf(
            Expense(id = "e1", merchant = "Phone", category = "Software", amountCents = 10_000, date = "2025-12-20"),
            Expense(id = "e2", merchant = "Office", category = "", amountCents = 5_000, date = "2026-01-20")
        )
        val payments = listOf(
            ExpensePayment(expenseId = "e1", amountCents = 4_000, date = "2026-01-02"),
            ExpensePayment(expenseId = "e1", amountCents = 6_000, date = "2025-12-30"),
            ExpensePayment(expenseId = "e2", amountCents = 2_000, date = "2026-02-01"),
            ExpensePayment(expenseId = "deleted", amountCents = 700, date = "2026-02-02"),
            ExpensePayment(expenseId = "e2", amountCents = 900, date = "not-a-date")
        )

        val totals = cashExpenseYearByCategory(2026, expenses, payments)

        assertEquals(listOf("Software", "Ohne Kategorie", "Nicht zugeordnet"), totals.map(CashExpenseCategoryTotal::category))
        assertEquals(listOf(4_000L, 2_000L, 700L), totals.map(CashExpenseCategoryTotal::paidCents))
        assertEquals(listOf(1, 1, 1), totals.map(CashExpenseCategoryTotal::paymentCount))
    }
}
