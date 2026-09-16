package de.kontoklar.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TradeRepublicPdfExtractionTest {
    @Test fun extractsAndImportsStatementRowsFromAnActualPdf() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        initializePdfBoxIfNeeded(context)
        val pdf = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font.HELVETICA, 10f)
                stream.newLineAtOffset(40f, 780f)
                listOf(
                    "TRADE REPUBLIC BANK GMBH",
                    "ACCOUNT TRANSACTIONS",
                    "DATE TYPE DESCRIPTION MONEY IN MONEY OUT BALANCE",
                    "01 Apr 2026 Transfer Incoming main bank 800.00 800.00",
                    "02 Apr 2026 Card Transaction Market 42.90 757.10"
                ).forEachIndexed { index, line ->
                    if (index > 0) stream.newLineAtOffset(0f, -14f)
                    stream.showText(line)
                }
                stream.endText()
            }
            document.save(pdf)
        }

        val parsed = parseTradeRepublicStatementPdf(ByteArrayInputStream(pdf.toByteArray()), context)

        assertEquals(listOf(80_000L, -4_290L), parsed.transactions.map(BankTransaction::amountCents))
        assertEquals(listOf("2026-04-01", "2026-04-02"), parsed.transactions.map(BankTransaction::date))
    }
}
