package de.kontoklar.app

import android.content.Context
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.FilterInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

private const val MAX_TRADE_REPUBLIC_PDF_BYTES = 60L * 1024 * 1024
private const val MAX_TRADE_REPUBLIC_ROWS = 50_000
private val statementDate = Regex("^\\s*(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[./]\\d{1,2}[./]\\d{4}|\\d{1,2}\\s+[A-Za-zÄÖÜäöü]{3,9}\\s+\\d{4})\\s+(.+?)\\s*$")
internal val statementAmount = Regex("(?<![\\w/])(?:EUR\\s*)?(?:€\\s*)?[+−-]?(?:\\d{1,3}(?:[ ,.'’]\\d{3})+|\\d+)(?:[,.]\\d{2})(?!\\d)", RegexOption.IGNORE_CASE)
private val germanStatementDate = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d. MMMM uuuu").toFormatter(Locale.GERMAN)
private val englishStatementDate = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM uuuu").toFormatter(Locale.ENGLISH)
private val c24TransactionStart = Regex("^\\s*(\\d{1,2})\\.(\\d{1,2})\\.\\s+\\d{1,2}\\.\\d{1,2}\\.\\s+(.+?)\\s+([+−-]?)\\s*((?:\\d{1,3}(?:[ .']\\d{3})+|\\d+)[,.]\\d{2})\\s*€?\\s*$")
private val c24StatementYear = Regex("(?i)(?:Kontoauszug|vorläufiger Kontoauszug)\\s+\\d{1,2}/(\\d{4})")

fun parseBankStatementPdf(input: InputStream, context: Context): ParsedBankStatement {
    initializePdfBoxIfNeeded(context)
    val text = PDDocument.load(
        LimitedTradeRepublicInputStream(input, MAX_TRADE_REPUBLIC_PDF_BYTES),
        MemoryUsageSetting.setupTempFileOnly()
    ).use { document -> PDFTextStripper().getText(document) }
    val normalized = text.lowercase(Locale.ROOT)
    return when {
        normalized.contains("trade republic") -> parseTradeRepublicStatementText(text)
        normalized.contains("c24 bank") && normalized.contains("transaktionsübersicht") -> parseC24StatementText(text)
        normalized.contains("comdirect") && (normalized.contains("finanzreport") || normalized.contains("umsatz in eur")) -> parseComdirectStatementText(text)
        else -> error("Das PDF ist kein unterstützter C24-, comdirect- oder Trade-Republic-Kontoauszug.")
    }
}

/** Parses comdirect Finanzreport rows only when the amount carries an explicit sign. */
internal fun parseComdirectStatementText(text: String): ParsedBankStatement {
    require(text.length <= MAX_TRADE_REPUBLIC_PDF_BYTES) { "Der extrahierte comdirect-Finanzreport ist zu groß." }
    val normalized = text.lowercase(Locale.ROOT)
    require(normalized.contains("comdirect") && (normalized.contains("finanzreport") || normalized.contains("umsatz in eur"))) {
        "Das PDF ist kein erkennbarer comdirect-Finanzreport."
    }
    val iban = Regex("(?i)\\bIBAN:\\s*([A-Z]{2}\\d{2}(?:\\s?[A-Z0-9]){11,30})\\b")
        .find(text)?.groupValues?.get(1)?.replace(" ", "").orEmpty()
    val rows = mutableListOf<BankTransaction>()
    val duplicateOrdinals = mutableMapOf<String, Int>()
    text.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        val dateMatch = Regex("^(\\d{1,2}\\.\\d{1,2}\\.\\d{4})\\s+(.+)$").matchEntire(line) ?: return@forEach
        val date = parseStatementDate(dateMatch.groupValues[1]) ?: return@forEach
        val rest = dateMatch.groupValues[2].trim()
        val amountMatches = statementAmount.findAll(rest).toList()
        if (amountMatches.size != 1 || amountMatches.single().range.last != rest.lastIndex) return@forEach
        val amountToken = amountMatches.single().value
        require(amountToken.contains('+') || amountToken.contains('-') || amountToken.contains('−')) {
            "Eine comdirect-Buchung enthält kein eindeutiges Vorzeichen."
        }
        val amount = parseStatementMoney(amountToken)
        require(amount != 0L) { "Eine comdirect-Buchung enthält keinen Betrag." }
        val description = rest.substring(0, amountMatches.single().range.first).trim()
        require(description.isNotBlank()) { "Eine comdirect-Buchung enthält keinen Buchungstext." }
        val stableKey = listOf(iban, date, amount.toString(), description).joinToString("\\u001f")
        val ordinal = duplicateOrdinals.getOrDefault(stableKey, 0)
        duplicateOrdinals[stableKey] = ordinal + 1
        val id = MessageDigest.getInstance("SHA-256").digest("$stableKey\\u001f$ordinal".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        rows += BankTransaction(id, iban, date, description.substringBefore(':').trim().ifBlank { description.take(120) }, description.take(800), amount, "")
        require(rows.size <= MAX_TRADE_REPUBLIC_ROWS) { "Der comdirect-Finanzreport enthält mehr als $MAX_TRADE_REPUBLIC_ROWS Buchungen." }
    }
    require(rows.isNotEmpty()) { "Im comdirect-Finanzreport wurden keine sicher lesbaren Buchungen gefunden." }
    return ParsedBankStatement(listOf(iban).filter(String::isNotBlank), rows)
}

