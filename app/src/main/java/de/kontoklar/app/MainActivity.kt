package de.kontoklar.app

import android.os.Bundle
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF172823)
private val Forest = Color(0xFF176B52)
private val Mint = Color(0xFFE7F3EC)
private val Canvas = Color(0xFFF7F8F5)
private val Muted = Color(0xFF78827D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KontoKlarApp() }
    }
}

private enum class Page(val title: String, val icon: ImageVector) {
    Home("Übersicht", Icons.Default.Home),
    Invoices("Rechnungen", Icons.Default.ReceiptLong),
    Expenses("Ausgaben", Icons.Default.Payments),
    Taxes("Steuern", Icons.Default.AccountBalance),
    More("Mehr", Icons.Default.Menu)
}

private data class Entry(val title: String, val subtitle: String, val amount: String, val icon: ImageVector, val tint: Color)

@Composable
private fun KontoKlarApp() {
    val context = LocalContext.current
    val store = remember { LocalData(context) }
    var invoices by remember { mutableStateOf(store.invoices()) }
    var expenses by remember { mutableStateOf(store.expenses()) }
    var selectedInvoice by remember { mutableStateOf<Invoice?>(null) }
    var page by remember { mutableStateOf(Page.Home) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(toast) { toast?.let { snackbar.showSnackbar(it); toast = null } }

    Scaffold(
        containerColor = Canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                Page.entries.forEach { item ->
                    NavigationBarItem(
                        selected = page == item,
                        onClick = { page = item },
                        icon = { Icon(item.icon, contentDescription = item.title) },
                        label = { Text(item.title, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Forest, selectedTextColor = Forest, indicatorColor = Mint, unselectedIconColor = Muted)
                    )
                }
            }
        },
        floatingActionButton = {
            if (page == Page.Home || page == Page.Invoices || page == Page.Expenses) {
                FloatingActionButton(onClick = { dialog = when (page) { Page.Expenses -> "Beleg erfassen"; else -> "Neue Rechnung" } }, containerColor = Forest, contentColor = Color.White) {
                    Icon(Icons.Default.Add, contentDescription = "Hinzufügen")
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Header(page.title)
            when (page) {
                Page.Home -> Dashboard(invoices, expenses, onNavigate = { page = it })
                Page.Invoices -> InvoiceScreen(invoices, onAction = { dialog = it }, onSelect = { selectedInvoice = it })
                Page.Expenses -> ExpenseScreen(expenses, onAction = { dialog = it })
                Page.Taxes -> TaxScreen(onAction = { dialog = it })
                Page.More -> MoreScreen(onAction = { toast = "$it – wird eingerichtet" })
            }
        }
        if (dialog != null) ActionDialog(
            title = dialog!!,
            onDismiss = { dialog = null },
            onSave = { toast = it; dialog = null },
            onCreateInvoice = { invoice -> invoices = listOf(invoice.copy(number = store.nextInvoiceNumber()) ) + invoices; store.saveInvoices(invoices); toast = "Rechnungsentwurf gespeichert"; dialog = null },
            onCreateExpense = { expense -> expenses = listOf(expense) + expenses; store.saveExpenses(expenses); toast = "Ausgabe gespeichert"; dialog = null }
        )
        selectedInvoice?.let { invoice ->
            InvoiceDetailsDialog(
                invoice = invoice,
                onDismiss = { selectedInvoice = null },
                onSharePdf = { runCatching { shareInvoiceDraft(context, invoice) }.onFailure { toast = "PDF konnte nicht erstellt werden: ${it.message}" } },
                onStatusChange = { status ->
                    invoices = invoices.map { if (it.id == invoice.id) it.copy(status = status) else it }
                    store.saveInvoices(invoices)
                    selectedInvoice = null
                    toast = "Rechnungsstatus: $status"
                }
            )
        }
    }
}

@Composable
private fun Header(title: String) {
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 20.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Guten Morgen, Alex", color = Muted, fontSize = 12.sp)
            Text(title, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.size(42.dp).background(Mint, CircleShape).clickable {}, contentAlignment = Alignment.Center) {
            Text("A", color = Forest, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

@Composable
private fun Dashboard(invoices: List<Invoice>, expenses: List<Expense>, onNavigate: (Page) -> Unit) {
    val invoiceTotal = invoices.sumOf { it.amountCents }
    val expenseTotal = expenses.sumOf { it.amountCents }
    LazyColumn(contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 90.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Forest)) {
                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ERFASSTES RECHNUNGSVOLUMEN", color = Color.White.copy(alpha = .76f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ReceiptLong, null, tint = Color.White.copy(alpha = .8f), modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(formatEuro(invoiceTotal), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("Lokale Entwürfe und Rechnungen · kein Bankkontostand", color = Color.White.copy(alpha = .78f), fontSize = 12.sp)
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = .2f))
                    Spacer(Modifier.height(15.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Balance("Bankkonto", "Nicht verbunden")
                        Balance("Ausgaben erfasst", formatEuro(expenseTotal))
                    }
                }
            }
        }
        item { SectionTitle("Dein Überblick", "Geschäftsjahr 2026") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("Rechnungen", formatEuro(invoiceTotal), "${invoices.size} erfasst", Icons.Default.TrendingUp, Modifier.weight(1f))
                MetricCard("Ausgaben", formatEuro(expenseTotal), "${expenses.size} erfasst", Icons.Default.Receipt, Modifier.weight(1f))
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Steuerrücklage", fontWeight = FontWeight.Bold, color = Ink); Text("Bankverbindung erforderlich", color = Muted, fontSize = 12.sp) }
                        Icon(Icons.Default.Lock, null, tint = Forest)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Automatische Rücklagen setzen eine autorisierte Banking-Anbindung voraus. Hier wird derzeit kein Geld bewegt.", color = Muted, fontSize = 12.sp)
                    TextButton(onClick = { onNavigate(Page.Taxes) }, contentPadding = PaddingValues(0.dp)) { Text("Steuerübersicht öffnen  →", color = Forest) }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Als Nächstes", "")
                Spacer(Modifier.weight(1f))
                Text("Alle", color = Forest, fontSize = 13.sp, modifier = Modifier.clickable { onNavigate(Page.Taxes) })
            }
        }
        if (invoices.any { it.status != "Bezahlt" }) item { TaskRow("Offene Rechnungen", "Entwürfe und unbezahlte Rechnungen", formatEuro(invoices.filter { it.status != "Bezahlt" }.sumOf { it.amountCents }), Icons.Default.Schedule, onClick = { onNavigate(Page.Invoices) }) }
        item { SectionTitle("Letzte Aktivitäten", "") }
        items((invoices.take(2).map { Entry(it.customer, "Rechnung · ${it.status}", formatEuro(it.amountCents), Icons.Default.Description, Mint) } + expenses.take(2).map { Entry(it.merchant, "${it.category} · ${it.date}", "−${formatEuro(it.amountCents)}", Icons.Default.Receipt, Color(0xFFFFF1E5)) }).take(4)) { EntryRow(it) }
        if (invoices.isEmpty() && expenses.isEmpty()) item { EmptyState("Dein Arbeitsbereich ist bereit", "Lege eine Rechnung oder Ausgabe an. Deine Daten bleiben auf diesem Gerät.") }
    }
}

