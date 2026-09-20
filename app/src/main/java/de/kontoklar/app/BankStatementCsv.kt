package de.kontoklar.app

import android.content.Context
import java.io.InputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import kotlin.math.roundToLong
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

private const val MAX_BANK_CSV_BYTES = 20L * 1024 * 1024
private const val MAX_BANK_CSV_ROWS = 50_000
private const val MAX_BANK_XLSX_CELLS = 250_000
private const val MAX_BANK_XLSX_COLUMNS = 256

/** API-26-compatible equivalent of InputStream.readNBytes(limit). */
internal fun readUpTo(input: InputStream, limit: Int): ByteArray {
    require(limit >= 0) { "Leselimit darf nicht negativ sein." }
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    var remaining = limit
    while (remaining > 0) {
        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
        if (count < 0) break
        if (count == 0) continue
        output.write(buffer, 0, count)
        remaining -= count
    }
    return output.toByteArray()
}

fun parseBankStatement(input: InputStream, context: Context? = null): ParsedBankStatement {
    val buffered = if (input.markSupported()) input else BufferedInputStream(input)
    buffered.mark(512)
    val head = readUpTo(buffered, 512)
    buffered.reset()
    if (head.size >= 5 && String(head.copyOfRange(0, 5), Charsets.US_ASCII) == "%PDF-") {
        return parseBankStatementPdf(buffered, context ?: error("Zum Lesen des Kontoauszug-PDFs wird die Android-PDF-Komponente benötigt."))
    }
    if (head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
        return parseBankStatementXlsx(buffered)
    }
    val start = head.firstOrNull { !it.toInt().toChar().isWhitespace() && it != 0xEF.toByte() && it != 0xBB.toByte() && it != 0xBF.toByte() }
    return when {
        start == '<'.code.toByte() -> parseCamt053(buffered)
        String(head, Charsets.US_ASCII).contains(":61:") -> parseMt940(buffered)
        else -> parseBankStatementCsv(buffered)
    }
}

/** Reads the first worksheet of an Excel .xlsx export entirely on-device. */
fun parseBankStatementXlsx(input: InputStream): ParsedBankStatement {
    val archive = readUpTo(input, (MAX_BANK_CSV_BYTES + 1).toInt())
    require(archive.size <= MAX_BANK_CSV_BYTES) { "Die Excel-Datei ist größer als 20 MB." }
    val entries = mutableMapOf<String, ByteArray>()
    var totalBytes = 0L
    ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) {
                val keepContent = entry.name in setOf(
                    "xl/sharedStrings.xml", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/worksheets/sheet1.xml"
                ) || (entry.name.startsWith("xl/worksheets/") && entry.name.endsWith(".xml"))
                val content = if (keepContent) ByteArrayOutputStream() else null
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    require(totalBytes <= MAX_BANK_CSV_BYTES) { "Die Excel-Datei enthält mehr als 20 MB entpackte Daten." }
                    content?.write(buffer, 0, count)
                }
                content?.let { entries[entry.name] = it.toByteArray() }
            }
            zip.closeEntry()
        }
    }
    val sheetPath = if (entries["xl/workbook.xml"] != null && entries["xl/_rels/workbook.xml.rels"] != null) {
        readXlsxFirstSheetPath(entries.getValue("xl/workbook.xml"), entries.getValue("xl/_rels/workbook.xml.rels"))
    } else "xl/worksheets/sheet1.xml"
    val sheet = entries[sheetPath] ?: error("Die Excel-Datei enthält kein lesbares erstes Tabellenblatt.")
    val sharedStrings = entries["xl/sharedStrings.xml"]?.let(::readXlsxSharedStrings).orEmpty()
    val rows = readXlsxRows(sheet, sharedStrings)
    require(rows.isNotEmpty()) { "Das Excel-Tabellenblatt ist leer." }
    val csv = rows.joinToString("\n") { row -> row.joinToString(";") { value -> "\"${value.replace("\"", "\"\"")}\"" } }
    return parseBankStatementCsv(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))
}

