package de.kontoklar.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

private val RecurringInk = Color(0xFF172823)
private val RecurringGreen = Color(0xFF176B52)
private val RecurringMuted = Color(0xFF78827D)

@Composable
fun RecurringInvoiceManagerDialog(
    plans: List<RecurringInvoicePlan>,
    invoices: List<Invoice>,
    paymentTermsDays: Int,
    today: LocalDate = LocalDate.now(),
    onDismiss: () -> Unit,
    onAdd: (RecurringInvoicePlan) -> Unit,
    onGenerate: (RecurringInvoicePlan) -> Unit,
    onToggle: (RecurringInvoicePlan) -> Unit,
    onDelete: (RecurringInvoicePlan) -> Unit
) {
    var editorOpen by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wiederkehrende Rechnungen", color = RecurringInk, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Vorlagen erzeugen zum Fälligkeitstermin nach deiner Bestätigung einen neuen Rechnungsentwurf. Es werden keine Rechnungen automatisch versendet.", color = RecurringMuted, fontSize = 12.sp)
                if (plans.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Noch keine wiederkehrenden Rechnungen", color = RecurringInk, fontWeight = FontWeight.SemiBold)
                    }
                } else LazyColumn(Modifier.heightIn(max = 410.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(plans, key = RecurringInvoicePlan::id) { plan ->
                        val nextDate = runCatching { LocalDate.parse(plan.nextRunDate) }.getOrNull()
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F8F5)), shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(plan.customer, color = RecurringInk, fontWeight = FontWeight.Bold)
                                        Text("${when (plan.intervalMonths) { 1 -> "Monatlich"; 3 -> "Vierteljährlich"; 12 -> "Jährlich"; else -> "Ungültiger Rhythmus" }} · nächster Termin ${nextDate ?: "ungültig"}", color = RecurringMuted, fontSize = 11.sp)
                                    }
                                    Text(if (plan.active) "Aktiv" else "Pausiert", color = if (plan.active) RecurringGreen else RecurringMuted, fontSize = 11.sp)
                                }
                                Text(plan.description, color = RecurringInk, maxLines = 2, fontSize = 12.sp)
                                Text(formatEuro(plan.amountCents), color = RecurringInk, fontWeight = FontWeight.Bold)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { onToggle(plan) }) { Icon(if (plan.active) Icons.Default.PauseCircle else Icons.Default.PlayCircle, if (plan.active) "Pausieren" else "Fortsetzen", tint = RecurringGreen) }
                                    IconButton(onClick = { onDelete(plan) }) { Icon(Icons.Default.DeleteOutline, "Vorlage löschen", tint = MaterialTheme.colorScheme.error) }
                                    Spacer(Modifier.weight(1f))
                                    val canGenerate = plan.active && nextDate != null && !nextDate.isAfter(today)
                                    Button(onClick = { onGenerate(plan) }, enabled = canGenerate, colors = ButtonDefaults.buttonColors(containerColor = RecurringGreen)) {
                                        Icon(Icons.Default.CheckCircle, null); Spacer(Modifier.width(5.dp)); Text(if (!plan.active) "Pausiert" else if (canGenerate) "Entwurf erzeugen" else "Noch nicht fällig")
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { editorOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(7.dp)); Text("Vorlage aus Rechnung erstellen")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen", color = RecurringGreen) } },
        containerColor = Color.White
    )
    if (editorOpen) RecurringInvoiceEditorDialog(
        invoices = invoices,
        paymentTermsDays = paymentTermsDays,
        onDismiss = { editorOpen = false },
        onSave = { onAdd(it); editorOpen = false }
    )
}

@Composable
private fun RecurringInvoiceEditorDialog(
    invoices: List<Invoice>,
    paymentTermsDays: Int,
    onDismiss: () -> Unit,
    onSave: (RecurringInvoicePlan) -> Unit
) {
    var invoiceId by remember { mutableStateOf(invoices.firstOrNull()?.id.orEmpty()) }
    var intervalMonths by remember { mutableIntStateOf(1) }
    var nextRunDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var menuExpanded by remember { mutableStateOf(false) }
    var intervalExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val selected = invoices.firstOrNull { it.id == invoiceId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wiederholung einrichten", color = RecurringInk, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (invoices.isEmpty()) Text("Lege zuerst einen Rechnungsentwurf an.", color = RecurringMuted)
                else {
                    Box {
                        OutlinedButton(onClick = { menuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(selected?.let { "${it.customer} · ${it.number.ifBlank { formatEuro(it.amountCents) }}" } ?: "Rechnung auswählen", modifier = Modifier.weight(1f))
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            invoices.filter { it.amountCents > 0 && it.customer.isNotBlank() }.forEach { invoice ->
                                DropdownMenuItem(text = { Text("${invoice.customer} · ${formatEuro(invoice.amountCents)}") }, onClick = { invoiceId = invoice.id; menuExpanded = false })
                            }
                        }
                    }
                    Box {
                        OutlinedButton(onClick = { intervalExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Intervall: ${if (intervalMonths == 1) "monatlich" else if (intervalMonths == 3) "vierteljährlich" else "jährlich"}") }
                        DropdownMenu(expanded = intervalExpanded, onDismissRequest = { intervalExpanded = false }) {
                            listOf(1 to "Monatlich", 3 to "Vierteljährlich", 12 to "Jährlich").forEach { (months, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { intervalMonths = months; intervalExpanded = false })
                            }
                        }
                    }
                    OutlinedTextField(nextRunDate, { nextRunDate = it; error = null }, modifier = Modifier.fillMaxWidth(), label = { Text("Nächster Rechnungszeitpunkt (JJJJ-MM-TT)") }, singleLine = true)
                    selected?.let { Text("Vorlage: ${it.description} · ${formatEuro(it.amountCents)}. Zahlungsziel und Steuersatz werden übernommen.", color = RecurringMuted, fontSize = 12.sp) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = invoices.isNotEmpty(), onClick = {
                val date = runCatching { LocalDate.parse(nextRunDate) }.getOrNull()
                if (selected == null) error = "Bitte wähle eine Rechnungsvorlage aus."
                else if (date == null) error = "Bitte prüfe das Datum im Format JJJJ-MM-TT."
                else runCatching { recurringPlanFromInvoice(selected, intervalMonths, date, paymentTermsDays) }
                    .onSuccess(onSave)
                    .onFailure { error = it.message ?: "Die Vorlage ist ungültig." }
            }) { Text("Vorlage speichern", color = RecurringGreen) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen", color = RecurringMuted) } },
        containerColor = Color.White
    )
}
