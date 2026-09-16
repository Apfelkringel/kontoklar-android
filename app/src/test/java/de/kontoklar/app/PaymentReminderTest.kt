package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PaymentReminderTest {
    private val today = LocalDate.parse("2026-09-16")

    @Test fun reminderIsAvailableOnlyForOverdueIssuedInvoiceWithBalance() {
        val base = Invoice(number = "RE-2026-0001", customer = "Muster GmbH", description = "Beratung", amountCents = 11900,
            dueDate = "2026-09-15", status = "Versendet")
        assertTrue(paymentReminderEligible(base, today))
        assertFalse(paymentReminderEligible(base.copy(dueDate = today.toString()), today))
        assertFalse(paymentReminderEligible(base.copy(dueDate = "2026-09-17"), today))
        assertFalse(paymentReminderEligible(base.copy(status = "Entwurf"), today))
        assertFalse(paymentReminderEligible(base.copy(status = "Bezahlt", paidCents = 11900), today))
        assertFalse(paymentReminderEligible(base.copy(dueDate = "bad-date"), today))
    }

    @Test fun emailDraftUsesRemainingBalanceAndUserProvidedPaymentDetails() {
        val invoice = Invoice(number = "RE-2026-0001", customer = "Muster GmbH", description = "Beratung", amountCents = 11900,
            dueDate = "2026-09-10", status = "Teilbezahlt", paidCents = 5000, customerEmail = "pay@example.test")
        val (subject, body) = paymentReminderEmail(invoice, BusinessProfile(businessName = "Beispiel Studio", iban = "DE02120300000000202051"))
        assertEquals("Zahlungserinnerung · Rechnung RE-2026-0001", subject)
        assertTrue(body.contains("69,00 €"))
        assertTrue(body.contains("die Zahlung am 10.09.2026 fällig"))
        assertTrue(body.contains("IBAN: DE02120300000000202051"))
        assertTrue(body.contains("Verwendungszweck: Rechnung RE-2026-0001"))
        assertTrue(body.contains("Falls Sie die Zahlung bereits veranlasst haben"))
    }

    @Test fun paidOrDraftInvoiceCannotCreateReminderAndNoBalanceIsNotEligible() {
        val invoice = Invoice(number = "R-1", customer = "Kunde", description = "Leistung", amountCents = 1000,
            dueDate = "2026-09-01", status = "Entwurf")
        assertThrows(IllegalArgumentException::class.java) { paymentReminderEmail(invoice, BusinessProfile()) }
    }
}