@Composable
private fun InvoiceScreen(invoices: List<Invoice>, onAction: (String) -> Unit, onSelect: (Invoice) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(18.dp, 14.dp, 18.dp, 90.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { MetricCard("Offen", formatEuro(invoices.filter { it.status != "Bezahlt" }.sumOf { it.amountCents }), "${invoices.count { it.status != "Bezahlt" }} Rechnungen", Icons.Default.Schedule, Modifier.weight(1f)); MetricCard("Gesamt", formatEuro(invoices.sumOf { it.amountCents }), "${invoices.size} Rechnungen", Icons.Default.ShowChart, Modifier.weight(1f)) } }
        item { SectionTitle("Alle Rechnungen", "2026") }
        if (invoices.isEmpty()) item { EmptyState("Noch keine Rechnungen", "Tippe auf +, um deinen ersten Entwurf anzulegen.") }
        items(invoices, key = { it.id }) { invoice ->
            val icon = if (invoice.status == "Bezahlt") Icons.Default.CheckCircle else Icons.Default.Description
            EntryRow(Entry(invoice.customer, "${invoice.number} · ${invoice.status} · fällig ${invoice.dueDate}", formatEuro(invoice.amountCents), icon, if (invoice.status == "Bezahlt") Mint else Color(0xFFE6F2EB)), onClick = { onSelect(invoice) })
        }
        item { OutlinedButton(onClick = { onAction("E-Rechnung empfangen") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.Inbox, null); Spacer(Modifier.width(8.dp)); Text("E-Rechnung empfangen") } }
    }
}