private fun readXlsxFirstSheetPath(workbook: ByteArray, relationships: ByteArray): String {
    val workbookParser = XmlPullParserFactory.newInstance().newPullParser().apply {
        setInput(ByteArrayInputStream(workbook), "UTF-8")
    }
    var relationshipId: String? = null
    var event = workbookParser.eventType
    while (event != XmlPullParser.END_DOCUMENT && relationshipId == null) {
        if (event == XmlPullParser.START_TAG && workbookParser.name == "sheet") {
            for (index in 0 until workbookParser.attributeCount) {
                if (workbookParser.getAttributeName(index).substringAfter(':') == "id") {
                    relationshipId = workbookParser.getAttributeValue(index)
                    break
                }
            }
        }
        event = workbookParser.next()
    }
    val firstSheetId = relationshipId ?: error("Die Excel-Datei enthält kein Tabellenblatt.")

    val relsParser = XmlPullParserFactory.newInstance().newPullParser().apply {
        setInput(ByteArrayInputStream(relationships), "UTF-8")
    }
    var target: String? = null
    event = relsParser.eventType
    while (event != XmlPullParser.END_DOCUMENT && target == null) {
        if (event == XmlPullParser.START_TAG && relsParser.name == "Relationship" &&
            relsParser.getAttributeValue(null, "Id") == firstSheetId &&
            relsParser.getAttributeValue(null, "TargetMode") != "External") {
            target = relsParser.getAttributeValue(null, "Target")
        }
        event = relsParser.next()
    }
    val rawTarget = target ?: error("Das erste Excel-Tabellenblatt ist nicht mit einer Arbeitsmappe verknüpft.")
    val normalized = if (rawTarget.startsWith("/")) rawTarget.removePrefix("/")
    else "xl/$rawTarget"
    require(normalized.startsWith("xl/worksheets/") && !normalized.split('/').any { it == ".." }) {
        "Der Excel-Arbeitsmappenverweis ist ungültig."
    }
    return normalized
}

private fun readXlsxSharedStrings(xml: ByteArray): List<String> {
    val parser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(ByteArrayInputStream(xml), "UTF-8") }
    val values = mutableListOf<String>()
    var inItem = false
    var item = StringBuilder()
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> if (parser.name == "si") { inItem = true; item = StringBuilder() }
            XmlPullParser.TEXT -> if (inItem) item.append(parser.text)
            XmlPullParser.END_TAG -> if (parser.name == "si" && inItem) { values += item.toString(); inItem = false }
        }
        event = parser.next()
    }
    return values
}

private fun readXlsxRows(xml: ByteArray, sharedStrings: List<String>): List<List<String>> {
    val parser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(ByteArrayInputStream(xml), "UTF-8") }
    val rows = mutableListOf<List<String>>()
    var currentRow: MutableMap<Int, String>? = null
    var cellColumn = -1
    var cellType = ""
    var cellValue = StringBuilder()
    var inValue = false
    var parsedCells = 0
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "row" -> currentRow = sortedMapOf()
                "c" -> {
                    cellColumn = xlsxColumnIndex(parser.getAttributeValue(null, "r").orEmpty())
                    cellType = parser.getAttributeValue(null, "t").orEmpty()
                    cellValue = StringBuilder()
                }
                "v", "t" -> if (currentRow != null && cellColumn >= 0) inValue = true
            }
            XmlPullParser.TEXT -> if (inValue) cellValue.append(parser.text)
            XmlPullParser.END_TAG -> when (parser.name) {
                "v", "t" -> inValue = false
                "c" -> if (currentRow != null && cellColumn >= 0) {
                    val raw = cellValue.toString()
                    currentRow!![cellColumn] = if (cellType == "s") sharedStrings.getOrNull(raw.toIntOrNull() ?: -1).orEmpty() else raw
                    parsedCells++
                    require(parsedCells <= MAX_BANK_XLSX_CELLS) { "Das Excel-Tabellenblatt enthält zu viele Zellen." }
                    cellColumn = -1
                }
                "row" -> currentRow?.let { values ->
                    if (values.isNotEmpty()) {
                        require(rows.size < MAX_BANK_CSV_ROWS) { "Das Excel-Tabellenblatt enthält zu viele Buchungszeilen." }
                        val last = values.keys.maxOrNull() ?: -1
                        rows += (0..last).map { values[it].orEmpty() }
                    }
                    currentRow = null
                }
            }
        }
        event = parser.next()
    }
    return rows
}

private fun xlsxColumnIndex(reference: String): Int {
    var value = 0
    var found = false
    for (character in reference) {
        if (!character.isLetter()) break
        found = true
        value = value * 26 + (character.uppercaseChar() - 'A' + 1)
    }
    return if (found && value <= MAX_BANK_XLSX_COLUMNS) value - 1 else -1
}

