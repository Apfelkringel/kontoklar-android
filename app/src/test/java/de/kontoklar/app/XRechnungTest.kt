package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class XRechnungTest {
    private val invoice = Invoice(
        number = "RE-2026-0001", customer = "Müller & Söhne", description = "Beratung <Tag>",
        amountCents = 11900, customerAddress = "Hauptstraße 1\n10115 Berlin", customerEmail = "kunde@example.de",
        date = "2026-09-15", serviceDate = "2026-09-14", dueDate = "2026-09-29"
    )
    private val profile = BusinessProfile(
        businessName = "KontoKlar GmbH", street = "Nebenweg 2", postalCode = "10117",
        city = "Berlin", vatId = "DE123456789", vatRatePercent = 19, email = "rechnung@example.de",
        contactName = "Erika Mustermann", phone = "+49 30 123456", iban = "DE89 3704 0044 0532 0130 00"
    )

    @Test fun exportsGrossAndTaxConsistentlyAndEscapesXml() {
        val xml = XRechnung.create(invoice, profile)
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        assertEquals("Invoice", document.documentElement.localName)
        assertTrue(xml.contains("<cbc:BuyerReference>-</cbc:BuyerReference>"))
        assertTrue(xml.contains("<cbc:EndpointID schemeID=\"EM\">rechnung@example.de</cbc:EndpointID>"))
        assertTrue(xml.contains("<cbc:EndpointID schemeID=\"EM\">kunde@example.de</cbc:EndpointID>"))
        assertTrue(xml.contains("<cbc:PaymentMeansCode>58</cbc:PaymentMeansCode>"))
        assertTrue(xml.contains("<cbc:ID>DE89370400440532013000</cbc:ID>"))
        assertTrue(xml.contains("<cbc:StartDate>2026-09-14</cbc:StartDate>"))
        assertTrue(xml.contains("<cbc:TaxableAmount currencyID=\"EUR\">100.00</cbc:TaxableAmount>"))
        assertTrue(xml.contains("<cbc:TaxAmount currencyID=\"EUR\">19.00</cbc:TaxAmount>"))
        assertTrue(xml.contains("<cbc:TaxInclusiveAmount currencyID=\"EUR\">119.00</cbc:TaxInclusiveAmount>"))
        assertTrue(xml.contains("Müller &amp; Söhne"))
        assertTrue(xml.contains("Beratung &lt;Tag&gt;"))
    }

    @Test fun rejectsIncompleteAndAmbiguousInvoices() {
        assertTrue(XRechnung.validationErrors(invoice.copy(customerAddress = ""), profile).any { it.contains("Kundenanschrift") })
        assertTrue(XRechnung.validationErrors(invoice.copy(serviceDate = ""), profile).any { it.contains("Leistungsdatum") })
        assertTrue(XRechnung.validationErrors(invoice, profile.copy(email = "")).any { it.contains("E-Mail") })
        assertTrue(XRechnung.validationErrors(invoice, profile.copy(iban = "DE00000000000000000000")).any { it.contains("IBAN") })
        assertTrue(XRechnung.validationErrors(invoice, profile.copy(vatRatePercent = 0)).any { it.contains("Sonderfälle") })
        val error = runCatching { XRechnung.create(invoice, profile.copy(businessName = "")) }.exceptionOrNull()
        assertEquals(true, error?.message?.contains("Rechnungsausstellers") == true)
    }
}
