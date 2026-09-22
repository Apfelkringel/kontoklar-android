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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import java.time.YearMonth

@Composable
fun BankingScreen(
    transactions: List<BankTransaction>,
    accounts: List<BankAccountSummary>,
    securities: List<BankSecurityPosition>,
    invoices: List<Invoice>,
    expenses: List<Expense>,
    bankingConfigured: Boolean,
    institutions: List<BankingInstitution>,
    connections: List<LiveBankConnection>,
    bankingBusy: Boolean,
    bankingMessage: String?,
    onConnectBank: (BankingInstitution?, List<String>) -> Unit,
    onRefreshConnections: () -> Unit,
    onSyncConnection: (LiveBankConnection) -> Unit,
    onDeleteConnection: (LiveBankConnection) -> Unit,
    onDeleteBankProfile: () -> Unit,
    onImportStatement: () -> Unit,
    onOpenComdirectApi: () -> Unit,
    onOpenCommunityUrl: (String) -> Unit,
    onMatchInvoice: (BankTransaction, Invoice) -> Unit,
    onMatchExpense: (BankTransaction, Expense) -> Unit,
    onClassifyTransaction: (BankTransaction, String) -> Unit,
    onDeleteTransaction: (BankTransaction) -> Unit
) {
    val credits = transactions.filter { it.amountCents > 0 }
    val debits = transactions.filter { it.amountCents < 0 }
    var confirmDeleteBankProfile by remember { mutableStateOf(false) }
    var communityGuideOpen by remember { mutableStateOf(false) }
    var selectedBankMonth by remember { mutableStateOf(YearMonth.now()) }
    var selectedAdditionalAccountTypes by remember { mutableStateOf(setOf("SECURITY")) }
    var transactionQuery by rememberSaveable { mutableStateOf("") }
    var transactionFilter by rememberSaveable { mutableStateOf("ALL") }
    val requestedAccountTypes = listOf("CHECKING") + listOf("SAVINGS", "CREDIT_CARD", "SECURITY").filter { it in selectedAdditionalAccountTypes }
    val normalizedTransactionQuery = transactionQuery.trim().lowercase()
    val visibleTransactions = transactions.filter { transaction ->
        val matchesQuery = normalizedTransactionQuery.isBlank() || listOf(
            transaction.counterparty,
            transaction.description,
            transaction.reference,
            transaction.accountIban,
            transaction.date,
            transaction.userClassification
        ).any { it.lowercase().contains(normalizedTransactionQuery) }
        val matchesFilter = when (transactionFilter) {
            "INCOME" -> transaction.amountCents > 0
            "EXPENSE" -> transaction.amountCents < 0
            "UNMATCHED" -> transaction.matchedInvoiceId == null && transaction.matchedExpenseId == null
            else -> true
        }
        matchesQuery && matchesFilter
    }.sortedByDescending(BankTransaction::date)
    if (confirmDeleteBankProfile) {
        AlertDialog(
            onDismissRequest = { confirmDeleteBankProfile = false },
            title = { Text("Alle Bankfreigaben löschen?") },
            text = { Text("KontoKlar löscht das Open-Banking-Profil und trennt alle Verbindungen beim Anbieter. Bereits lokal importierte Umsätze bleiben auf diesem Gerät erhalten. Diese Aktion kann nicht rückgängig gemacht werden.") },
            confirmButton = {
                TextButton(onClick = { confirmDeleteBankProfile = false; onDeleteBankProfile() }) { Text("Profil löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteBankProfile = false }) { Text("Abbrechen") }
            }
        )
    }
    if (communityGuideOpen) {
        AlertDialog(
            onDismissRequest = { communityGuideOpen = false },
            title = { Text("Kostenlose Bankimporte") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Die Dateien werden nur auf deinem Gerät verarbeitet. KontoKlar fragt keine Bank-PIN ab.", color = Muted, fontSize = 12.sp)
                    Text("C24", color = Ink, fontWeight = FontWeight.Bold)
                    Text("Im C24-Webbanking CSV, Excel oder Kontoauszug-PDF exportieren und anschließend hier importieren.", color = Muted, fontSize = 11.sp)
                    TextButton(onClick = { onOpenCommunityUrl("https://hilfe.c24.de/hc/de/articles/360017014279-Wie-ist-PSD2-bei-der-C24-Bank-umgesetzt") }) { Text("C24-Hinweise öffnen") }
                    Text("comdirect", color = Ink, fontWeight = FontWeight.Bold)
                    Text("CSV-Export direkt importieren. Für das kostenlose eigene API-Konto Clientdaten bei comdirect registrieren; PIN und Client-Secret gehören niemals in KontoKlar.", color = Muted, fontSize = 11.sp)
                    TextButton(onClick = onOpenComdirectApi) { Text("comdirect API öffnen") }
                    Text("Trade Republic", color = Ink, fontWeight = FontWeight.Bold)
                    Text("Zuerst den offiziellen Transaktions-CSV-Export oder Kontoauszug-PDF aus Trade Republic verwenden. Falls der Export bei deinem Konto noch fehlt, kann außerhalb der App das kostenlose pytr-Tool eine CSV erzeugen.", color = Muted, fontSize = 11.sp)
                    TextButton(onClick = { onOpenCommunityUrl("https://support.traderepublic.com/de-de/267") }) { Text("Offizielle TR-Anleitung öffnen") }
                    TextButton(onClick = { onOpenCommunityUrl("https://github.com/pytr-org/pytr") }) { Text("pytr auf GitHub öffnen") }
                }
            },
            confirmButton = { TextButton(onClick = { communityGuideOpen = false }) { Text("Schließen") } }
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            if (accounts.isNotEmpty() || securities.isNotEmpty()) {
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Konten & Depot", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        accounts.forEach { account ->
                            Text("${account.name} · ${account.type}", color = Ink, fontWeight = FontWeight.SemiBold)
                            Text(account.balanceMinor?.let { formatBankMoney(it, account.currency) } ?: "Kontostand nicht verfügbar", color = Muted, fontSize = 12.sp)
                            securities.filter { it.accountId == account.id }.forEach { position ->
                                Text("${position.name}${position.isin.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}", color = Ink, fontSize = 13.sp)
                                Text("Bestand: ${position.quantityNominal?.toString() ?: "–"} ${position.quantityType} · Wert: ${position.marketValueMinor?.let { formatBankMoney(it, position.marketValueCurrency) } ?: "–"}", color = Muted, fontSize = 11.sp)
                                if (position.quoteDate.isNotBlank()) Text("Kursdatum: ${position.quoteDate}", color = Muted, fontSize = 10.sp)
                            }
                        }
                        Text("Bestände und Werte laut letztem Anbieterabruf; keine Steuer- oder Renditeberechnung.", color = Muted, fontSize = 10.sp)
                    }
                }
            }
        }
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
            val monthSummary = bankMonthSummary(transactions, selectedBankMonth)
            val monthNames = listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Monatsauswertung", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { selectedBankMonth = selectedBankMonth.minusMonths(1) }) { Text("‹", color = Forest, fontSize = 22.sp) }
                        TextButton(onClick = { if (selectedBankMonth < YearMonth.now()) selectedBankMonth = selectedBankMonth.plusMonths(1) }, enabled = selectedBankMonth < YearMonth.now()) { Text("›", color = Forest, fontSize = 22.sp) }
                    }
                    Text("${monthNames[selectedBankMonth.monthValue - 1]} ${selectedBankMonth.year} · ${monthSummary.transactionCount} Buchungen", color = Muted, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        BankMetric("Einnahmen", formatEuro(monthSummary.incomeMinor), Modifier.weight(1f))
                        BankMetric("Ausgaben", formatEuro(monthSummary.expenseMinor), Modifier.weight(1f))
                    }
                    Text("Saldo ${formatEuro(monthSummary.netMinor)}", color = if (monthSummary.netMinor >= 0) Forest else Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    monthSummary.classifiedCounts.entries.sortedByDescending { it.value }.take(4).forEach { (label, count) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label, color = Muted, fontSize = 11.sp)
                            Text("$count", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Text("Auswertung der lokal gespeicherten Buchungen; keine steuerliche Kategorisierung.", color = Muted, fontSize = 10.sp)
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SyncAlt, null, tint = Forest)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Bank direkt verbinden", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("Freigabe bei deiner Bank · Zugangsdaten nicht in KontoKlar eingeben", color = Muted, fontSize = 11.sp)
                        }
                        if (bankingConfigured) TextButton(onClick = onRefreshConnections, enabled = !bankingBusy) { Text("Aktualisieren") }
                    }
                    if (bankingConfigured) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Kontotypen", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Girokonto ist immer dabei. Weitere Typen können zusätzliche Freigaben erfordern.", color = Muted, fontSize = 11.sp)
                            listOf(
                                "SAVINGS" to "Spar- und Tagesgeldkonten",
                                "CREDIT_CARD" to "Kreditkarten",
                                "SECURITY" to "Wertpapierdepots"
                            ).forEach { (accountType, label) ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(label, color = Ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                    Switch(
                                        checked = accountType in selectedAdditionalAccountTypes,
                                        onCheckedChange = { checked ->
                                            selectedAdditionalAccountTypes = if (checked) selectedAdditionalAccountTypes + accountType
                                            else selectedAdditionalAccountTypes - accountType
                                        },
                                        enabled = !bankingBusy
                                    )
                                }
                            }
                        }
                    }
                    if (!bankingConfigured) {
                        Text("Eigene kostenlose Lösung aktiv: KontoKlar verarbeitet deine Auszüge direkt auf dem Gerät – ohne finAPI, Abo oder Bankzugang.", color = Forest, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Unterstützt werden CAMT.053, MT940/Swift, C24-/comdirect-CSV, C24-Excel (.xlsx), C24-/Trade-Republic-PDFs, der offizielle Trade-Republic-Transaktionsexport und pytr-CSV. Alles wird lokal auf dem Gerät verarbeitet.", color = Muted, fontSize = 11.sp)
                        OutlinedButton(onClick = onOpenComdirectApi, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                            Text("comdirect-API für eigenes Konto einrichten")
                        }
                        OutlinedButton(onClick = { communityGuideOpen = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                            Text("Kostenlose Import-Anleitung anzeigen")
                        }
                        Text("Die offizielle Registrierung öffnet sich im Browser. Client-Secret, PIN und TAN bleiben bei dir und werden nicht in KontoKlar eingegeben.", color = Muted, fontSize = 10.sp)
                    } else {
                        Text("Freigabe und Datenabruf laufen über finAPI. PIN und TAN gibst du ausschließlich im Bank-/finAPI-Dialog ein. Umsätze werden vom Anbieter abgerufen und danach in KontoKlar lokal gespeichert.", color = Muted, fontSize = 11.sp)
                        Text("Schnell verbinden", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        listOf(
                            "C24" to listOf("c24"),
                            "comdirect" to listOf("comdirect"),
                            "Trade Republic" to listOf("trade republic", "traderepublic")
                        ).forEach { (label, needles) ->
                            val matches = institutions.filter { institution -> needles.any { needle -> institution.name.lowercase().contains(needle) } }
                            OutlinedButton(
                                onClick = { onConnectBank(matches.firstOrNull(), requestedAccountTypes) },
                                enabled = !bankingBusy,
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(if (matches.isEmpty()) "$label · Banksuche öffnen" else "$label verbinden")
                            }
                            if (matches.isEmpty() && institutions.isNotEmpty()) Text("Kein eindeutiger ${label}-Treffer im aktuellen Anbieterprofil; die Banksuche bleibt verfügbar.", color = Muted, fontSize = 10.sp)
                        }
                        Button(
                            onClick = { onConnectBank(null, requestedAccountTypes) }, enabled = !bankingBusy,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Forest)
                        ) { Text("Bank suchen und verbinden") }
                        if (institutions.isEmpty() && connections.isEmpty()) {
                            Text(
                                if (bankingBusy) "Banken werden geladen …" else "Der Anbieter meldet aktuell keine unterstützte Bank. Prüfe später erneut oder importiere einen Auszug.",
                                color = Muted,
                                fontSize = 12.sp
                            )
                        }
                        institutions.forEach { institution ->
                            OutlinedButton(
                                onClick = { onConnectBank(institution, requestedAccountTypes) }, enabled = !bankingBusy,
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
                            ) { Text("${institution.name} direkt verbinden") }
                        }
                        connections.forEach { connection ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(connection.bankName, color = Ink, fontWeight = FontWeight.SemiBold)
                                    Text(connection.status, color = Muted, fontSize = 11.sp)
                                }
                                TextButton(enabled = !bankingBusy, onClick = { onSyncConnection(connection) }) { Text("Sync") }
                                TextButton(enabled = !bankingBusy, onClick = { onDeleteConnection(connection) }) { Text("Trennen") }
                            }
                        }
                        TextButton(
                            onClick = { confirmDeleteBankProfile = true },
                            enabled = !bankingBusy,
                            modifier = Modifier.align(Alignment.End)
                        ) { Text("Alle Bankfreigaben und Anbieterprofil löschen", color = Muted, fontSize = 11.sp) }
                        if (bankingBusy) Text("Sichere Verbindung wird verarbeitet …", color = Muted, fontSize = 12.sp)
                        bankingMessage?.let { Text(it, color = Forest, fontSize = 12.sp) }
                    }
                }
            }
        }
        item {
            Button(onClick = onImportStatement, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Forest)) {
                Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Kontoauszug importieren · CAMT / MT940 / CSV / XLSX / C24-/TR-PDF / TR-CSV / pytr")
            }
        }
        item {
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Mint)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.CloudOff, null, tint = Forest)
                    Spacer(Modifier.width(10.dp))
                    Text("KontoKlar bewegt kein Geld. Zahlungsvorschläge werden erst nach deiner Bestätigung übernommen. Live-Umsätze werden nur nach deiner Bankfreigabe geladen.", color = Ink, fontSize = 12.sp)
                }
            }
        }
        item { Text("Buchungen", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        item {
            androidx.compose.material3.OutlinedTextField(
                value = transactionQuery,
                onValueChange = { transactionQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (transactionQuery.isNotBlank()) androidx.compose.material3.IconButton(onClick = { transactionQuery = "" }) { Icon(Icons.Default.Clear, "Suche löschen") } },
                label = { Text("Buchungen durchsuchen") },
                placeholder = { Text("Zahlungspartner, Zweck, Referenz oder Label") }
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ALL" to "Alle", "INCOME" to "Eingänge", "EXPENSE" to "Ausgänge", "UNMATCHED" to "Offen").forEach { (filter, label) ->
                    OutlinedButton(
                        onClick = { transactionFilter = filter },
                        modifier = Modifier.weight(1f).height(38.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp),
                        colors = if (transactionFilter == filter) ButtonDefaults.outlinedButtonColors(contentColor = Forest) else ButtonDefaults.outlinedButtonColors()
                    ) { Text(label, fontSize = 10.sp, maxLines = 1) }
                }
            }
        }
        if (transactions.isEmpty()) item {
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.SyncAlt, null, tint = Forest)
                    Spacer(Modifier.height(8.dp))
                    Text("Umsätze sicher abgleichen", color = Ink, fontWeight = FontWeight.SemiBold)
                    Text("Importiere CAMT.053, C24-/comdirect-CSV, C24-Excel (.xlsx), C24-/Trade-Republic-PDF, den nativen Trade-Republic-Transaktionsexport oder pytr-CSV. Dateien werden auf dem Gerät verarbeitet.", color = Muted, fontSize = 12.sp)
                }
            }
        }
        if (transactions.isNotEmpty() && visibleTransactions.isEmpty()) item {
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Text("Keine Buchung passt zur Suche.", color = Muted, modifier = Modifier.padding(18.dp))
            }
        }
        items(visibleTransactions, key = BankTransaction::id) { transaction ->
            BankTransactionCard(transaction, invoices, expenses, transactions, onMatchInvoice, onMatchExpense, onClassifyTransaction, onDeleteTransaction)
        }
    }
}

