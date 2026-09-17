package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceAmountsTest {
    @Test fun splitsGrossAmountIntoRoundedNetAndVat() {
        assertEquals(InvoiceAmountBreakdown(netCents = 10_000, vatCents = 1_900, grossCents = 11_900), invoiceAmountBreakdown(11_900, 19))
        assertEquals(InvoiceAmountBreakdown(netCents = 10_000, vatCents = 700, grossCents = 10_700), invoiceAmountBreakdown(10_700, 7))
    }

    @Test fun zeroRateKeepsWholeAmountAsNet() {
        assertEquals(InvoiceAmountBreakdown(netCents = 9_999, vatCents = 0, grossCents = 9_999), invoiceAmountBreakdown(9_999, 0))
    }

    @Test fun invoiceCsvUsesItsSavedRateInsteadOfAnUnknownCurrentProfile() {
        val saved = invoiceCsvFields(Invoice(customer = "A", description = "Work", amountCents = 11_900, vatRatePercent = 19))
        assertEquals("100,00", saved[9])
        assertEquals("19,00", saved[10])
        val legacy = invoiceCsvFields(Invoice(customer = "A", description = "Old work", amountCents = 11_900))
        assertEquals("", legacy[9])
        assertEquals("", legacy[10])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeGrossAmount() {
        invoiceAmountBreakdown(-1, 19)
    }

    @Test fun finalInvoiceRequiresAnImmutableTaxSnapshotAndCompleteParties() {
        val invoice = Invoice(number = "RE-1", customer = "Kundin", description = "Leistung", amountCents = 11_900, status = "Versendet")
        val profile = BusinessProfile(businessName = "Mein Betrieb", street = "Hauptstraße 1", postalCode = "10115", city = "Berlin", taxNumber = "12/345/67890")

        val errors = finalInvoiceValidationErrors(invoice, profile)

        assertTrue(errors.any { it.contains("Empfänger") })
        assertTrue(errors.any { it.contains("Steuersatz", ignoreCase = true) })
    }

    @Test fun completeFinalInvoicePassesPdfValidation() {
        val invoice = Invoice(
            number = "RE-2", customer = "Kundin", customerAddress = "Kundenweg 2\n10117 Berlin",
            description = "Leistung", amountCents = 11_900, status = "Versendet", vatRatePercent = 19
        )
        val profile = BusinessProfile(businessName = "Mein Betrieb", street = "Hauptstraße 1", postalCode = "10115", city = "Berlin", taxNumber = "12/345/67890")

        assertTrue(finalInvoiceValidationErrors(invoice, profile).isEmpty())
    }
}
