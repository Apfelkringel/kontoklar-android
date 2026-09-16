package de.kontoklar.app

import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

data class RecurringInvoicePlan(
    val id: String = UUID.randomUUID().toString(),
    val templateInvoiceId: String,
    val customer: String,
    val customerId: String? = null,
    val customerAddress: String = "",
    val customerEmail: String = "",
    val lines: List<InvoiceLine>,
    val intervalMonths: Int,
    val nextRunDate: String,
    val anchorDay: Int,
    val paymentTermsDays: Int,
    val vatRatePercent: Int?,
    val active: Boolean = true
) {
    val amountCents: Long get() = lines.fold(0L) { total, line -> Math.addExact(total, line.amountCents) }
    val description: String get() = lines.joinToString(" · ") { it.description }
}

fun nextRecurringDate(current: LocalDate, intervalMonths: Int, anchorDay: Int): LocalDate {
    require(intervalMonths in setOf(1, 3, 12))
    require(anchorDay in 1..31)
    val month = YearMonth.from(current).plusMonths(intervalMonths.toLong())
    return month.atDay(anchorDay.coerceAtMost(month.lengthOfMonth()))
}

fun recurringInvoiceDraft(plan: RecurringInvoicePlan, number: String, issueDate: LocalDate): Invoice {
    require(plan.lines.isNotEmpty() && plan.lines.size <= 20)
    require(plan.lines.all { it.description.isNotBlank() && it.amountCents > 0 })
    require(plan.intervalMonths in setOf(1, 3, 12))
    require(plan.paymentTermsDays in 1..90)
    require(plan.vatRatePercent == null || plan.vatRatePercent in 0..27)
    return Invoice(
        number = number,
        customer = plan.customer,
        description = plan.description,
        amountCents = plan.amountCents,
        customerId = plan.customerId,
        customerAddress = plan.customerAddress,
        customerEmail = plan.customerEmail,
        date = issueDate.toString(),
        serviceDate = issueDate.toString(),
        dueDate = issueDate.plusDays(plan.paymentTermsDays.toLong()).toString(),
        status = "Entwurf",
        vatRatePercent = plan.vatRatePercent,
        lines = plan.lines.toList()
    )
}

fun recurringPlanFromInvoice(
    invoice: Invoice,
    intervalMonths: Int,
    nextRunDate: LocalDate,
    paymentTermsDays: Int
): RecurringInvoicePlan {
    require(intervalMonths in setOf(1, 3, 12))
    require(paymentTermsDays in 1..90)
    require(invoice.amountCents > 0 && invoice.customer.isNotBlank())
    val lines = invoiceLines(invoice)
    require(lines.size <= 20 && lines.all { it.description.isNotBlank() && it.amountCents > 0 })
    require(lines.fold(0L) { total, line -> Math.addExact(total, line.amountCents) } == invoice.amountCents)
    return RecurringInvoicePlan(
        templateInvoiceId = invoice.id,
        customer = invoice.customer,
        customerId = invoice.customerId,
        customerAddress = invoice.customerAddress,
        customerEmail = invoice.customerEmail,
        lines = lines,
        intervalMonths = intervalMonths,
        nextRunDate = nextRunDate.toString(),
        anchorDay = nextRunDate.dayOfMonth,
        paymentTermsDays = paymentTermsDays,
        vatRatePercent = invoice.vatRatePercent,
        active = true
    )
}