private fun formatBankMoney(minor: Long, currency: String): String {
    val amount = String.format(Locale.GERMANY, "%.2f", minor / 100.0)
    return if (currency.isBlank()) amount else "$amount $currency"
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
    onMatchExpense: (BankTransaction, Expense) -> Unit,
    onClassifyTransaction: (BankTransaction, String) -> Unit,
    onDeleteTransaction: (BankTransaction) -> Unit
) {
    val amountColor = if (transaction.amountCents >= 0) Forest else Ink
    val linkedInvoice = invoices.firstOrNull { it.id == transaction.matchedInvoiceId }
    val suggestion = suggestInvoiceMatch(transaction, invoices).takeIf { transaction.userClassification.isBlank() }
    val linkedExpense = expenses.firstOrNull { it.id == transaction.matchedExpenseId }
    val expenseSuggestion = suggestExpenseMatch(transaction, expenses, transactions).takeIf { transaction.userClassification.isBlank() }
    var confirmMatch by remember(transaction.id) { mutableStateOf(false) }
    var confirmExpenseMatch by remember(transaction.id) { mutableStateOf(false) }
    var classificationDialog by remember(transaction.id) { mutableStateOf(false) }
    var confirmDelete by remember(transaction.id) { mutableStateOf(false) }
    if (confirmMatch && suggestion != null) {
        AlertDialog(
            onDismissRequest = { confirmMatch = false },
            title = { Text("Zahlung zuordnen?") },
            text = { Text("${transaction.counterparty} hat ${formatEuro(transaction.amountCents)} überwiesen. Den Betrag der Rechnung ${suggestion.number} zuordnen? Offener Rest danach: ${formatEuro(invoiceOutstandingCents(suggestion) - transaction.amountCents)}.") },
            confirmButton = {
                TextButton(onClick = { confirmMatch = false; onMatchInvoice(transaction, suggestion) }) { Text("Zahlung zuordnen") }
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
    if (classificationDialog) {
        AlertDialog(
            onDismissRequest = { classificationDialog = false },
            title = { Text("Bankumsatz kennzeichnen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Eigene Sortierhilfe. Diese Kennzeichnung ist keine steuerliche Zuordnung und ändert keine Einnahmen, Ausgaben oder Steuerwerte.", color = Muted, fontSize = 12.sp)
                    if (transaction.userClassification.isNotBlank()) {
                        TextButton(onClick = { classificationDialog = false; onClassifyTransaction(transaction, "") }) { Text("Kennzeichnung entfernen") }
                    }
                    BANK_TRANSACTION_CLASSIFICATIONS.forEach { classification ->
                        TextButton(onClick = { classificationDialog = false; onClassifyTransaction(transaction, classification) }) {
                            Text(classification, color = Ink)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { classificationDialog = false }) { Text("Schließen") } }
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Bankumsatz löschen?") },
        text = { Text("Der lokale Bankumsatz von ${transaction.counterparty} über ${formatEuro(kotlin.math.abs(transaction.amountCents))} wird von diesem Gerät entfernt. Ein erneuter Import kann ihn wieder hinzufügen.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDeleteTransaction(transaction) }) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } }
    )
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
                transaction.userClassification.isNotBlank() -> Text("Eigene Kennzeichnung · ${transaction.userClassification}", color = Forest, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                suggestion != null -> OutlinedButton(onClick = { confirmMatch = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("${formatEuro(transaction.amountCents)} auf ${suggestion.number} buchen", color = Forest)
                }
                transaction.amountCents > 0 -> Text("Keine eindeutige offene Rechnung mit diesem Betrag gefunden.", color = Muted, fontSize = 11.sp)
                expenseSuggestion != null -> OutlinedButton(onClick = { confirmExpenseMatch = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Ausgabe ${expenseSuggestion.merchant} abgleichen", color = Forest)
                }
                transaction.amountCents < 0L -> Text("Keine eindeutige erfasste Ausgabe mit diesem Betrag gefunden.", color = Muted, fontSize = 11.sp)
            }
            if (transaction.matchedInvoiceId == null && transaction.matchedExpenseId == null) {
                OutlinedButton(onClick = { classificationDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (transaction.userClassification.isBlank()) "Als privat/geschäftlich kennzeichnen" else "Kennzeichnung ändern", color = Forest)
                }
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Lokalen Bankumsatz löschen")
                }
            }
        }
    }
}
