package de.kontoklar.app

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.FilterInputStream
import java.io.InputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class ParsedIncomingInvoice(
    val invoiceNumber: String,
    val supplier: String,
    val description: String,
    val amountCents: Long,
    val vatCents: Long?,
    val date: String,
    val dueDate: String?,
    val sourceName: String
)

private const val MAX_INCOMING_XML_BYTES = 15L * 1024 * 1024
private const val MAX_INCOMING_PDF_BYTES = 60L * 1024 * 1024
private const val UBL_INVOICE_NAMESPACE = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
private const val CII_INVOICE_NAMESPACE = "urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"
private val pdfBoxInitLock = Any()
private var pdfBoxInitialized = false

fun parseIncomingInvoice(input: InputStream, sourceName: String, context: Context): ParsedIncomingInvoice {
    val buffered = if (input.markSupported()) input else BufferedInputStream(input)
    buffered.mark(5)
    val signature = ByteArray(5)
    val signatureLength = buffered.read(signature)
    buffered.reset()
    if (signatureLength == signature.size && String(signature, Charsets.US_ASCII) == "%PDF-") {
        return parseZugferdPdf(buffered, sourceName, context)
    }
    return parseIncomingInvoiceXml(buffered, sourceName)
}

private fun parseZugferdPdf(input: InputStream, sourceName: String, context: Context): ParsedIncomingInvoice {
    synchronized(pdfBoxInitLock) {
        if (!pdfBoxInitialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            pdfBoxInitialized = true
        }
    }
    val embeddedInvoice = PDDocument.load(
        LimitedInputStream(input, MAX_INCOMING_PDF_BYTES),
        MemoryUsageSetting.setupTempFileOnly()
    ).use { document ->
        val embeddedFiles = document.documentCatalog.names?.embeddedFiles?.names.orEmpty()
        val candidates = embeddedFiles.filterKeys { it.substringAfterLast('/').endsWith(".xml", ignoreCase = true) }
        require(candidates.size == 1) {
            if (candidates.isEmpty()) "Das PDF enthält keine eingebettete XML-E-Rechnung."
            else "Das PDF enthält mehrere XML-Anhänge. Bitte wähle die XML-Rechnung direkt aus."
        }
        val specification = candidates.values.single()
        val embedded = specification.embeddedFileUnicode ?: specification.embeddedFile
            ?: error("Der XML-Anhang im PDF konnte nicht geöffnet werden.")
        require(embedded.size in 1..MAX_INCOMING_XML_BYTES) {
            "Die eingebettete XML-Rechnung ist leer oder größer als 15 MB."
        }
        embedded.createInputStream().use { xml ->
            LimitedInputStream(xml, MAX_INCOMING_XML_BYTES).use { boundedXml -> boundedXml.readBytes() }
        }
    }
    return parseIncomingInvoiceXml(ByteArrayInputStream(embeddedInvoice), sourceName)
}

fun parseIncomingInvoiceXml(input: InputStream, sourceName: String): ParsedIncomingInvoice {
    val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(LimitedInputStream(input, MAX_INCOMING_XML_BYTES), "UTF-8")
    }
    val root = parser.readRoot()
    return when {
        root.name == "Invoice" && root.namespace == UBL_INVOICE_NAMESPACE -> parseUblInvoice(root, sourceName)
        root.name == "CrossIndustryInvoice" && root.namespace == CII_INVOICE_NAMESPACE -> parseCiiInvoice(root, sourceName)
        else -> error("Dieses Dateiformat wird nicht unterstützt. Bitte XRechnung oder ZUGFeRD im UBL- oder CII-XML-Format auswählen.")
    }
}

