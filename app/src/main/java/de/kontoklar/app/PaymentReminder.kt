package de.kontoklar.app

import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val reminderDateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

fun paymentReminderEligible(invoice: Invoice, today: LocalDate = LocalDate.now()): Boolean {
    if (invoice.status == "Entwurf" || invoiceOutstandingCents(invoice) <= 0) return false
    val dueDate = runCatching { LocalDate.parse(invoice.dueDate) }.getOrNull() ?: return false
    return dueDate.isBefore(today)
}

fun paymentReminderEmail(invoice: Invoice, profile: BusinessProfile): Pair<String, String> {
    require(invoice.status != "Entwurf" && invoiceOutstandingCents(invoice) > 0) { "Für diese Rechnung ist kein offener Betrag vorhanden." }
    val dueDate = runCatching { LocalDate.parse(invoice.dueDate) }.getOrNull()
        ?: error("Das Fälligkeitsdatum der Rechnung ist ungültig.")
    val subject = "Zahlungserinnerung · Rechnung ${invoice.number}"
    val sender = profile.businessName.ifBlank { profile.contactName }
    val body = buildString {
        appendLine("Guten Tag ${invoice.customer},")
        appendLine()
        val invoiceDate = runCatching { LocalDate.parse(invoice.date) }.getOrNull()?.format(reminderDateFormat) ?: invoice.date
        appendLine("zu unserer Rechnung ${invoice.number} vom $invoiceDate über ${formatEuro(invoice.amountCents)} war die Zahlung am ${dueDate.format(reminderDateFormat)} fällig.")
        appendLine("Laut unseren Unterlagen ist noch ein Betrag von ${formatEuro(invoiceOutstandingCents(invoice))} offen.")
        appendLine()
        appendLine("Falls Sie die Zahlung bereits veranlasst haben, betrachten Sie diese Nachricht bitte als gegenstandslos. Andernfalls freuen wir uns über einen kurzen Abgleich.")
        if (profile.iban.isNotBlank()) {
            appendLine()
            appendLine("IBAN: ${profile.iban}")
            if (sender.isNotBlank()) appendLine("Kontoinhaber: $sender")
            appendLine("Verwendungszweck: Rechnung ${invoice.number}")
        }
        appendLine()
        appendLine("Freundliche Grüße")
        append(sender.ifBlank { "" })
    }
    return subject to body
}
