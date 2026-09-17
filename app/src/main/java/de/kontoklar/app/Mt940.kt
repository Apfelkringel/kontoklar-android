package de.kontoklar.app

import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.Charset
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val MAX_MT940_BYTES = 20L * 1024 * 1024
private const val MAX_MT940_ENTRIES = 50_000
private val MT940_DATE = DateTimeFormatter.ofPattern("yyMMdd")

/** Imports read-only MT940/Swift statements locally. No network access or credentials are involved. */
fun parseMt940(input: InputStream): ParsedBankStatement {
    val bytes = readUpTo(input, (MAX_MT940_BYTES + 1).toInt())
    require(bytes.size <= MAX_MT940_BYTES) { "Die MT940-Datei ist größer als 20 MB." }
    val content = decodeMt940(bytes)
    val lines = content.replace("\r\n", "\n").replace('\r', '\n').lines()
    val account = lines.firstNotNullOfOrNull { line ->
        line.removePrefix(":25:").takeIf { line.startsWith(":25:") }?.trim()
    }.orEmpty()
    val transactions = mutableListOf<BankTransaction>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (!line.startsWith(":61:")) {
            index++
            continue
        }
        val booking = line.removePrefix(":61:").trim()
        val parsed = parseMt940Entry(booking, account)
        index++
        val additional = StringBuilder()
        if (index < lines.size && lines[index].startsWith(":86:")) {
            additional.append(lines[index].removePrefix(":86:").trim())
            index++
        }
        while (index < lines.size && !lines[index].startsWith(":")) {
            if (lines[index].isNotBlank()) {
                if (additional.isNotEmpty()) additional.append(' ')
                additional.append(lines[index].trim())
            }
            index++
        }
        val description = listOf(parsed.description, additional.toString())
            .filter(String::isNotBlank).joinToString(" · ").take(800)
        transactions += parsed.copy(
            description = description,
            id = transactionId(account, parsed.date, parsed.amountCents, description, parsed.reference)
        )
        require(transactions.size <= MAX_MT940_ENTRIES) { "Die MT940-Datei enthält mehr als $MAX_MT940_ENTRIES Buchungen." }
    }
    require(transactions.isNotEmpty()) { "Die MT940-Datei enthält keine importierbaren Buchungen." }
    return ParsedBankStatement(account.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(), transactions.distinctBy(BankTransaction::id))
}

private fun parseMt940Entry(value: String, account: String): BankTransaction {
    require(value.length >= 8) { "Eine MT940-Buchung ist zu kurz." }
    val date = runCatching { LocalDate.parse(value.substring(0, 6), MT940_DATE) }
        .getOrElse { error("Eine MT940-Buchung enthält ein ungültiges Datum.") }
    var cursor = 6
    if (value.length >= cursor + 4 && value.substring(cursor, cursor + 4).all(Char::isDigit)) cursor += 4
    val direction = when {
        value.startsWith("RC", cursor) -> { cursor += 2; 1 }
        value.startsWith("RD", cursor) -> { cursor += 2; -1 }
        value.getOrNull(cursor) == 'C' -> { cursor++; 1 }
        value.getOrNull(cursor) == 'D' -> { cursor++; -1 }
        else -> error("Eine MT940-Buchung enthält keine Soll/Haben-Kennzeichnung.")
    }
    val amountStart = cursor
    while (cursor < value.length && (value[cursor].isDigit() || value[cursor] == ',' || value[cursor] == '.')) cursor++
    val amountText = value.substring(amountStart, cursor).replace(',', '.')
    val cents = BigDecimal(amountText).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
    require(cents > 0) { "Eine MT940-Buchung enthält keinen positiven Betrag." }
    val reference = value.substring(cursor).trim().take(300)
    return BankTransaction(
        id = "mt940:pending", accountIban = account, date = date.toString(),
        counterparty = "Unbekannter Zahlungspartner", description = reference,
        amountCents = direction * cents, reference = reference
    )
}

private fun transactionId(account: String, date: String, amount: Long, description: String, reference: String): String =
    "mt940:" + MessageDigest.getInstance("SHA-256").digest(listOf(account, date, amount.toString(), description, reference).joinToString("\u001f").toByteArray()).joinToString("") { "%02x".format(it) }

private fun decodeMt940(bytes: ByteArray): String {
    val utf8 = String(bytes, Charsets.UTF_8)
    if ('�' !in utf8) return utf8
    return String(bytes, Charset.forName("ISO-8859-1"))
}
