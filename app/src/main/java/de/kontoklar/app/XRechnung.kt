package de.kontoklar.app

/** Export of the app's currently supported German domestic invoice case. */
object XRechnung {
    fun validationErrors(invoice: Invoice, profile: BusinessProfile): List<String> = buildList {
        if (invoice.number.isBlank()) add("Rechnungsnummer fehlt.")
        if (runCatching { java.time.LocalDate.parse(invoice.date) }.isFailure) add("Rechnungsdatum ist ungültig.")
        if (runCatching { java.time.LocalDate.parse(invoice.serviceDate) }.isFailure) add("Leistungsdatum ist ungültig.")
        if (runCatching { java.time.LocalDate.parse(invoice.dueDate) }.isFailure) add("Fälligkeitsdatum ist ungültig.")
        if (invoice.customer.isBlank()) add("Kundenname fehlt.")
        if (invoice.customerAddress.lineSequence().count(String::isNotBlank) < 2) add("Kundenanschrift mit Straße sowie PLZ/Ort fehlt.")
        if (invoiceLines(invoice).any { it.description.isBlank() }) add("Leistungsbeschreibung fehlt.")
        if (invoice.lines.isNotEmpty() && (invoice.lines.size > 20 || invoice.lines.any { it.amountCents <= 0 } || runCatching { invoice.lines.fold(0L) { total, line -> Math.addExact(total, line.amountCents) } != invoice.amountCents }.getOrDefault(true))) add("Rechnungspositionen sind ungültig oder ihre Summe stimmt nicht.")
        if (invoice.amountCents <= 0) add("Rechnungsbetrag muss positiv sein.")
        if (profile.businessName.isBlank()) add("Name des Rechnungsausstellers fehlt.")
        if (profile.street.isBlank() || profile.postalCode.isBlank() || profile.city.isBlank()) add("Vollständige Anschrift des Rechnungsausstellers fehlt.")
        if (!profile.vatId.replace(" ", "").matches(Regex("DE[0-9]{9}"))) add("Für diesen Export wird eine deutsche USt-IdNr. im Format DE123456789 benötigt; reine Steuernummern sind noch nicht unterstützt.")
        if (!profile.email.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) add("Eine gültige elektronische Adresse (E-Mail) des Rechnungsausstellers fehlt.")
        if (!invoice.customerEmail.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) add("Eine gültige elektronische Adresse (E-Mail) des Rechnungsempfängers fehlt.")
        if (profile.contactName.isBlank()) add("Name der Kontaktperson fehlt.")
        if (profile.phone.isBlank()) add("Telefonnummer der Kontaktperson fehlt.")
        if (!validGermanIban(profile.iban)) add("Eine gültige deutsche IBAN für Zahlungsanweisungen fehlt.")
        val invoiceVatRate = invoice.vatRatePercent ?: profile.vatRatePercent
        if (invoiceVatRate !in 1..27) add("Für diesen Export muss ein Umsatzsteuersatz von 1–27 % auf der Rechnung hinterlegt sein; steuerfreie Sonderfälle werden noch nicht unterstützt.")
        if (invoice.lines.isNotEmpty() && invoiceVatRate in 1..27 && !invoiceVatRoundingIsConsistent(invoice, invoiceVatRate)) add("Die Umsatzsteuer-Rundung der Positionen passt nicht zur Rechnungssumme. Bitte prüfe die Cent-Beträge, bevor du eine XRechnung erstellst.")
    }

