package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate

class ExpensePaymentsTest {
    private val expense = Expense(id = "expense-1", merchant = "Office", category = "Büro", amountCents = 10_000)

    @Test fun recordsPartialPaymentsUsingActualPaymentDates() {
        val first = recordExpensePayment(expense, emptyList(), 4_000, LocalDate.parse("2026-12-31"))
        val second = recordExpensePayment(expense, listOf(first), 6_000, LocalDate.parse("2027-01-02"))

        assertEquals(4_000L, expensePaidCents(expense, listOf(first)))
        assertEquals(10_000L, expensePaidCents(expense, listOf(first, second)))
        assertEquals("2027-01-02", second.date)
    }

    @Test fun rejectsOverpaymentAndBankSourceWithoutTransactionReference() {
        val partial = recordExpensePayment(expense, emptyList(), 4_000, LocalDate.parse("2026-01-01"))

        assertFalse(runCatching { recordExpensePayment(expense, listOf(partial), 6_001, LocalDate.parse("2026-01-02")) }.isSuccess)
        assertFalse(runCatching { recordExpensePayment(expense, emptyList(), 1_000, LocalDate.parse("2026-01-02"), "Kontoauszug") }.isSuccess)
    }

    @Test fun recoversLegacyExactBankExpenseMatchesOnlyOnce() {
        val transaction = BankTransaction("a".repeat(64), "", "2026-04-03", "Office", "", -10_000, "ref", matchedExpenseId = expense.id)
        val recovered = recoverMatchedBankExpensePayments(listOf(expense), emptyList(), listOf(transaction, transaction))

        assertEquals(1, recovered.size)
        assertEquals("Kontoauszug", recovered.single().source)
        assertEquals(10_000L, recovered.single().amountCents)
        assertEquals(emptyList<ExpensePayment>(), recoverMatchedBankExpensePayments(listOf(expense), recovered, listOf(transaction)))
    }

    @Test fun cashOverviewUsesRecordedPaymentDatesInsteadOfDocumentDates() {
        val income = listOf(InvoicePayment(invoiceId = "invoice-1", amountCents = 8_000, date = "2027-01-02"))
        val outgoing = listOf(
            ExpensePayment(expenseId = expense.id, amountCents = 4_000, date = "2026-12-31"),
            ExpensePayment(expenseId = expense.id, amountCents = 6_000, date = "2027-01-02")
        )

        val totals = recordedCashYearTotals(2027, income, outgoing)

        assertEquals(8_000L, totals.paymentReceiptsCents)
        assertEquals(6_000L, totals.expensePaymentsCents)
        assertEquals(1, totals.receiptCount)
        assertEquals(1, totals.expensePaymentCount)
    }
}
