package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class InvoicePaymentsTest {
    @Test fun partialPaymentsKeepTheirIndividualDatesAndUpdateTheInvoiceBalance() {
        val invoice = Invoice(id = "invoice-1", number = "RE-1", customer = "Kunde", description = "Service", amountCents = 10_000, status = "Versendet")
        val first = recordInvoicePayment(invoice, 3_000, LocalDate.parse("2026-03-05"))
        val second = recordInvoicePayment(first.invoice, 2_000, LocalDate.parse("2026-03-18"))

        assertEquals(3_000L, first.payment.amountCents)
        assertEquals("2026-03-05", first.payment.date)
        assertEquals(2_000L, second.payment.amountCents)
        assertEquals("2026-03-18", second.payment.date)
        assertEquals(5_000L, second.invoice.paidCents)
        assertEquals(5_000L, invoiceOutstandingCents(second.invoice))
        assertEquals("Teilbezahlt", second.invoice.status)
    }

    @Test fun bankPaymentRetainsTransactionIdAndSourceForReconciliation() {
        val invoice = Invoice(id = "invoice-2", number = "RE-2", customer = "Kunde", description = "Service", amountCents = 5_000, status = "Versendet")
        val result = recordInvoicePayment(invoice, 5_000, LocalDate.parse("2026-04-02"), "Kontoauszug", "tx-1")

        assertEquals("Kontoauszug", result.payment.source)
        assertEquals("tx-1", result.payment.bankTransactionId)
        assertEquals("Bezahlt", result.invoice.status)
    }

    @Test fun draftsOverpaymentsAndIncompleteBankSourcesAreRejected() {
        val draft = Invoice(id = "draft", customer = "Kunde", description = "Service", amountCents = 1_000)
        assertThrows(IllegalArgumentException::class.java) { recordInvoicePayment(draft, 100, LocalDate.parse("2026-01-01")) }
        val issued = draft.copy(id = "sent", status = "Versendet")
        assertThrows(IllegalArgumentException::class.java) { recordInvoicePayment(issued, 1_001, LocalDate.parse("2026-01-01")) }
        assertThrows(IllegalArgumentException::class.java) { recordInvoicePayment(issued, 100, LocalDate.parse("2026-01-01"), "Kontoauszug") }
        assertThrows(IllegalArgumentException::class.java) { recordInvoicePayment(issued, 100, LocalDate.parse("2026-01-01"), "Manuell", "tx-1") }
    }

    @Test fun existingPaidTotalsAreNotGivenInventedHistoricalDates() {
        val legacy = Invoice(id = "legacy", customer = "Kunde", description = "Service", amountCents = 10_000, status = "Teilbezahlt", paidCents = 6_000)
        val newReceipt = recordInvoicePayment(legacy, 1_000, LocalDate.parse("2026-05-01"))

        assertEquals("2026-05-01", newReceipt.payment.date)
        assertEquals(1_000L, newReceipt.payment.amountCents)
        assertEquals(7_000L, newReceipt.invoice.paidCents)
    }

    @Test fun matchedBankPaymentsRecoverTheirOriginalDateAndDoNotDuplicate() {
        val invoice = Invoice(id = "recovered", customer = "Kunde", description = "Service", amountCents = 5_000,
            status = "Bezahlt", paidCents = 5_000)
        val first = BankTransaction("bank-1", "DE00", "2026-02-03", "Kunde", "Abschlag", 2_000, "R-1", invoice.id)
        val second = BankTransaction("bank-2", "DE00", "2026-02-11", "Kunde", "Rest", 3_000, "R-2", invoice.id)

        val recovered = recoverMatchedBankPayments(listOf(invoice), emptyList(), listOf(first, second))

        assertEquals(listOf("2026-02-03", "2026-02-11"), recovered.map(InvoicePayment::date))
        assertEquals(listOf(2_000L, 3_000L), recovered.map(InvoicePayment::amountCents))
        assertEquals(emptyList<InvoicePayment>(), recoverMatchedBankPayments(listOf(invoice), recovered, listOf(first, second)))
    }

    @Test fun recoveryIgnoresUnmatchedInvalidAndAmountsBeyondRecordedPaidTotal() {
        val invoice = Invoice(id = "recovered", customer = "Kunde", description = "Service", amountCents = 5_000,
            status = "Teilbezahlt", paidCents = 2_000)
        val valid = BankTransaction("bank-1", "DE00", "2026-02-03", "Kunde", "Zahlung", 2_000, "R-1", invoice.id)
        val unmatched = valid.copy(id = "bank-2", matchedInvoiceId = null)
        val invalidDate = valid.copy(id = "bank-3", date = "03.02.2026")

        assertEquals(listOf("bank-1"), recoverMatchedBankPayments(listOf(invoice), emptyList(), listOf(valid, unmatched, invalidDate)).mapNotNull(InvoicePayment::bankTransactionId))
        assertEquals(emptyList<InvoicePayment>(), recoverMatchedBankPayments(listOf(invoice), emptyList(), listOf(valid.copy(amountCents = 2_001))))
    }
}
