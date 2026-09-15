package de.kontoklar.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ProductInk = Color(0xFF172823)
private val ProductForest = Color(0xFF176B52)
private val ProductMuted = Color(0xFF78827D)

@Composable
fun ProductManagerDialog(
    products: List<Product>,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Product) -> Unit,
    onDelete: (Product) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Produkte & Dienstleistungen", color = ProductInk, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Speichere häufig angebotene Leistungen und Preise zur schnellen Übernahme in Rechnungen.", color = ProductMuted, fontSize = 12.sp)
                if (products.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Inventory2, null, tint = ProductForest, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Katalog ist noch leer", color = ProductInk, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(products, key = Product::id) { product ->
                            Row(
                                Modifier.fillMaxWidth().background(Color(0xFFF7F8F5), RoundedCornerShape(12.dp)).padding(start = 12.dp, top = 7.dp, bottom = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(product.name, color = ProductInk, fontWeight = FontWeight.SemiBold)
                                    if (product.description.isNotBlank()) Text(product.description, color = ProductMuted, fontSize = 11.sp, maxLines = 2)
                                    Text(formatEuro(product.unitPriceCents), color = ProductForest, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                IconButton(onClick = { onEdit(product) }) { Icon(Icons.Default.Edit, "Bearbeiten", tint = ProductForest) }
                                IconButton(onClick = { onDelete(product) }) { Icon(Icons.Default.DeleteOutline, "Löschen", tint = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Produkt / Dienstleistung hinzufügen") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen", color = ProductForest) } },
        containerColor = Color.White
    )
}

@Composable
fun ProductEditorDialog(product: Product, onDismiss: () -> Unit, onSave: (Product) -> Unit) {
    var name by remember(product.id) { mutableStateOf(product.name) }
    var description by remember(product.id) { mutableStateOf(product.description) }
    var price by remember(product.id) { mutableStateOf(if (product.unitPriceCents > 0) formatEuro(product.unitPriceCents) else "") }
    var error by remember(product.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (product.name.isBlank()) "Katalogeintrag hinzufügen" else "Katalogeintrag bearbeiten", color = ProductInk, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it; error = null }, modifier = Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                OutlinedTextField(description, { description = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Rechnungsbeschreibung (optional)") })
                OutlinedTextField(price, { price = it; error = null }, modifier = Modifier.fillMaxWidth(), label = { Text("Preis inkl. USt. (€)") }, singleLine = true)
                Text("Beim Einfügen in eine Rechnung werden Beschreibung und Bruttopreis übernommen. Umsatzsteuer wird im aktuellen Rechnungsentwurf nicht separat berechnet.", color = ProductMuted, fontSize = 11.sp)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cents = parseEuroCents(price)
                when {
                    name.isBlank() -> error = "Bitte gib einen Namen an."
                    cents == null -> error = "Bitte gib einen gültigen positiven Preis an."
                    else -> onSave(product.copy(name = name.trim(), description = description.trim(), unitPriceCents = cents))
                }
            }) { Text("Speichern", color = ProductForest) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen", color = ProductMuted) } },
        containerColor = Color.White
    )
}