    fun create(invoice: Invoice, profile: BusinessProfile): String {
        val errors = validationErrors(invoice, profile)
        require(errors.isEmpty()) { errors.joinToString("\n") }
        val address = invoice.customerAddress.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val street = address.first()
        val postal = address.last().split(Regex("\\s+"), limit = 2)
        require(postal.size == 2) { "Kundenanschrift muss in der zweiten Zeile PLZ und Ort enthalten." }
        val invoiceVatRate = invoice.vatRatePercent ?: profile.vatRatePercent
        val amounts = invoiceTaxBreakdown(invoice, invoiceVatRate)
        val net = amounts.netCents
        val tax = amounts.vatCents
        fun money(cents: Long) = "%d.%02d".format(java.util.Locale.US, cents / 100, cents % 100)
        fun x(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        fun tag(name: String, value: String) = "<$name>${x(value)}</$name>"
        fun endpoint(value: String) = "<cbc:EndpointID schemeID=\"EM\">${x(value)}</cbc:EndpointID>"
        val lineXml = invoiceLines(invoice).mapIndexed { index, line ->
            val lineAmounts = invoiceAmountBreakdown(line.amountCents, invoiceVatRate)
            """<cac:InvoiceLine><cbc:ID>${index + 1}</cbc:ID><cbc:InvoicedQuantity unitCode="C62">1</cbc:InvoicedQuantity><cbc:LineExtensionAmount currencyID="EUR">${money(lineAmounts.netCents)}</cbc:LineExtensionAmount><cac:InvoicePeriod>${tag("cbc:StartDate", invoice.serviceDate)}${tag("cbc:EndDate", invoice.serviceDate)}</cac:InvoicePeriod><cac:Item>${tag("cbc:Name", line.description)}<cac:ClassifiedTaxCategory>${tag("cbc:ID", "S")}${tag("cbc:Percent", invoiceVatRate.toString())}<cac:TaxScheme>${tag("cbc:ID", "VAT")}</cac:TaxScheme></cac:ClassifiedTaxCategory></cac:Item><cac:Price><cbc:PriceAmount currencyID="EUR">${money(lineAmounts.netCents)}</cbc:PriceAmount></cac:Price></cac:InvoiceLine>"""
        }.joinToString("\n")
        fun addressXml(streetValue: String, postalValue: String, cityValue: String) = """
            <cac:PostalAddress>${tag("cbc:StreetName", streetValue)}${tag("cbc:CityName", cityValue)}${tag("cbc:PostalZone", postalValue)}<cac:Country>${tag("cbc:IdentificationCode", "DE")}</cac:Country></cac:PostalAddress>
        """.trimIndent()
        val supplierTax = profile.vatId.replace(" ", "")
        return """<?xml version="1.0" encoding="UTF-8"?>
<ubl:Invoice xmlns:ubl="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2" xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2" xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
${tag("cbc:CustomizationID", "urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0")}
${tag("cbc:ProfileID", "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0")}
${tag("cbc:ID", invoice.number)}
${tag("cbc:IssueDate", invoice.date)}
${tag("cbc:DueDate", invoice.dueDate)}
${tag("cbc:InvoiceTypeCode", "380")}
${tag("cbc:DocumentCurrencyCode", "EUR")}
${tag("cbc:BuyerReference", "-")}
<cac:AccountingSupplierParty><cac:Party>${endpoint(profile.email)}<cac:PartyName>${tag("cbc:Name", profile.businessName)}</cac:PartyName>
${addressXml(profile.street, profile.postalCode, profile.city)}
<cac:PartyTaxScheme>${tag("cbc:CompanyID", supplierTax)}<cac:TaxScheme>${tag("cbc:ID", "VAT")}</cac:TaxScheme></cac:PartyTaxScheme>
<cac:PartyLegalEntity>${tag("cbc:RegistrationName", profile.businessName)}</cac:PartyLegalEntity><cac:Contact>${tag("cbc:Name", profile.contactName)}${tag("cbc:Telephone", profile.phone)}${tag("cbc:ElectronicMail", profile.email)}</cac:Contact></cac:Party></cac:AccountingSupplierParty>
<cac:AccountingCustomerParty><cac:Party>${endpoint(invoice.customerEmail)}
${addressXml(street, postal[0], postal[1])}<cac:PartyLegalEntity>${tag("cbc:RegistrationName", invoice.customer)}</cac:PartyLegalEntity></cac:Party></cac:AccountingCustomerParty>
<cac:PaymentMeans>${tag("cbc:PaymentMeansCode", "58")}<cac:PayeeFinancialAccount>${tag("cbc:ID", profile.iban.replace(" ", ""))}</cac:PayeeFinancialAccount></cac:PaymentMeans>
<cac:PaymentTerms>${tag("cbc:Note", "Zahlbar bis ${invoice.dueDate}.")}</cac:PaymentTerms>
<cac:TaxTotal><cbc:TaxAmount currencyID="EUR">${money(tax)}</cbc:TaxAmount><cac:TaxSubtotal><cbc:TaxableAmount currencyID="EUR">${money(net)}</cbc:TaxableAmount><cbc:TaxAmount currencyID="EUR">${money(tax)}</cbc:TaxAmount><cac:TaxCategory>${tag("cbc:ID", "S")}${tag("cbc:Percent", invoiceVatRate.toString())}<cac:TaxScheme>${tag("cbc:ID", "VAT")}</cac:TaxScheme></cac:TaxCategory></cac:TaxSubtotal></cac:TaxTotal>
<cac:LegalMonetaryTotal><cbc:LineExtensionAmount currencyID="EUR">${money(net)}</cbc:LineExtensionAmount><cbc:TaxExclusiveAmount currencyID="EUR">${money(net)}</cbc:TaxExclusiveAmount><cbc:TaxInclusiveAmount currencyID="EUR">${money(invoice.amountCents)}</cbc:TaxInclusiveAmount><cbc:PayableAmount currencyID="EUR">${money(invoice.amountCents)}</cbc:PayableAmount></cac:LegalMonetaryTotal>
$lineXml
</ubl:Invoice>""".trimIndent()
    }
}

private fun validGermanIban(raw: String): Boolean {
    val iban = raw.filterNot(Char::isWhitespace).uppercase()
    if (!iban.matches(Regex("DE[0-9]{20}"))) return false
    val rearranged = iban.drop(4) + iban.take(4)
    var remainder = 0
    for (character in rearranged) {
        val digits = if (character.isDigit()) character.toString() else (character.code - 'A'.code + 10).toString()
        for (digit in digits) remainder = (remainder * 10 + digit.digitToInt()) % 97
    }
    return remainder == 1
}
