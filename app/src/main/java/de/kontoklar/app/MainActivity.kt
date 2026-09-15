package de.kontoklar.app

import android.Manifest
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

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

    override fun onResume() {
        super.onResume()
        InvoiceReminderScheduler.reconcile(this, LocalData(this).invoices())
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
    var customers by remember { mutableStateOf(store.customers()) }
    var offers by remember { mutableStateOf(store.offers()) }
    var products by remember { mutableStateOf(store.products()) }
    var profile by remember { mutableStateOf(store.businessProfile()) }
    var selectedInvoice by remember { mutableStateOf<Invoice?>(null) }
    var invoiceToEdit by remember { mutableStateOf<Invoice?>(null) }
    var invoiceToDelete by remember { mutableStateOf<Invoice?>(null) }
    var selectedExpense by remember { mutableStateOf<Expense?>(null) }
    var expenseToEdit by remember { mutableStateOf<Expense?>(null) }
    var expenseToDelete by remember { mutableStateOf<Expense?>(null) }
    var page by remember { mutableStateOf(Page.Home) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var customersOpen by remember { mutableStateOf(false) }
    var productsOpen by remember { mutableStateOf(false) }
    var productEditorOpen by remember { mutableStateOf(false) }
    var editingProduct by remember { mutableStateOf(Product(name = "", unitPriceCents = 0)) }
    var productToDelete by remember { mutableStateOf<Product?>(null) }
    var documentsOpen by remember { mutableStateOf(false) }
    var restoreBackupUri by remember { mutableStateOf<Uri?>(null) }
    var customerEditorOpen by remember { mutableStateOf(false) }
    var editingCustomer by remember { mutableStateOf(Customer()) }
    var customerToDelete by remember { mutableStateOf<Customer?>(null) }
    var offersOpen by remember { mutableStateOf(false) }
    var offerEditorOpen by remember { mutableStateOf(false) }
    var editingOffer by remember { mutableStateOf(Offer(customer = "", description = "", amountCents = 0)) }
    var offerToDelete by remember { mutableStateOf<Offer?>(null) }
    var pendingInvoiceXml by remember { mutableStateOf<String?>(null) }
    var incomingInvoice by remember { mutableStateOf<ParsedIncomingInvoice?>(null) }
    var incomingInvoiceUri by remember { mutableStateOf<Uri?>(null) }
    var incomingInvoiceError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val backupExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { destination ->
        if (destination != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { exportBackup(context, destination, store) } }
                .onSuccess { toast = "Sicherung mit ${expenses.size} Ausgaben und ${invoices.size} Rechnungen erstellt" }
                .onFailure { toast = it.message ?: "Sicherung konnte nicht erstellt werden." }
        }
    }
    val backupImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
        if (source != null) restoreBackupUri = source
    }
    val incomingInvoiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
        if (source != null) scope.launch {
            runCatching {
                runCatching { context.contentResolver.takePersistableUriPermission(source, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
                    }?.takeIf(String::isNotBlank) ?: source.lastPathSegment?.substringAfterLast('/')?.ifBlank { "E-Rechnung" } ?: "E-Rechnung"
                    context.contentResolver.openInputStream(source)?.use { parseIncomingInvoice(it, name, context.applicationContext) }
                        ?: error("Die ausgewählte Datei kann nicht gelesen werden.")
                }
            }.onSuccess { parsed ->
                incomingInvoiceError = null
                if (expenses.any { it.receiptUri == source.toString() }) toast = "Diese Datei wurde bereits als Ausgabe übernommen."
                else { incomingInvoiceUri = source; incomingInvoice = parsed }
            }.onFailure { incomingInvoiceError = it.message ?: "E-Rechnung konnte nicht importiert werden." }
        }
    }
    val invoiceXmlExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/xml")) { destination ->
        val xml = pendingInvoiceXml
        pendingInvoiceXml = null
        if (destination != null && xml != null) runCatching {
            context.contentResolver.openOutputStream(destination)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(xml) }
                ?: error("Datei konnte nicht geöffnet werden.")
        }.onSuccess { toast = "XRechnung-XML exportiert. Vor dem Versand bitte mit einem offiziellen Validator prüfen." }
            .onFailure { toast = it.message ?: "XML-Export fehlgeschlagen." }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) InvoiceReminderScheduler.reconcile(context, store.invoices())
        else toast = "Zahlungserinnerungen sind aus. Du kannst Mitteilungen später in den Android-Einstellungen erlauben."
    }
    LaunchedEffect(invoices) { InvoiceReminderScheduler.reconcile(context, invoices) }
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
                Page.Expenses -> ExpenseScreen(expenses, onAction = { action ->
                    if (action == "E-Rechnung empfangen") incomingInvoiceLauncher.launch(arrayOf("*/*")) else dialog = action
                }, onSelect = { selectedExpense = it })
                Page.Taxes -> TaxScreen(invoices = invoices, expenses = expenses, onAction = { action ->
                    when (action) {
                        "Steuerberater teilen" -> runCatching { shareBookkeepingCsv(context, invoices, expenses) }
                            .onFailure { toast = it.message ?: "Export konnte nicht erstellt werden." }
                        "Steuerprofil" -> dialog = "Unternehmensprofil"
                        else -> dialog = action
                    }
                })
                Page.More -> MoreScreen(onAction = { action ->
                    if (action == "Einstellungen" || action == "Unternehmensprofil") dialog = "Unternehmensprofil"
                    else if (action == "Kunden") customersOpen = true
                    else if (action == "Angebote") offersOpen = true
                    else if (action == "Produkte & Dienstleistungen") productsOpen = true
                    else if (action == "Dokumente") documentsOpen = true
                    else if (action == "Mit Buchhalter teilen") runCatching { shareBookkeepingCsv(context, invoices, expenses) }
                        .onFailure { toast = it.message ?: "Export konnte nicht erstellt werden." }
                    else toast = "$action – wird eingerichtet"
                })
            }
        }
        if (dialog != null) ActionDialog(
            title = dialog!!,
            existingInvoice = invoiceToEdit,
            existingExpense = expenseToEdit,
            onDismiss = { dialog = null; expenseToEdit = null; invoiceToEdit = null },
            onSave = { toast = it; dialog = null },
            onCreateInvoice = { invoice ->
                val editing = invoices.any { it.id == invoice.id }
                val persisted = if (invoice.number.isBlank()) invoice.copy(number = store.nextInvoiceNumber()) else invoice
                invoices = invoices.upsertInvoice(persisted)
                store.saveInvoices(invoices)
                toast = if (editing) "Rechnungsentwurf aktualisiert" else "Rechnungsentwurf gespeichert"
                invoiceToEdit = null
                dialog = null
            },
            onCreateExpense = { expense ->
                val isEditing = expenses.any { it.id == expense.id }
                expenses = expenses.upsertExpense(expense)
                store.saveExpenses(expenses)
                toast = if (isEditing) "Ausgabe aktualisiert" else "Ausgabe gespeichert"
                expenseToEdit = null
                dialog = null
            },
            profile = profile,
            customers = customers,
            products = products,
            onSaveProfile = { updated -> profile = updated; store.saveBusinessProfile(updated); toast = "Unternehmensprofil gespeichert"; dialog = null }
        )
        if (customersOpen) CustomerManagerDialog(
            customers = customers,
            onDismiss = { customersOpen = false },
            onAdd = { editingCustomer = Customer(); customerEditorOpen = true },
            onEdit = { editingCustomer = it; customerEditorOpen = true },
            onDelete = { customerToDelete = it }
        )
        if (productsOpen) ProductManagerDialog(
            products = products,
            onDismiss = { productsOpen = false },
            onAdd = { editingProduct = Product(name = "", unitPriceCents = 0); productEditorOpen = true },
            onEdit = { editingProduct = it; productEditorOpen = true },
            onDelete = { productToDelete = it }
        )
        if (productEditorOpen) ProductEditorDialog(
            product = editingProduct,
            onDismiss = { productEditorOpen = false },
            onSave = { saved ->
                products = products.upsertProduct(saved)
                store.saveProducts(products)
                productEditorOpen = false
                toast = "${saved.name} im Katalog gespeichert"
            }
        )
        productToDelete?.let { product ->
            AlertDialog(
                onDismissRequest = { productToDelete = null },
                title = { Text("Katalogeintrag löschen?") },
                text = { Text("${product.name} wird aus dem lokalen Produktkatalog entfernt. Bereits erstellte Rechnungen bleiben unverändert.") },
                confirmButton = {
                    TextButton(onClick = {
                        products = products.withoutProduct(product.id)
                        store.saveProducts(products)
                        productToDelete = null
                        toast = "Katalogeintrag gelöscht"
                    }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { productToDelete = null }) { Text("Abbrechen") } }
            )
        }
        if (documentsOpen) DataManagementDialog(
            onDismiss = { documentsOpen = false },
            onExport = { documentsOpen = false; backupExportLauncher.launch("KontoKlar-Sicherung-${LocalDate.now()}.zip") },
            onImport = { documentsOpen = false; backupImportLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed")) }
        )
        restoreBackupUri?.let { source ->
            AlertDialog(
                onDismissRequest = { restoreBackupUri = null },
                title = { Text("Sicherung wiederherstellen?") },
                text = { Text("Die Sicherung ersetzt deine lokalen Rechnungen, Angebote, Kunden, Ausgaben und das Unternehmensprofil. Ein angehängter Beleg wird mit übernommen. Erstelle vorher eine aktuelle Sicherung, wenn du vorhandene Daten behalten möchtest.") },
                confirmButton = {
                    TextButton(onClick = {
                        restoreBackupUri = null
                        scope.launch {
                            val result = runCatching { withContext(Dispatchers.IO) { restoreBackup(context, source, store) } }
                            result.onSuccess {
                                invoices = store.invoices()
                                expenses = store.expenses()
                                customers = store.customers()
                                offers = store.offers()
                                products = store.products()
                                profile = store.businessProfile()
                                toast = "Sicherung erfolgreich wiederhergestellt"
                            }.onFailure { toast = it.message ?: "Sicherung konnte nicht wiederhergestellt werden." }
                        }
                    }) { Text("Wiederherstellen", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { restoreBackupUri = null }) { Text("Abbrechen") } }
            )
        }
        if (customerEditorOpen) CustomerEditorDialog(
            customer = editingCustomer,
            onDismiss = { customerEditorOpen = false },
            onSave = { saved ->
                customers = if (customers.any { it.id == saved.id }) customers.map { if (it.id == saved.id) saved else it } else listOf(saved) + customers
                store.saveCustomers(customers)
                customerEditorOpen = false
                toast = "Kunde gespeichert"
            }
        )
        customerToDelete?.let { customer ->
            AlertDialog(
                onDismissRequest = { customerToDelete = null },
                title = { Text("Kunden löschen?") },
                text = { Text("${customer.name} wird aus deiner lokalen Kundenliste entfernt. Bereits erstellte Rechnungen bleiben unverändert.") },
                confirmButton = {
                    TextButton(onClick = {
                        customers = customers.filterNot { it.id == customer.id }
                        store.saveCustomers(customers)
                        customerToDelete = null
                        toast = "Kunde gelöscht"
                    }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { customerToDelete = null }) { Text("Abbrechen") } }
            )
        }
        if (offersOpen) OfferManagerDialog(
            offers = offers,
            onDismiss = { offersOpen = false },
            onAdd = { editingOffer = Offer(customer = "", description = "", amountCents = 0); offerEditorOpen = true },
            onEdit = { editingOffer = it; offerEditorOpen = true },
            onDelete = { offerToDelete = it },
            onMarkSent = { offer -> offers = offers.map { if (it.id == offer.id) it.copy(status = "Versendet") else it }; store.saveOffers(offers); toast = "Angebot als versendet markiert" },
            onAccept = { offer -> offers = offers.map { if (it.id == offer.id) it.copy(status = "Angenommen") else it }; store.saveOffers(offers); toast = "Angebot angenommen" },
            onReject = { offer -> offers = offers.map { if (it.id == offer.id) it.copy(status = "Abgelehnt") else it }; store.saveOffers(offers); toast = "Angebot abgelehnt" },
            onConvert = { offer ->
                if (offer.status == "Angenommen" && offer.convertedInvoiceId == null) {
                    val invoice = offer.toInvoice(store.nextInvoiceNumber(), profile.paymentTermsDays)
                    invoices = listOf(invoice) + invoices
                    store.saveInvoices(invoices)
                    offers = offers.map { if (it.id == offer.id) it.copy(status = "Abgerechnet", convertedInvoiceId = invoice.id) else it }
                    store.saveOffers(offers)
                    toast = "Rechnungsentwurf ${invoice.number} erstellt"
                }
            },
            onShare = { offer -> runCatching { shareOfferDraft(context, offer) }.onFailure { toast = "Angebots-PDF konnte nicht erstellt werden: ${it.message}" } }
        )
        if (offerEditorOpen) OfferEditorDialog(
            offer = editingOffer,
            customers = customers,
            products = products,
            onDismiss = { offerEditorOpen = false },
            onSave = { saved ->
                val persisted = if (saved.number.isBlank()) saved.copy(number = store.nextOfferNumber()) else saved
                offers = if (offers.any { it.id == persisted.id }) offers.map { if (it.id == persisted.id) persisted else it } else listOf(persisted) + offers
                store.saveOffers(offers)
                offerEditorOpen = false
                toast = "Angebot ${persisted.number} gespeichert"
            }
        )
        offerToDelete?.let { offer ->
            AlertDialog(
                onDismissRequest = { offerToDelete = null },
                title = { Text("Angebot löschen?") },
                text = { Text("${offer.number} für ${offer.customer} wird entfernt. Bereits erstellte Rechnungen bleiben unberührt.") },
                confirmButton = { TextButton(onClick = { offers = offers.filterNot { it.id == offer.id }; store.saveOffers(offers); offerToDelete = null; toast = "Angebot gelöscht" }) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { offerToDelete = null }) { Text("Abbrechen") } }
            )
        }
        selectedInvoice?.let { invoice ->
            InvoiceDetailsDialog(
                invoice = invoice,
                profile = profile,
                onDismiss = { selectedInvoice = null },
                onExportXml = {
                    val errors = XRechnung.validationErrors(invoice, profile)
                    if (errors.isNotEmpty()) toast = "XRechnung noch nicht möglich: ${errors.joinToString(" ")}"
                    else runCatching { XRechnung.create(invoice, profile) }
                        .onSuccess { pendingInvoiceXml = it; invoiceXmlExportLauncher.launch("${invoice.number.ifBlank { "rechnung" }}.xml") }
                        .onFailure { toast = it.message ?: "XRechnung konnte nicht erstellt werden." }
                },
                onSharePdf = { runCatching { shareInvoiceDraft(context, invoice) }.onFailure { toast = "PDF konnte nicht erstellt werden: ${it.message}" } },
                onEdit = { invoiceToEdit = invoice; selectedInvoice = null; dialog = "Rechnung bearbeiten" },
                onDelete = { invoiceToDelete = invoice; selectedInvoice = null },
                onStatusChange = { status ->
                    invoices = invoices.map { if (it.id == invoice.id) it.copy(status = status) else it }
                    store.saveInvoices(invoices)
                    if (status == "Versendet" && android.os.Build.VERSION.SDK_INT >= 33 &&
                        androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    selectedInvoice = null
                    toast = "Rechnungsstatus: $status"
                }
            )
        }
        invoiceToDelete?.let { invoice ->
            AlertDialog(
                onDismissRequest = { invoiceToDelete = null },
                title = { Text("Rechnungsentwurf löschen?") },
                text = { Text("${invoice.number} für ${invoice.customer} wird dauerhaft aus den lokalen Rechnungen entfernt.") },
                confirmButton = { TextButton(onClick = { invoices = invoices.withoutInvoice(invoice.id); store.saveInvoices(invoices); invoiceToDelete = null; toast = "Rechnungsentwurf gelöscht" }) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { invoiceToDelete = null }) { Text("Abbrechen") } }
            )
        }
        selectedExpense?.let { expense ->
            ExpenseDetailsDialog(
                expense = expense,
                onDismiss = { selectedExpense = null },
                onEdit = { expenseToEdit = expense; selectedExpense = null; dialog = "Ausgabe bearbeiten" },
                onDelete = { expenseToDelete = expense; selectedExpense = null },
                onOpenReceipt = {
                    runCatching {
                        val uri = Uri.parse(expense.receiptUri)
                        val mimeType = context.contentResolver.getType(uri) ?: if (uri.lastPathSegment?.endsWith(".pdf", true) == true) "application/pdf" else "image/*"
                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }.onFailure { toast = "Beleg kann nicht geöffnet werden. Bitte prüfe, ob die Quelldatei noch vorhanden ist." }
                }
            )
        }
        expenseToDelete?.let { expense ->
            AlertDialog(
                onDismissRequest = { expenseToDelete = null },
                title = { Text("Ausgabe löschen?") },
                text = { Text("${expense.merchant} · ${formatEuro(expense.amountCents)} wird von diesem Gerät entfernt. Der angehängte Originalbeleg bleibt in der Ablage erhalten.") },
                confirmButton = {
                    TextButton(onClick = {
                        expenses = expenses.withoutExpense(expense.id)
                        store.saveExpenses(expenses)
                        expenseToDelete = null
                        toast = "Ausgabe gelöscht"
                    }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { expenseToDelete = null }) { Text("Abbrechen") } }
            )
        }
        incomingInvoice?.let { parsed ->
            IncomingInvoiceReviewDialog(
                invoice = parsed,
                onDismiss = { incomingInvoice = null; incomingInvoiceUri = null },
                onConfirm = {
                    val source = incomingInvoiceUri
                    if (source == null) toast = "Die Originaldatei ist nicht mehr verfügbar. Bitte importiere sie erneut."
                    else {
                        val imported = Expense(
                            merchant = parsed.supplier,
                            category = "Sonstiges",
                            amountCents = parsed.amountCents,
                            date = parsed.date,
                            note = buildString {
                                append("E-Rechnung ${parsed.invoiceNumber}")
                                parsed.vatCents?.let { append(" · USt ${formatEuro(it)}") }
                            },
                            receiptUri = source.toString()
                        )
                        expenses = expenses.upsertExpense(imported)
                        store.saveExpenses(expenses)
                        incomingInvoice = null
                        incomingInvoiceUri = null
                        page = Page.Expenses
                        selectedExpense = imported
                        toast = "E-Rechnung geprüft und als Ausgabe übernommen"
                    }
                }
            )
        }
        incomingInvoiceError?.let { message ->
            AlertDialog(
                onDismissRequest = { incomingInvoiceError = null },
                title = { Text("E-Rechnung konnte nicht importiert werden") },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = { incomingInvoiceError = null }) { Text("Schließen") } }
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
    }
}

