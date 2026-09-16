package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class RecurringInvoiceTest {
    @Test fun monthlyDateKeepsOriginalBillingDayAcrossShortMonths() {
        val february = nextRecurringDate(LocalDate.parse("2026-01-31"), 1, 31)
        assertEquals(LocalDate.parse("2026-02-28"), february)
        assertEquals(LocalDate.parse("2026-03-31"), nextRecurringDate(february, 1, 31))
    }

    @Test fun quarterlyScheduleHandlesLeapYearAndAnnualInterval() {
        assertEquals(LocalDate.parse("2024-02-29"), nextRecurringDate(LocalDate.parse("2023-11-29"), 3, 29))
        assertEquals(LocalDate.parse("2025-02-28"), nextRecurringDate(LocalDate.parse("2024-02-29"), 12, 29))
    }

    @Test fun recurringDraftCopiesSavedCustomerLinesAndTaxAndUsesScheduledDate() {
        val plan = RecurringInvoicePlan(
            templateInvoiceId = "source", customer = "Muster GmbH", customerId = "customer-1",
            customerAddress = "Musterstraße 1", customerEmail = "rechnung@example.test",
            lines = listOf(InvoiceLine("Wartung", 12000), InvoiceLine("Hosting", 3000)),
            intervalMonths = 3, nextRunDate = "2026-04-15", anchorDay = 15,
            paymentTermsDays = 21, vatRatePercent = 19
        )
        val invoice = recurringInvoiceDraft(plan, "RE-2026-0002", LocalDate.parse(plan.nextRunDate))
        assertEquals("Entwurf", invoice.status)
        assertEquals("Muster GmbH", invoice.customer)
        assertEquals("customer-1", invoice.customerId)
        assertEquals(15000L, invoice.amountCents)
        assertEquals("2026-04-15", invoice.date)
        assertEquals("2026-05-06", invoice.dueDate)
        assertEquals(19, invoice.vatRatePercent)
        assertEquals(2, invoice.lines.size)
    }

    @Test fun unsupportedIntervalsAndInvalidTemplatesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            nextRecurringDate(LocalDate.parse("2026-01-01"), 2, 1)
        }
        val invalid = RecurringInvoicePlan(
            templateInvoiceId = "source", customer = "Kunde", lines = listOf(InvoiceLine("", 100)),
            intervalMonths = 1, nextRunDate = "2026-01-01", anchorDay = 1,
            paymentTermsDays = 14, vatRatePercent = 19
        )
        assertThrows(IllegalArgumentException::class.java) {
            recurringInvoiceDraft(invalid, "RE-2026-0001", LocalDate.parse("2026-01-01"))
        }
    }
}
