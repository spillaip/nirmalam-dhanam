package com.nirmalamgroup.nirmalamdhanam

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.net.toUri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import com.nirmalamgroup.nirmalamdhanam.data.ai.NirmalamAiClient
import com.nirmalamgroup.nirmalamdhanam.data.ai.NirmalamAiInsight
import com.nirmalamgroup.nirmalamdhanam.data.ai.NirmalamAiPreferences
import com.nirmalamgroup.nirmalamdhanam.data.ai.prepareNirmalamAiContext
import com.nirmalamgroup.nirmalamdhanam.data.local.*
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.AccountRolePolicy
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.CoolDownTankInterceptorUseCase
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.MoneyFormatter
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.FinancialCalculations
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.InvestmentPerformanceMetric
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.InvestmentDeltaEngine
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.FinancialTimelineBuilder
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.ExplainableInsightEngine
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.FinancialHealthCalculator
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.GoalProgressCalculator
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.FinancialTimeMachine
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.LocalFinancialCopilot
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.CopilotResult
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.ReconciliationStatus
import com.nirmalamgroup.nirmalamdhanam.ui.components.CoolDownTankCard
import com.nirmalamgroup.nirmalamdhanam.ui.components.NeurodiverseModeToggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID


private enum class NdfFileAction { EXPORT, IMPORT }
private enum class CsvReviewFilter { ALL, NEEDS_REVIEW, NEW }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SettingsScreen(state: MvpFinanceState, onBack: () -> Unit, onNeurodiverseModeChanged: (Boolean) -> Unit, onCurrencyChanged: (String) -> Unit, onDateFormatPreferenceChanged: (DateFormatPreference) -> Unit, onSaveNirmalamAi: (String, String, String, Boolean) -> Unit, onDisableNirmalamAi: () -> Unit, onNirmalamAiInsight: (NirmalamAiInsight) -> Unit, onExportInterchange: (Uri) -> Unit, onImportInterchange: (Uri) -> Unit, onExportNdf: (Uri, String) -> Unit, onImportNdf: (Uri, String) -> Unit, onSaveCategory: (String, TransactionDirection, String, CategoryPriority, CategoryNature) -> Unit, onUpdateCategory: (String, String, TransactionDirection, String, CategoryPriority, CategoryNature) -> Unit, onDeleteCategory: (String) -> Unit, onSavePayee: (String, String?) -> Unit, onUpdatePayee: (String, String, String?) -> Unit, onDeletePayee: (String) -> Unit, onUpdateAccount: (String, String, AccountProductType, AssetClass, String, AccountKind) -> Unit, onArchiveAccount: (String) -> Unit, onExportTransactionsCsv: (Uri) -> Unit, onImportTransactionsCsv: (Uri, String) -> Unit, onConfirmTransactionsCsvImport: (Map<Int, CsvImportAction>) -> Unit, onCancelTransactionsCsvImport: () -> Unit, onRemoveStarterData: () -> Unit) {
    var showKhataManagement by remember { mutableStateOf(false) }
    var showVargaManagement by remember { mutableStateOf(false) }
    var showVyaktiManagement by remember { mutableStateOf(false) }
    var showUserGuide by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showNirmalamAi by remember { mutableStateOf(false) }
    var showRemoveStarterDataConfirmation by remember { mutableStateOf(false) }
    var showJsonExportConfirmation by remember { mutableStateOf(false) }
    var showCsvImportPicker by remember { mutableStateOf(false) }
    var selectedImportAccountId by remember { mutableStateOf<String?>(null) }
    var importAccountExpanded by remember { mutableStateOf(false) }

