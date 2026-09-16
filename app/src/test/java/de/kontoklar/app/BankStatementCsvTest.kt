package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BankStatementCsvTest {
    @Test fun importsC24CsvWithQuotedSeparatorsAndGermanAmounts() {
        val csv = """Transaktionstyp;Buchungsdatum;Betrag;Zahlungsempfänger;IBAN;Verwendungszweck;Beschreibung
            SEPA-Überweisung;16.09.2026;-1.234,56;Lieferant GmbH;DE89370400440532013000;Rechnung 42;"Material; dringend"
            Gutschrift;2026-09-17;250,00;Kundin;DE02120300000000202051;RE-8;Überweisung
        """.trimIndent()

        val parsed = parseBankStatementCsv(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))

        assertEquals(2, parsed.transactions.size)
        assertEquals(listOf("DE89370400440532013000", "DE02120300000000202051"), parsed.accountIbans)
        assertEquals(-123456L, parsed.transactions[0].amountCents)
        assertEquals("2026-09-16", parsed.transactions[0].date)
        assertEquals("Lieferant GmbH", parsed.transactions[0].counterparty)
        assertTrue(parsed.transactions[0].description.contains("Material; dringend"))
        assertEquals(25_000L, parsed.transactions[1].amountCents)
    }

    @Test fun importsComdirectCsvWithBomAndSemicolonDecimal() {
        val csv = "\uFEFF\"Buchungstag\";\"Wertstellung (Valuta)\";\"Vorgang\";\"Buchungstext\";\"Umsatz in EUR\"\r\n" +
            "\"15.09.2026\";\"15.09.2026\";\"SEPA-Lastschrift\";\"Empfänger: Stadtwerke\";\"-87,42\"\r\n"

        val parsed = parseBankStatement(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))

        assertEquals(1, parsed.transactions.size)
        assertEquals(-8_742L, parsed.transactions.single().amountCents)
        assertEquals("2026-09-15", parsed.transactions.single().date)
        assertTrue(parsed.transactions.single().description.contains("SEPA-Lastschrift"))
        assertTrue(parsed.transactions.single().description.contains("Empfänger: Stadtwerke"))
    }

    @Test fun rejectsUnknownExportsAndRowsWithInvalidAmounts() {
        val unknown = "name;sum\nA;12,00"
        assertTrue(runCatching { parseBankStatementCsv(ByteArrayInputStream(unknown.toByteArray())) }.isFailure)
        val invalid = "Buchungstag;Vorgang;Umsatz in EUR\n16.09.2026;Überweisung;falsch"
        assertTrue(runCatching { parseBankStatementCsv(ByteArrayInputStream(invalid.toByteArray())) }.isFailure)
    }

    @Test fun importsC24StyleXlsxWithSharedStringsAndExcelDateSerials() {
        val workbook = ByteArrayOutputStream()
        ZipOutputStream(workbook).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write("""<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>Buchungsdatum</t></si><si><t>Betrag</t></si><si><t>Zahlungsempfänger</t></si><si><t>Verwendungszweck</t></si><si><t>15.09.2026</t></si><si><t>Lieferant GmbH</t></si><si><t>Rechnung 42</t></si></sst>""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c><c r="D1" t="s"><v>3</v></c></row><row r="2"><c r="A2"><v>46280</v></c><c r="B2"><v>-1234.56</v></c><c r="C2" t="s"><v>5</v></c><c r="D2" t="s"><v>6</v></c></row></sheetData></worksheet>""".toByteArray())
            zip.closeEntry()
        }

        val parsed = parseBankStatement(ByteArrayInputStream(workbook.toByteArray()))

        assertEquals(1, parsed.transactions.size)
        assertEquals("2026-09-15", parsed.transactions.single().date)
        assertEquals(-123_456L, parsed.transactions.single().amountCents)
        assertEquals("Lieferant GmbH", parsed.transactions.single().counterparty)
        assertTrue(parsed.transactions.single().description.contains("Rechnung 42"))
    }
}