@Composable
private fun ExpenseScreen(expenses: List<Expense>, onAction: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(18.dp, 14.dp, 18.dp, 90.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Card(colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(20.dp)) { Row(Modifier.fillMaxWidth().clickable { onAction("Beleg scannen") }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.DocumentScanner, null, tint = Forest, modifier = Modifier.size(28.dp)); Spacer(Modifier.width(14.dp)); Column { Text("Beleg scannen", color = Ink, fontWeight = FontWeight.Bold); Text("Foto aufnehmen oder Datei auswählen", color = Muted, fontSize = 12.sp) }; Spacer(Modifier.weight(1f)); Icon(Icons.Default.ChevronRight, null, tint = Forest) } } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { MetricCard("Ausgaben", formatEuro(expenses.sumOf { it.amountCents }), "${expenses.size} erfasst", Icons.Default.Payments, Modifier.weight(1f)); MetricCard("Noch zu prüfen", "${expenses.size}", "Belege", Icons.Default.ErrorOutline, Modifier.weight(1f)) } }
        item { SectionTitle("Letzte Ausgaben", "Alle ansehen") }
        if (expenses.isEmpty()) item { EmptyState("Noch keine Ausgaben", "Erfasse einen Beleg oder füge eine Ausgabe hinzu.") }
        items(expenses, key = { it.id }) { expense -> EntryRow(Entry(expense.merchant, "${expense.category} · ${expense.date}${if (expense.receiptUri != null) " · Beleg angehängt" else ""}", "−${formatEuro(expense.amountCents)}", Icons.Default.Receipt, Color(0xFFFFF1E5)), onClick = { onAction("Ausgabe: ${expense.merchant}") }) }
    }
}

@Composable
private fun TaxScreen(onAction: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(18.dp, 14.dp, 18.dp, 90.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Forest), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("STEUERPROFIL", color = Color.White.copy(alpha = .75f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp)); Text("Noch nicht eingerichtet", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Eine belastbare Schätzung braucht Angaben zu Tätigkeit, Rechtsform, Umsatzsteuer und Vorauszahlungen.", color = Color.White.copy(alpha = .8f), fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("Diese App erstellt derzeit keine rechtsverbindlichen Erklärungen und berechnet keine verbindliche Steuerlast.", color = Color.White.copy(alpha = .85f), fontSize = 12.sp)
                }
            }
        }
        item { Text("Nächste Schritte", fontWeight = FontWeight.Bold, color = Ink, fontSize = 18.sp) }
        item { TaskRow("Steuerprofil vervollständigen", "Tätigkeit, Rechtsform und Umsatzsteuer", "Einrichten", Icons.Default.Tune, onClick = { onAction("Steuerprofil") }) }
        item { TaskRow("Steuertermine verbinden", "Fristen hängen von deinen Angaben ab", "Einrichten", Icons.Default.Event, onClick = { onAction("Steuertermine") }) }
        item { OutlinedButton(onClick = { onAction("Steuerberater teilen") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("Mit Steuerberater teilen") } }
    }
}

@Composable
private fun MoreScreen(onAction: (String) -> Unit) {
    val links = listOf("Bankkonten & Accountable Banking" to Icons.Default.AccountBalanceWallet, "Kunden" to Icons.Default.People, "Produkte & Dienstleistungen" to Icons.Default.Inventory2, "Dokumente" to Icons.Default.Folder, "Steuer-Assistent" to Icons.Default.AutoAwesome, "Mit Buchhalter teilen" to Icons.Default.Share, "Einstellungen" to Icons.Default.Settings, "Hilfe & Support" to Icons.Default.HelpOutline)
    LazyColumn(contentPadding = PaddingValues(18.dp, 14.dp, 18.dp, 90.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(18.dp)) { Text("Alex Beispiel", fontWeight = FontWeight.Bold, color = Ink, fontSize = 18.sp); Text("Freiberufler · KontoKlar Plus", color = Muted, fontSize = 13.sp) } } }
        item { AppUpdateCard() }
        items(links) { (label, icon) -> Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).clickable { onAction(label) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Forest); Spacer(Modifier.width(14.dp)); Text(label, color = Ink, modifier = Modifier.weight(1f)); Icon(Icons.Default.ChevronRight, null, tint = Muted) } }
    }
}