/** Imports supported bank exports without sending them off-device.
 *
 * This also accepts Trade Republic's native transaction export and the
 * documented pytr community export. The native export uses
 * datetime,date,category,type,...,amount,fee,tax,... and its three money
 * columns form the net cash movement.
 */
fun parseBankStatementCsv(input: InputStream): ParsedBankStatement {
    val bytes = readUpTo(input, (MAX_BANK_CSV_BYTES + 1).toInt())
    require(bytes.size <= MAX_BANK_CSV_BYTES) { "Die CSV-Datei ist größer als 20 MB." }
    val content = decodeBankCsv(bytes)
    val delimiter = listOf(';', ',', '\t').maxBy { candidate -> content.lineSequence().firstOrNull().orEmpty().count { it == candidate } }
    val rows = parseCsvRows(content, delimiter)
    val headerIndex = rows.indexOfFirst { row ->
        val headers = row.map(::normalizeBankCsvHeader)
        headers.any { it in setOf("buchungstag", "buchungsdatum", "bookingdate", "date", "datum") } &&
            headers.any {
                it.contains("umsatzineur") || it == "betrag" || it == "amount" || it == "value" ||
                    it == "wert" || it == "betragineur" || it == "betrageur" ||
                    it == "saldonachbuchung" || it.contains("zahlungseingang")
            }
    }
    require(headerIndex >= 0) { "Die CSV-Datei sieht nicht wie ein unterstützter Umsatzexport aus (C24, comdirect, DKB, ING, N26)." }
    val headers = rows[headerIndex].map(::normalizeBankCsvHeader)
    fun column(vararg names: String): Int = headers.indexOfFirst { it in names }
    val dateColumn = column("buchungstag", "buchungsdatum", "bookingdate", "date", "datum")
    val amountColumn = column(
        "umsatzineur", "betrag", "amount", "value", "wert", "betragineur", "betrageur", "saldonachbuchung"
    )
    val feeColumn = headers.indexOfFirst { it in setOf("fee", "fees", "gebuehr", "gebuehren") }
    val taxColumn = headers.indexOfFirst { it in setOf("tax", "taxes", "steuer", "steuern") }
    val transactionIdColumn = headers.indexOfFirst { it in setOf("transactionid", "transaktionsid", "transactionid") }
    val officialTradeRepublicCsv = headers.contains("datetime") && headers.contains("assetclass") &&
        headers.contains("currency")
    val creditColumn = headers.indexOfFirst { it.contains("zahlungseingang") || it == "credit" }
    val debitColumn = headers.indexOfFirst { it.contains("zahlungsausgang") || it == "debit" }
    val typeColumn = headers.indexOfFirst { it in setOf("transaktionstyp", "vorgang", "umsatzart", "typ", "type") }
    val counterpartyColumn = headers.indexOfFirst {
        it in setOf("zahlungsempfanger", "empfanger", "auftraggeber", "gegenkonto", "counterparty", "name", "note", "notiz")
    }
    val counterpartyNameColumn = headers.indexOfFirst { it in setOf("counterpartyname", "gegenparteiname") }
    val referenceColumn = headers.indexOfFirst { it in setOf("paymentreference", "zahlungsreferenz", "referenz") }
    val detailColumn = headers.indexOfFirst { it in setOf("description", "beschreibung", "details") }
    val purposeColumns = headers.mapIndexedNotNull { index, header ->
        index.takeIf { header in setOf("verwendungszweck", "buchungstext", "beschreibung", "purpose", "description", "remittance") }
    }
    val ibanColumn = headers.indexOfFirst { it == "iban" || it.contains("iban") }
    val symbolColumn = headers.indexOfFirst { it == "symbol" || it == "isin" }
    val sharesColumn = headers.indexOfFirst { it == "shares" || it == "stuck" || it == "anteile" }
    val priceColumn = headers.indexOfFirst { it == "price" || it == "kurs" }
    val currencyColumn = headers.indexOfFirst { it == "currency" || it == "waehrung" }
    val nativeTradeShape = officialTradeRepublicCsv && symbolColumn >= 0 && sharesColumn >= 0 && priceColumn >= 0
    require(dateColumn >= 0 && (amountColumn >= 0 || creditColumn >= 0 || debitColumn >= 0)) { "In der CSV fehlen Buchungsdatum oder Betrag." }

    val duplicateOrdinals = mutableMapOf<String, Int>()
    val nativeTradeRows = mutableListOf<NativeTradeRow>()
    val parsed = rows.drop(headerIndex + 1).asSequence().filter { it.any(String::isNotBlank) }.mapNotNull { row ->
        fun value(index: Int): String = row.getOrNull(index)?.trim().orEmpty()
        val rawDate = value(dateColumn)
        if (rawDate.isBlank()) return@mapNotNull null
        val date = parseBankCsvDate(rawDate) ?: error("Ungültiges Buchungsdatum in der CSV: $rawDate")
        val signedAmount = when {
            creditColumn >= 0 || debitColumn >= 0 -> {
                val incoming = value(creditColumn).takeIf(String::isNotBlank)?.let(::parseBankCsvMoneyCents) ?: 0L
                val outgoing = value(debitColumn).takeIf(String::isNotBlank)?.let(::parseBankCsvMoneyCents) ?: 0L
                require(incoming >= 0 && outgoing >= 0 && (incoming == 0L) != (outgoing == 0L)) {
                    "Eine CSV-Buchung muss genau einen positiven Zahlungseingang oder -ausgang enthalten."
                }
                if (incoming > 0) incoming else -outgoing
            }
            else -> {
                val rawAmount = value(amountColumn)
                val amount = if (nativeTradeShape && rawAmount.isBlank()) 0L else parseBankCsvMoneyCents(rawAmount)
                if (!officialTradeRepublicCsv) amount
                else amount + listOf(feeColumn, taxColumn)
                    .filter { it >= 0 }
                    .sumOf { index -> value(index).takeIf(String::isNotBlank)?.let(::parseBankCsvMoneyCents) ?: 0L }
            }
        }
        val type = value(typeColumn)
        val counterparty = value(counterpartyColumn).ifBlank { value(counterpartyNameColumn) }
        val purpose = (purposeColumns.map(::value) + listOf(value(detailColumn))).filter(String::isNotBlank).distinct()
        val description = (listOf(type) + purpose).filter(String::isNotBlank).distinct().joinToString(" · ").take(800)
        val iban = value(ibanColumn)
        val sourceId = value(transactionIdColumn)
        if (nativeTradeShape && typeColumn >= 0) {
            parseBankCsvQuantity(value(sharesColumn))?.takeIf { it > 0.0 }?.let { shares ->
                nativeTradeRows += NativeTradeRow(
                    id = sourceId,
                    type = type,
                    isin = value(symbolColumn),
                    name = counterparty,
                    shares = shares,
                    priceMinor = priceColumn.takeIf { it >= 0 }?.let { index -> value(index).takeIf(String::isNotBlank)?.let(::parseBankCsvMoneyCents) },
                    currency = value(currencyColumn).ifBlank { "EUR" },
                    date = date
                )
            }
        }
        if (signedAmount == 0L) return@mapNotNull null
        val stableFields = if (sourceId.isNotBlank()) listOf(sourceId) else listOf(iban, date, signedAmount.toString(), counterparty, description)
        val stableKey = stableFields.joinToString("\u001f")
        val ordinal = duplicateOrdinals.getOrDefault(stableKey, 0)
        duplicateOrdinals[stableKey] = ordinal + 1
        val id = MessageDigest.getInstance("SHA-256").digest("$stableKey\u001f$ordinal".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        BankTransaction(id, iban, date, counterparty.ifBlank { "Unbekannter Zahlungspartner" }, description, signedAmount, value(referenceColumn))
    }.toList()
    require(parsed.isNotEmpty() || nativeTradeRows.isNotEmpty()) { "Die CSV-Datei enthält keine importierbaren Buchungen oder Depotdaten." }
    require(parsed.size <= MAX_BANK_CSV_ROWS) { "Die CSV-Datei enthält mehr als $MAX_BANK_CSV_ROWS Buchungen." }
    return ParsedBankStatement(parsed.map(BankTransaction::accountIban).filter(String::isNotBlank).distinct(), parsed, aggregateNativeTradeRepublicPositions(nativeTradeRows))
}

private data class NativeTradeRow(
    val id: String,
    val type: String,
    val isin: String,
    val name: String,
    val shares: Double,
    val priceMinor: Long?,
    val currency: String,
    val date: String
)

private fun aggregateNativeTradeRepublicPositions(rows: List<NativeTradeRow>): List<BankSecurityPosition> {
    if (rows.isEmpty()) return emptyList()
    return rows.groupBy { it.isin.ifBlank { it.name } }.mapNotNull { (key, group) ->
        val latest = group.maxByOrNull { it.date } ?: return@mapNotNull null
        val quantity = group.sumOf { row ->
            when (row.type.uppercase()) {
                "SELL", "REMOVAL" -> -row.shares
                "BUY", "DEPOSIT", "SAVINGS_PLAN_EXECUTED" -> row.shares
                else -> 0.0
            }
        }
        if (quantity <= 0.0000001) return@mapNotNull null
        val marketValue = latest.priceMinor?.let { (it.toDouble() * quantity).roundToLong() }
        BankSecurityPosition(
            id = "trade-republic:csv:${key.ifBlank { latest.id }}",
            accountId = LOCAL_TRADE_REPUBLIC_ACCOUNT_ID,
            connectionId = LOCAL_TRADE_REPUBLIC_CONNECTION_ID,
            name = latest.name.ifBlank { key },
            isin = latest.isin,
            wkn = "",
            quantityNominal = quantity,
            quantityType = "Stück",
            quoteType = "",
            quoteMinor = latest.priceMinor,
            quoteCurrency = latest.currency,
            marketValueMinor = marketValue,
            marketValueCurrency = latest.currency,
            profitOrLossMinor = null,
            quoteDate = latest.date
        )
    }
}

internal const val LOCAL_TRADE_REPUBLIC_ACCOUNT_ID = "local:trade-republic-csv"
private const val LOCAL_TRADE_REPUBLIC_CONNECTION_ID = "local:trade-republic-csv"

private fun decodeBankCsv(bytes: ByteArray): String {
    val decoded = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> String(bytes, Charsets.UTF_8)
    }
    // Older comdirect exports are ISO-8859-15 without a BOM. A replacement
    // character is a reliable signal that UTF-8 decoding was not applicable.
    return if (decoded.contains('\uFFFD')) {
        String(bytes, charset("ISO-8859-15"))
    } else decoded
}

