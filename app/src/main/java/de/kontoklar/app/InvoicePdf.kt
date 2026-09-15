package de.kontoklar.app

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

fun shareInvoiceDraft(context: Context, invoice: Invoice) {
    val directory = File(context.filesDir, "invoices").apply { mkdirs() }
    val file = File(directory, "${invoice.number.ifBlank { invoice.id }}-ENTWURF.pdf")
    val document = PdfDocument()
    try {
        val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = page.canvas
        val normal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(35, 48, 43); textSize = 13f }
        val muted = Paint(normal).apply { color = android.graphics.Color.rgb(112, 124, 118); textSize = 10f }
        val title = Paint(normal).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 25f }
        val heading = Paint(normal).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 15f }
        val green = Paint(heading).apply { color = android.graphics.Color.rgb(23, 107, 82) }
        canvas.drawText("KontoKlar", 42f, 62f, green)
        canvas.drawText("RECHNUNGSENTWURF", 42f, 125f, title)
        canvas.drawText(invoice.number.ifBlank { "Rechnungsnummer nicht vergeben" }, 42f, 153f, muted)
        canvas.drawText("NICHT VERSENDET · NICHT ALS STEUERDOKUMENT VERWENDEN", 42f, 180f, muted)

        canvas.drawText("Rechnung an", 42f, 240f, heading)
        canvas.drawText(invoice.customer.take(80), 42f, 265f, normal)
        var recipientY = 283f
        (invoice.customerAddress.lines() + invoice.customerEmail).filter(String::isNotBlank).take(3).forEach { line ->
            canvas.drawText(line.take(76), 42f, recipientY, muted)
            recipientY += 16f
        }
        canvas.drawText("Rechnungsdatum: ${invoice.date}", 340f, 240f, normal)
        canvas.drawText("Fällig am: ${invoice.dueDate}", 340f, 265f, normal)

        val tableTop = maxOf(310f, recipientY + 12f)
        canvas.drawLine(42f, tableTop, 553f, tableTop, muted)
        canvas.drawText("Beschreibung", 42f, tableTop + 28f, heading)
        canvas.drawText("Betrag", 455f, tableTop + 28f, heading)
        var y = tableTop + 62f
        wrap(invoice.description, normal, 385f).take(8).forEach { line ->
            canvas.drawText(line, 42f, y, normal)
            y += 19f
        }
        canvas.drawText(formatEuro(invoice.amountCents), 455f, tableTop + 62f, normal)
        y = maxOf(y + 30f, 430f)
        canvas.drawLine(42f, y, 553f, y, muted)
        y += 34f
        canvas.drawText("Gesamtbetrag", 340f, y, heading)
        canvas.drawText(formatEuro(invoice.amountCents), 455f, y, heading)
        canvas.drawText("Dieser PDF-Entwurf enthält keine vollständigen Pflichtangaben. Ergänze vor Versand dein Unternehmensprofil, Steuerangaben und Zahlungsdaten.", 42f, 770f, muted)
        document.finishPage(page)
        FileOutputStream(file).use(document::writeTo)
    } finally {
        document.close()
    }

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_SUBJECT, "Rechnungsentwurf ${invoice.number}")
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Rechnungsentwurf teilen"))
}

private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
    val words = text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    val result = mutableListOf<String>()
    var line = ""
    for (word in words) {
        val candidate = if (line.isEmpty()) word else "$line $word"
        if (line.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
            result += line
            line = word
        } else line = candidate
    }
    if (line.isNotEmpty()) result += line
    return result
}
