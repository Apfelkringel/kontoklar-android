package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BookkeepingExportTest {
    @Test fun invoiceExportPreservesLineDescriptionsAndAddsLineTaxAmounts() {
        val invoice = Invoice(
            number = "RE-7", customer = "Beispiel", description = "Audit · Umsetzung", amountCents = 35_700,
            vatRatePercent = 19, lines = listOf(InvoiceLine("Audit", 11_900), InvoiceLine("Umsetzung", 23_800))
        )

        val row = invoiceCsvFields(invoice)

        assertEquals("Audit | Umsetzung", row[3])
        assertEquals("300,00", row[9])
        assertEquals("57,00", row[10])
    }
}
