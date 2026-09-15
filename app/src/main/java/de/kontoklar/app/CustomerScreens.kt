package de.kontoklar.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CustomerInk = Color(0xFF172823)
private val CustomerForest = Color(0xFF176B52)
private val CustomerMuted = Color(0xFF78827D)

@Composable
fun CustomerManagerDialog(
    customers: List<Customer>,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Customer) -> Unit,
    onDelete: (Customer) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val visible = customers.filter { customer ->
        listOf(customer.name, customer.email, customer.city).any { it.contains(query, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kunden", color = CustomerInk, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Kunden suchen") }, singleLine = true)
                if (visible.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.People, null, tint = CustomerForest, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(if (customers.isEmpty()) "Noch keine Kunden" else "Keine Treffer", color = CustomerInk, fontWeight = FontWeight.SemiBold)
                        Text("Gespeicherte Kunden kannst du bei Rechnungen auswählen.", color = CustomerMuted, fontSize = 12.sp)
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 330.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(visible, key = Customer::id) { customer ->
                            Row(
                                Modifier.fillMaxWidth().background(Color(0xFFF7F8F5), RoundedCornerShape(12.dp)).clickable { onEdit(customer) }.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(customer.name, color = CustomerInk, fontWeight = FontWeight.SemiBold)
                                    Text(listOf(customer.email, customer.city).filter(String::isNotBlank).joinToString(" · ").ifBlank { "Keine Kontaktdaten" }, color = CustomerMuted, fontSize = 11.sp)
                                }
                                IconButton(onClick = { onEdit(customer) }) { Icon(Icons.Default.Edit, "Bearbeiten", tint = CustomerForest) }
                                IconButton(onClick = { onDelete(customer) }) { Icon(Icons.Default.DeleteOutline, "Löschen", tint = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Kunden hinzufügen")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen", color = CustomerForest) } },
        containerColor = Color.White
    )
}

@Composable
fun CustomerEditorDialog(customer: Customer, onDismiss: () -> Unit, onSave: (Customer) -> Unit) {
    var name by remember(customer.id) { mutableStateOf(customer.name) }
    var email by remember(customer.id) { mutableStateOf(customer.email) }
    var street by remember(customer.id) { mutableStateOf(customer.street) }
    var postalCode by remember(customer.id) { mutableStateOf(customer.postalCode) }
    var city by remember(customer.id) { mutableStateOf(customer.city) }
    var taxNumber by remember(customer.id) { mutableStateOf(customer.taxNumber) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (customer.name.isBlank()) "Kunde hinzufügen" else "Kunde bearbeiten", color = CustomerInk, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Kunden-Stammdaten werden lokal auf diesem Gerät gespeichert.", color = CustomerMuted, fontSize = 12.sp)
                OutlinedTextField(name, { name = it; error = null }, modifier = Modifier.fillMaxWidth(), label = { Text("Name / Unternehmen") }, singleLine = true)
                OutlinedTextField(email, { email = it }, modifier = Modifier.fillMaxWidth(), label = { Text("E-Mail") }, singleLine = true)
                OutlinedTextField(street, { street = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Straße und Hausnummer") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(postalCode, { postalCode = it }, modifier = Modifier.weight(1f), label = { Text("PLZ") }, singleLine = true)
                    OutlinedTextField(city, { city = it }, modifier = Modifier.weight(2f), label = { Text("Ort") }, singleLine = true)
                }
                OutlinedTextField(taxNumber, { taxNumber = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Steuernummer / USt-IdNr. (optional)") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) error = "Bitte gib einen Namen an."
                else onSave(customer.copy(name = name.trim(), email = email.trim(), street = street.trim(), postalCode = postalCode.trim(), city = city.trim(), taxNumber = taxNumber.trim()))
            }) { Text("Speichern", color = CustomerForest) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen", color = CustomerMuted) } },
        containerColor = Color.White
    )
}