private fun parseUblInvoice(root: XmlNode, sourceName: String): ParsedIncomingInvoice {
    val number = root.child("ID")?.text?.takeIf(String::isNotBlank)
        ?: error("Die E-Rechnung enthält keine Rechnungsnummer.")
    val supplierParty = root.path("AccountingSupplierParty", "Party")
        ?: error("Die E-Rechnung enthält keine Lieferantendaten.")
    val supplier = supplierParty.path("PartyLegalEntity", "RegistrationName")?.text
        ?.ifBlank { null }
        ?: supplierParty.path("PartyName", "Name")?.text?.ifBlank { null }
        ?: error("Der Lieferantenname fehlt in der E-Rechnung.")
    val lines = root.children("InvoiceLine").mapNotNull { line ->
        line.path("Item", "Name")?.text?.ifBlank { null }
            ?: line.path("Item", "Description")?.text?.ifBlank { null }
    }
    val description = lines.joinToString("; ").ifBlank { "Importierte E-Rechnung" }
    val issueDate = root.child("IssueDate")?.text?.takeIf(String::isNotBlank)
        ?: error("Das Rechnungsdatum fehlt in der E-Rechnung.")
    require(runCatching { LocalDate.parse(issueDate) }.isSuccess) { "Das Rechnungsdatum ist ungültig." }

    val monetaryTotal = root.child("LegalMonetaryTotal")
        ?: error("Die E-Rechnung enthält keine Gesamtsumme.")
    val payable = monetaryTotal.child("PayableAmount") ?: monetaryTotal.child("TaxInclusiveAmount")
        ?: error("Der Bruttobetrag fehlt in der E-Rechnung.")
    val currency = payable.attributes["currencyID"].orEmpty().ifBlank {
        root.child("DocumentCurrencyCode")?.text.orEmpty()
    }
    require(currency == "EUR") { "Importiert werden derzeit nur E-Rechnungen in EUR (gefunden: ${currency.ifBlank { "keine Währung" }})." }
    val grossCents = payable.text.toCents()
    require(grossCents > 0) { "Der Rechnungsbetrag muss positiv sein." }
    val vat = root.child("TaxTotal")?.child("TaxAmount")?.text?.toCents()
    require(vat == null || vat in 0..grossCents) { "Die Umsatzsteuerangabe der E-Rechnung ist widersprüchlich." }
    val dueDate = root.child("DueDate")?.text?.takeIf(String::isNotBlank)
    require(dueDate == null || runCatching { LocalDate.parse(dueDate) }.isSuccess) { "Das Fälligkeitsdatum ist ungültig." }

    return ParsedIncomingInvoice(number, supplier, description, grossCents, vat, issueDate, dueDate, sourceName)
}

private fun parseCiiInvoice(root: XmlNode, sourceName: String): ParsedIncomingInvoice {
    val document = root.child("ExchangedDocument") ?: error("Die CII-Rechnung enthält keine Rechnungsdaten.")
    val number = document.child("ID")?.text?.takeIf(String::isNotBlank)
        ?: error("Die E-Rechnung enthält keine Rechnungsnummer.")
    val issueDateValue = document.path("IssueDateTime", "DateTimeString")
        ?: error("Das Rechnungsdatum fehlt in der E-Rechnung.")
    val issueDate = issueDateValue.text.asInvoiceDate(issueDateValue.attributes["format"])
    val transaction = root.child("SupplyChainTradeTransaction")
        ?: error("Die CII-Rechnung enthält keine Transaktionsdaten.")
    val agreement = transaction.child("ApplicableHeaderTradeAgreement")
        ?: error("Die CII-Rechnung enthält keine Lieferantendaten.")
    val supplier = agreement.path("SellerTradeParty", "Name")?.text?.ifBlank { null }
        ?: error("Der Lieferantenname fehlt in der E-Rechnung.")
    val lines = transaction.children("IncludedSupplyChainTradeLineItem").mapNotNull { line ->
        line.path("SpecifiedTradeProduct", "Name")?.text?.ifBlank { null }
            ?: line.path("SpecifiedTradeProduct", "Description")?.text?.ifBlank { null }
    }
    val description = lines.joinToString("; ").ifBlank { "Importierte E-Rechnung" }
    val settlement = transaction.child("ApplicableHeaderTradeSettlement")
        ?: error("Die CII-Rechnung enthält keine Zahlungsdaten.")
    val total = settlement.path("SpecifiedTradeSettlementHeaderMonetarySummation", "GrandTotalAmount")
        ?: error("Der Bruttobetrag fehlt in der E-Rechnung.")
    val currency = total.attributes["currencyID"].orEmpty().ifBlank {
        settlement.child("InvoiceCurrencyCode")?.text.orEmpty()
    }
    require(currency == "EUR") { "Importiert werden derzeit nur E-Rechnungen in EUR (gefunden: ${currency.ifBlank { "keine Währung" }})." }
    val grossCents = total.text.toCents()
    require(grossCents > 0) { "Der Rechnungsbetrag muss positiv sein." }
    val headerTaxTotal = settlement.path("SpecifiedTradeSettlementHeaderMonetarySummation", "TaxTotalAmount")
    val vat = headerTaxTotal?.text?.toCents() ?: settlement.children("ApplicableTradeTax")
        .mapNotNull { it.child("CalculatedAmount")?.text?.toCents() }
        .takeIf { it.isNotEmpty() }?.sum()
    require(vat == null || vat in 0..grossCents) { "Die Umsatzsteuerangabe der E-Rechnung ist widersprüchlich." }
    val dueValue = settlement.path("SpecifiedTradePaymentTerms", "DueDateDateTime", "DateTimeString")
    val dueDate = dueValue?.text?.takeIf(String::isNotBlank)?.asInvoiceDate(dueValue.attributes["format"])
    return ParsedIncomingInvoice(number, supplier, description, grossCents, vat, issueDate, dueDate, sourceName)
}