private fun charset(name: String): java.nio.charset.Charset = java.nio.charset.Charset.forName(name)

private fun parseCsvRows(content: String, delimiter: Char): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var index = 0
    while (index < content.length) {
        val character = content[index]
        when {
            character == '"' && quoted && content.getOrNull(index + 1) == '"' -> { field.append('"'); index++ }
            character == '"' -> quoted = !quoted
            character == delimiter && !quoted -> { row += field.toString(); field.setLength(0) }
            (character == '\n' || character == '\r') && !quoted -> {
                if (character == '\r' && content.getOrNull(index + 1) == '\n') index++
                row += field.toString(); field.setLength(0)
                if (row.any(String::isNotBlank)) rows += row
                row = mutableListOf()
            }
            else -> field.append(character)
        }
        index++
    }
    require(!quoted) { "Die CSV-Datei enthält ein nicht geschlossenes Anführungszeichen." }
    row += field.toString()
    if (row.any(String::isNotBlank)) rows += row
    return rows
}

private fun normalizeBankCsvHeader(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase().filter(Char::isLetterOrDigit)

private fun parseBankCsvDate(value: String): String? = runCatching { LocalDate.parse(value.trim()).toString() }.getOrNull()
    ?: runCatching { DateTimeFormatter.ISO_DATE_TIME.parse(value.trim(), LocalDate::from).toString() }.getOrNull()
    ?: runCatching {
        val parts = value.trim().split('.', '/')
        require(parts.size == 3)
        LocalDate.of(parts[2].toInt(), parts[1].toInt(), parts[0].toInt()).toString()
    }.getOrNull()
    ?: runCatching {
        val serial = value.trim().toDouble().toLong()
        require(serial in 1..100_000)
        LocalDate.of(1899, 12, 30).plusDays(serial).toString()
    }.getOrNull()

private fun parseBankCsvMoneyCents(value: String): Long {
    val cleaned = value.trim().replace("€", "").replace("EUR", "", ignoreCase = true).replace("\u00a0", "").replace("\u202f", "").replace(" ", "")
    if (cleaned.isBlank()) return 0L
    val normalized = if (cleaned.contains(',')) cleaned.replace(".", "").replace(',', '.') else cleaned
    return BigDecimal(normalized).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
}

private fun parseBankCsvQuantity(value: String): Double? {
    if (value.isBlank()) return null
    val cleaned = value.trim().replace("\u00a0", "").replace(" ", "")
    val normalized = if (cleaned.contains(',')) cleaned.replace(".", "").replace(',', '.') else cleaned
    return normalized.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
}
