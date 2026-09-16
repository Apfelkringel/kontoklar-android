package de.kontoklar.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BankingScreen(
    transactions: List<BankTransaction>,
    invoices: List<Invoice>,
    expenses: List<Expense>,
    onImportStatement: () -> Unit,
    onMatchInvoice: (BankTransaction, Invoice) -> Unit,
    onMatchExpense: (BankTransaction, Expense) -> Unit
) {
    val credits = transactions.filter { it.amountCents > 0 }
    val debits = transactions.filter { it.amountCents < 0 }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AccountBalanceWallet, null, tint = Forest)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Bankumsätze", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(if (transactions.isEmpty()) "Noch kein Auszug importiert" else "${transactions.size} lokale Buchungen", color = Muted, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        BankMetric("Eingänge", formatEuro(credits.sumOf { it.amountCents }), Modifier.weight(1f))
                        BankMetric("Ausgänge", formatEuro(debits.sumOf { -it.amountCents }), Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Button(onClick = onImportStatement, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Forest)) {
                Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("CAMT.053-Kontoauszug importieren")
            }
        }
        item {
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Mint)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.CloudOff, null, tint = Forest)
                    Spacer(Modifier.width(10.dp))
                    Text("Kein Live-Bankzugriff: Der Kontoauszug wird lokal verarbeitet. KontoKlar erhält keine Bank-Zugangsdaten und bewegt kein Geld. Zahlungsvorschläge werden erst nach deiner Bestätigung übernommen.", color = Ink, fontSize = 12.sp)
                }
            }
        }
        item { Text("Buchungen", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        if (transactions.isEmpty()) item {
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.SyncAlt, null, tint = Forest)
                    Spacer(Modifier.height(8.dp))
                    Text("Umsätze sicher abgleichen", color = Ink, fontWeight = FontWeight.SemiBold)
                    Text("Importiere einen CAMT.053-Auszug deiner Bank.", color = Muted, fontSize = 12.sp)
                }
            }
        }
        items(transactions.sortedByDescending(BankTransaction::date), key = BankTransaction::id) { transaction ->
            BankTransactionCard(transaction, invoices, expenses, transactions, onMatchInvoice, onMatchExpense)
        }
    }
}

@Composable
private fun BankMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(Color(0xFFF7F8F5), RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text(label, color = Muted, fontSize = 11.sp)
        Text(value, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BankTransactionCard(
    transaction: BankTransaction,
    invoices: List<Invoice>,
    expenses: List<Expense>,
    transactions: List<BankTransaction>,
    onMatchInvoice: (BankTransaction, Invoice) -> Unit,
    onMatchExpense: (BankTransaction, Expense) -> Unit
) {
    val amountColor = if (transaction.amountCents >= 0) Forest else Ink
    val linkedInvoice = invoices.firstOrNull { it.id == transaction.matchedInvoiceId }
    val suggestion = suggestInvoiceMatch(transaction, invoices)
    val linkedExpense = expenses.firstOrNull { it.id == transaction.matchedExpenseId }
    val expenseSuggestion = suggestExpenseMatch(transaction, expenses, transactions)
    var confirmMatch by remember(transaction.id) { mutableStateOf(false) }
    var confirmExpenseMatch by remember(transaction.id) { mutableStateOf(false) }
    if (confirmMatch && suggestion != null) {
        AlertDialog(
            onDismissRequest = { confirmMatch = false },
            title = { Text("Zahlung zuordnen?") },
            text = { Text("${transaction.counterparty} hat ${formatEuro(transaction.amountCents)} überwiesen. Rechnung ${suggestion.number} als bezahlt markieren?") },
            confirmButton = {
                TextButton(onClick = { confirmMatch = false; onMatchInvoice(transaction, suggestion) }) { Text("Als bezahlt markieren") }
            },
            dismissButton = { TextButton(onClick = { confirmMatch = false }) { Text("Abbrechen") } }
        )
    }
    if (confirmExpenseMatch && expenseSuggestion != null) {
        AlertDialog(
            onDismissRequest = { confirmExpenseMatch = false },
            title = { Text("Ausgabe zuordnen?") },
            text = { Text("Die Abbuchung von ${transaction.counterparty} über ${formatEuro(-transaction.amountCents)} passt zum erfassten Beleg „${expenseSuggestion.merchant}“ vom ${expenseSuggestion.date}. Die Zuordnung ändert keine Beträge oder Steuerangaben.") },
            confirmButton = {
                TextButton(onClick = { confirmExpenseMatch = false; onMatchExpense(transaction, expenseSuggestion) }) { Text("Ausgabe zuordnen") }
            },
            dismissButton = { TextButton(onClick = { confirmExpenseMatch = false }) { Text("Abbrechen") } }
        )
    }
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(transaction.counterparty, color = Ink, fontWeight = FontWeight.SemiBold)
                    Text(transaction.date, color = Muted, fontSize = 11.sp)
                }
                Text(
                    (if (transaction.amountCents > 0) "+" else "−") + formatEuro(kotlin.math.abs(transaction.amountCents)),
                    color = amountColor,
                    fontWeight = FontWeight.Bold
                )
            }
            if (transaction.description.isNotBlank()) Text(transaction.description, color = Muted, fontSize = 12.sp)
            if (transaction.reference.isNotBlank()) Text("Referenz: ${transaction.reference}", color = Muted, fontSize = 10.sp)
            when {
                linkedExpense != null -> Text("Abgeglichen mit Ausgabe · ${linkedExpense.merchant}", color = Forest, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                transaction.matchedExpenseId != null -> Text("Zugeordnete Ausgabe nicht mehr vorhanden", color = Muted, fontSize = 12.sp)
                linkedInvoice != null -> Text("Zugeordnet zu ${linkedInvoice.number} · ${linkedInvoice.status}", color = Forest, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                transaction.matchedInvoiceId != null -> Text("Zugeordnete Rechnung nicht mehr vorhanden", color = Muted, fontSize = 12.sp)
                suggestion != null -> OutlinedButton(onClick = { confirmMatch = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("${suggestion.number} als bezahlt markieren", color = Forest)
                }
                transaction.amountCents > 0 -> Text("Keine eindeutige offene Rechnung mit diesem Betrag gefunden.", color = Muted, fontSize = 11.sp)
                expenseSuggestion != null -> OutlinedButton(onClick = { confirmExpenseMatch = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Ausgabe ${expenseSuggestion.merchant} abgleichen", color = Forest)
                }
                transaction.amountCents < 0L -> Text("Keine eindeutige erfasste Ausgabe mit diesem Betrag gefunden.", color = Muted, fontSize = 11.sp)
            }
        }
    }
}