fun parseTradeRepublicStatementPdf(input: InputStream, context: Context): ParsedBankStatement {
    return parseBankStatementPdf(input, context).also { statement ->
        require(statement.transactions.isNotEmpty()) { "Der Trade-Republic-Kontoauszug ist leer." }
    }
}

/** Parses C24's digital statement layout locally. Rows may span several lines and are
 * terminated by the next dated row; summary balances are deliberately ignored. */
internal fun parseC24StatementText(text: String): ParsedBankStatement {
    require(text.length <= MAX_TRADE_REPUBLIC_PDF_BYTES) { "Der extrahierte C24-Kontoauszug ist zu groß." }
    val normalized = text.lowercase(Locale.ROOT)
    require(normalized.contains("c24 bank") && normalized.contains("transaktionsübersicht")) {
        "Das PDF ist kein erkennbarer C24-Kontoauszug."
    }
    val accountIban = Regex("(?i)\\bIBAN:\\s*([A-Z]{2}\\d{2}(?:\\s?[A-Z0-9]){11,30})\\b")
        .find(text)?.groupValues?.get(1)?.replace(" ", "").orEmpty()
    var year = c24StatementYear.find(text)?.groupValues?.get(1)?.toIntOrNull()
    data class Pending(val date: String, val amount: Long, val type: String, val details: MutableList<String>)
    val pending = mutableListOf<Pending>()
    var current: Pending? = null
    fun isC24SummaryOrFooter(line: String): Boolean =
        line.startsWith("Zusammenfassung", ignoreCase = true) ||
            line.startsWith("Startsaldo", ignoreCase = true) ||
            line.startsWith("Kontobelastungen", ignoreCase = true) ||
            line.startsWith("Kontogutschriften", ignoreCase = true) ||
            line.startsWith("Endsaldo", ignoreCase = true) ||
            line.startsWith("C24 Bank", ignoreCase = true) ||
            line.contains("Seite ", ignoreCase = true)
    fun flush() {
        current?.let(pending::add)
        current = null
    }
    text.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        c24StatementYear.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { year = it }
        val match = c24TransactionStart.matchEntire(line)
        if (match != null) {
            flush()
            val transactionYear = year ?: error("C24-Buchung enthält kein eindeutig ermittelbares Jahr.")
            val date = runCatching { LocalDate.of(transactionYear, match.groupValues[2].toInt(), match.groupValues[1].toInt()).toString() }
                .getOrElse { error("Ungültiges C24-Buchungsdatum.") }
            val amount = parseStatementMoney(match.groupValues[5])
            val signed = when (match.groupValues[4]) {
                "+" -> kotlin.math.abs(amount)
                "-", "−" -> -kotlin.math.abs(amount)
                else -> error("C24-Buchung ohne eindeutige Soll-/Haben-Kennzeichnung.")
            }
            // The text before the sign is the transaction type; keep it as the first detail.
            current = Pending(date, signed, match.groupValues[3], mutableListOf(match.groupValues[3]))
            return@forEach
        }
        if (isC24SummaryOrFooter(line)) {
            flush()
            return@forEach
        }
        if (current != null && line.isNotBlank() &&
            !line.startsWith("IBAN:", ignoreCase = true) &&
            !line.startsWith("BIC:", ignoreCase = true)
        ) current!!.details += line
    }
    flush()
    require(pending.isNotEmpty()) { "Im C24-Kontoauszug wurden keine sicher lesbaren Buchungen gefunden." }
    val duplicateOrdinals = mutableMapOf<String, Int>()
    val rows = pending.map { row ->
        val description = row.details.filter(String::isNotBlank).distinct().joinToString(" · ").take(800)
        require(description.isNotBlank()) { "Eine C24-Buchung enthält keinen Buchungstext." }
        val counterparty = row.details.drop(1).firstOrNull().orEmpty().ifBlank { row.details.first() }.take(120)
        val stableKey = listOf(accountIban, row.date, row.amount.toString(), description).joinToString("\u001f")
        val ordinal = duplicateOrdinals.getOrDefault(stableKey, 0)
        duplicateOrdinals[stableKey] = ordinal + 1
        val id = MessageDigest.getInstance("SHA-256").digest("$stableKey\u001f$ordinal".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        BankTransaction(id, accountIban, row.date, counterparty, description, row.amount, "")
    }
    return ParsedBankStatement(listOf(accountIban).filter(String::isNotBlank), rows)
}

