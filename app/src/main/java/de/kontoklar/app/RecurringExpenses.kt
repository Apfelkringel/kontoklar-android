package de.kontoklar.app

import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

data class RecurringExpensePlan(
    val id: String = UUID.randomUUID().toString(),
    val merchant: String,
    val category: String,
    val amountCents: Long,
    val note: String = "",
    val inputVatCents: Long? = null,
    val intervalMonths: Int,
    val nextRunDate: String,
    val anchorDay: Int = LocalDate.parse(nextRunDate).dayOfMonth,
    val active: Boolean = true
)

fun recurringExpenseFromPlan(plan: RecurringExpensePlan, date: LocalDate): Expense {
    require(plan.merchant.isNotBlank() && plan.category.isNotBlank())
    require(plan.amountCents > 0 && (plan.inputVatCents == null || plan.inputVatCents in 0..plan.amountCents))
    require(plan.intervalMonths in setOf(1, 3, 12) && plan.anchorDay in 1..31)
    return Expense(merchant = plan.merchant, category = plan.category, amountCents = plan.amountCents,
        date = date.toString(), note = plan.note, inputVatCents = plan.inputVatCents)
}

fun nextRecurringExpenseDate(plan: RecurringExpensePlan): LocalDate {
    require(plan.intervalMonths in setOf(1, 3, 12) && plan.anchorDay in 1..31)
    val month = YearMonth.from(LocalDate.parse(plan.nextRunDate)).plusMonths(plan.intervalMonths.toLong())
    return month.atDay(plan.anchorDay.coerceAtMost(month.lengthOfMonth()))
}