@Composable
private fun ExpenseScreen(expenses: List<Expense>, onAction: (String) -> Unit, onSelect: (Expense) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(18.dp, 14.dp, 18.dp, 90.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Card(colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(20.dp)) { Row(Modifier.fillMaxWidth().clickable { onAction("Beleg scannen") }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.DocumentScanner, null, tint = Forest, modifier = Modifier.size(28.dp)); Spacer(Modifier.width(14.dp)); Column { Text("Beleg scannen", color = Ink, fontWeight = FontWeight.Bold); Text("Foto aufnehmen oder Datei auswählen", color = Muted, fontSize = 12.sp) }; Spacer(Modifier.weight(1f)); Icon(Icons.Default.ChevronRight, null, tint = Forest) } } }
        item { OutlinedButton(onClick = { onAction("E-Rechnung empfangen") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.Inbox, null); Spacer(Modifier.width(8.dp)); Text("E-Rechnung importieren") } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { MetricCard("Ausgaben", formatEuro(expenses.sumOf { it.amountCents }), "${expenses.size} erfasst", Icons.Default.Payments, Modifier.weight(1f)); MetricCard("Beleg fehlt", "${expenses.count { it.receiptUri.isNullOrBlank() }}", "Ausgaben", Icons.Default.ErrorOutline, Modifier.weight(1f)) } }
        item { SectionTitle("Alle Ausgaben", "") }
        if (expenses.isEmpty()) item { EmptyState("Noch keine Ausgaben", "Erfasse einen Beleg oder füge eine Ausgabe hinzu.") }
        items(expenses, key = { it.id }) { expense -> EntryRow(Entry(expense.merchant, "${expense.category} · ${expense.date}${if (expense.receiptUri != null) " · Beleg angehängt" else " · Beleg fehlt"}", "−${formatEuro(expense.amountCents)}", Icons.Default.Receipt, Color(0xFFFFF1E5)), onClick = { onSelect(expense) }) }
    }
}