@Composable
private fun AppUpdateCard() {
    val context = LocalContext.current
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(true) }
    var downloading by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { fetchLatestRelease() }
            .onSuccess { release = it }
            .onFailure { error = it.message ?: "Release konnte nicht geladen werden." }
        checking = false
    }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, null, tint = Forest)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("App-Updates", color = Ink, fontWeight = FontWeight.Bold)
                    Text("Installiert: ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp)
                }
                if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Forest)
            }
            when {
                checking -> Text("Suche nach einer neuen Version …", color = Muted, fontSize = 12.sp)
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                release == null -> Text("Kein Release gefunden.", color = Muted, fontSize = 12.sp)
                else -> {
                    val latest = release!!
                    val hasUpdate = isNewerVersion(latest.version, BuildConfig.VERSION_NAME)
                    Text(if (hasUpdate) "Version ${latest.version} ist verfügbar." else "Du verwendest die aktuelle Version (${latest.version}).", color = if (hasUpdate) Forest else Muted, fontSize = 12.sp)
                    if (latest.notes.isNotBlank()) Text(latest.notes, color = Muted, fontSize = 11.sp, maxLines = 5)
                    if (hasUpdate) {
                        Button(
                            onClick = {
                                if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                                } else {
                                    runCatching {
                                        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                        val request = DownloadManager.Request(Uri.parse(latest.apkUrl))
                                            .setTitle("KontoKlar ${latest.version}")
                                            .setDescription("APK wird geladen. Tippe nach Abschluss auf die Download-Benachrichtigung, um das Update zu installieren.")
                                            .setMimeType("application/vnd.android.package-archive")
                                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                            .setAllowedOverMetered(true)
                                            .setAllowedOverRoaming(false)
                                            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "KontoKlar-${latest.version}.apk")
                                        manager.enqueue(request)
                                        downloading = true
                                    }.onFailure { error = it.message ?: "Download konnte nicht gestartet werden." }
                                }
                            },
                            enabled = !downloading,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Forest),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(if (downloading) "Download gestartet" else "Update herunterladen") }
                        if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                            Text("Für die Installation musst du KontoKlar einmalig in den Android-Einstellungen als Installationsquelle erlauben.", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
            Text("Downloads kommen signiert aus den öffentlichen GitHub-Releases. Android zeigt vor dem Installieren seine Systembestätigung.", color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun Balance(label: String, value: String) { Column { Text(label, color = Color.White.copy(alpha = .72f), fontSize = 11.sp); Text(value, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) } }

@Composable
private fun SectionTitle(title: String, trailing: String) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f)); if (trailing.isNotBlank()) Text(trailing, color = Muted, fontSize = 12.sp) } }

@Composable
private fun MetricCard(label: String, value: String, note: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(15.dp)) { Icon(icon, null, tint = Forest, modifier = Modifier.size(20.dp)); Spacer(Modifier.height(12.dp)); Text(value, color = Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp); Text(label, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium); Text(note, color = Muted, fontSize = 11.sp) }
    }
}

@Composable
private fun TaskRow(title: String, subtitle: String, tag: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(Mint, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Forest, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp); Text(subtitle, color = Muted, fontSize = 11.sp) }
        Text(tag, color = Forest, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
    }
}

