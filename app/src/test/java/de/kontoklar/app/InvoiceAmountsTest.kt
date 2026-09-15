package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Test

class InvoiceAmountsTest {
    @Test fun splitsGrossAmountIntoRoundedNetAndVat() {
        assertEquals(InvoiceAmountBreakdown(netCents = 10_000, vatCents = 1_900, grossCents = 11_900), invoiceAmountBreakdown(11_900, 19))
        assertEquals(InvoiceAmountBreakdown(netCents = 10_000, vatCents = 700, grossCents = 10_700), invoiceAmountBreakdown(10_700, 7))
    }

    @Test fun zeroRateKeepsWholeAmountAsNet() {
        assertEquals(InvoiceAmountBreakdown(netCents = 9_999, vatCents = 0, grossCents = 9_999), invoiceAmountBreakdown(9_999, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeGrossAmount() {
        invoiceAmountBreakdown(-1, 19)
    }
}