@Composable
private fun TaxScreen(invoices: List<Invoice>, expenses: List<Expense>, onAction: (String) -> Unit) {
    var selectedYear by remember { mutableIntStateOf(LocalDate.now().year) }
    val report = remember(selectedYear, invoices, expenses) { taxYearReport(selectedYear, invoices, expenses) }
    LazyColumn(contentPadding = PaddingValues(18.dp, 14.dp, 18.dp, 90.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Forest), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("JAHRESÜBERSICHT", color = Color.White.copy(alpha = .75f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("${report.year}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { selectedYear-- }) { Icon(Icons.Default.ChevronLeft, "Vorjahr", tint = Color.White) }
                        IconButton(onClick = { if (selectedYear < LocalDate.now().year) selectedYear++ }, enabled = selectedYear < LocalDate.now().year) { Icon(Icons.Default.ChevronRight, "Folgejahr", tint = Color.White) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Gestellte Rechnungen · ${report.issuedInvoiceCount}", color = Color.White.copy(alpha = .8f), fontSize = 13.sp)
                    Text(formatEuro(report.issuedInvoiceCents), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Text("Abzüglich erfasster Ausgaben: ${formatEuro(report.expenseCents)}", color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
                    Text("Differenz der erfassten Bruttobeträge: ${formatEuro(report.recordedDifferenceCents)}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Text("Nur eine Übersicht deiner Eingaben: keine Steuerberechnung. Umsatzsteuer, Zahlungszeitpunkt, Abschreibungen und weitere steuerliche Regeln werden nicht berücksichtigt.", color = Color.White.copy(alpha = .82f), fontSize = 11.sp)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Prüfen", fontWeight = FontWeight.Bold, color = Ink, fontSize = 17.sp)
                    Text("Offene Rechnungen: ${formatEuro(report.openInvoiceCents)}", color = Ink)
                    Text("Überfällige Rechnungen: ${report.overdueInvoiceCount}", color = if (report.overdueInvoiceCount > 0) Color(0xFF9B3D24) else Muted)
                    Text("Ausgaben ohne Beleg: ${report.missingReceiptCount}", color = if (report.missingReceiptCount > 0) Color(0xFF9B3D24) else Muted)
                }
            }
        }
        item { Text("Weitere Werkzeuge", fontWeight = FontWeight.Bold, color = Ink, fontSize = 18.sp) }
        item { TaskRow("Steuerprofil vervollständigen", "Tätigkeit, Rechtsform und Umsatzsteuer", "Einrichten", Icons.Default.Tune, onClick = { onAction("Steuerprofil") }) }
        item { TaskRow("Steuertermine verbinden", "Fristen hängen von deinen Angaben ab", "Einrichten", Icons.Default.Event, onClick = { onAction("Steuertermine") }) }
        item { OutlinedButton(onClick = { onAction("Steuerberater teilen") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("Mit Steuerberater teilen") } }
    }
}

@Composable
private fun MoreScreen(onAction: (String) -> Unit) {
    val links = listOf("Bankkonten & Accountable Banking" to Icons.Default.AccountBalanceWallet, "Kunden" to Icons.Default.People, "Angebote" to Icons.Default.RequestQuote, "Produkte & Dienstleistungen" to Icons.Default.Inventory2, "Dokumente" to Icons.Default.Folder, "Steuer-Assistent" to Icons.Default.AutoAwesome, "Mit Buchhalter teilen" to Icons.Default.Share, "Einstellungen" to Icons.Default.Settings, "Hilfe & Support" to Icons.Default.HelpOutline)
    LazyColumn(contentPadding = PaddingValues(18.dp, 14.dp, 18.dp, 90.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(18.dp)) { Text("Alex Beispiel", fontWeight = FontWeight.Bold, color = Ink, fontSize = 18.sp); Text("Freiberufler · KontoKlar Plus", color = Muted, fontSize = 13.sp) } } }
        item { AppUpdateCard() }
        items(links) { (label, icon) -> Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).clickable { onAction(label) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Forest); Spacer(Modifier.width(14.dp)); Text(label, color = Ink, modifier = Modifier.weight(1f)); Icon(Icons.Default.ChevronRight, null, tint = Muted) } }
    }
}

