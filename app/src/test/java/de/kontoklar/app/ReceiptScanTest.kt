package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ReceiptScanTest {
    @Test fun expenseEditReplacesRecordWithoutDuplicatingAndDeleteRemovesIt() {
        val original = Expense(id = "expense-1", merchant = "Cafe", category = "Büro", amountCents = 1000)
        val other = Expense(id = "expense-2", merchant = "Bahn", category = "Reisekosten", amountCents = 2000)
        val edited = original.copy(merchant = "Café", amountCents = 1250)

        val saved = listOf(original, other).upsertExpense(edited)
        assertEquals(2, saved.size)
        assertEquals(edited, saved.first { it.id == original.id })
        assertEquals(listOf(other), saved.withoutExpense(original.id))
    }

    @Test fun invoiceDraftEditKeepsOneRecordAndDeleteUsesStableId() {
        val original = Invoice(id = "invoice-1", number = "RE-2026-0001", customer = "Mira", description = "Design", amountCents = 12000)
        val edited = original.copy(description = "Brand design", amountCents = 13500)

        val saved = listOf(original).upsertInvoice(edited)

        assertEquals(1, saved.size)
        assertEquals(edited, saved.single())
        assertEquals(emptyList<Invoice>(), saved.withoutInvoice(original.id))
    }

    @Test fun productCatalogUpdatesAndDeletesByStableId() {
        val product = Product(id = "product-1", name = "Beratung", unitPriceCents = 15000)
        val edited = product.copy(name = "Beratung (Stunde)", unitPriceCents = 17500)
        val saved = listOf(product).upsertProduct(edited)

        assertEquals(1, saved.size)
        assertEquals(edited, saved.single())
        assertEquals(emptyList<Product>(), saved.withoutProduct(product.id))
    }

    @Test fun offerNumbersAreSequentialPerYear() {
        assertEquals("ANG-2026-0003", nextOfferNumber(2026, listOf("ANG-2026-0001", "ANG-2026-0002", "ANG-2025-0009")))
    }

    @Test fun sentOfferExpiresAndAcceptedOfferDoesNot() {
        val today = LocalDate.of(2026, 9, 15)
        assertEquals("Abgelaufen", offerStatus(Offer(customer = "A", description = "B", amountCents = 100, validUntil = "2026-09-14", status = "Versendet"), today))
        assertEquals("Angenommen", offerStatus(Offer(customer = "A", description = "B", amountCents = 100, validUntil = "2026-09-14", status = "Angenommen"), today))
    }

    @Test fun acceptedOfferConvertsWithCustomerAndAmountSnapshot() {
        val offer = Offer(customer = "Mira", description = "Design", amountCents = 12345, customerId = "customer-1", customerAddress = "Berlin", customerEmail = "mira@example.com")
        val invoice = offer.toInvoice("RE-2026-0001", 21)
        assertEquals("Mira", invoice.customer)
        assertEquals("Design", invoice.description)
        assertEquals(12345L, invoice.amountCents)
        assertEquals("customer-1", invoice.customerId)
        assertEquals("Berlin", invoice.customerAddress)
        assertEquals("mira@example.com", invoice.customerEmail)
        assertEquals(LocalDate.now().plusDays(21).toString(), invoice.dueDate)
    }

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
