package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptScanTest {
    @Test fun customerAddressIsFormattedForInvoiceUse() {
        assertEquals("Hauptstraße 4\n10115 Berlin", Customer(name = "Mira", street = "Hauptstraße 4", postalCode = "10115", city = "Berlin").postalAddress)
        assertEquals("Berlin", Customer(name = "Mira", city = "Berlin").postalAddress)
    }

    @Test fun extractsMerchantDateAndSuggestedTotal() {
        val result = parseReceiptText("Bäckerei Morgenrot\nKassenbon\nDatum: 14.09.2026\nZwischensumme 8,50\nGesamtbetrag 9,20 EUR")
        assertEquals("Bäckerei Morgenrot", result.merchant)
        assertEquals("2026-09-14", result.date)
        assertEquals(920L, result.amountCents)
    }

    @Test fun rejectsImpossibleReceiptDateAndLeavesUnknownFieldsEmpty() {
        val result = parseReceiptText("Kassenbon\nDatum 31.02.2026\nVielen Dank")
        assertNull(result.date)
        assertNull(result.amountCents)
        assertEquals("Vielen Dank", result.merchant)
    }

    @Test fun readsIsoLikeSeparatedDateAndGermanThousandsAmount() {
        val result = parseReceiptText("Supermarkt Nord\n02/09/26\nZu zahlen: 1.234,56")
        assertEquals("2026-09-02", result.date)
        assertEquals(123456L, result.amountCents)
    }
}
