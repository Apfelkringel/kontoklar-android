package de.kontoklar.app

import java.io.FilterInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDate
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class BankTransaction(
    val id: String,
    val accountIban: String,
    val date: String,
    val counterparty: String,
    val description: String,
    val amountCents: Long,
    val reference: String,
    val matchedInvoiceId: String? = null,
    val matchedExpenseId: String? = null,
    val userClassification: String = ""
)

val BANK_TRANSACTION_CLASSIFICATIONS = listOf(
    "Privat",
    "Geschäftliche Einnahme · ohne Rechnung",
    "Geschäftliche Ausgabe · ohne Beleg",
    "Steuerzahlung",
    "Umbuchung",
    "Sonstiges geschäftlich"
)

fun classifyBankTransaction(transaction: BankTransaction, classification: String): BankTransaction {
    require(classification.isBlank() || classification in BANK_TRANSACTION_CLASSIFICATIONS) { "Diese Kennzeichnung ist nicht verfügbar." }
    require(classification.isBlank() || (transaction.matchedInvoiceId == null && transaction.matchedExpenseId == null)) {
        "Eine zugeordnete Buchung wird über ihre Rechnung oder Ausgabe gekennzeichnet."
    }
    return transaction.copy(userClassification = classification)
}

data class ParsedBankStatement(
    val accountIbans: List<String>,
    val transactions: List<BankTransaction>,
    val securities: List<BankSecurityPosition> = emptyList()
)

private const val MAX_CAMT_BYTES = 20L * 1024 * 1024
private const val CAMT_ENTRY_LIMIT = 50_000

fun parseCamt053(input: InputStream): ParsedBankStatement {
    val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(LimitedCamtInputStream(input, MAX_CAMT_BYTES), "UTF-8")
    }
    val root = parser.readCamtRoot()
    require(root.name == "Document") { "Die Datei ist kein CAMT-Kontoauszug (ISO 20022)." }
    val statements = root.path("BkToCstmrStmt")?.children("Stmt").orEmpty()
    require(statements.isNotEmpty()) { "Der CAMT-Kontoauszug enthält keine Kontoauszüge." }
    val accounts = statements.mapNotNull { statement ->
        statement.path("Acct", "Id", "IBAN")?.text?.takeIf(String::isNotBlank)
            ?: statement.path("Acct", "Id", "Othr", "Id")?.text?.takeIf(String::isNotBlank)
    }.distinct()
    val transactions = statements.flatMap { statement ->
        val iban = statement.path("Acct", "Id", "IBAN")?.text?.ifBlank { null }
            ?: statement.path("Acct", "Id", "Othr", "Id")?.text.orEmpty()
        statement.children("Ntry").mapNotNull { entry -> parseCamtEntry(entry, iban) }
    }
    require(transactions.isNotEmpty()) { "Der CAMT-Kontoauszug enthält keine unterstützten Buchungen in EUR." }
    require(transactions.size <= CAMT_ENTRY_LIMIT) { "Der Kontoauszug enthält mehr als $CAMT_ENTRY_LIMIT Buchungen." }
    return ParsedBankStatement(accounts, transactions.distinctBy(BankTransaction::id))
}

private fun parseCamtEntry(entry: CamtNode, iban: String): BankTransaction? {
    val amountNode = entry.child("Amt") ?: error("Eine CAMT-Buchung enthält keinen Betrag.")
    val currency = amountNode.attributes["Ccy"].orEmpty()
    require(currency == "EUR") { "Dieser Kontoimport unterstützt derzeit nur EUR (gefunden: ${currency.ifBlank { "keine Währung" }})." }
    val magnitude = BigDecimal(amountNode.text).setScale(2, RoundingMode.UNNECESSARY)
        .movePointRight(2).longValueExact()
    require(magnitude > 0) { "Eine Buchung im Kontoauszug hat einen ungültigen Betrag." }
    val indicator = entry.child("CdtDbtInd")?.text.orEmpty()
    require(indicator == "CRDT" || indicator == "DBIT") { "Eine Buchung enthält keine gültige Soll/Haben-Kennzeichnung." }
    val amount = if (indicator == "CRDT") magnitude else -magnitude
    val bookingDate = entry.path("BookgDt", "Dt")?.text?.ifBlank { null }
        ?: entry.path("BookgDt", "DtTm")?.text?.take(10)?.ifBlank { null }
        ?: entry.path("ValDt", "Dt")?.text?.ifBlank { null }
        ?: entry.path("ValDt", "DtTm")?.text?.take(10)?.ifBlank { null }
        ?: error("Eine Buchung im Kontoauszug enthält kein Buchungs- oder Wertstellungsdatum.")
    require(runCatching { LocalDate.parse(bookingDate) }.isSuccess) { "Der Kontoauszug enthält ein ungültiges Buchungsdatum." }

    val details = entry.children("NtryDtls").flatMap { it.children("TxDtls") }
    val counterparty = details.firstNotNullOfOrNull { detail ->
        val partyNames = if (indicator == "CRDT") listOf("Dbtr", "Cdtr") else listOf("Cdtr", "Dbtr")
        partyNames.firstNotNullOfOrNull { name ->
            detail.path("RltdPties", name, "Pty", "Nm")?.text?.ifBlank { null }
                ?: detail.path("RltdPties", name, "Nm")?.text?.ifBlank { null }
                ?: detail.path("RltdPties", name, "Pty", "Id", "OrgId", "Othr", "Id")?.text?.ifBlank { null }
        }
    }.orEmpty()
    val remittance = details.flatMap { detail ->
        detail.children("RmtInf").flatMap { info -> info.children("Ustrd").map(CamtNode::text) }
    }.filter(String::isNotBlank).distinct().joinToString(" · ").take(600)
    val reference = listOfNotNull(
        entry.child("NtryRef")?.text?.ifBlank { null },
        entry.child("AcctSvcrRef")?.text?.ifBlank { null },
        details.firstNotNullOfOrNull { it.path("Refs", "EndToEndId")?.text?.ifBlank { null } }
    ).distinct().joinToString(" · ").take(300)
    val additional = entry.child("AddtlNtryInf")?.text?.ifBlank { null }.orEmpty()
    val description = listOf(remittance, additional).filter(String::isNotBlank).distinct().joinToString(" · ").take(800)
    val fingerprint = listOf(iban, bookingDate, amount.toString(), counterparty, description, reference).joinToString("\u001f")
    val id = MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
    return BankTransaction(id, iban, bookingDate, counterparty.ifBlank { "Unbekannter Zahlungspartner" }, description, amount, reference)
}