    var currencyExpanded by remember { mutableStateOf(false) }
    var dateFormatExpanded by remember { mutableStateOf(false) }
    var ndfAction by remember { mutableStateOf<NdfFileAction?>(null) }
    var ndfPassphrase by remember { mutableStateOf("") }
    var pendingNdfPassphrase by remember { mutableStateOf("") }
    var importReplacementConfirmed by remember { mutableStateOf(false) }
    var passphraseVisible by remember { mutableStateOf(false) }
    val createInterchangeFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.nirmalam-dhanam+json")) { uri -> uri?.let(onExportInterchange) }
    val openInterchangeFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onImportInterchange) }
    val createCsvFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let(onExportTransactionsCsv) }
    val openCsvFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> 
        uri?.let { source -> 
            selectedImportAccountId?.let { accountId ->
                onImportTransactionsCsv(source, accountId)
            }
        }
    }
    val createNdfFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.nirmalam-dhanam.backup+zip")) { uri ->
        uri?.let { onExportNdf(it, pendingNdfPassphrase) }
        pendingNdfPassphrase = ""
    }
    val openNdfFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onImportNdf(it, pendingNdfPassphrase) }
        pendingNdfPassphrase = ""
    }
    if (showUserGuide) { UserGuideScreen(onBack = { showUserGuide = false }); return }
    if (showPrivacy) { PrivacyAndPermissionsScreen(onBack = { showPrivacy = false }); return }
    if (showAbout) { AboutScreen(onBack = { showAbout = false }); return }
    if (showNirmalamAi) { NirmalamAiScreen(state, onBack = { showNirmalamAi = false }, onSave = onSaveNirmalamAi, onDisable = onDisableNirmalamAi, onInsight = onNirmalamAiInsight); return }
    if (showKhataManagement) { KhataManagementScreen(state, onBack = { showKhataManagement = false }, onUpdate = onUpdateAccount, onArchive = onArchiveAccount); return }
    if (showVargaManagement) { VargaManagementScreen(state.categories, onBack = { showVargaManagement = false }, onSave = onSaveCategory, onUpdate = onUpdateCategory, onDelete = onDeleteCategory); return }
    if (showVyaktiManagement) { VyaktiManagementScreen(state.payees, state.categories, onBack = { showVyaktiManagement = false }, onSave = onSavePayee, onUpdate = onUpdatePayee, onDelete = onDeletePayee); return }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            DhanamScreenTopBar(
                title = "Vinyasa",
                subtitle = "Preferences, data and privacy",
                onBack = onBack,
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { OneLineText("Experience", style = MaterialTheme.typography.titleMedium) }
            item { NeurodiverseModeToggle(state.neurodiverseModeEnabled, onNeurodiverseModeChanged) }
            item {
                ExposedDropdownMenuBox(expanded = currencyExpanded, onExpandedChange = { currencyExpanded = !currencyExpanded }) {
                    OutlinedTextField(state.currencyCode, {}, Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(), readOnly = true, label = { OneLineText("Currency") }, supportingText = { OneLineText("INR for this release", style = MaterialTheme.typography.labelSmall) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(currencyExpanded) })
                    ExposedDropdownMenu(expanded = currencyExpanded, onDismissRequest = { currencyExpanded = false }) {
                        DropdownMenuItem(text = { OneLineText("Indian Rupee (INR) · ₹") }, onClick = { onCurrencyChanged("INR"); currencyExpanded = false })
                    }
                }
            }
            item {
                ExposedDropdownMenuBox(expanded = dateFormatExpanded, onExpandedChange = { dateFormatExpanded = !dateFormatExpanded }) {
                    OutlinedTextField(
                        value = dateFormatPreferenceLabel(state.dateFormatPreference),
                        onValueChange = {},
                        modifier = Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(),
                        readOnly = true,
                        label = { OneLineText("Date format") },
                        supportingText = { OneLineText("Example · ${formatDate(LocalDate.of(2026, 8, 26), state.dateFormatPreference)}", style = MaterialTheme.typography.labelSmall) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(dateFormatExpanded) }
                    )
                    ExposedDropdownMenu(expanded = dateFormatExpanded, onDismissRequest = { dateFormatExpanded = false }) {
                        DateFormatPreference.entries.forEach { preference ->
                            DropdownMenuItem(
                                text = { Text("${dateFormatPreferenceLabel(preference)} · ${formatDate(LocalDate.of(2026, 8, 26), preference)}") },
                                onClick = {
                                    onDateFormatPreferenceChanged(preference)
                                    dateFormatExpanded = false
                                }
                            )
                        }
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { OneLineText("User guide", style = MaterialTheme.typography.titleMedium); OneLineText("Concepts, features, and FAQ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick = { showUserGuide = true }) { DhanamActionText("Open") }
                    }
                }
            }
            item { ManagementLinkCard("About", "App version, support, privacy, and acknowledgements", onClick = { showAbout = true }) }
            item { ManagementLinkCard("Khata", "${state.accounts.size} Khatas · edit names and types or archive", onClick = { showKhataManagement = true }) }
            item { ManagementLinkCard("Varga", "${state.categories.size} categories · create, search, edit, and organise", onClick = { showVargaManagement = true }) }
            item { ManagementLinkCard("Vyakti", "${state.payees.size} saved people, shops, and institutions", onClick = { showVyaktiManagement = true }) }
            item { ManagementLinkCard("Nirmalam AI", if (state.nirmalamAiReady) "BYOL enabled · preset private insights" else "Optional BYOL insights · disabled", onClick = { showNirmalamAi = true }) }
            item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OneLineText("Starter data", style = MaterialTheme.typography.titleMedium)
                        Text("Remove demo Khatas, demo Vyavahara, seeded Varga, and suggested Vyakti. Your entries stay untouched and the starter data will not return.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { showRemoveStarterDataConfirmation = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { DhanamActionText("Remove demo data") }
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OneLineText("Data & interoperability", style = MaterialTheme.typography.titleMedium)
                        OneLineText(
                            "Three file tools · each has one clear job",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        DataToolRow(
                            title = "CSV transactions",
                            subtitle = "Import opens reconciliation first",
                            primaryLabel = "Export",
                            onPrimary = { createCsvFile.launch("nirmalam-dhanam-transactions.csv") },
                            secondaryLabel = "Import",
                            onSecondary = { showCsvImportPicker = true },
                        )
                        DataToolRow(
                            title = ".dhanam portable file",
                            subtitle = "Shared Android + Python data",
                            primaryLabel = "Export",
                            onPrimary = { showJsonExportConfirmation = true },
                            secondaryLabel = "Import",
                            onSecondary = { openInterchangeFile.launch(arrayOf("application/vnd.nirmalam-dhanam+json", "application/json", "text/plain")) },
                        )
                        DataToolRow(
                            title = ".ndf encrypted backup",
                            subtitle = "Private backup and full restore",
                            primaryLabel = "Backup",
                            onPrimary = { ndfAction = NdfFileAction.EXPORT },
                            secondaryLabel = "Restore",
                            onSecondary = { ndfAction = NdfFileAction.IMPORT },
                        )
                        OneLineText(
                            "Restore replaces local data only after validation",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { OneLineText("Privacy & permissions", style = MaterialTheme.typography.titleMedium); OneLineText("Encryption, sharing and backup", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick = { showPrivacy = true }) { DhanamActionText("Open") }
                    }
                }
            }
            item { ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { OneLineText("Your vocabulary", style = MaterialTheme.typography.titleMedium); Text("Prarambha: home and daily actions\nVyavahara: money activity\nKhata: money place\nNivesha: investments\nSampada: overall wealth\nVinyasa: preferences and data controls", style = MaterialTheme.typography.bodySmall) } } }
        }
    }
    if (showCsvImportPicker) {
        val importableAccounts = state.accounts.filter { AccountRolePolicy.supportsTransactions(it.kind) }
        AlertDialog(
            onDismissRequest = { showCsvImportPicker = false },
            title = { Text("Import transactions") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Select the Khata where these transactions belong. New Varga and Vyakti will be created automatically.", style = MaterialTheme.typography.bodyMedium)
                    ExposedDropdownMenuBox(importAccountExpanded, { importAccountExpanded = !importAccountExpanded }) {
                        OutlinedTextField(
                            value = importableAccounts.firstOrNull { it.id == selectedImportAccountId }?.name ?: "Select a Khata",
                            onValueChange = {},
                            modifier = Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(),
                            readOnly = true,
                            label = { OneLineText("Target Khata") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(importAccountExpanded) }
                        )
                        ExposedDropdownMenu(importAccountExpanded, { importAccountExpanded = false }) {
                            importableAccounts.forEach { account ->
                                DropdownMenuItem(text = { OneLineText(account.name) }, onClick = { selectedImportAccountId = account.id; importAccountExpanded = false })
                            }
                        }
                    }
                    Text("CSV format must be: Date [DD/MM/YYYY], Type [Aaya/Vyaya], Amount, Category, Payee, Account, Notes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(onClick = {
                    showCsvImportPicker = false
                    openCsvFile.launch(arrayOf("text/csv", "text/comma-separated-values"))
                }, enabled = selectedImportAccountId != null) { DhanamActionText("Choose file") }
            },
            dismissButton = { TextButton(onClick = { showCsvImportPicker = false }) { DhanamActionText("Cancel") } }
        )
    }
    state.csvImportPlan?.let { plan ->
        CsvReconciliationDialog(
            plan = plan,
            currencyCode = state.currencyCode,
            dateFormatPreference = state.dateFormatPreference,
            onConfirm = onConfirmTransactionsCsvImport,
            onDismiss = onCancelTransactionsCsvImport,
        )
    }

    if (showRemoveStarterDataConfirmation) {
        AlertDialog(
            onDismissRequest = { showRemoveStarterDataConfirmation = false },
            title = { Text("Remove starter data?") },
            text = { Text("This removes only data supplied with the app: demo Khatas and their demo records, seeded Varga, and suggested Vyakti. Your own Khatas, Vyavahara, Varga, and Vyakti remain. You can add anything back manually.") },
            confirmButton = { Button(onClick = { onRemoveStarterData(); showRemoveStarterDataConfirmation = false }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { DhanamActionText("Remove") } },
            dismissButton = { TextButton(onClick = { showRemoveStarterDataConfirmation = false }) { DhanamActionText("Cancel") } }
        )
    }
    if (showJsonExportConfirmation) {
        AlertDialog(
            onDismissRequest = { showJsonExportConfirmation = false },
            title = { Text("Export plaintext .dhanam?") },
            text = { Text("This file contains readable financial data. Save it only to a trusted location and share it only with tools you trust. Use encrypted .ndf for backup and restore.") },
            confirmButton = {
                Button(onClick = {
                    showJsonExportConfirmation = false
                    createInterchangeFile.launch("nirmalam-finances.dhanam")
                }) { Text("Choose location") }
            },
            dismissButton = { TextButton(onClick = { showJsonExportConfirmation = false }) { DhanamActionText("Cancel") } }
        )
    }
    ndfAction?.let { action ->
        AlertDialog(
            onDismissRequest = { ndfAction = null; ndfPassphrase = ""; importReplacementConfirmed = false; passphraseVisible = false },
            title = { Text(if (action == NdfFileAction.EXPORT) "Export encrypted backup" else "Import encrypted backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (action == NdfFileAction.EXPORT) "Confirm the current database passphrase. The backup remains encrypted and is safe to move only to locations you trust." else "Enter the passphrase used for the selected .ndf backup. If the passphrase is wrong or the file is invalid, your current device data remains unchanged.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        ndfPassphrase,
                        { ndfPassphrase = it },
                        Modifier.fillMaxWidth(),
                        label = { OneLineText("Database passphrase") },
                        visualTransformation = if (passphraseVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        trailingIcon = { TextButton(onClick = { passphraseVisible = !passphraseVisible }) { Text(if (passphraseVisible) "Hide" else "Show") } },
                        supportingText = { Text(if (action == NdfFileAction.EXPORT) "A mistyped passphrase will stop the export before any backup is created." else "Use the passphrase from the backup's original device or export session.") },
                        singleLine = true
                    )
                    if (action == NdfFileAction.IMPORT) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = importReplacementConfirmed, onCheckedChange = { importReplacementConfirmed = it })
                            Text("I understand that a successful restore replaces this device's current local finance database.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    pendingNdfPassphrase = ndfPassphrase
                    ndfPassphrase = ""
                    importReplacementConfirmed = false
                    passphraseVisible = false
                    ndfAction = null
                    if (action == NdfFileAction.EXPORT) createNdfFile.launch("nirmalam-dhanam-backup.ndf") else openNdfFile.launch(arrayOf("application/vnd.nirmalam-dhanam.backup+zip", "application/zip"))
                }, enabled = pendingNdfPassphrase.isEmpty() && ndfPassphrase.length >= 8 && (action == NdfFileAction.EXPORT || importReplacementConfirmed)) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { ndfAction = null; ndfPassphrase = ""; importReplacementConfirmed = false; passphraseVisible = false }) { DhanamActionText("Cancel") } }
        )
    }
}

