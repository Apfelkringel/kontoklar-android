package de.kontoklar.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

private val OfferInk = Color(0xFF172823)
private val OfferForest = Color(0xFF176B52)
private val OfferMuted = Color(0xFF78827D)

@Composable
fun OfferManagerDialog(
    offers: List<Offer>,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Offer) -> Unit,
    onDelete: (Offer) -> Unit,
    onMarkSent: (Offer) -> Unit,
    onAccept: (Offer) -> Unit,
    onReject: (Offer) -> Unit,
    onConvert: (Offer) -> Unit,
    onShare: (Offer) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Angebote", color = OfferInk, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Erstelle Angebote, verfolge die Rückmeldung und übernimm angenommene Angebote als Rechnungsentwurf.", color = OfferMuted, fontSize = 12.sp)
                if (offers.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ReceiptLong, null, tint = OfferForest, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Noch keine Angebote", color = OfferInk, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(offers, key = Offer::id) { offer ->
                            val status = offerStatus(offer)
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F8F5)), shape = RoundedCornerShape(14.dp)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(offer.customer, color = OfferInk, fontWeight = FontWeight.Bold)
                                            Text("${offer.number} · gültig bis ${offer.validUntil}", color = OfferMuted, fontSize = 11.sp)
                                        }
                                        Text(status, color = if (status == "Angenommen") OfferForest else OfferMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    }
                                    Text(offer.description, color = OfferInk, fontSize = 13.sp, maxLines = 2)
                                    Text(formatEuro(offer.amountCents), color = OfferInk, fontWeight = FontWeight.Bold)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { onShare(offer) }) { Icon(Icons.Default.PictureAsPdf, "Angebotsentwurf teilen", tint = OfferForest) }
                                        if (offer.status == "Entwurf") IconButton(onClick = { onEdit(offer) }) { Icon(Icons.Default.Edit, "Bearbeiten", tint = OfferForest) }
                                        IconButton(onClick = { onDelete(offer) }) { Icon(Icons.Default.DeleteOutline, "Löschen", tint = MaterialTheme.colorScheme.error) }
                                        Spacer(Modifier.weight(1f))
                                        when {
                                            offer.status == "Entwurf" -> TextButton(onClick = { onMarkSent(offer) }) { Icon(Icons.Default.Send, null); Spacer(Modifier.width(4.dp)); Text("Versendet") }
                                            status == "Versendet" -> {
                                                TextButton(onClick = { onReject(offer) }) { Icon(Icons.Default.ThumbDown, null); Text("Absagen") }
                                                Button(onClick = { onAccept(offer) }, colors = ButtonDefaults.buttonColors(containerColor = OfferForest)) { Icon(Icons.Default.CheckCircle, null); Text("Annehmen") }
                                            }
                                            offer.status == "Angenommen" && offer.convertedInvoiceId == null -> Button(onClick = { onConvert(offer) }, colors = ButtonDefaults.buttonColors(containerColor = OfferForest)) { Icon(Icons.Default.ReceiptLong, null); Text("Rechnung") }
                                            offer.convertedInvoiceId != null -> Text("Rechnung erstellt", color = OfferForest, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Angebot erstellen")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen", color = OfferForest) } },
        containerColor = Color.White
    )
}

@Composable
fun OfferEditorDialog(
    offer: Offer,
    customers: List<Customer>,
    onDismiss: () -> Unit,
    onSave: (Offer) -> Unit
) {
    var customer by remember(offer.id) { mutableStateOf(offer.customer) }
    var selectedCustomer by remember(offer.id) { mutableStateOf(customers.firstOrNull { it.id == offer.customerId }) }
    var description by remember(offer.id) { mutableStateOf(offer.description) }
    var amount by remember(offer.id) { mutableStateOf(if (offer.amountCents > 0) formatEuro(offer.amountCents) else "") }
    var date by remember(offer.id) { mutableStateOf(offer.date.ifBlank { LocalDate.now().toString() }) }
    var validUntil by remember(offer.id) { mutableStateOf(offer.validUntil.ifBlank { LocalDate.now().plusDays(30).toString() }) }
    var menuExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (offer.number.isBlank()) "Neues Angebot" else "Angebot bearbeiten", color = OfferInk, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (customers.isNotEmpty()) Box {
                    OutlinedTextField(
                        customer,
                        { value -> customer = value; selectedCustomer = customers.firstOrNull { it.name.equals(value, true) } },
                        modifier = Modifier.fillMaxWidth(), label = { Text("Kunde") }, singleLine = true,
                        trailingIcon = { IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.ArrowDropDown, "Kunden auswählen") } }
                    )
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        customers.filter { customerName -> customerName.name.contains(customer, true) || customer.isBlank() }.forEach { saved ->
                            DropdownMenuItem(text = { Text(saved.name) }, onClick = { customer = saved.name; selectedCustomer = saved; menuExpanded = false })
                        }
                    }
                } else OutlinedTextField(customer, { customer = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Kunde") }, singleLine = true)
                OutlinedTextField(description, { description = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Leistung / Beschreibung") })
                OutlinedTextField(amount, { amount = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Gesamtbetrag (€)") }, singleLine = true)
                OutlinedTextField(date, { date = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Angebotsdatum (JJJJ-MM-TT)") }, singleLine = true)
                OutlinedTextField(validUntil, { validUntil = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Gültig bis (JJJJ-MM-TT)") }, singleLine = true)
                Text("Das PDF wird als Entwurf markiert und ist noch kein rechtsgültiges Angebot.", color = OfferMuted, fontSize = 11.sp)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cents = parseEuroCents(amount)
                val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
                val parsedValid = runCatching { LocalDate.parse(validUntil) }.getOrNull()
                when {
                    customer.isBlank() -> error = "Bitte gib einen Kunden an."
                    description.isBlank() -> error = "Bitte beschreibe die angebotene Leistung."
                    cents == null -> error = "Bitte gib einen gültigen positiven Betrag an."
                    parsedDate == null || parsedValid == null || parsedValid.isBefore(parsedDate) -> error = "Bitte prüfe Angebotsdatum und Gültigkeitsdatum."
                    else -> onSave(offer.copy(customer = customer.trim(), customerId = selectedCustomer?.id, customerAddress = selectedCustomer?.postalAddress.orEmpty(), customerEmail = selectedCustomer?.email.orEmpty(), description = description.trim(), amountCents = cents, date = date, validUntil = validUntil))
                }
            }) { Text("Angebot speichern", color = OfferForest) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen", color = OfferMuted) } },
        containerColor = Color.White
    )
}