private fun String.asInvoiceDate(format: String?): String {
    val normalized = when (format) {
        "102" -> runCatching { LocalDate.parse(this, java.time.format.DateTimeFormatter.BASIC_ISO_DATE).toString() }.getOrNull()
        "203" -> take(8).takeIf { it.matches(Regex("\\d{8}")) }
            ?.let { runCatching { LocalDate.parse(it, java.time.format.DateTimeFormatter.BASIC_ISO_DATE).toString() }.getOrNull() }
        "", null -> takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
        else -> null
    } ?: error("Das Datumsformat der E-Rechnung wird nicht unterstützt.")
    require(runCatching { LocalDate.parse(normalized) }.isSuccess) { "Ein Rechnungsdatum ist ungültig." }
    return normalized
}

private fun String.toCents(): Long = BigDecimal(trim()).setScale(2, RoundingMode.HALF_UP)
    .movePointRight(2).longValueExact()

private data class XmlNode(
    val name: String,
    val namespace: String?,
    val attributes: Map<String, String>,
    val children: MutableList<XmlNode> = mutableListOf(),
    val content: StringBuilder = StringBuilder()
) {
    val text: String get() = content.toString().trim()
    fun child(name: String): XmlNode? = children.firstOrNull { it.name == name }
    fun children(name: String): List<XmlNode> = children.filter { it.name == name }
    fun path(vararg names: String): XmlNode? = names.fold(this as XmlNode?) { parent, name -> parent?.child(name) }
}

private fun XmlPullParser.readRoot(): XmlNode {
    val stack = ArrayDeque<XmlNode>()
    var root: XmlNode? = null
    while (eventType != XmlPullParser.END_DOCUMENT) {
        when (eventType) {
            XmlPullParser.DOCDECL -> error("DOCTYPE-Deklarationen sind nicht erlaubt.")
            XmlPullParser.START_TAG -> {
                val attributes = buildMap {
                    for (index in 0 until attributeCount) put(getAttributeName(index), getAttributeValue(index))
                }
                val node = XmlNode(name, namespace, attributes)
                if (stack.isEmpty()) {
                    require(root == null) { "Ungültiges XML-Dokument." }
                    root = node
                } else stack.last().children += node
                stack.addLast(node)
            }
            XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF ->
                if (stack.isNotEmpty()) stack.last().content.append(text.orEmpty())
            XmlPullParser.END_TAG -> if (stack.isNotEmpty()) stack.removeLast()
        }
        next()
    }
    return root ?: error("Die ausgewählte Datei ist leer oder kein XML-Dokument.")
}

private class LimitedInputStream(input: InputStream, private val maxBytes: Long) : FilterInputStream(input) {
    private var bytesRead = 0L

    override fun read(): Int {
        val result = super.read()
        if (result >= 0 && ++bytesRead > maxBytes) throw IllegalArgumentException("Die XML-Datei ist größer als 15 MB.")
        return result
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val result = super.read(buffer, offset, minOf(length.toLong(), (maxBytes - bytesRead + 1).coerceAtLeast(1)).toInt())
        if (result > 0) {
            bytesRead += result
            if (bytesRead > maxBytes) throw IllegalArgumentException("Die XML-Datei ist größer als 15 MB.")
        }
        return result
    }
}
