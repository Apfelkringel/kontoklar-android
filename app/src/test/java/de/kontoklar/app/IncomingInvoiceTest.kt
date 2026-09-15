package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class IncomingInvoiceTest {
    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
        <ubl:Invoice xmlns:ubl="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
          xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
          xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
          <cbc:ID>LS-2026-0042</cbc:ID><cbc:IssueDate>2026-09-14</cbc:IssueDate>
          <cbc:DueDate>2026-09-28</cbc:DueDate><cbc:DocumentCurrencyCode>EUR</cbc:DocumentCurrencyCode>
          <cac:AccountingSupplierParty><cac:Party><cac:PartyLegalEntity><cbc:RegistrationName>Lieferant &amp; Co</cbc:RegistrationName></cac:PartyLegalEntity></cac:Party></cac:AccountingSupplierParty>
          <cac:TaxTotal><cbc:TaxAmount currencyID="EUR">19.00</cbc:TaxAmount></cac:TaxTotal>
          <cac:LegalMonetaryTotal><cbc:PayableAmount currencyID="EUR">119.00</cbc:PayableAmount></cac:LegalMonetaryTotal>
          <cac:InvoiceLine><cac:Item><cbc:Name>Material &lt;Spezial&gt;</cbc:Name></cac:Item></cac:InvoiceLine>
        </ubl:Invoice>""".trimIndent()

    @Test fun extractsUblInvoiceForLocalExpenseReview() {
        val parsed = parseIncomingInvoiceXml(ByteArrayInputStream(xml.toByteArray()), "lieferant.xml")
        assertEquals("LS-2026-0042", parsed.invoiceNumber)
        assertEquals("Lieferant & Co", parsed.supplier)
        assertEquals("Material <Spezial>", parsed.description)
        assertEquals(11900L, parsed.amountCents)
        assertEquals(1900L, parsed.vatCents)
        assertEquals("2026-09-14", parsed.date)
        assertEquals("2026-09-28", parsed.dueDate)
    }

    @Test fun rejectsUnsupportedCurrenciesAndNonUblDocuments() {
        val usd = xml.replace("EUR", "USD")
        assertTrue(runCatching { parseIncomingInvoiceXml(ByteArrayInputStream(usd.toByteArray()), "invoice.xml") }
            .exceptionOrNull()?.message?.contains("nur E-Rechnungen in EUR") == true)
        val html = "<html><body>not an invoice</body></html>"
        assertTrue(runCatching { parseIncomingInvoiceXml(ByteArrayInputStream(html.toByteArray()), "invoice.html") }.isFailure)
    }

    @Test fun rejectsXmlExternalEntityDeclarations() {
        val hostile = """<!DOCTYPE Invoice [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2">&xxe;</Invoice>"""
        assertTrue(runCatching { parseIncomingInvoiceXml(ByteArrayInputStream(hostile.toByteArray()), "invoice.xml") }.isFailure)
    }
}