@Composable
private fun CsvReconciliationDialog(
    plan: CsvImportPlan,
    currencyCode: String,
    dateFormatPreference: DateFormatPreference,
    onConfirm: (Map<Int, CsvImportAction>) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember(plan) { mutableStateOf(if (plan.attentionCount > 0) CsvReviewFilter.NEEDS_REVIEW else CsvReviewFilter.ALL) }
    val actions = remember(plan) {
        mutableStateMapOf<Int, CsvImportAction>().apply {
            plan.items.forEach { item -> put(item.rowNumber, item.suggestedAction) }
        }
    }
    val shown = when (filter) {
        CsvReviewFilter.ALL -> plan.items
        CsvReviewFilter.NEEDS_REVIEW -> plan.items.filter { it.status != ReconciliationStatus.NEW }
        CsvReviewFilter.NEW -> plan.items.filter { it.status == ReconciliationStatus.NEW }
    }
    val writeCount = actions.count { (_, action) -> action != CsvImportAction.SKIP }
    val replaceCount = actions.count { (_, action) -> action == CsvImportAction.REPLACE }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OneLineText("Review CSV import", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${plan.items.size} rows for ${plan.accountName} · ${plan.newCount} new · ${plan.alreadyReconciledCount} already reconciled · ${plan.attentionCount} need review",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Nothing has been written yet. Keep imports a new row, Skip ignores it, and Replace updates the matched existing ledger row.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = filter == CsvReviewFilter.NEEDS_REVIEW,
                            onClick = { filter = CsvReviewFilter.NEEDS_REVIEW },
                            label = { OneLineText("Review ${plan.attentionCount}", style = MaterialTheme.typography.labelMedium) },
                            enabled = plan.attentionCount > 0,
                        )
                    }
                    item {
                        FilterChip(
                            selected = filter == CsvReviewFilter.NEW,
                            onClick = { filter = CsvReviewFilter.NEW },
                            label = { OneLineText("New ${plan.newCount}", style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                    item {
                        FilterChip(
                            selected = filter == CsvReviewFilter.ALL,
                            onClick = { filter = CsvReviewFilter.ALL },
                            label = { OneLineText("All ${plan.items.size}", style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }

                if (shown.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No rows in this view.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(shown.size) { index ->
                            val item = shown[index]
                            val candidate = item.candidate
                            val signedAmount = if (candidate.direction == TransactionDirection.CREDIT) candidate.amountPaise else -candidate.amountPaise
                            val statusColor = when (item.status) {
                                ReconciliationStatus.NEW -> MaterialTheme.colorScheme.primary
                                ReconciliationStatus.PROBABLE_DUPLICATE -> MaterialTheme.colorScheme.tertiary
                                ReconciliationStatus.POSSIBLE_MATCH -> MaterialTheme.colorScheme.secondary
                                ReconciliationStatus.CONFLICT -> MaterialTheme.colorScheme.error
                                ReconciliationStatus.ALREADY_RECONCILED -> MaterialTheme.colorScheme.outline
                            }
                            val statusLabel = when (item.status) {
                                ReconciliationStatus.NEW -> "NEW"
                                ReconciliationStatus.PROBABLE_DUPLICATE -> "PROBABLE DUPLICATE"
                                ReconciliationStatus.POSSIBLE_MATCH -> "POSSIBLE MATCH"
                                ReconciliationStatus.CONFLICT -> "CONFLICT"
                                ReconciliationStatus.ALREADY_RECONCILED -> "ALREADY RECONCILED"
                            }

                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Row ${item.rowNumber} · $statusLabel", style = MaterialTheme.typography.labelMedium, color = statusColor)
                                            OneLineText(candidate.payee ?: candidate.merchant ?: "Unlabelled", style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                "${formatDate(candidate.occurredAtEpochMs, dateFormatPreference)} · ${candidate.category ?: "Uncategorised"}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Text(
                                            formatMoney(signedAmount, currencyCode, includeSign = true),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (signedAmount < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                        )
                                    }

                                    Text(item.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                                    item.matchedTransaction?.let { matched ->
                                        val matchedSigned = if (matched.direction == TransactionDirection.CREDIT) matched.amountPaise else -matched.amountPaise
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = MaterialTheme.shapes.medium,
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                        ) {
                                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(
                                                    if (item.matchedExistingRecord) "Matched existing ledger row" else "Matched another CSV row",
                                                    style = MaterialTheme.typography.labelMedium,
                                                )
                                                Text(
                                                    "${formatDate(matched.occurredAtEpochMs, dateFormatPreference)} · ${matched.payee ?: matched.merchant ?: "Unlabelled"} · ${formatMoney(matchedSigned, currencyCode, includeSign = true)}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                        }
                                    }

                                    LazyRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        item {
                                            FilterChip(
                                                selected = actions[item.rowNumber] == CsvImportAction.KEEP,
                                                onClick = { actions[item.rowNumber] = CsvImportAction.KEEP },
                                                label = { OneLineText(if (item.status == ReconciliationStatus.NEW) "Keep" else "Keep anyway", style = MaterialTheme.typography.labelMedium) },
                                                enabled = item.status != ReconciliationStatus.ALREADY_RECONCILED,
                                            )
                                        }
                                        item {
                                            FilterChip(
                                                selected = actions[item.rowNumber] == CsvImportAction.SKIP,
                                                onClick = { actions[item.rowNumber] = CsvImportAction.SKIP },
                                                label = { OneLineText("Skip", style = MaterialTheme.typography.labelMedium) },
                                            )
                                        }
                                        if (item.canReplace) {
                                            item {
                                                FilterChip(
                                                    selected = actions[item.rowNumber] == CsvImportAction.REPLACE,
                                                    onClick = { actions[item.rowNumber] = CsvImportAction.REPLACE },
                                                    label = { OneLineText("Replace", style = MaterialTheme.typography.labelMedium) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("$writeCount row${if (writeCount == 1) "" else "s"} will be written", style = MaterialTheme.typography.labelLarge)
                        if (replaceCount > 0) {
                            Text("$replaceCount existing row${if (replaceCount == 1) "" else "s"} will be replaced", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = {
                        plan.items.forEach { item -> actions[item.rowNumber] = item.suggestedAction }
                    }) { Text("Reset") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { DhanamActionText("Cancel") }
                    Button(
                        onClick = { onConfirm(actions.toMap()) },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (writeCount > 0) "Confirm import" else "Confirm skips") }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun NirmalamAiScreen(state: MvpFinanceState, onBack: () -> Unit, onSave: (String, String, String, Boolean) -> Unit, onDisable: () -> Unit, onInsight: (NirmalamAiInsight) -> Unit) {
    val context = LocalContext.current
    val saved = remember { NirmalamAiPreferences(context).settings() }
    
    val providers = listOf(
        Triple("ChatGPT (OpenAI)", "https://api.openai.com/v1", listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo")),
        Triple("Gemini (Google)", "https://generativelanguage.googleapis.com/v1beta/openai/", listOf("gemini-1.5-pro", "gemini-1.5-flash")),
        Triple("Custom (OpenAI-compatible)", "", emptyList())
    )

    var selectedProviderIdx by remember { 
        val idx = providers.indexOfFirst { it.second == saved.endpoint }.coerceAtLeast(0)
        mutableStateOf(if (saved.endpoint.isBlank()) 0 else idx)
    }
    var endpoint by remember { mutableStateOf(if (providers[selectedProviderIdx].second.isEmpty()) saved.endpoint else providers[selectedProviderIdx].second) }
    var model by remember { mutableStateOf(saved.model) }
    var apiKey by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            DhanamScreenTopBar(
                title = "Nirmalam AI",
                subtitle = "Preset private insights",
                onBack = onBack,
            )
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OneLineText("Not a chat assistant", style = MaterialTheme.typography.titleMedium)
                        Text("Nirmalam AI offers only the fixed reflections below. It does not read free-form questions or make trades, tax, credit, or investment recommendations.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            if (!state.nirmalamAiReady) {
                item { OneLineText("Bring your own LLM", style = MaterialTheme.typography.titleMedium) }
                item { Text("Use an OpenAI-compatible HTTPS endpoint. Your API key is encrypted with Android Keystore and is never written to the finance database or export files.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                
                item {
                    ExposedDropdownMenuBox(providerExpanded, { providerExpanded = !providerExpanded }) {
                        OutlinedTextField(providers[selectedProviderIdx].first, {}, Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(), readOnly = true, label = { OneLineText("Provider") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerExpanded) })
                        ExposedDropdownMenu(providerExpanded, { providerExpanded = false }) {
                            providers.forEachIndexed { index, p ->
                                DropdownMenuItem(text = { OneLineText(p.first) }, onClick = { 
                                    selectedProviderIdx = index
                                    if (p.second.isNotEmpty()) endpoint = p.second
                                    if (p.third.isNotEmpty()) model = p.third.first()
                                    providerExpanded = false 
                                })
                            }
                        }
                    }
                }

                if (providers[selectedProviderIdx].second.isEmpty()) {
                    item { OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { OneLineText("Base URL") }, supportingText = { Text("Example: https://api.openai.com/v1") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true) }
                }

                item {
                    if (providers[selectedProviderIdx].third.isNotEmpty()) {
                        ExposedDropdownMenuBox(modelExpanded, { modelExpanded = !modelExpanded }) {
                            OutlinedTextField(model, {}, Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(), readOnly = true, label = { OneLineText("Model") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelExpanded) })
                            ExposedDropdownMenu(modelExpanded, { modelExpanded = false }) {
                                providers[selectedProviderIdx].third.forEach { m ->
                                    DropdownMenuItem(text = { OneLineText(m) }, onClick = { model = m; modelExpanded = false })
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { OneLineText("Model") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
                    }
                }

                item { OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { OneLineText("API key") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = consent, onCheckedChange = { consent = it })
                        Text("I understand that pressing a preset sends only its preset-specific aggregate summary to my chosen provider. No raw Vyavahara, Vyakti, descriptions, or account IDs are sent.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { Button(onClick = { onSave(endpoint, model, apiKey, true) }, enabled = consent && endpoint.startsWith("https://") && model.isNotBlank() && apiKey.isNotBlank(), modifier = Modifier.fillMaxWidth()) { DhanamActionText("Enable Nirmalam AI") } }
            } else {
                item { OneLineText("Choose an insight", style = MaterialTheme.typography.titleMedium) }
                item { Text("Each preset sends only the aggregate fields it needs. Presets are disabled when required local evidence is missing. Data is sent only when you press an available preset.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(NirmalamAiInsight.entries.size) { index ->
                    val insight = NirmalamAiInsight.entries[index]
                    val prepared = prepareNirmalamAiContext(
                        insight = insight,
                        cashPaise = state.cashPaise,
                        transactions = state.allTransactions,
                        investmentHistory = state.investmentHistory,
                        accounts = state.accounts,
                        balances = state.accountBalances,
                        categories = state.categories
                    )
                    ElevatedCard(
                        onClick = { if (!state.nirmalamAiLoading && prepared.available) onInsight(insight) },
                        enabled = prepared.available && !state.nirmalamAiLoading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                OneLineText(insight.title, style = MaterialTheme.typography.titleMedium)
                                Text(insight.prompt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (!prepared.available) {
                                    Text(prepared.unavailableReason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                } else {
                                    Text("Required local data available", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            OneLineText(if (prepared.available) "›" else "—", style = MaterialTheme.typography.headlineMedium, color = if (prepared.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        }
                    }
                }
                if (state.nirmalamAiLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Preparing your preset insight…", style = MaterialTheme.typography.bodySmall) }
                state.nirmalamAiResponse?.let { response -> item { ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { OneLineText("Nirmalam AI reflection", style = MaterialTheme.typography.titleMedium); Text(response, style = MaterialTheme.typography.bodyMedium); Text("Review this as a reflection, not financial advice.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer) } } } }
                item { 
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onDisable, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)) { DhanamActionText("Disable AI") }
                        Button(onClick = { NirmalamAiPreferences(context).clear(); onDisable() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { DhanamActionText("Reset AI") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DataToolRow(
    title: String,
    subtitle: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OneLineText(title, style = MaterialTheme.typography.titleSmall)
            OneLineText(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onPrimary, modifier = Modifier.weight(1f)) { DhanamActionText(primaryLabel) }
                OutlinedButton(onClick = onSecondary, modifier = Modifier.weight(1f)) { DhanamActionText(secondaryLabel) }
            }
        }
    }
}

@Composable
private fun ManagementLinkCard(title: String, subtitle: String, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                OneLineText(title, style = MaterialTheme.typography.titleMedium)
                OneLineText(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OneLineText("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun VargaManagementScreen(categories: List<CategoryEntity>, onBack: () -> Unit, onSave: (String, TransactionDirection, String, CategoryPriority, CategoryNature) -> Unit, onUpdate: (String, String, TransactionDirection, String, CategoryPriority, CategoryNature) -> Unit, onDelete: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    val shown = categories.filter { it.name.contains(query.trim(), ignoreCase = true) }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            DhanamScreenTopBar(
                title = "Varga",
                subtitle = "Categories",
                onBack = onBack,
                actions = { TextButton(onClick = { showNew = true }) { DhanamActionText("Add") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { OneLineText("Search Varga") }, placeholder = { OneLineText("Name or purpose") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true) }
            item { Text("${shown.size} of ${categories.size} Varga", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(shown.size) { index ->
                val category = shown[index]
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editingId == category.id) InlineCategoryEditor(category, onCancel = { editingId = null }, onSave = { name, direction, icon, priority, nature -> onUpdate(category.id, name, direction, icon, priority, nature); editingId = null })
                    else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            IconifiedCategoryLabel(category.name, category.iconKey)
                            Row { TextButton(onClick = { editingId = category.id }) { DhanamActionText("Edit") }; if (!category.isSystem) TextButton(onClick = { onDelete(category.id) }) { DhanamActionText("Remove") } }
                        }
                    }
                } }
            }
        }
    }
    if (showNew) CategoryEditorDialog(onDismiss = { showNew = false }, onSave = { name, direction, icon, priority, nature -> onSave(name, direction, icon, priority, nature); showNew = false })
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun VyaktiManagementScreen(payees: List<PayeeEntity>, categories: List<CategoryEntity>, onBack: () -> Unit, onSave: (String, String?) -> Unit, onUpdate: (String, String, String?) -> Unit, onDelete: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    val shown = payees.filter { payee -> listOfNotNull(payee.name, payee.defaultCategory).any { it.contains(query.trim(), ignoreCase = true) } }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            DhanamScreenTopBar(
                title = "Vyakti",
                subtitle = "Payees",
                onBack = onBack,
                actions = { TextButton(onClick = { showNew = true }) { DhanamActionText("Add") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { OneLineText("Search Vyakti") }, placeholder = { OneLineText("Person, shop, or institution") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true) }
            item { Text("${shown.size} of ${payees.size} saved Vyakti", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(shown.size) { index ->
                val payee = shown[index]
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editingId == payee.id) InlinePayeeEditor(payee, categories, onCancel = { editingId = null }, onSave = { name, category -> onUpdate(payee.id, name, category); editingId = null })
                    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column { OneLineText(payee.name, style = MaterialTheme.typography.titleSmall); Text(payee.defaultCategory?.let { "Default Varga · $it" } ?: "No default Varga", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Row { TextButton(onClick = { editingId = payee.id }) { DhanamActionText("Edit") }; TextButton(onClick = { onDelete(payee.id) }) { DhanamActionText("Remove") } }
                    }
                } }
            }
        }
    }
    if (showNew) PayeeEditorDialog(categories, onDismiss = { showNew = false }, onSave = { name, category -> onSave(name, category); showNew = false })
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun KhataManagementScreen(state: MvpFinanceState, onBack: () -> Unit, onUpdate: (String, String, AccountProductType, AssetClass, String, AccountKind) -> Unit, onArchive: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var khataToArchive by remember { mutableStateOf<AccountEntity?>(null) }
    val shown = state.accounts.filter { it.name.contains(query.trim(), ignoreCase = true) }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { DhanamScreenTopBar(title = "Khata", subtitle = "Money places", onBack = onBack) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { OneLineText("Search Khata") }, placeholder = { OneLineText("Account name") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true) }
            items(shown.size) { index ->
                val account = shown[index]
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editingId == account.id) InlineKhataEditor(account, onCancel = { editingId = null }, onSave = { name, product, assetClass, target, cashKind -> onUpdate(account.id, name, product, assetClass, target, cashKind); editingId = null })
                    else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                OneLineText(account.name, style = MaterialTheme.typography.titleSmall)
                                val roleLabel = when (account.kind) {
                                    AccountKind.SPENDING -> "Daily"
                                    AccountKind.SAVINGS -> "Savings"
                                    AccountKind.EMERGENCY -> "Emergency"
                                    AccountKind.CREDIT -> "Liability"
                                    AccountKind.INVESTMENT -> "Investment"
                                }
                                Text("${account.productType.name.replace('_', ' ')} · $roleLabel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row {
                                TextButton(onClick = { editingId = account.id }) { DhanamActionText("Edit") }
                                TextButton(onClick = { khataToArchive = account }) { DhanamActionText("Archive") }
                            }
                        }
                    }
                } }
            }
        }
    }
    khataToArchive?.let { account -> AlertDialog(onDismissRequest = { khataToArchive = null }, title = { Text("Archive Khata?") }, text = { Text("${account.name} will be hidden from active views. Its history remains safely in this encrypted database.") }, confirmButton = { Button(onClick = { onArchive(account.id); khataToArchive = null }) { DhanamActionText("Archive") } }, dismissButton = { TextButton(onClick = { khataToArchive = null }) { DhanamActionText("Cancel") } }) }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun InlineKhataEditor(account: AccountEntity, onCancel: () -> Unit, onSave: (String, AccountProductType, AssetClass, String, AccountKind) -> Unit) {
    var name by remember(account.id) { mutableStateOf(account.name) }
    var product by remember(account.id) { mutableStateOf(account.productType) }
    var assetClass by remember(account.id) { mutableStateOf(account.assetClass) }
    var target by remember(account.id) { mutableStateOf((account.targetAllocationBps / 100.0).toString()) }
    var cashKind by remember(account.id) { mutableStateOf(account.kind.takeIf { it in AccountRolePolicy.cashAccountKinds } ?: AccountKind.SPENDING) }
    var productExpanded by remember(account.id) { mutableStateOf(false) }
    var classExpanded by remember(account.id) { mutableStateOf(false) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("Modify Khata", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { OneLineText("Khata name") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
    ExposedDropdownMenuBox(productExpanded, { productExpanded = !productExpanded }) {
        OutlinedTextField(product.name.replace('_', ' '), {}, Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(), readOnly = true, label = { OneLineText("Product type") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(productExpanded) })
        ExposedDropdownMenu(productExpanded, { productExpanded = false }) {
            Text("DAILY KHATAS", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall)
            listOf(AccountProductType.CASH, AccountProductType.BANK).forEach { type -> DropdownMenuItem(text = { OneLineText(type.name.replace('_', ' ')) }, onClick = { product = type; assetClass = suggestedAssetClass(type); productExpanded = false }) }
            HorizontalDivider()
            Text("LIABILITIES", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall)
            listOf(AccountProductType.CREDIT_CARD, AccountProductType.LOAN).forEach { type -> DropdownMenuItem(text = { OneLineText(type.name.replace('_', ' ')) }, onClick = { product = type; assetClass = suggestedAssetClass(type); productExpanded = false }) }
            HorizontalDivider()
            Text("INVESTMENT HOLDINGS", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall)
            investmentProductTypes.forEach { type -> DropdownMenuItem(text = { OneLineText(type.name.replace('_', ' ')) }, onClick = { product = type; assetClass = suggestedAssetClass(type); productExpanded = false }) }
        }
    }
    if (AccountRolePolicy.supportsUserSelectedCashRole(product)) {
        Text("Purpose", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                AccountKind.SPENDING to "Daily",
                AccountKind.SAVINGS to "Savings",
                AccountKind.EMERGENCY to "Emergency"
            ).forEach { (kind, label) ->
                FilterChip(selected = cashKind == kind, onClick = { cashKind = kind }, label = { OneLineText(label, style = MaterialTheme.typography.labelMedium) })
            }
        }
    }
    ExposedDropdownMenuBox(classExpanded, { classExpanded = !classExpanded }) {
        OutlinedTextField(assetClass.name, {}, Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(), readOnly = true, label = { OneLineText("Asset class") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(classExpanded) })
        ExposedDropdownMenu(classExpanded, { classExpanded = false }) { AssetClass.entries.forEach { type -> DropdownMenuItem(text = { OneLineText(type.name) }, onClick = { assetClass = type; classExpanded = false }) } }
    }
    if (product in investmentProductTypes) {
        OutlinedTextField(target, { target = it }, Modifier.fillMaxWidth(), label = { OneLineText("Target %") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onCancel) { DhanamActionText("Cancel") }; Button(onClick = { onSave(name, product, assetClass, target, cashKind) }, enabled = name.isNotBlank()) { DhanamActionText("Save") } }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun UserGuideScreen(onBack: () -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { DhanamScreenTopBar(title = "User guide", onBack = onBack) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { OneLineText("Nirmalam Dhanam", style = MaterialTheme.typography.headlineSmall) }
            item { Text("A calm, private daily money practice. Records stay encrypted on this device; optional BYOL AI requests are explained below.", style = MaterialTheme.typography.bodyLarge) }
            item { UserGuideSection("Concepts", listOf(
                "Prarambha — your daily starting point for available cash and quick entry.",
                "Vyavahara — a money event. Aaya is inflow; Vyaya is outflow.",
                "Khata — a money place, such as cash, bank, card, loan, or investment holding.",
                "Varga — a reusable category that explains the purpose of a Vyavahara.",
                "Vyakti — a person, shop, employer, or institution involved in a Vyavahara.",
                "Nivesha and Sampada — investments and your overall wealth view."
            )) }
            item { UserGuideSection("Features", listOf(
                "Create Khatas for cash, banks, liabilities, and investment products.",
                "Record Aaya or Vyaya with a transaction date, Varga, Vyakti, and private description.",
                "Manage Varga entries with label-backed glyphs and manage saved Vyakti defaults.",
                "Review Vyavahara by Khata, Varga, timeframe, inflow/outflow, or search.",
                "Use Money Pulse and Spend Map for local-only summaries.",
                "Record dated Nivesha cost-and-value check-ins; Dhanam automatically derives contributions, withdrawals, unrealised gain/loss, movement, and return since the previous snapshot.",
                "For a redemption, cost/value alone cannot prove the cash proceeds. Dhanam marks the return as an estimate and never invents realised gain without proceeds evidence.",
                "Open the Financial timeline under Insights for one local chronology of Aaya/Vyaya, Nivesha check-ins and returns, Sampada snapshots, goal milestones, and explainable unusual-spending signals. The timeline is derived and never stores a second history.",
                "Link percentages of existing Khatas or Nivesha holdings to goals. Goal progress, remaining amount, target-date status, and completion projections are derived from those existing facts rather than a separate goal balance.",
                "Use the cooling tank for larger wants purchases and neurodiverse mode for a calmer presentation."
            )) }
            item { UserGuideSection("Nirmalam AI", listOf(
                "Nirmalam AI is optional and disabled by default. It is a set of fixed financial reflections, not an open chat assistant.",
                "Set it up in Vinyasa with your own OpenAI-compatible HTTPS provider, model, and API key. The key is protected by Android Keystore and is not put in exports or backups.",
                "Choose only from Spending focus, Cash plan, Portfolio review, or Monthly recap. You cannot submit arbitrary questions.",
                "A request is sent only when you press one of these buttons. It contains a minimised aggregate summary: available cash, monthly income/expense, top Varga totals, and portfolio totals.",
                "Raw Vyavahara, Vyakti, descriptions, and account identifiers are excluded. Your selected provider processes the summary under its own privacy terms.",
                "AI reflections are for awareness, not investment, tax, credit, legal, or trading advice. You can disable Nirmalam AI at any time in Vinyasa."
            )) }
            item { UserGuideSection("FAQ", listOf(
                "Is my data uploaded? The database remains local and encrypted. If you enable Nirmalam AI, only its minimised aggregate summary is sent to your chosen provider when you tap a preset insight.",
                "Khata or Varga? Khata holds money; Varga explains why money moved.",
                "How do goals work? A goal references a percentage of one or more existing Khatas or Nivesha holdings. Linking or unlinking a goal never moves money or creates a second balance.",
                "Why a cooling tank? Wants above the threshold wait 48 hours before confirmation.",
                "Can I remove a Vyakti? Yes. Removing it only affects the reusable list; old records stay intact.",
                "Can I recover a forgotten passphrase? No. Keep it safe; encryption is intentional.",
                "Where are backups? Use Vinyasa to export or import an encrypted .ndf file. Keep the backup passphrase safe; a wrong passphrase never replaces your local data.",
                "Why does Nirmalam AI show a dash or limited insight? It needs enough aggregate, dated records for the selected reflection; it will not invent missing financial facts."
            )) }
        }
    }
}

@Composable
private fun UserGuideSection(title: String, items: List<String>) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OneLineText(title, style = MaterialTheme.typography.titleMedium)
            items.forEach { item -> Text("•  $item", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun CategoryEditorDialog(onDismiss: () -> Unit, onSave: (String, TransactionDirection, String, CategoryPriority, CategoryNature) -> Unit) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("other") }
    var priority by remember { mutableStateOf(CategoryPriority.WANT) }
    var nature by remember { mutableStateOf(CategoryNature.VARIABLE) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New Varga") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(name, { name = it }, label = { OneLineText("Varga name") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
        Text("Priority", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(priority == CategoryPriority.NEED, { priority = CategoryPriority.NEED }, label = { OneLineText("Need", style = MaterialTheme.typography.labelMedium) })
            FilterChip(priority == CategoryPriority.WANT, { priority = CategoryPriority.WANT }, label = { OneLineText("Want", style = MaterialTheme.typography.labelMedium) })
        }
        Text("Nature", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(nature == CategoryNature.FIXED, { nature = CategoryNature.FIXED }, label = { OneLineText("Fixed", style = MaterialTheme.typography.labelMedium) })
            FilterChip(nature == CategoryNature.VARIABLE, { nature = CategoryNature.VARIABLE }, label = { OneLineText("Variable", style = MaterialTheme.typography.labelMedium) })
        }
        Text("Choose a glyph", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(categoryGlyphKeys.size) { index -> val key = categoryGlyphKeys[index]; FilterChip(icon == key, { icon = key }, label = { CategoryGlyph(key, 22.dp) }) } }
    } }, confirmButton = { Button(onClick = { onSave(name, TransactionDirection.DEBIT, icon, priority, nature) }, enabled = name.isNotBlank()) { DhanamActionText("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { DhanamActionText("Cancel") } })
}

@Composable
private fun PayeeEditorDialog(categories: List<CategoryEntity>, onDismiss: () -> Unit, onSave: (String, String?) -> Unit) {
    var name by remember { mutableStateOf("") }; var defaultCategory by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New Vyakti") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(name, { name = it }, label = { OneLineText("Name") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
        OutlinedTextField(defaultCategory, { defaultCategory = it }, label = { OneLineText("Default Varga") }, placeholder = { OneLineText(categories.firstOrNull()?.name ?: "Food") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
    } }, confirmButton = { Button(onClick = { onSave(name, defaultCategory.ifBlank { null }) }, enabled = name.isNotBlank()) { DhanamActionText("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { DhanamActionText("Cancel") } })
}

@Composable
private fun InlineCategoryEditor(category: CategoryEntity, onCancel: () -> Unit, onSave: (String, TransactionDirection, String, CategoryPriority, CategoryNature) -> Unit) {
    var name by remember(category.id) { mutableStateOf(category.name) }
    var icon by remember(category.id) { mutableStateOf(category.iconKey ?: "other") }
    var priority by remember(category.id) { mutableStateOf(category.priority) }
    var nature by remember(category.id) { mutableStateOf(category.nature) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("Modify Varga", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { OneLineText("Varga name") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
    Text("Priority", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(priority == CategoryPriority.NEED, { priority = CategoryPriority.NEED }, label = { OneLineText("Need", style = MaterialTheme.typography.labelMedium) })
        FilterChip(priority == CategoryPriority.WANT, { priority = CategoryPriority.WANT }, label = { OneLineText("Want", style = MaterialTheme.typography.labelMedium) })
    }
    Text("Nature", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(nature == CategoryNature.FIXED, { nature = CategoryNature.FIXED }, label = { OneLineText("Fixed", style = MaterialTheme.typography.labelMedium) })
        FilterChip(nature == CategoryNature.VARIABLE, { nature = CategoryNature.VARIABLE }, label = { OneLineText("Variable", style = MaterialTheme.typography.labelMedium) })
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(categoryGlyphKeys.size) { index -> val key = categoryGlyphKeys[index]; FilterChip(icon == key, { icon = key }, label = { CategoryGlyph(key, 22.dp) }) } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onCancel) { DhanamActionText("Cancel") }; Button(onClick = { onSave(name, category.transactionDirection, icon, priority, nature) }, enabled = name.isNotBlank()) { DhanamActionText("Save") } }
}

@Composable
private fun InlinePayeeEditor(payee: PayeeEntity, categories: List<CategoryEntity>, onCancel: () -> Unit, onSave: (String, String?) -> Unit) {
    var name by remember(payee.id) { mutableStateOf(payee.name) }
    var defaultCategory by remember(payee.id) { mutableStateOf(payee.defaultCategory.orEmpty()) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("Modify Vyakti", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { OneLineText("Name") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
    OutlinedTextField(defaultCategory, { defaultCategory = it }, Modifier.fillMaxWidth(), label = { OneLineText("Default Varga") }, placeholder = { OneLineText(categories.firstOrNull()?.name ?: "Optional") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onCancel) { DhanamActionText("Cancel") }; Button(onClick = { onSave(name, defaultCategory.ifBlank { null }) }, enabled = name.isNotBlank()) { DhanamActionText("Save") } }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PrivacyAndPermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { DhanamScreenTopBar(title = "Privacy & permissions", onBack = onBack) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { OneLineText("Your data stays yours", style = MaterialTheme.typography.headlineSmall) }
            item { Text("Nirmalam Dhanam is local-first. It has no account sign-in, advertising SDK, analytics SDK, or cloud sync in this release.", style = MaterialTheme.typography.bodyMedium) }
            item { PrivacySection("Encrypted local storage", "Financial records are stored in a SQLCipher-encrypted on-device database unlocked with your passphrase. App backups are disabled by default.") }
            item { PrivacySection("Device feedback", "Vibration is used only for optional haptic confirmation around cooling-tank actions.") }
            item { PrivacySection("Backups", "Encrypted .ndf backup files should be stored only in locations you trust. Treat a backup and its passphrase as sensitive financial information.") }
            item { PrivacySection("Data sharing", "The app does not upload, sell, or share raw financial records. If you explicitly enable Nirmalam AI and press a preset insight, it sends a minimised aggregate summary to the provider you chose. JSON exports and encrypted .ndf backups are created only after you select a destination through Android's system file picker.") }
            item { OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, PrivacyPolicyUrl.toUri())) }, modifier = Modifier.fillMaxWidth()) { DhanamActionText("Open privacy policy") } }
        }
    }
}

@Composable
private fun PrivacySection(title: String, body: String) {
    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { OneLineText(title, style = MaterialTheme.typography.titleSmall); Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val versionLabel = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    val packageName = context.packageName
    val contactIntent = remember {
        Intent(Intent.ACTION_SENDTO, "mailto:$SupportEmail".toUri()).apply {
            putExtra(Intent.EXTRA_SUBJECT, "Nirmalam Dhanam support")
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { DhanamScreenTopBar(title = "About", onBack = onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 20.dp),
            contentPadding = PaddingValues(vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(88.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                OneLineText("ND", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OneLineText("Nirmalam Dhanam", style = MaterialTheme.typography.headlineSmall)
                            Text("A clear, private money practice", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Version $versionLabel", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OneLineText("What this app is", style = MaterialTheme.typography.titleMedium)
                        Text("Nirmalam Dhanam is a local-first personal finance app for cashflow, investments, and net worth. It is designed to keep records on-device, reduce clutter, and support calmer decisions.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OneLineText("App details", style = MaterialTheme.typography.titleMedium)
                        AboutDetailRow("Version", versionLabel)
                        AboutDetailRow("Package", packageName)
                        AboutDetailRow("Database", "SQLCipher + Room")
                        AboutDetailRow("Storage", "Local encrypted records")
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OneLineText("Support & trust", style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, PrivacyPolicyUrl.toUri())) }, modifier = Modifier.fillMaxWidth()) { DhanamActionText("Open privacy policy") }
                        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, SupportWebsiteUrl.toUri())) }, modifier = Modifier.fillMaxWidth()) { DhanamActionText("Open website") }
                        OutlinedButton(onClick = { context.startActivity(contactIntent) }, modifier = Modifier.fillMaxWidth()) { DhanamActionText("Email support") }
                        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, GithubRepositoryUrl.toUri())) }, modifier = Modifier.fillMaxWidth()) { DhanamActionText("Open GitHub repository") }
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OneLineText("Acknowledgements", style = MaterialTheme.typography.titleMedium)
                        Text("Built with Kotlin, Jetpack Compose Material 3, Coroutines, Room, and SQLCipher for encrypted local storage.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Nirmalam AI is optional, bring-your-own-provider, and available only through fixed insight buttons.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