@Composable
private fun DataManagementDialog(onDismiss: () -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dokumente & Datensicherung", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Erstelle eine lokale ZIP-Sicherung mit Rechnungen, Angeboten, Kunden, Ausgaben, Profilangaben und angehängten Belegen. Die Datei wird nur am gewählten Speicherort abgelegt. Sie ist nicht verschlüsselt und enthält vertrauliche Geschäfts- und Kundendaten – bewahre sie geschützt auf.", color = Muted, fontSize = 13.sp)
                Button(onClick = onExport, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Forest)) {
                    Icon(Icons.Default.Backup, null); Spacer(Modifier.width(8.dp)); Text("Sicherung exportieren")
                }
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Restore, null); Spacer(Modifier.width(8.dp)); Text("Sicherung wiederherstellen")
                }
                Text("Eine Wiederherstellung ersetzt den aktuellen lokalen Datenbestand erst nach deiner Bestätigung.", color = Muted, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen", color = Forest) } },
        containerColor = Color.White
    )
}

@Composable
private fun AppUpdateCard() {
    val context = LocalContext.current
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(true) }
    var downloading by remember { mutableStateOf(false) }
    var installerOpened by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val startDownload: (AppRelease) -> Unit = { latest ->
        if (!downloading) {
            downloading = true
            error = null
            installerOpened = false
            scope.launch {
                runCatching {
                    val apk = downloadVerifiedApk(context.cacheDir, latest)
                    val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
                    val installIntent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(apkUri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(installIntent)
                    installerOpened = true
                }.onFailure { failure ->
                    error = failure.message?.takeIf { it.isNotBlank() } ?: "Update konnte nicht vorbereitet werden. Bitte erneut versuchen."
                }
                downloading = false
            }
        }
    }
    val installPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val latest = release
        if (Build.VERSION.SDK_INT >= 26 && context.packageManager.canRequestPackageInstalls() && latest != null) {
            startDownload(latest)
        } else if (latest != null) {
            error = "Bitte erlaube KontoKlar in Android, Apps aus dieser Quelle zu installieren, und tippe danach erneut auf „Update installieren“."
        }
    }
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
                release == null -> Text("Kein Release gefunden.", color = Muted, fontSize = 12.sp)
                else -> {
                    val latest = release!!
                    val hasUpdate = isNewerVersion(latest.version, BuildConfig.VERSION_NAME)
                    Text(if (hasUpdate) "Version ${latest.version} ist verfügbar." else "Du verwendest die aktuelle Version (${latest.version}).", color = if (hasUpdate) Forest else Muted, fontSize = 12.sp)
                    if (latest.notes.isNotBlank()) Text(latest.notes, color = Muted, fontSize = 11.sp, maxLines = 5)
                    if (hasUpdate) {
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                                    installPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                                } else {
                                    startDownload(latest)
                                }
                            },
                            enabled = !downloading && !installerOpened,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Forest),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (downloading) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            Text(when {
                                downloading -> "APK wird geprüft …"
                                installerOpened -> "Im Android-Installer geöffnet"
                                else -> "Update installieren"
                            })
                        }
                        if (downloading) Text("APK wird geladen und auf Echtheit geprüft …", color = Muted, fontSize = 11.sp)
                        if (installerOpened) Text("Bestätige die Installation im Android-Systemdialog.", color = Forest, fontSize = 11.sp)
                        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                            Text("Für die Installation musst du KontoKlar einmalig in den Android-Einstellungen als Installationsquelle erlauben.", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
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
    profile: BusinessProfile,
    onDismiss: () -> Unit,
    onExportXml: () -> Unit,
    onSharePdf: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
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
                val xmlErrors = XRechnung.validationErrors(invoice, profile)
                OutlinedButton(onClick = onExportXml, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Code, null); Spacer(Modifier.width(8.dp)); Text("XRechnung-XML speichern")
                }
                Text(if (xmlErrors.isEmpty()) "Ein-Zeilen-Rechnung · deutsches Inland · Regelsteuersatz" else "Voraussetzungen: ${xmlErrors.joinToString(" ")}", color = Muted, fontSize = 11.sp)
                OutlinedButton(onClick = onSharePdf, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text("Entwurfs-PDF teilen")
                }
                if (invoice.status == "Entwurf") {
                    OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Edit, null); Spacer(Modifier.width(8.dp)); Text("Entwurf bearbeiten")
                    }
                    TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp)); Text("Entwurf löschen", color = MaterialTheme.colorScheme.error)
                    }
                    Text("Nach der Fälligkeit kann KontoKlar einmalig eine Zahlungserinnerung senden. Android-Mitteilungen müssen dafür erlaubt sein.", color = Muted, fontSize = 11.sp)
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
private fun ExpenseDetailsDialog(
    expense: Expense,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenReceipt: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(expense.merchant, color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("${expense.category} · ${expense.date}", color = Muted, fontSize = 12.sp)
                Text(formatEuro(expense.amountCents), color = Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                if (expense.note.isNotBlank()) Text(expense.note, color = Muted)
                if (!expense.receiptUri.isNullOrBlank()) {
                    OutlinedButton(onClick = onOpenReceipt, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.AttachFile, null); Spacer(Modifier.width(8.dp)); Text("Angehängten Beleg öffnen")
                    }
                } else Text("Zu dieser Ausgabe ist kein Beleg angehängt.", color = Muted, fontSize = 12.sp)
                OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Edit, null); Spacer(Modifier.width(8.dp)); Text("Ausgabe bearbeiten")
                }
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp)); Text("Ausgabe löschen", color = MaterialTheme.colorScheme.error)
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
    existingInvoice: Invoice?,
    existingExpense: Expense?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onCreateInvoice: (Invoice) -> Unit,
    onCreateExpense: (Expense) -> Unit,
    profile: BusinessProfile,
    customers: List<Customer>,
    products: List<Product>,
    onSaveProfile: (BusinessProfile) -> Unit
) {
    var customer by remember(title, existingInvoice?.id) { mutableStateOf(existingInvoice?.customer.orEmpty()) }
    var selectedCustomer by remember(title, existingInvoice?.id) { mutableStateOf(customers.firstOrNull { it.id == existingInvoice?.customerId }) }
    var customerMenuExpanded by remember { mutableStateOf(false) }
    var productMenuExpanded by remember { mutableStateOf(false) }
    var description by remember(title, existingInvoice?.id) { mutableStateOf(existingInvoice?.description.orEmpty()) }
    var amount by remember(title, existingExpense?.id, existingInvoice?.id) { mutableStateOf((existingInvoice?.amountCents ?: existingExpense?.amountCents)?.let(::formatEuro).orEmpty()) }
    var category by remember(title, existingExpense?.id) { mutableStateOf(existingExpense?.category ?: "Sonstiges") }
    var merchant by remember(title, existingExpense?.id) { mutableStateOf(existingExpense?.merchant.orEmpty()) }
    var note by remember(title, existingExpense?.id) { mutableStateOf(existingExpense?.note.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var receiptUri by remember(title, existingExpense?.id) { mutableStateOf(existingExpense?.receiptUri) }
    var expenseDate by remember(title, existingExpense?.id) { mutableStateOf(existingExpense?.date ?: LocalDate.now().toString()) }
    var invoiceDate by remember(title, existingInvoice?.id) { mutableStateOf(existingInvoice?.date ?: LocalDate.now().toString()) }
    var serviceDate by remember(title, existingInvoice?.id) { mutableStateOf(existingInvoice?.serviceDate ?: LocalDate.now().toString()) }
    var invoiceDueDate by remember(title, existingInvoice?.id) { mutableStateOf(existingInvoice?.dueDate ?: LocalDate.now().plusDays(profile.paymentTermsDays.toLong()).toString()) }
    var scanStatus by remember { mutableStateOf<String?>(null) }
    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var businessName by remember { mutableStateOf(profile.businessName) }
    var businessEmail by remember { mutableStateOf(profile.email) }
    var contactName by remember { mutableStateOf(profile.contactName) }
    var contactPhone by remember { mutableStateOf(profile.phone) }
    var businessIban by remember { mutableStateOf(profile.iban) }
    var street by remember { mutableStateOf(profile.street) }
    var postalCode by remember { mutableStateOf(profile.postalCode) }
    var city by remember { mutableStateOf(profile.city) }
    var taxNumber by remember { mutableStateOf(profile.taxNumber) }
    var vatId by remember { mutableStateOf(profile.vatId) }
    var invoicePrefix by remember { mutableStateOf(profile.invoicePrefix) }
    var paymentTermsDays by remember { mutableStateOf(profile.paymentTermsDays.toString()) }
    var vatRatePercent by remember { mutableStateOf(profile.vatRatePercent.toString()) }
    val isInvoice = title.contains("Rechnung", true)
    val isExpense = title.contains("Beleg", true) || title.contains("Ausgabe", true)
    val isProfile = title == "Unternehmensprofil"
    val context = LocalContext.current
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            receiptUri = uri.toString()
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val imageUri = cameraOutputUri
        if (captured && imageUri != null) {
            receiptUri = imageUri.toString()
            scanStatus = "Beleg wird lokal ausgelesen …"
            scanReceipt(context, imageUri,
                onResult = { result ->
                    result.merchant?.let { merchant = it }
                    result.date?.let { expenseDate = it }
                    result.amountCents?.let { amount = formatEuro(it) }
                    scanStatus = if (result.merchant == null && result.date == null && result.amountCents == null)
                        "Kein sicherer Vorschlag erkannt. Bitte Angaben manuell ergänzen."
                    else "Vorschläge übernommen – bitte vor dem Speichern prüfen."
                },
                onError = { scanStatus = "Texterkennung fehlgeschlagen: $it. Du kannst die Angaben manuell erfassen." }
            )
        } else if (!captured) scanStatus = "Aufnahme abgebrochen."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isProfile) {
                    Text("Diese Angaben bleiben auf diesem Gerät. Prüfe Pflichtangaben vor dem Versand deiner Rechnungen.", color = Muted, fontSize = 12.sp)
                    OutlinedTextField(businessName, { businessName = it }, label = { Text("Name / Unternehmen") }, singleLine = true)
                    OutlinedTextField(businessEmail, { businessEmail = it }, label = { Text("Geschäftliche E-Mail für E-Rechnungen") }, singleLine = true)
                    OutlinedTextField(contactName, { contactName = it }, label = { Text("Ansprechpartner für Rechnungen") }, singleLine = true)
                    OutlinedTextField(contactPhone, { contactPhone = it }, label = { Text("Telefon des Ansprechpartners") }, singleLine = true)
                    OutlinedTextField(street, { street = it }, label = { Text("Straße und Hausnummer") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(postalCode, { postalCode = it }, label = { Text("PLZ") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(city, { city = it }, label = { Text("Ort") }, modifier = Modifier.weight(2f), singleLine = true)
                    }
                    OutlinedTextField(taxNumber, { taxNumber = it }, label = { Text("Steuernummer (optional)") }, singleLine = true)
                    OutlinedTextField(vatId, { vatId = it }, label = { Text("USt-IdNr. (optional)") }, singleLine = true)
                    OutlinedTextField(businessIban, { businessIban = it }, label = { Text("IBAN für Überweisungen") }, singleLine = true)
                    OutlinedTextField(invoicePrefix, { invoicePrefix = it.take(12) }, label = { Text("Rechnungsnummer-Präfix") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(paymentTermsDays, { paymentTermsDays = it.filter(Char::isDigit).take(2) }, label = { Text("Zahlungsziel (Tage)") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(vatRatePercent, { vatRatePercent = it.filter(Char::isDigit).take(2) }, label = { Text("USt.-Satz (%)") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                    Text("Der USt.-Satz wird für den eingeschränkten XRechnung-Export verwendet. Steuerfreie Sonderfälle sind nicht unterstützt.", color = Muted, fontSize = 11.sp)
                } else if (isInvoice) {
                    Box {
                        OutlinedTextField(
                            value = customer,
                            onValueChange = { value ->
                                customer = value
                                selectedCustomer = customers.firstOrNull { it.name.equals(value, ignoreCase = true) }
                                error = null
                            },
                            label = { Text("Kunde / Rechnungsempfänger") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                if (customers.isNotEmpty()) IconButton(onClick = { customerMenuExpanded = true }) { Icon(Icons.Default.ArrowDropDown, "Kunden auswählen") }
                            }
                        )
                        DropdownMenu(expanded = customerMenuExpanded, onDismissRequest = { customerMenuExpanded = false }) {
                            customers.filter { customerQuery -> customerQuery.name.contains(customer, ignoreCase = true) || customer.isBlank() }
                                .forEach { savedCustomer ->
                                    DropdownMenuItem(
                                        text = { Column { Text(savedCustomer.name); Text(listOf(savedCustomer.email, savedCustomer.city).filter(String::isNotBlank).joinToString(" · "), color = Muted, fontSize = 11.sp) } },
                                        onClick = {
                                            customer = savedCustomer.name
                                            selectedCustomer = savedCustomer
                                            customerMenuExpanded = false
                                        }
                                    )
                                }
                        }
                    }
                    selectedCustomer?.let { saved ->
                        if (saved.postalAddress.isNotBlank()) Text("Rechnungsanschrift:\n${saved.postalAddress}", color = Muted, fontSize = 11.sp)
                    }
                    if (products.isNotEmpty()) Box {
                        OutlinedButton(onClick = { productMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Inventory2, null); Spacer(Modifier.width(8.dp)); Text("Aus Produktkatalog übernehmen")
                        }
                        DropdownMenu(expanded = productMenuExpanded, onDismissRequest = { productMenuExpanded = false }) {
                            products.forEach { product ->
                                DropdownMenuItem(
                                    text = { Column { Text(product.name); Text(formatEuro(product.unitPriceCents), color = Muted, fontSize = 11.sp) } },
                                    onClick = {
                                        description = product.description.ifBlank { product.name }
                                        amount = formatEuro(product.unitPriceCents)
                                        productMenuExpanded = false
                                        error = null
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(description, { description = it }, label = { Text("Leistung / Beschreibung") }, singleLine = true)
                    OutlinedTextField(amount, { amount = it; error = null }, label = { Text("Betrag inkl. USt. (€)") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(invoiceDate, { invoiceDate = it; error = null }, label = { Text("Rechnungsdatum") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(invoiceDueDate, { invoiceDueDate = it; error = null }, label = { Text("Fällig am") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                    OutlinedTextField(serviceDate, { serviceDate = it; error = null }, label = { Text("Leistungsdatum (JJJJ-MM-TT)") }, singleLine = true)
                    Text("Bitte das tatsächliche Leistungsdatum angeben. Die erzeugte XML-Rechnung muss vor Versand fachlich und mit einem Validator geprüft werden.", color = Muted, fontSize = 11.sp)
                    Text("Zahlungsziel: ${profile.paymentTermsDays} Tage · Nummernpräfix: ${profile.invoicePrefix}", color = Muted, fontSize = 11.sp)
                } else if (isExpense) {
                    OutlinedTextField(merchant, { merchant = it; error = null }, label = { Text("Händler / Lieferant") }, singleLine = true)
                    OutlinedTextField(amount, { amount = it; error = null }, label = { Text("Betrag (€)") }, singleLine = true)
                    OutlinedTextField(expenseDate, { expenseDate = it; error = null }, label = { Text("Datum (JJJJ-MM-TT)") }, singleLine = true)
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
                    OutlinedButton(onClick = {
                        runCatching {
                            val directory = File(context.filesDir, "receipts").apply { mkdirs() }
                            val imageFile = File(directory, "receipt-${System.currentTimeMillis()}.jpg")
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", imageFile)
                            cameraOutputUri = uri
                            cameraLauncher.launch(uri)
                        }.onFailure { scanStatus = "Kamera konnte nicht gestartet werden: ${it.localizedMessage}" }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.DocumentScanner, null); Spacer(Modifier.width(8.dp)); Text("Fotografieren & Text auslesen")
                    }
                    scanStatus?.let { Text(it, color = if (it.startsWith("Texterkennung fehlgeschlagen")) MaterialTheme.colorScheme.error else Muted, fontSize = 11.sp) }
                    Text("Texterkennung läuft auf dem Gerät. Händler, Datum und Betrag sind Vorschläge und müssen geprüft werden.", color = Muted, fontSize = 11.sp)
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
                    isProfile && invoicePrefix.isBlank() -> error = "Bitte gib ein Rechnungsnummer-Präfix an."
                    isProfile && (paymentTermsDays.toIntOrNull() !in 1..90 || vatRatePercent.toIntOrNull() !in 0..27) -> error = "Zahlungsziel: 1–90 Tage; USt.-Satz: 0–27 %."
                    isProfile -> onSaveProfile(BusinessProfile(businessName.trim(), street.trim(), postalCode.trim(), city.trim(), taxNumber.trim(), vatId.trim(), invoicePrefix.trim(), paymentTermsDays.toInt(), vatRatePercent.toInt(), businessEmail.trim(), contactName.trim(), contactPhone.trim(), businessIban.trim()))
                    isInvoice && customer.isBlank() -> error = "Bitte gib einen Kunden an."
                    isInvoice && description.isBlank() -> error = "Bitte beschreibe die Leistung."
                    isInvoice && (runCatching { LocalDate.parse(invoiceDate) }.isFailure || runCatching { LocalDate.parse(serviceDate) }.isFailure || runCatching { LocalDate.parse(invoiceDueDate) }.isFailure) -> error = "Bitte gib Rechnungs-, Leistungs- und Fälligkeitsdatum als JJJJ-MM-TT an."
                    isExpense && merchant.isBlank() -> error = "Bitte gib einen Händler an."
                    isExpense && runCatching { LocalDate.parse(expenseDate) }.isFailure -> error = "Bitte gib ein Datum im Format JJJJ-MM-TT an."
                    cents == null -> error = "Bitte gib einen gültigen positiven Betrag an (z. B. 125,50)."
                    isInvoice -> onCreateInvoice(
                        (existingInvoice ?: Invoice(customer = "", description = "", amountCents = 0)).copy(
                            customer = customer.trim(),
                            customerId = selectedCustomer?.id ?: existingInvoice?.takeIf { it.customer == customer.trim() }?.customerId,
                            customerAddress = selectedCustomer?.postalAddress ?: existingInvoice?.takeIf { it.customer == customer.trim() }?.customerAddress.orEmpty(),
                            customerEmail = selectedCustomer?.email ?: existingInvoice?.takeIf { it.customer == customer.trim() }?.customerEmail.orEmpty(),
                            description = description.trim(),
                            amountCents = cents,
                            date = invoiceDate,
                            serviceDate = serviceDate,
                            dueDate = invoiceDueDate
                        )
                    )
                    isExpense -> onCreateExpense(
                        existingExpense?.copy(merchant = merchant.trim(), category = category, amountCents = cents, date = expenseDate, note = note.trim(), receiptUri = receiptUri)
                            ?: Expense(merchant = merchant.trim(), category = category, amountCents = cents, date = expenseDate, note = note.trim(), receiptUri = receiptUri)
                    )
                    else -> onSave("$title geöffnet")
                }
        }) { Text(if (isProfile) "Profil speichern" else if (isInvoice && existingInvoice != null) "Änderungen speichern" else if (isInvoice) "Entwurf speichern" else if (isExpense && existingExpense != null) "Änderungen speichern" else if (isExpense) "Ausgabe speichern" else "Weiter", color = Forest) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen", color = Muted) } },
        containerColor = Color.White
    )
}
