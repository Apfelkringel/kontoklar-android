package de.kontoklar.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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

private val RecExpenseGreen = Color(0xFF176B52)
private val RecExpenseInk = Color(0xFF172823)
private val RecExpenseMuted = Color(0xFF78827D)

@Composable
fun RecurringExpenseManagerDialog(
    plans: List<RecurringExpensePlan>,
    today: LocalDate = LocalDate.now(),
    onDismiss: () -> Unit,
    onAdd: (RecurringExpensePlan) -> Unit,
    onGenerate: (RecurringExpensePlan) -> Unit,
    onToggle: (RecurringExpensePlan) -> Unit,
    onDelete: (RecurringExpensePlan) -> Unit
) {
    var editorOpen by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wiederkehrende Ausgaben", color = RecExpenseInk, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Fällige Vorlagen werden erst nach deiner Bestätigung als Ausgabe erfasst. Es werden keine Belege oder Steuerbeträge automatisch erzeugt.", color = RecExpenseMuted, fontSize = 12.sp)
                if (plans.isEmpty()) Text("Noch keine Ausgabevorlagen", modifier = Modifier.padding(vertical = 18.dp), color = RecExpenseMuted)
                else LazyColumn(Modifier.heightIn(max = 390.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(plans, key = RecurringExpensePlan::id) { plan ->
                        val date = runCatching { LocalDate.parse(plan.nextRunDate) }.getOrNull()
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F8F5)), shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(plan.merchant, color = RecExpenseInk, fontWeight = FontWeight.Bold)
                                        Text("${when (plan.intervalMonths) { 1 -> "Monatlich"; 3 -> "Vierteljährlich"; 12 -> "Jährlich"; else -> "Ungültiger Rhythmus" }} · nächster Termin ${date ?: "ungültig"}", color = RecExpenseMuted, fontSize = 11.sp)
                                    }
                                    Text(if (plan.active) "Aktiv" else "Pausiert", color = if (plan.active) RecExpenseGreen else RecExpenseMuted, fontSize = 11.sp)
                                }
                                Text("${plan.category} · ${formatEuro(plan.amountCents)}", color = RecExpenseInk, fontWeight = FontWeight.SemiBold)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { onToggle(plan) }) { Icon(if (plan.active) Icons.Default.PauseCircle else Icons.Default.PlayCircle, if (plan.active) "Pausieren" else "Fortsetzen", tint = RecExpenseGreen) }
                                    IconButton(onClick = { onDelete(plan) }) { Icon(Icons.Default.DeleteOutline, "Vorlage löschen", tint = MaterialTheme.colorScheme.error) }
                                    Spacer(Modifier.weight(1f))
                                    val due = plan.active && date != null && !date.isAfter(today)
                                    Button(onClick = { onGenerate(plan) }, enabled = due, colors = ButtonDefaults.buttonColors(containerColor = RecExpenseGreen)) {
                                        Text(if (!plan.active) "Pausiert" else if (due) "Ausgabe erfassen" else "Noch nicht fällig")
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { editorOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(7.dp)); Text("Ausgabevorlage hinzufügen")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen", color = RecExpenseGreen) } },
        containerColor = Color.White
    )
    if (editorOpen) RecurringExpenseEditorDialog(
        onDismiss = { editorOpen = false },
        onSave = { onAdd(it); editorOpen = false }
    )
}

@Composable
private fun RecurringExpenseEditorDialog(onDismiss: () -> Unit, onSave: (RecurringExpensePlan) -> Unit) {
    var merchant by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Sonstiges") }
    var amount by remember { mutableStateOf("") }
    var inputVat by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var interval by remember { mutableIntStateOf(1) }
    var nextDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ausgabe-Wiederholung einrichten", color = RecExpenseInk, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 490.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(merchant, { merchant = it; error = null }, label = { Text("Händler / Empfänger") }, singleLine = true)
                OutlinedTextField(category, { category = it }, label = { Text("Kategorie") }, singleLine = true)
                OutlinedTextField(amount, { amount = it; error = null }, label = { Text("Bruttobetrag (€)") }, singleLine = true)
                OutlinedTextField(inputVat, { inputVat = it; error = null }, label = { Text("Beleg-USt. (€), optional") }, singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text("Notiz, optional") }, minLines = 2)
                Box {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) { Text("Intervall: ${when (interval) { 1 -> "monatlich"; 3 -> "vierteljährlich"; else -> "jährlich" }}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        listOf(1 to "Monatlich", 3 to "Vierteljährlich", 12 to "Jährlich").forEach { (months, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { interval = months; menu = false })
                        }
                    }
                }
                OutlinedTextField(nextDate, { nextDate = it; error = null }, label = { Text("Nächster Termin (JJJJ-MM-TT)") }, singleLine = true)
                Text("Der USt.-Betrag wird nur als manuell eingetragener Belegwert übernommen und ist keine steuerliche Beurteilung.", color = RecExpenseMuted, fontSize = 11.sp)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val date = runCatching { LocalDate.parse(nextDate) }.getOrNull()
                val cents = parseEuroCents(amount)
                val vat = if (inputVat.isBlank()) null else parseEuroCents(inputVat)
                if (merchant.isBlank()) error = "Bitte gib einen Händler oder Empfänger ein."
                else if (category.isBlank()) error = "Bitte gib eine Kategorie ein."
                else if (date == null) error = "Bitte prüfe das Datum im Format JJJJ-MM-TT."
                else if (cents == null) error = "Bitte gib einen gültigen Bruttobetrag ein."
                else if (inputVat.isNotBlank() && vat == null) error = "Bitte gib einen gültigen USt.-Betrag ein."
                else if (vat != null && vat > cents!!) error = "Die erfasste USt. kann den Bruttobetrag nicht überschreiten."
                else runCatching {
                    RecurringExpensePlan(merchant = merchant.trim(), category = category.trim(), amountCents = cents!!,
                        note = note.trim(), inputVatCents = vat, intervalMonths = interval, nextRunDate = date.toString())
                }.onSuccess(onSave).onFailure { error = it.message ?: "Die Vorlage ist ungültig." }
            }) { Text("Vorlage speichern", color = RecExpenseGreen) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen", color = RecExpenseMuted) } },
        containerColor = Color.White
    )
}