@Composable
private fun EntryRow(entry: Entry, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(entry.tint, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Icon(entry.icon, null, tint = Forest, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(entry.title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp); Text(entry.subtitle, color = Muted, fontSize = 11.sp) }
        Text(entry.amount, color = if (entry.amount.startsWith("+")) Forest else Ink, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Inbox, null, tint = Forest, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(8.dp)); Text(title, color = Ink, fontWeight = FontWeight.Bold)
            Text(body, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun InvoiceDetailsDialog(
    invoice: Invoice,
    onDismiss: () -> Unit,
    onSharePdf: () -> Unit,
    onStatusChange: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(invoice.number.ifBlank { "Rechnung" }, color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(invoice.customer, color = Ink, fontWeight = FontWeight.SemiBold)
                Text(invoice.description, color = Muted)
                Text("Betrag: ${formatEuro(invoice.amountCents)}", color = Ink)
                Text("Datum: ${invoice.date} · fällig: ${invoice.dueDate}", color = Muted, fontSize = 12.sp)
                Text("Status: ${invoice.status}", color = Forest, fontWeight = FontWeight.SemiBold)
                Text("Das PDF wird ausdrücklich als unvollständiger Entwurf gekennzeichnet.", color = Muted, fontSize = 11.sp)
                OutlinedButton(onClick = onSharePdf, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text("Entwurfs-PDF teilen")
                }
                if (invoice.status == "Entwurf") {
                    Button(onClick = { onStatusChange("Versendet") }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Forest)) { Text("Als versendet markieren") }
                } else if (invoice.status == "Versendet") {
                    Button(onClick = { onStatusChange("Bezahlt") }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Forest)) { Text("Als bezahlt markieren") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen", color = Forest) } },
        containerColor = Color.White
    )
}

@Composable
private fun ActionDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onCreateInvoice: (Invoice) -> Unit,
    onCreateExpense: (Expense) -> Unit
) {
    var customer by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Sonstiges") }
    var merchant by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var receiptUri by remember { mutableStateOf<String?>(null) }
    val isInvoice = title.contains("Rechnung", true)
    val isExpense = title.contains("Beleg", true) || title.contains("Ausgabe", true)
    val context = LocalContext.current
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            receiptUri = uri.toString()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isInvoice) {
                    OutlinedTextField(customer, { customer = it; error = null }, label = { Text("Kunde / Rechnungsempfänger") }, singleLine = true)
                    OutlinedTextField(description, { description = it }, label = { Text("Leistung / Beschreibung") }, singleLine = true)
                    OutlinedTextField(amount, { amount = it; error = null }, label = { Text("Betrag inkl. USt. (€)") }, singleLine = true)
                    Text("Zahlungsziel: 14 Tage · USt.-Satz und Rechnungsnummer in Einstellungen konfigurierbar.", color = Muted, fontSize = 11.sp)
                } else if (isExpense) {
                    OutlinedTextField(merchant, { merchant = it; error = null }, label = { Text("Händler / Lieferant") }, singleLine = true)
                    OutlinedTextField(amount, { amount = it; error = null }, label = { Text("Betrag (€)") }, singleLine = true)
                    Box {
                        OutlinedButton(onClick = { categoryExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Kategorie: $category", modifier = Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null) }
                        DropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                            listOf("Sonstiges", "Arbeitsmittel", "Reisekosten", "Software", "Büro", "Telefon & Internet", "Bewirtung").forEach { option ->
                                DropdownMenuItem(text = { Text(option) }, onClick = { category = option; categoryExpanded = false })
                            }
                        }
                    }
                    OutlinedTextField(note, { note = it }, label = { Text("Notiz (optional)") }, singleLine = true)
                    OutlinedButton(onClick = { documentPicker.launch(arrayOf("image/*", "application/pdf")) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(if (receiptUri == null) Icons.Default.AttachFile else Icons.Default.CheckCircle, null); Spacer(Modifier.width(8.dp)); Text(if (receiptUri == null) "Foto oder PDF-Beleg anhängen" else "Beleg angehängt · ändern")
                    }
                    Text("Der Beleg wird lokal verknüpft. OCR-Auslesen und Bankabgleich sind noch nicht aktiv.", color = Muted, fontSize = 11.sp)
                } else {
                    Text("Diese Funktion benötigt noch eine externe Anbieteranbindung. Deine Daten werden bis dahin nicht an Dritte gesendet.", color = Muted, fontSize = 13.sp)
                    OutlinedTextField(note, { note = it }, label = { Text("Notiz (optional)") }, singleLine = true)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cents = parseEuroCents(amount)
                when {
                    isInvoice && customer.isBlank() -> error = "Bitte gib einen Kunden an."
                    isInvoice && description.isBlank() -> error = "Bitte beschreibe die Leistung."
                    isExpense && merchant.isBlank() -> error = "Bitte gib einen Händler an."
                    cents == null -> error = "Bitte gib einen gültigen positiven Betrag an (z. B. 125,50)."
                    isInvoice -> onCreateInvoice(Invoice(customer = customer.trim(), description = description.trim(), amountCents = cents))
                    isExpense -> onCreateExpense(Expense(merchant = merchant.trim(), category = category, amountCents = cents, note = note.trim(), receiptUri = receiptUri))
                    else -> onSave("$title geöffnet")
                }
            }) { Text(if (isInvoice) "Entwurf speichern" else if (isExpense) "Ausgabe speichern" else "Weiter", color = Forest) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen", color = Muted) } },
        containerColor = Color.White
    )
}
