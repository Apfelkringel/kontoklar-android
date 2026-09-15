package de.kontoklar.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun IncomingInvoiceReviewDialog(
    invoice: ParsedIncomingInvoice,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("E-Rechnung prüfen", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                Text("Lieferant", fontWeight = FontWeight.SemiBold)
                Text(invoice.supplier)
                Spacer(Modifier.height(10.dp))
                Text("Rechnungsnummer", fontWeight = FontWeight.SemiBold)
                Text(invoice.invoiceNumber)
                Spacer(Modifier.height(10.dp))
                Text("Leistung", fontWeight = FontWeight.SemiBold)
                Text(invoice.description)
                Spacer(Modifier.height(10.dp))
                Text("Bruttobetrag", fontWeight = FontWeight.SemiBold)
                Text(formatEuro(invoice.amountCents))
                invoice.vatCents?.let { Text("Enthaltene Umsatzsteuer: ${formatEuro(it)}") }
                Spacer(Modifier.height(10.dp))
                Text("Rechnungsdatum", fontWeight = FontWeight.SemiBold)
                Text(invoice.date)
                invoice.dueDate?.let { Text("Fällig am: $it") }
                Spacer(Modifier.height(10.dp))
                Text("Datei: ${invoice.sourceName}")
                Spacer(Modifier.height(8.dp))
                Text("Prüfe die übernommenen Daten. Beim Übernehmen wird eine Ausgabe angelegt und die Original-XML als Beleg angehängt. Dieses XML kann lokal, ohne Übertragung an einen Server, verarbeitet werden.")
                Spacer(Modifier.height(6.dp))
                Text("Unterstützt werden XRechnung-UBL/CII sowie ZUGFeRD- und Factur-X-PDFs mit eingebetteter CII-Rechnung. Die PDF-Datei wird als Originalbeleg gespeichert.")
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors()) {
                Text("Als Ausgabe übernehmen")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}
