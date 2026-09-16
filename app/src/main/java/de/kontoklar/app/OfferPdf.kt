package de.kontoklar.app

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

fun shareOfferDraft(context: Context, offer: Offer) {
    val directory = File(context.filesDir, "invoices").apply { mkdirs() }
    val file = File(directory, "${offer.number.ifBlank { offer.id }}-ANGEBOTSENTWURF.pdf")
    val document = PdfDocument()
    try {
        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
        var canvas = page.canvas
        val normal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(35, 48, 43); textSize = 13f }
        val muted = Paint(normal).apply { color = android.graphics.Color.rgb(112, 124, 118); textSize = 10f }
        val title = Paint(normal).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 25f }
        val heading = Paint(normal).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 15f }
        val green = Paint(heading).apply { color = android.graphics.Color.rgb(23, 107, 82) }
        canvas.drawText("KontoKlar", 42f, 62f, green)
        canvas.drawText("ANGEBOTSENTWURF", 42f, 125f, title)
        canvas.drawText(offer.number.ifBlank { "Angebotsnummer nicht vergeben" }, 42f, 153f, muted)
        canvas.drawText("NICHT VERSENDET · ENTWURF", 42f, 180f, muted)
        canvas.drawText("Angebot für", 42f, 240f, heading)
        canvas.drawText(offer.customer.take(80), 42f, 265f, normal)
        var recipientY = 283f
        (offer.customerAddress.lines() + offer.customerEmail).filter(String::isNotBlank).take(3).forEach { line ->
            canvas.drawText(line.take(76), 42f, recipientY, muted)
            recipientY += 16f
        }
        canvas.drawText("Angebotsdatum: ${offer.date}", 340f, 240f, normal)
        canvas.drawText("Gültig bis: ${offer.validUntil}", 340f, 265f, normal)
        fun drawTableHeader(top: Float) {
            canvas.drawLine(42f, top, 553f, top, muted)
            canvas.drawText("Leistung", 42f, top + 28f, heading)
            canvas.drawText("Gesamtbetrag", 420f, top + 28f, heading)
        }
        fun nextPage(): Float {
            document.finishPage(page)
            pageNumber += 1
            page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            canvas = page.canvas
            canvas.drawText("ANGEBOTSENTWURF · FORTSETZUNG", 42f, 58f, heading)
            canvas.drawText(offer.number, 42f, 80f, muted)
            drawTableHeader(104f)
            return 158f
        }
        val tableTop = maxOf(310f, recipientY + 12f)
        drawTableHeader(tableTop)
        var y = tableTop + 62f
        offerLines(offer).forEach { item ->
            val descriptionLines = wrapOffer(item.description, normal, 350f).ifEmpty { listOf("") }
            val lineHeight = maxOf(22f, descriptionLines.size * 19f)
            if (y + lineHeight > 725f) y = nextPage()
            descriptionLines.forEachIndexed { index, descriptionLine ->
                canvas.drawText(descriptionLine, 42f, y + index * 19f, normal)
            }
            canvas.drawText(formatEuro(item.amountCents), 420f, y, normal)
            y += lineHeight + 10f
            canvas.drawLine(42f, y - 5f, 553f, y - 5f, muted)
        }
        if (y + 100f > 725f) y = nextPage()
        y += 29f
        canvas.drawText("Gesamtbetrag", 340f, y, heading)
        canvas.drawText(formatEuro(offer.amountCents), 420f, y, heading)
        canvas.drawText("Dieser Angebotsentwurf ist unvollständig. Prüfe Pflichtangaben und Bedingungen vor dem Versand.", 42f, 770f, muted)
        canvas.drawText("KontoKlar · Seite $pageNumber", 42f, 790f, muted)
        document.finishPage(page)
        FileOutputStream(file).use(document::writeTo)
    } finally {
        document.close()
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_SUBJECT, "Angebotsentwurf ${offer.number}")
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Angebotsentwurf teilen"))
}

private fun wrapOffer(text: String, paint: Paint, maxWidth: Float): List<String> {
    val result = mutableListOf<String>()
    var line = ""
    for (word in text.trim().split(Regex("\\s+")).filter(String::isNotBlank)) {
        val candidate = if (line.isEmpty()) word else "$line $word"
        if (line.isNotEmpty() && paint.measureText(candidate) > maxWidth) { result += line; line = word } else line = candidate
    }
    if (line.isNotEmpty()) result += line
    return result
}
