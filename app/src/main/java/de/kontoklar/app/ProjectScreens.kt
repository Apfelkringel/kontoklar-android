package de.kontoklar.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ProjectManagerDialog(
    projects: List<Project>,
    customers: List<Customer>,
    invoices: List<Invoice>,
    expenses: List<Expense>,
    timeEntries: List<TimeEntry>,
    onSaveTimeEntry: (TimeEntry) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Project) -> Unit,
    onDelete: (Project) -> Unit
) {
    var editor by remember { mutableStateOf<Project?>(null) }
    var timeEditor by remember { mutableStateOf<Project?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Projekte", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 500.dp)) {
                Text("Rechnungen, Ausgaben und später auch Arbeitszeiten lassen sich einem Projekt zuordnen.", color = Muted, fontSize = 12.sp)
                OutlinedButton(onClick = { editor = Project(name = "") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Projekt anlegen")
                }
                if (projects.isEmpty()) Text("Noch keine Projekte angelegt.", color = Muted, fontSize = 13.sp)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(projects, key = { it.id }) { project ->
                        val projectInvoices = invoices.filter { it.projectId == project.id && it.status != "Entwurf" }
                        val projectExpenses = expenses.filter { it.projectId == project.id }
                        val revenue = projectInvoices.sumOf { it.amountCents }
                        val costs = projectExpenses.sumOf { it.amountCents }
                        val minutes = timeEntries.filter { it.projectId == project.id }.sumOf { it.minutes }
                        Row(Modifier.fillMaxWidth().background(Mint, RoundedCornerShape(12.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null, tint = Forest)
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(project.name, color = Ink, fontWeight = FontWeight.SemiBold)
                                if (project.description.isNotBlank()) Text(project.description, color = Muted, fontSize = 11.sp)
                                Text(if (project.active) "Aktiv" else "Archiviert", color = Muted, fontSize = 11.sp)
                                Text("Umsatz ${formatEuro(revenue)} · Kosten ${formatEuro(costs)} · Ergebnis ${formatEuro(revenue - costs)}", color = Forest, fontSize = 11.sp)
                                Text("Arbeitszeit ${minutes / 60} h ${minutes % 60} min", color = Muted, fontSize = 11.sp)
                            }
                            TextButton(onClick = { timeEditor = project }) { Text("Zeit", color = Forest, fontSize = 11.sp) }
                            IconButton(onClick = { editor = project }) { Icon(Icons.Default.Edit, "Bearbeiten", tint = Forest) }
                            IconButton(onClick = { onDelete(project) }) { Icon(Icons.Default.DeleteOutline, "Löschen", tint = Muted) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen", color = Forest) } },
        containerColor = Color.White
    )
    editor?.let { project ->
        ProjectEditorDialog(project, customers, onDismiss = { editor = null }, onSave = { onSave(it); editor = null })
    }
    timeEditor?.let { project ->
        TimeEntryEditorDialog(project, onDismiss = { timeEditor = null }, onSave = { onSaveTimeEntry(it); timeEditor = null })
    }
}

@Composable
private fun ProjectEditorDialog(project: Project, customers: List<Customer>, onDismiss: () -> Unit, onSave: (Project) -> Unit) {
    var name by remember(project.id) { mutableStateOf(project.name) }
    var description by remember(project.id) { mutableStateOf(project.description) }
    var active by remember(project.id) { mutableStateOf(project.active) }
    var customerId by remember(project.id) { mutableStateOf(project.customerId) }
    var error by remember(project.id) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (project.name.isBlank()) "Projekt anlegen" else "Projekt bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it; error = null }, label = { Text("Projektname") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("Beschreibung (optional)") }, minLines = 2)
                if (customers.isNotEmpty()) {
                    Text("Kunde (optional)", color = Muted, fontSize = 12.sp)
                    customers.forEach { customer ->
                        Row(Modifier.fillMaxWidth().clickable { customerId = if (customerId == customer.id) null else customer.id }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = customerId == customer.id, onClick = { customerId = if (customerId == customer.id) null else customer.id })
                            Text(customer.name, color = Ink)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(active, { active = it }); Text("Aktiv", color = Ink) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = { TextButton(onClick = {
            when {
                name.isBlank() -> error = "Bitte gib einen Projektnamen ein."
                name.length > 200 || description.length > 2000 -> error = "Projektname max. 200, Beschreibung max. 2.000 Zeichen."
                else -> onSave(project.copy(name = name.trim(), description = description.trim(), customerId = customerId, active = active))
            }
        }) { Text("Speichern", color = Forest) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
        containerColor = Color.White
    )
}

@Composable
private fun TimeEntryEditorDialog(project: Project, onDismiss: () -> Unit, onSave: (TimeEntry) -> Unit) {
    var date by remember { mutableStateOf(java.time.LocalDate.now().toString()) }
    var minutes by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var rate by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Arbeitszeit · ${project.name}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(date, { date = it }, label = { Text("Datum (JJJJ-MM-TT)") }, singleLine = true)
            OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit) }, label = { Text("Minuten") }, singleLine = true)
            OutlinedTextField(rate, { rate = it }, label = { Text("Stundensatz (€), optional") }, singleLine = true)
            OutlinedTextField(note, { note = it }, label = { Text("Notiz (optional)") }, singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
    }, confirmButton = { TextButton(onClick = {
        val parsedMinutes = minutes.toIntOrNull()
        val parsedRate = if (rate.isBlank()) null else parseEuroCents(rate)
        when {
            runCatching { java.time.LocalDate.parse(date) }.isFailure -> error = "Bitte ein gültiges Datum eingeben."
            parsedMinutes !in 1..1440 -> error = "Bitte 1 bis 1.440 Minuten eingeben."
            rate.isNotBlank() && parsedRate == null -> error = "Bitte einen gültigen Stundensatz eingeben."
            note.length > 2000 -> error = "Die Notiz darf höchstens 2.000 Zeichen enthalten."
            else -> onSave(TimeEntry(projectId = project.id, date = date, minutes = parsedMinutes!!, note = note.trim(), hourlyRateCents = parsedRate))
        }
    }) { Text("Speichern", color = Forest) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }, containerColor = Color.White)
}
