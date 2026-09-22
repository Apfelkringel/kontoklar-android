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
    onDismiss: () -> Unit,
    onSave: (Project) -> Unit,
    onDelete: (Project) -> Unit
) {
    var editor by remember { mutableStateOf<Project?>(null) }
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
                        Row(Modifier.fillMaxWidth().background(Mint, RoundedCornerShape(12.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null, tint = Forest)
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(project.name, color = Ink, fontWeight = FontWeight.SemiBold)
                                if (project.description.isNotBlank()) Text(project.description, color = Muted, fontSize = 11.sp)
                                Text(if (project.active) "Aktiv" else "Archiviert", color = Muted, fontSize = 11.sp)
                            }
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