/** Parses only recognized Trade Republic account-statement table layouts; no values are guessed from unknown PDFs. */
internal fun parseTradeRepublicStatementText(text: String): ParsedBankStatement {
    require(text.length <= MAX_TRADE_REPUBLIC_PDF_BYTES) { "Der extrahierte Kontoauszug ist zu groß." }
    require(text.contains("TRADE REPUBLIC", ignoreCase = true)) { "Das PDF ist kein erkennbarer Trade-Republic-Kontoauszug." }
    val normalized = text.lowercase(Locale.ROOT)
    val modern = (normalized.contains("account transactions") || normalized.contains("kontoumsätze") || normalized.contains("kontoumsaetze")) &&
        (normalized.contains("money in") || normalized.contains("geldeingang")) &&
        (normalized.contains("money out") || normalized.contains("geldausgang"))
    val legacy = normalized.contains("buchungen") && normalized.contains("buchungstag") && normalized.contains("betrag in eur")
    require(modern || legacy) { "Das Trade-Republic-PDF hat ein nicht unterstütztes Kontoauszug-Layout." }

    val iban = Regex("(?i)\\b([A-Z]{2}\\d{2}(?:\\s?[A-Z0-9]){11,30})\\b")
        .find(text)?.groupValues?.get(1)?.replace(" ", "").orEmpty()
    val rows = mutableListOf<BankTransaction>()
    val duplicateOrdinals = mutableMapOf<String, Int>()
    text.lineSequence().forEach { line ->
        val dateMatch = statementDate.matchEntire(line) ?: return@forEach
        val date = parseStatementDate(dateMatch.groupValues[1]) ?: return@forEach
        val rest = dateMatch.groupValues[2].trim()
        val amountMatches = statementAmount.findAll(rest).toList()
        if (amountMatches.isEmpty() || amountMatches.last().range.last != rest.lastIndex) return@forEach

        val amountCount = amountMatches.size
        val signedAmount: Long
        val description: String
        if (legacy) {
            if (amountCount == 0) return@forEach
            require(amountCount == 1) { "Eine Buchungszeile im Kontoauszug konnte nicht sicher gelesen werden." }
            signedAmount = parseStatementMoney(amountMatches.single().value)
            description = rest.substring(0, amountMatches.single().range.first).trim()
        } else {
            require(amountCount in 2..3) { "Eine Buchungszeile im Kontoauszug konnte nicht sicher gelesen werden." }
            val values = amountMatches.map { kotlin.math.abs(parseStatementMoney(it.value)) }
            val direction = when {
                amountCount == 3 -> {
                    require((values[0] == 0L) xor (values[1] == 0L)) { "Eine Buchungszeile enthält widersprüchliche Ein- und Ausgänge." }
                    if (values[0] > 0) values[0] else -values[1]
                }
                else -> directionFromDescription(rest.substring(0, amountMatches.first().range.first)) * values[0]
            }
            require(direction != 0L) { "Eine Buchungszeile enthält keinen eindeutigen Betrag." }
            signedAmount = direction
            description = rest.substring(0, amountMatches.first().range.first).trim()
        }
        require(signedAmount != 0L) { "Der Kontoauszug enthält eine Buchung ohne Betrag." }
        require(description.isNotBlank()) { "Eine Buchung im Kontoauszug enthält keinen Buchungstext." }
        val counterparty = description.substringBefore(':').trim().ifBlank { description.take(120) }
        val stableKey = listOf(iban, date, signedAmount.toString(), description).joinToString("\u001f")
        val ordinal = duplicateOrdinals.getOrDefault(stableKey, 0)
        duplicateOrdinals[stableKey] = ordinal + 1
        val id = MessageDigest.getInstance("SHA-256").digest("$stableKey\u001f$ordinal".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        rows += BankTransaction(id, iban, date, counterparty, description.take(800), signedAmount, "")
        require(rows.size <= MAX_TRADE_REPUBLIC_ROWS) { "Der Kontoauszug enthält mehr als $MAX_TRADE_REPUBLIC_ROWS Buchungen." }
    }
    require(rows.isNotEmpty()) { "Im Trade-Republic-Kontoauszug wurden keine sicher lesbaren Buchungen gefunden." }
    return ParsedBankStatement(listOf(iban).filter(String::isNotBlank), rows)
}

private fun directionFromDescription(description: String): Long {
    val value = description.lowercase(Locale.ROOT)
    return when {
        listOf("outgoing", "card transaction", "kartenzahlung", "lastschrift", "ausgehend", "abbuchung", "kauf").any(value::contains) -> -1L
        listOf("incoming", "interest", "zinsen", "gutschrift", "eingehend", "einzahlung", "dividende").any(value::contains) -> 1L
        else -> error("Die Ein-/Ausgangsrichtung einer Trade-Republic-Buchung ist nicht eindeutig.")
    }
}

private fun parseStatementDate(value: String): String? {
    runCatching { LocalDate.parse(value).toString() }.getOrNull()?.let { return it }
    runCatching {
        val parts = value.trim().split('.', '/')
        LocalDate.of(parts[2].toInt(), parts[1].toInt(), parts[0].toInt()).toString()
    }.getOrNull()?.let { return it }
    for (formatter in listOf(englishStatementDate, germanStatementDate)) {
        runCatching { LocalDate.parse(value.trim().replace(Regex("\\s+"), " "), formatter).toString() }
            .getOrNull()?.let { return it }
    }
    return null
}

internal fun parseStatementMoney(value: String): Long {
    val cleaned = value.replace("EUR", "", ignoreCase = true).replace("€", "")
        .replace("−", "-").replace(Regex("[\\s\\u00a0\\u202f']"), "").trim()
    val sign = if (cleaned.startsWith('-')) -1 else 1
    val unsigned = cleaned.removePrefix("-").removePrefix("+")
    val decimalSeparator = maxOf(unsigned.lastIndexOf(','), unsigned.lastIndexOf('.'))
    require(decimalSeparator >= 0) { "Ungültiger Betrag im Trade-Republic-Kontoauszug." }
    val integer = unsigned.substring(0, decimalSeparator).replace(",", "").replace(".", "")
    val decimal = unsigned.substring(decimalSeparator + 1)
    require(decimal.length == 2 && integer.isNotBlank()) { "Ungültiger Betrag im Trade-Republic-Kontoauszug." }
    val normalized = "$integer.$decimal"
    return BigDecimal(normalized).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact() * sign
}

private class LimitedTradeRepublicInputStream(input: InputStream, private val maxBytes: Long) : FilterInputStream(input) {
    private var consumed = 0L
    override fun read(): Int {
        val value = super.read()
        if (value >= 0 && ++consumed > maxBytes) throw IllegalArgumentException("Das Trade-Republic-PDF ist größer als 60 MB.")
        return value
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val allowed = minOf(length.toLong(), maxBytes - consumed + 1).coerceAtLeast(1).toInt()
        val count = super.read(buffer, offset, allowed)
        if (count > 0 && (consumed + count > maxBytes)) throw IllegalArgumentException("Das Trade-Republic-PDF ist größer als 60 MB.")
        if (count > 0) consumed += count
        return count
    }
}
