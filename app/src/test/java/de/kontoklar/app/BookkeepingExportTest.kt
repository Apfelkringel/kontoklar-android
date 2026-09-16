package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BookkeepingExportTest {
    @Test fun invoiceExportPreservesLineDescriptionsAndAddsLineTaxAmounts() {
        val invoice = Invoice(
            number = "RE-7", customer = "Beispiel", description = "Audit · Umsetzung", amountCents = 35_700,
            vatRatePercent = 19, lines = listOf(InvoiceLine("Audit", 11_900), InvoiceLine("Umsetzung", 23_800)), status = "Teilbezahlt", paidCents = 11_900
        )

        val row = invoiceCsvFields(invoice)

        assertEquals("Audit | Umsetzung", row[3])
        assertEquals("300,00", row[9])
        assertEquals("57,00", row[10])
        assertEquals("Erhalten 119,00 · Rest 238,00", row[8])
    }

    @Test fun recordedPaymentCsvRowPreservesActualPaymentDateAndDoesNotInferTax() {
        val invoice = Invoice(number = "RE-8", customer = "Beispiel", description = "Arbeit", amountCents = 10_000)
        val payment = InvoicePayment(invoiceId = invoice.id, amountCents = 4_000, date = "2026-09-12")

        val row = invoicePaymentCsvFields(payment, invoice)

        assertEquals("Zahlungseingang", row[0])
        assertEquals("RE-8", row[1])
        assertEquals("2026-09-12", row[4])
        assertEquals("40,00", row[6])
        assertEquals("Manuell", row[7])
        assertEquals("", row[9])
        assertEquals("", row[10])
    }

    @Test fun recordedExpensePaymentCsvRowPreservesPaymentDateAndSource() {
        val expense = Expense(id = "expense-9", merchant = "Büro", category = "Material", amountCents = 9_500)
        val payment = ExpensePayment(expenseId = expense.id, amountCents = 3_500, date = "2026-09-14")

        val row = expensePaymentCsvFields(payment, expense)

        assertEquals("Auszahlung", row[0])
        assertEquals("Büro", row[2])
        assertEquals("2026-09-14", row[4])
        assertEquals("35,00", row[6])
        assertEquals("Manuell", row[7])
        assertEquals("", row[9])
        assertEquals("", row[10])
    }
}
