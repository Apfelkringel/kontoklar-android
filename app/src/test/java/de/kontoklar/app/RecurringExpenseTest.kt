package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class RecurringExpenseTest {
    @Test fun monthlyDateRetainsAnchorAcrossShortMonth() {
        val plan = RecurringExpensePlan(merchant = "Miete", category = "Miete", amountCents = 10000,
            intervalMonths = 1, nextRunDate = "2026-01-31")
        assertEquals(LocalDate.parse("2026-02-28"), nextRecurringExpenseDate(plan))
        assertEquals(LocalDate.parse("2026-03-31"), nextRecurringExpenseDate(plan.copy(nextRunDate = "2026-02-28")))
    }

    @Test fun generatedExpenseUsesScheduledDateAndOnlyUserEnteredVat() {
        val plan = RecurringExpensePlan(merchant = "Hosting", category = "Software", amountCents = 11900,
            note = "Jahresplan", inputVatCents = 1900, intervalMonths = 12, nextRunDate = "2026-02-01")
        val expense = recurringExpenseFromPlan(plan, LocalDate.parse(plan.nextRunDate))
        assertEquals("2026-02-01", expense.date)
        assertEquals("Hosting", expense.merchant)
        assertEquals(1900L, expense.inputVatCents)
        assertEquals(null, expense.receiptUri)
    }

    @Test fun invalidAmountsVatAndIntervalsAreRejected() {
        val negativeAmount = RecurringExpensePlan(merchant = "Miete", category = "Miete", amountCents = 0,
            intervalMonths = 1, nextRunDate = "2026-01-01")
        assertThrows(IllegalArgumentException::class.java) { recurringExpenseFromPlan(negativeAmount, LocalDate.parse("2026-01-01")) }
        val excessVat = negativeAmount.copy(amountCents = 100, inputVatCents = 101)
        assertThrows(IllegalArgumentException::class.java) { recurringExpenseFromPlan(excessVat, LocalDate.parse("2026-01-01")) }
        val invalidInterval = negativeAmount.copy(amountCents = 100, intervalMonths = 2)
        assertThrows(IllegalArgumentException::class.java) { recurringExpenseFromPlan(invalidInterval, LocalDate.parse("2026-01-01")) }
    }
}