fun suggestInvoiceMatch(transaction: BankTransaction, invoices: List<Invoice>): Invoice? {
    if (transaction.amountCents <= 0 || transaction.matchedInvoiceId != null) return null
    val candidates = invoices.filter {
        it.status != "Entwurf" && transaction.amountCents <= invoiceOutstandingCents(it)
    }
    if (candidates.isEmpty()) return null
    fun score(invoice: Invoice): Int {
        val evidence = listOf(transaction.counterparty, transaction.description, transaction.reference)
        var result = 1
        if (invoice.number.isNotBlank() && evidence.any { it.contains(invoice.number, ignoreCase = true) }) result += 10
        if (invoice.customer.isNotBlank() && evidence.any { it.contains(invoice.customer, ignoreCase = true) }) result += 5
        return result
    }
    val ranked = candidates.map { it to score(it) }.sortedByDescending { it.second }
    return ranked.first().first.takeIf { ranked.size == 1 || ranked[0].second > ranked[1].second }
}

fun suggestExpenseMatch(
    transaction: BankTransaction,
    expenses: List<Expense>,
    transactions: List<BankTransaction> = emptyList()
): Expense? {
    if (transaction.amountCents >= 0 || transaction.amountCents == Long.MIN_VALUE || transaction.matchedExpenseId != null) return null
    val alreadyMatched = transactions.asSequence()
        .filter { it.id != transaction.id }
        .mapNotNull(BankTransaction::matchedExpenseId)
        .toSet()
    val candidates = expenses.filter { it.amountCents == -transaction.amountCents && it.id !in alreadyMatched }
    if (candidates.isEmpty()) return null
    fun score(expense: Expense): Int {
        val evidence = listOf(transaction.counterparty, transaction.description, transaction.reference)
            .map { it.lowercase().filter(Char::isLetterOrDigit) }
            .filter(String::isNotBlank)
        val merchant = expense.merchant.lowercase().filter(Char::isLetterOrDigit)
        if (merchant.isBlank()) return 1
        return if (evidence.any { it.contains(merchant) || merchant.contains(it) && it.length >= 4 }) 6 else 1
    }
    val ranked = candidates.map { it to score(it) }.sortedByDescending { it.second }
    return ranked.first().first.takeIf { ranked.size == 1 || ranked[0].second > ranked[1].second }
}

private data class CamtNode(
    val name: String,
    val attributes: Map<String, String>,
    val children: MutableList<CamtNode> = mutableListOf(),
    val content: StringBuilder = StringBuilder()
) {
    val text: String get() = content.toString().trim()
    fun child(name: String): CamtNode? = children.firstOrNull { it.name == name }
    fun children(name: String): List<CamtNode> = children.filter { it.name == name }
    fun path(vararg names: String): CamtNode? = names.fold(this as CamtNode?) { parent, name -> parent?.child(name) }
}

private fun XmlPullParser.readCamtRoot(): CamtNode {
    val stack = ArrayDeque<CamtNode>()
    var root: CamtNode? = null
    while (eventType != XmlPullParser.END_DOCUMENT) {
        when (eventType) {
            XmlPullParser.DOCDECL -> error("DOCTYPE-Deklarationen sind in Kontoauszügen nicht erlaubt.")
            XmlPullParser.START_TAG -> {
                val node = CamtNode(name, buildMap {
                    for (index in 0 until attributeCount) put(getAttributeName(index), getAttributeValue(index))
                })
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
    return root ?: error("Der Kontoauszug ist leer oder kein XML-Dokument.")
}

private class LimitedCamtInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L
    override fun read(): Int {
        val value = super.read()
        if (value >= 0 && ++count > limit) throw IllegalArgumentException("Der Kontoauszug ist größer als 20 MB.")
        return value
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(buffer, offset, minOf(length.toLong(), (limit - count + 1).coerceAtLeast(1)).toInt())
        if (read > 0 && (count + read).also { count = it } > limit) throw IllegalArgumentException("Der Kontoauszug ist größer als 20 MB.")
        return read
    }
}
