package de.kontoklar.app

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

fun shareInvoiceDraft(context: Context, invoice: Invoice, profile: BusinessProfile) {
    val directory = File(context.filesDir, "invoices").apply { mkdirs() }
    val file = File(directory, "${invoice.number.ifBlank { invoice.id }}-ENTWURF.pdf")
    val document = PdfDocument()
    try {
        val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = page.canvas
        val normal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(35, 48, 43); textSize = 13f }
        val muted = Paint(normal).apply { color = android.graphics.Color.rgb(112, 124, 118); textSize = 10f }
        val title = Paint(normal).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 22f }
        val heading = Paint(normal).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 15f }
        val green = Paint(heading).apply { color = android.graphics.Color.rgb(23, 107, 82) }
        val amounts = invoiceAmountBreakdown(invoice.amountCents, profile.vatRatePercent)
        canvas.drawText(profile.businessName.ifBlank { "KontoKlar" }.take(45), 42f, 54f, green)
        canvas.drawText("RECHNUNGSENTWURF", 42f, 105f, title)
        canvas.drawText(invoice.number.ifBlank { "Rechnungsnummer nicht vergeben" }, 42f, 128f, muted)
        canvas.drawText("NICHT VERSENDET · NICHT ALS STEUERDOKUMENT VERWENDEN", 42f, 148f, muted)

        canvas.drawText("Rechnungsaussteller", 42f, 188f, heading)
        var sellerY = 211f
        val sellerLines = listOf(profile.contactName, profile.street, "${profile.postalCode} ${profile.city}".trim(), profile.email, profile.phone)
            .filter(String::isNotBlank)
        sellerLines.take(6).forEach { line ->
            wrap(line, normal, 235f).take(2).forEach { wrapped -> canvas.drawText(wrapped, 42f, sellerY, muted); sellerY += 15f }
        }
        listOfNotNull(
            profile.taxNumber.takeIf(String::isNotBlank)?.let { "Steuernummer: $it" },
            profile.vatId.takeIf(String::isNotBlank)?.let { "USt-IdNr.: $it" }
        ).take(2).forEach { line -> wrap(line, muted, 245f).forEach { canvas.drawText(it, 42f, sellerY, muted); sellerY += 14f } }

        canvas.drawText("Rechnung an", 315f, 188f, heading)
        canvas.drawText(invoice.customer.take(36), 315f, 211f, normal)
        var recipientY = 229f
        (invoice.customerAddress.lines() + invoice.customerEmail).filter(String::isNotBlank).take(5).forEach { line ->
            wrap(line, muted, 235f).take(2).forEach { canvas.drawText(it, 315f, recipientY, muted); recipientY += 15f }
        }
        canvas.drawText("Rechnungsdatum: ${invoice.date}", 315f, maxOf(recipientY + 10f, 300f), normal)
        canvas.drawText("Leistungsdatum: ${invoice.serviceDate}", 315f, maxOf(recipientY + 29f, 319f), normal)
        canvas.drawText("Fällig am: ${invoice.dueDate}", 315f, maxOf(recipientY + 48f, 338f), normal)

        val tableTop = maxOf(sellerY, recipientY + 68f, 365f) + 12f
        canvas.drawLine(42f, tableTop, 553f, tableTop, muted)
        canvas.drawText("Leistung / Beschreibung", 42f, tableTop + 25f, heading)
        canvas.drawText("Brutto", 480f, tableTop + 25f, heading)
        var y = tableTop + 53f
        wrap(invoice.description, normal, 405f).take(5).forEach { line ->
            canvas.drawText(line, 42f, y, normal)
            y += 19f
        }
        canvas.drawText(formatEuro(amounts.grossCents), 480f, tableTop + 53f, normal)
        y += 15f
        canvas.drawLine(42f, y, 553f, y, muted)
        y += 23f
        canvas.drawText("Nettobetrag", 370f, y, normal); canvas.drawText(formatEuro(amounts.netCents), 480f, y, normal)
        y += 19f
        canvas.drawText("Umsatzsteuer (${profile.vatRatePercent} %)", 370f, y, normal); canvas.drawText(formatEuro(amounts.vatCents), 480f, y, normal)
        y += 24f
        canvas.drawText("Gesamtbetrag", 370f, y, heading); canvas.drawText(formatEuro(amounts.grossCents), 480f, y, heading)
        if (profile.iban.isNotBlank()) {
            y += 34f
            canvas.drawText("Zahlung per Überweisung", 42f, y, heading)
            canvas.drawText("IBAN: ${profile.iban}", 42f, y + 19f, normal)
            if (profile.businessName.isNotBlank()) canvas.drawText("Empfänger: ${profile.businessName.take(55)}", 42f, y + 37f, normal)
        }
        canvas.drawText("Entwurf: Angaben prüfen und vor Versand ergänzen. Nicht als fertige Rechnung verwenden.", 42f, 785f, muted)
        canvas.drawText("KontoKlar · Arbeitsdokument", 42f, 805f, muted)
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

internal fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
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
