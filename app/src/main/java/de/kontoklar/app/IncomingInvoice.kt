package de.kontoklar.app

import java.io.FilterInputStream
import java.io.InputStream
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
private const val UBL_INVOICE_NAMESPACE = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"

fun parseIncomingInvoiceXml(input: InputStream, sourceName: String): ParsedIncomingInvoice {
    val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(LimitedInputStream(input, MAX_INCOMING_XML_BYTES), "UTF-8")
    }
    val root = parser.readRoot()
    require(root.name == "Invoice" && root.namespace == UBL_INVOICE_NAMESPACE) {
        "Dieses Dateiformat wird noch nicht unterstützt. Bitte eine XRechnung im UBL-XML-Format auswählen."
    }

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
