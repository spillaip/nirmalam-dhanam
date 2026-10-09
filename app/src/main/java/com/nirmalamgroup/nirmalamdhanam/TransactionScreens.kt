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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
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


@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun TransactionHistoryScreen(state: MvpFinanceState, onBack: () -> Unit, onReports: () -> Unit, onDelete: (String) -> Unit, onUpdate: (String, String, String, String, String, TransactionDirection) -> Unit, onRecord: (String, String, String, String, TransactionDirection, String, Long) -> Unit, onSavedLedgerViewChanged: (LedgerRange, LedgerFilter, String?, String?) -> Unit) {
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var actionMenuExpanded by remember { mutableStateOf(false) }
    var showNewVyavahara by remember { mutableStateOf(false) }
    var filter by remember(state.savedLedgerFilter) { mutableStateOf(ledgerFilterFromConfig(state.savedLedgerFilter)) }
    var range by remember(state.savedLedgerRange) { mutableStateOf(ledgerRangeFromConfig(state.savedLedgerRange)) }
    var selectedAccountId by remember(state.savedLedgerAccountId) { mutableStateOf(state.savedLedgerAccountId) }
    var selectedCategoryName by remember(state.savedLedgerCategoryName) { mutableStateOf(state.savedLedgerCategoryName) }
    var transactionToDelete by remember { mutableStateOf<TransactionEntity?>(null) }
    var editingTransactionId by remember { mutableStateOf<String?>(null) }
    val defaultViewLabel = "${range.label} · ${selectedAccountId?.let { id -> state.accounts.firstOrNull { it.id == id }?.name } ?: "All Khatas"} · ${selectedCategoryName ?: "All Varga"} · ${filter.label}"
    val accountNames = state.accounts.associate { it.id to it.name }
    val selectedAccount = state.accounts.firstOrNull { it.id == selectedAccountId }
    val queryMatches = state.allTransactions.filter { transaction ->
        query.isBlank() || listOfNotNull(transaction.payee, transaction.category, transaction.description, accountNames[transaction.accountId]).any { it.contains(query.trim(), ignoreCase = true) }
    }
    val accountScoped = queryMatches.filter { transaction -> selectedAccountId == null || transaction.accountId == selectedAccountId }
    val categoryScoped = accountScoped.filter { transaction -> selectedCategoryName == null || transaction.category == selectedCategoryName }
    val periodScoped = categoryScoped.filter { transaction -> range.includes(transaction.occurredAtEpochMs) }
    val shown = periodScoped.filter { transaction ->
        when (filter) {
            LedgerFilter.ALL -> true
            LedgerFilter.SPENT -> transaction.direction == TransactionDirection.DEBIT
            LedgerFilter.INCOME -> transaction.direction == TransactionDirection.CREDIT
        }
    }
    val income = periodScoped.filter { it.direction == TransactionDirection.CREDIT }.sumOf { it.amountPaise }
    val spent = periodScoped.filter { it.direction == TransactionDirection.DEBIT }.sumOf { it.amountPaise }
    val net = income - spent
    val spendingByCategory = periodScoped.filter { it.direction == TransactionDirection.DEBIT }
        .groupBy { it.category?.ifBlank { null } ?: "Uncategorised" }
        .mapValues { (_, entries) -> entries.sumOf { it.amountPaise } }
        .toList()
        .sortedByDescending { it.second }
    val recentAmounts = state.recentTransactions
        .asSequence()
        .filterNot { it.isHoldingTank }
        .map { it.amountPaise }
        .distinct()
        .take(6)
        .toList()
    val mostUsedPayee = periodScoped.filter { it.direction == TransactionDirection.DEBIT && !it.payee.isNullOrBlank() }
        .groupingBy { it.payee!! }
        .eachCount()
        .maxByOrNull { it.value }
    val grouped = shown.groupBy { Instant.ofEpochMilli(it.occurredAtEpochMs).atZone(ZoneId.systemDefault()).toLocalDate() }
        .toSortedMap(compareByDescending { it })
    LaunchedEffect(range, filter, selectedAccountId, selectedCategoryName) {
        onSavedLedgerViewChanged(range, filter, selectedAccountId, selectedCategoryName)
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            if (searchExpanded) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { query = ""; searchExpanded = false }) {
                            Icon(StandardBackIcon, contentDescription = "Close search")
                        }
                    },
                    title = {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { OneLineText("Search Vyavahara") },
                            placeholder = { OneLineText("Payee, Varga, Khata or note") },
                            singleLine = true,
                        )
                    },
                )
            } else {
                DhanamScreenTopBar(
                    title = "Vyavahara",
                    subtitle = "Money activity",
                    onBack = onBack,
                    actions = {
                        IconButton(
                            onClick = { searchExpanded = true },
                            modifier = Modifier.semantics { contentDescription = "Search Vyavahara" },
                        ) { OneLineText("⌕", style = MaterialTheme.typography.headlineSmall) }
                        Box {
                            IconButton(
                                onClick = { actionMenuExpanded = true },
                                modifier = Modifier.semantics { contentDescription = "Vyavahara actions" },
                            ) { OneLineText("⋮", style = MaterialTheme.typography.headlineSmall) }
                            DropdownMenu(
                                expanded = actionMenuExpanded,
                                onDismissRequest = { actionMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { OneLineText("Filters") },
                                    onClick = { actionMenuExpanded = false; showFilters = true },
                                )
                                DropdownMenuItem(
                                    text = { OneLineText("Reports") },
                                    onClick = { actionMenuExpanded = false; onReports() },
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewVyavahara = true }, modifier = Modifier.navigationBarsPadding(), containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                OneLineText("+", style = MaterialTheme.typography.headlineMedium)
            }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("MONEY PULSE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            LedgerMetric("In", formatMoney(income, state.currencyCode, includeSign = true), MaterialTheme.colorScheme.primary)
                            LedgerMetric("Out", formatMoney(-spent, state.currencyCode), MaterialTheme.colorScheme.error)
                            LedgerMetric("Net", formatMoney(net, state.currencyCode, includeSign = true), if (net < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                        Text("${range.description} · ${periodScoped.size} entries${selectedAccount?.let { " in ${it.name}" } ?: ""}. Use this as a gentle check-in, not a judgement.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { showFilters = true }, label = { OneLineText(defaultViewLabel, style = MaterialTheme.typography.labelMedium) })
                    if (query.isNotBlank() || filter != LedgerFilter.ALL || range != ledgerRangeFromConfig(state.savedLedgerRange) || selectedAccountId != state.savedLedgerAccountId || selectedCategoryName != state.savedLedgerCategoryName) {
                        AssistChip(onClick = {
                            query = ""
                            filter = ledgerFilterFromConfig(state.savedLedgerFilter)
                            range = ledgerRangeFromConfig(state.savedLedgerRange)
                            selectedAccountId = state.savedLedgerAccountId
                            selectedCategoryName = state.savedLedgerCategoryName
                        }, label = { OneLineText("Reset", style = MaterialTheme.typography.labelMedium) })
                    }
                }
            }
            if (query.isBlank()) item {
                Text("This default view is saved automatically for your next visit.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (spendingByCategory.isNotEmpty()) item {
                SpendingMap(
                    categories = spendingByCategory,
                    totalSpent = spent,
                    mostUsedPayee = mostUsedPayee?.key,
                    mostUsedPayeeCount = mostUsedPayee?.value ?: 0,
                    currencyCode = state.currencyCode
                )
            }
            selectedAccount?.let { account ->
                val balance = state.accountBalances.firstOrNull { it.accountId == account.id }?.balancePaise ?: account.openingBalancePaise
                item {
                    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) { Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Column { OneLineText("Selected Khata", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); Text(account.name, style = MaterialTheme.typography.titleSmall); OneLineText(account.productType.name.replace('_', ' '), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text(formatMoney(balance, state.currencyCode), style = MaterialTheme.typography.titleMedium) } }
                }
            }
            item { OneLineText("Vyavahara", style = MaterialTheme.typography.titleMedium) }
            if (grouped.isEmpty()) item { EmptyLedgerState(query.isNotBlank() || filter != LedgerFilter.ALL) }
            grouped.forEach { (date, transactions) ->
                item { Text(ledgerDayLabel(date, state.dateFormatPreference), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                items(transactions.size) { index ->
                    val transaction = transactions[index]
                    val signed = if (transaction.direction == TransactionDirection.CREDIT) transaction.amountPaise else -transaction.amountPaise
                    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                OneLineText(transaction.payee ?: "Unlabelled Vyavahara", style = MaterialTheme.typography.titleSmall)
                                Text(accountNames[transaction.accountId] ?: "Khata", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OneLineText(formatMoney(signed, state.currencyCode, includeSign = true), style = MaterialTheme.typography.titleMedium, color = if (signed < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            transaction.category?.let { AssistChip(onClick = {}, label = { IconifiedCategoryLabel(it, compact = true) }) }
                            transaction.envelopeType?.let { Text(it.name.lowercase().replaceFirstChar { char -> char.titlecase() }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        transaction.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (editingTransactionId == transaction.id) {
                            InlineVyavaharaEditor(
                                transaction = transaction,
                                payees = state.payees,
                                categories = state.categories,
                                onCancel = { editingTransactionId = null },
                                onSave = { amount, payee, category, description, direction -> onUpdate(transaction.id, amount, payee, category, description, direction); editingTransactionId = null }
                            )
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { editingTransactionId = transaction.id }) { DhanamActionText("Edit") }
                                TextButton(onClick = { transactionToDelete = transaction }) { DhanamActionText("Delete") }
                            }
                        }
                    } }
                }
            }
        }
    }
    if (showFilters) LedgerFilterSheet(
        state = state, selectedAccountId = selectedAccountId, selectedCategoryName = selectedCategoryName, range = range, filter = filter,
        onAccountSelected = { selectedAccountId = it }, onCategorySelected = { selectedCategoryName = it }, onRangeSelected = { range = it }, onFilterSelected = { filter = it }, onDismiss = { showFilters = false }
    )
    if (showNewVyavahara) NewVyavaharaDialog(state.categories, state.payees, state.accounts, state.dateFormatPreference, recentAmounts, state.recentTransactions, selectedAccountId, selectedCategoryName, onDismiss = { showNewVyavahara = false }, onSave = { amount, payee, category, description, direction, accountId, occurredAtEpochMs -> onRecord(amount, payee, category, description, direction, accountId, occurredAtEpochMs); showNewVyavahara = false })
    transactionToDelete?.let { transaction -> AlertDialog(onDismissRequest = { transactionToDelete = null }, title = { Text("Delete Vyavahara?") }, text = { Text("This removes the entry from your encrypted money activity and updates balances.") }, confirmButton = { Button(onClick = { onDelete(transaction.id); transactionToDelete = null }) { DhanamActionText("Delete") } }, dismissButton = { TextButton(onClick = { transactionToDelete = null }) { DhanamActionText("Cancel") } }) }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun IncomeExpenseReportsScreen(state: MvpFinanceState, onBack: () -> Unit) {
    var range by remember { mutableStateOf(LedgerRange.MONTH) }
    val entries = state.allTransactions.filter { range.includes(it.occurredAtEpochMs) && !it.isHoldingTank && it.envelopeType != EnvelopeType.INVESTMENT }
    val previousEntries = state.allTransactions.filter { range.previousWindowIncludes(it.occurredAtEpochMs) && !it.isHoldingTank && it.envelopeType != EnvelopeType.INVESTMENT }
    val income = entries.filter { it.direction == TransactionDirection.CREDIT }.sumOf { it.amountPaise }
    val expense = entries.filter { it.direction == TransactionDirection.DEBIT }.sumOf { it.amountPaise }
    val net = income - expense
    val previousNet = previousEntries.filter { it.direction == TransactionDirection.CREDIT }.sumOf { it.amountPaise } -
        previousEntries.filter { it.direction == TransactionDirection.DEBIT }.sumOf { it.amountPaise }

    val liquidAndReserves = state.accountBalances.filter { it.kind == AccountKind.SPENDING || it.kind == AccountKind.SAVINGS || it.kind == AccountKind.EMERGENCY }.sumOf { it.balancePaise }
    val categoryMetadata = state.categories.associate { it.name to (it.priority to it.nature) }
    val needsVyaya = entries.filter { it.direction == TransactionDirection.DEBIT && (categoryMetadata[it.category]?.first ?: CategoryPriority.NEED) == CategoryPriority.NEED }.sumOf { it.amountPaise }
    val wantsVyaya = entries.filter { it.direction == TransactionDirection.DEBIT && (categoryMetadata[it.category]?.first ?: CategoryPriority.NEED) == CategoryPriority.WANT }.sumOf { it.amountPaise }

    val allVyaya = state.allTransactions.filter { it.direction == TransactionDirection.DEBIT && !it.isHoldingTank && it.envelopeType != EnvelopeType.INVESTMENT }
    val vyayaByMonth = allVyaya.groupBy { YearMonth.from(Instant.ofEpochMilli(it.occurredAtEpochMs).atZone(ZoneId.systemDefault())) }
        .mapValues { (_, v) -> v.sumOf { it.amountPaise } }
    val avgMonthlyVyaya = if (vyayaByMonth.isEmpty()) 0L else vyayaByMonth.values.sum() / vyayaByMonth.size
    val runwayMonths = if (avgMonthlyVyaya == 0L) null else liquidAndReserves.toDouble() / avgMonthlyVyaya

    val categoryTotals = entries.filter { it.direction == TransactionDirection.DEBIT }
        .groupBy { it.category?.ifBlank { null } ?: "Uncategorised" }
        .mapValues { (_, values) -> values.sumOf { it.amountPaise } }
        .entries.sortedByDescending { it.value }

    val hourlyRate = state.hourlyRatePaise
    val lifeHoursExpenses = categoryTotals.take(3).map { (cat, amount) ->
        val hours = if (hourlyRate == 0L) 0.0 else amount.toDouble() / hourlyRate
        cat to hours
    }
    val payeeTotals = entries.filter { it.direction == TransactionDirection.DEBIT && !it.payee.isNullOrBlank() }
        .groupBy { it.payee!! }
        .mapValues { (_, values) -> values.sumOf { it.amountPaise } }
        .entries.sortedByDescending { it.value }
    val accountTotals = entries.groupBy { it.accountId }
        .mapValues { (_, values) ->
            values.sumOf { if (it.direction == TransactionDirection.CREDIT) it.amountPaise else -it.amountPaise }
        }
        .entries.sortedByDescending { kotlin.math.abs(it.value) }
    val categoryIcons = state.categories.associate { it.name to it.iconKey }
    val accountNames = state.accounts.associate { it.id to it.name }
    val monthly = entries.groupBy { YearMonth.from(Instant.ofEpochMilli(it.occurredAtEpochMs).atZone(ZoneId.systemDefault())) }
        .toSortedMap(compareByDescending { it }).entries.take(6)
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            DhanamScreenTopBar(
                title = "Aaya & Vyaya reports",
                subtitle = "Income and expense insights",
                onBack = onBack,
            )
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LedgerRange.entries.size) { index -> val option = LedgerRange.entries[index]; FilterChip(selected = range == option, onClick = { range = option }, label = { OneLineText(option.label, style = MaterialTheme.typography.labelMedium) }) }
                }
            }
            if (entries.isEmpty()) item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OneLineText("No report data for this period", style = MaterialTheme.typography.titleSmall)
                        Text("Record an Aaya or Vyaya in a daily Khata to see cashflow, monthly trends, and Varga insights here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("CASHFLOW SUMMARY", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { OneLineText("AAYA", style = MaterialTheme.typography.labelSmall); Text(formatMoney(income, state.currencyCode, includeSign = true), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) { OneLineText("NET", style = MaterialTheme.typography.labelSmall); Text(formatMoney(net, state.currencyCode, includeSign = true), style = MaterialTheme.typography.titleMedium, color = if (net < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                            Column(horizontalAlignment = Alignment.End) { OneLineText("VYAYA", style = MaterialTheme.typography.labelSmall); Text(formatMoney(-expense, state.currencyCode), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error) }
                        }
                        Text("${entries.size} confirmed entries · Holding-tank purchases excluded.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OneLineText("Period comparison", style = MaterialTheme.typography.titleMedium)
                            Text("Current net ${formatMoney(net, state.currencyCode, includeSign = true)}", style = MaterialTheme.typography.bodyMedium)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Previous", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OneLineText(formatMoney(previousNet, state.currencyCode, includeSign = true), style = MaterialTheme.typography.titleSmall, color = if (previousNet < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("MINDFUL INSIGHTS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("FINANCIAL RUNWAY", style = MaterialTheme.typography.labelSmall)
                                OneLineText(runwayMonths?.let { "%.1f months".format(it) } ?: "—", style = MaterialTheme.typography.titleMedium)
                                Text("Survival on liquid cash", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f))
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("SAVINGS RATE", style = MaterialTheme.typography.labelSmall)
                                val savingsRate = if (income == 0L) null else (income - expense).toDouble() * 100.0 / income
                                OneLineText(formatPercent(savingsRate), style = MaterialTheme.typography.titleMedium)
                                Text("Of total Aaya", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f))
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.2f))
                        Text("HOURS OF LIFE (Top Expenses)", style = MaterialTheme.typography.labelSmall)
                        lifeHoursExpenses.forEach { (cat, hours) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(cat, style = MaterialTheme.typography.bodySmall); Text("%.1f hours".format(hours), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.2f))
                        val totalVyaya = (needsVyaya + wantsVyaya).toDouble()
                        val needsShare = if (totalVyaya == 0.0) 0.5f else (needsVyaya / totalVyaya).toFloat()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Essentials (Needs)", style = MaterialTheme.typography.labelSmall); Text("Lifestyle (Wants)", style = MaterialTheme.typography.labelSmall) }
                            LinearProgressIndicator(progress = { needsShare }, modifier = Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatMoney(needsVyaya, state.currencyCode), style = MaterialTheme.typography.labelSmall); Text(formatMoney(wantsVyaya, state.currencyCode), style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
            item { OneLineText("Vyaya by Varga", style = MaterialTheme.typography.titleMedium) }
            if (categoryTotals.isEmpty()) item { ElevatedCard(Modifier.fillMaxWidth()) { Text("Record a Vyaya to see its Varga breakdown.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) } }
            items(categoryTotals.take(8).size) { index ->
                val (category, amount) = categoryTotals[index]
                val share = if (expense == 0L) 0f else (amount.toDouble() / expense).toFloat()
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { IconifiedCategoryLabel(category, categoryIcons[category], compact = true); OneLineText(formatMoney(amount, state.currencyCode), style = MaterialTheme.typography.titleSmall) }
                    LinearProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth())
                    Text("${"%.1f".format(share * 100)}% of Vyaya", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
            item { OneLineText("Top Vyakti", style = MaterialTheme.typography.titleMedium) }
            if (payeeTotals.isEmpty()) item { ElevatedCard(Modifier.fillMaxWidth()) { Text("Add a Vyakti to your Vyaya and the most-used people, shops, or institutions will appear here.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) } }
            items(payeeTotals.take(5).size) { index ->
                val payeeEntry = payeeTotals[index]
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            OneLineText(payeeEntry.key, style = MaterialTheme.typography.titleSmall)
                            Text("${entries.count { it.payee == payeeEntry.key }} entries", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OneLineText(formatMoney(payeeEntry.value, state.currencyCode), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            item { OneLineText("Cashflow by Khata", style = MaterialTheme.typography.titleMedium) }
            if (accountTotals.isEmpty()) item { ElevatedCard(Modifier.fillMaxWidth()) { Text("Your Khata-level flow appears after your first confirmed entry.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) } }
            items(accountTotals.take(6).size) { index ->
                val accountEntry = accountTotals[index]
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            OneLineText(accountNames[accountEntry.key] ?: "Khata", style = MaterialTheme.typography.titleSmall)
                            Text("Net movement for ${range.label.lowercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OneLineText(formatMoney(accountEntry.value, state.currencyCode, includeSign = true), style = MaterialTheme.typography.titleSmall, color = if (accountEntry.value < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item { OneLineText("Monthly cashflow", style = MaterialTheme.typography.titleMedium) }
            if (monthly.isEmpty()) item { ElevatedCard(Modifier.fillMaxWidth()) { Text("Your month-by-month cashflow appears after your first entry.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) } }
            items(monthly.size) { index ->
                val (month, monthEntries) = monthly[index]
                val monthIncome = monthEntries.filter { it.direction == TransactionDirection.CREDIT }.sumOf { it.amountPaise }
                val monthExpense = monthEntries.filter { it.direction == TransactionDirection.DEBIT && !it.isHoldingTank }.sumOf { it.amountPaise }
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OneLineText(month.toString(), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Aaya ${formatMoney(monthIncome, state.currencyCode)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary); Text("Vyaya ${formatMoney(monthExpense, state.currencyCode)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Text("Net ${formatMoney(monthIncome - monthExpense, state.currencyCode, includeSign = true)}", style = MaterialTheme.typography.bodyMedium, color = if (monthIncome < monthExpense) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                } }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun InvestmentPerformanceReportScreen(state: MvpFinanceState, onBack: () -> Unit, onExportPdf: (List<InvestmentPerformanceMetric>) -> Unit) {
    val monthlyData = remember(state.investmentHistory) {
        val historyByMonth = state.investmentHistory
            .groupBy { YearMonth.from(LocalDate.ofEpochDay(it.asOfEpochDay)) }
            .mapValues { (_, snapshots) -> snapshots.maxBy { it.asOfEpochDay } }
            .toSortedMap()

        val results = mutableListOf<InvestmentPerformanceMetric>()
        var priorAppreciation = 0L
        var priorValue = 0L

        val sortedMonths = historyByMonth.keys.toList()
        sortedMonths.forEachIndexed { index, month ->
            val snapshot = historyByMonth[month]!!
            val cost = snapshot.totalCostPaise
            val value = snapshot.currentValuePaise
            val appreciation = value - cost
            val contribution = snapshot.netContributionPaise
            
            val revaluation = if (index == 0) appreciation else appreciation - priorAppreciation
            val totalGainPercent = if (cost == 0L) null else appreciation * 100.0 / cost
            val monthlyReturnPercent = if (index == 0) 100.0 else if (priorAppreciation == 0L) null else revaluation * 100.0 / priorAppreciation
            val portfolioReturnPercent = if (index == 0) null else if (priorValue == 0L) null else revaluation * 100.0 / priorValue

            val historyUpToNow = state.investmentHistory.filter { it.asOfEpochDay <= snapshot.asOfEpochDay }
            val xirr = FinancialCalculations.xirrPercent(cashFlowsForXirr(historyUpToNow))

            results.add(InvestmentPerformanceMetric(month, cost, value, contribution, appreciation, revaluation, totalGainPercent, monthlyReturnPercent, portfolioReturnPercent, xirr))
            priorAppreciation = appreciation
            priorValue = value
        }
        results.reversed()
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            DhanamScreenTopBar(
                title = "Nivesha performance",
                subtitle = "Periodical return analysis",
                onBack = onBack,
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            InvestmentNavigationTabs(
                selectedTab = InvestmentSection.PERFORMANCE,
                onOverview = onBack,
                onPerformance = {},
            )

            if (monthlyData.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Record monthly Nivesha balance check-ins to see performance analysis.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(onClick = { onExportPdf(monthlyData) }) {
                        Text("Export PDF")
                    }
                }

                val scrollState = rememberScrollState()
                val monthFormatter = remember { DateTimeFormatter.ofPattern("MMM yyyy", Locale.US) }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(scrollState),
                ) {
                    Column {
                        // Header
                        Row(Modifier.background(MaterialTheme.colorScheme.primaryContainer).padding(vertical = 12.dp, horizontal = 16.dp)) {
                            PerformanceHeaderCell("Month", 100)
                            PerformanceHeaderCell("Cost", 120)
                            PerformanceHeaderCell("Mkt Value", 120)
                            PerformanceHeaderCell("Contrib.", 110)
                            PerformanceHeaderCell("Apprec.", 120)
                            PerformanceHeaderCell("Reval.", 120)
                            PerformanceHeaderCell("Gain %", 80)
                            PerformanceHeaderCell("Month %", 80)
                            PerformanceHeaderCell("Port %", 80)
                            PerformanceHeaderCell("XIRR %", 80)
                        }
                        
                        LazyColumn(Modifier.weight(1f)) {
                            items(monthlyData.size) { index ->
                                val metric = monthlyData[index]
                                Row(Modifier.padding(vertical = 10.dp, horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    PerformanceCell(metric.month.format(monthFormatter), 100, isTitle = true)
                                    PerformanceCell(formatMoney(metric.cost, state.currencyCode), 120)
                                    PerformanceCell(formatMoney(metric.value, state.currencyCode), 120)
                                    PerformanceCell(formatMoney(metric.contribution, state.currencyCode), 110)
                                    PerformanceCell(formatMoney(metric.appreciation, state.currencyCode), 120, color = if (metric.appreciation < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                                    PerformanceCell(formatMoney(metric.revaluation, state.currencyCode, includeSign = true), 120, color = if (metric.revaluation < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                    PerformanceCell(formatPercent(metric.totalGainPercent), 80, color = if ((metric.totalGainPercent ?: 0.0) < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                                    PerformanceCell(formatPercent(metric.monthlyReturnPercent), 80, color = if ((metric.monthlyReturnPercent ?: 0.0) < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                                    PerformanceCell(formatPercent(metric.portfolioReturnPercent), 80, color = if ((metric.portfolioReturnPercent ?: 0.0) < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                                    PerformanceCell(formatPercent(metric.xirrPercent), 80, color = if ((metric.xirrPercent ?: 0.0) < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                                }
                                HorizontalDivider(Modifier.width((100 + 120 * 4 + 110 + 80 * 4).dp), color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PerformanceHeaderCell(text: String, width: Int) {
    OneLineText(text, Modifier.width(width.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
}

@Composable
private fun PerformanceCell(text: String, width: Int, isTitle: Boolean = false, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(text, Modifier.width(width.dp), style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, textAlign = if (isTitle) TextAlign.Start else TextAlign.End)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LedgerFilterSheet(state: MvpFinanceState, selectedAccountId: String?, selectedCategoryName: String?, range: LedgerRange, filter: LedgerFilter, onAccountSelected: (String?) -> Unit, onCategorySelected: (String?) -> Unit, onRangeSelected: (LedgerRange) -> Unit, onFilterSelected: (LedgerFilter) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.padding(horizontal = 20.dp), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { OneLineText("Filter Vyavahara", style = MaterialTheme.typography.titleLarge) }
            item { Text("Khata", style = MaterialTheme.typography.labelLarge) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = selectedAccountId == null, onClick = { onAccountSelected(null) }, label = { OneLineText("All Khatas", style = MaterialTheme.typography.labelMedium) }) }
                    items(state.accounts.size) { index -> val account = state.accounts[index]; FilterChip(selected = selectedAccountId == account.id, onClick = { onAccountSelected(account.id) }, label = { OneLineText(account.name, style = MaterialTheme.typography.labelMedium) }) }
                }
            }
            item { Text("Varga", style = MaterialTheme.typography.labelLarge) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = selectedCategoryName == null, onClick = { onCategorySelected(null) }, label = { OneLineText("All Varga", style = MaterialTheme.typography.labelMedium) }) }
                    items(state.categories.size) { index -> val category = state.categories[index]; FilterChip(selected = selectedCategoryName == category.name, onClick = { onCategorySelected(category.name) }, label = { IconifiedCategoryLabel(category.name, category.iconKey, compact = true) }) }
                }
            }
            item { Text("Period", style = MaterialTheme.typography.labelLarge) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(LedgerRange.entries.size) { index -> val option = LedgerRange.entries[index]; FilterChip(selected = range == option, onClick = { onRangeSelected(option) }, label = { OneLineText(option.label, style = MaterialTheme.typography.labelMedium) }) } }
            }
            item { Text("Direction", style = MaterialTheme.typography.labelLarge) }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(LedgerFilter.entries.size) { index -> val option = LedgerFilter.entries[index]; FilterChip(selected = filter == option, onClick = { onFilterSelected(option) }, label = { OneLineText(option.label, style = MaterialTheme.typography.labelMedium) }) } } }
            item { Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { DhanamActionText("Apply filters") } }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun InlineVyavaharaEditor(transaction: TransactionEntity, payees: List<PayeeEntity>, categories: List<CategoryEntity>, onCancel: () -> Unit, onSave: (String, String, String, String, TransactionDirection) -> Unit) {
    var amount by remember(transaction.id) { mutableStateOf((transaction.amountPaise / 100.0).toString()) }
    var payee by remember(transaction.id) { mutableStateOf(transaction.payee.orEmpty()) }
    var category by remember(transaction.id) { mutableStateOf(transaction.category.orEmpty()) }
    var description by remember(transaction.id) { mutableStateOf(transaction.description.orEmpty()) }
    var direction by remember(transaction.id) { mutableStateOf(transaction.direction) }
    var payeeExpanded by remember(transaction.id) { mutableStateOf(false) }
    var categoryExpanded by remember(transaction.id) { mutableStateOf(false) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("Modify Vyavahara", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = direction == TransactionDirection.DEBIT, onClick = { direction = TransactionDirection.DEBIT }, label = { OneLineText("Vyaya", style = MaterialTheme.typography.labelMedium) })
        FilterChip(selected = direction == TransactionDirection.CREDIT, onClick = { direction = TransactionDirection.CREDIT }, label = { OneLineText("Aaya", style = MaterialTheme.typography.labelMedium) })
    }
    OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { OneLineText("Amount in ₹") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
    ExposedDropdownMenuBox(expanded = payeeExpanded, onExpandedChange = { payeeExpanded = !payeeExpanded }) {
        OutlinedTextField(
            value = payee,
            onValueChange = { payee = it; payeeExpanded = true },
            modifier = Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = true).fillMaxWidth(),
            label = { OneLineText("Vyakti") },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(payeeExpanded) }
        )
        ExposedDropdownMenu(expanded = payeeExpanded, onDismissRequest = { payeeExpanded = false }) {
            payees.filter { it.name.contains(payee, ignoreCase = true) }.forEach { savedPayee ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(savedPayee.name)
                            savedPayee.defaultCategory?.let { Text("Default Varga: $it", style = MaterialTheme.typography.labelSmall) }
                        }
                    },
                    onClick = {
                        payee = savedPayee.name
                        savedPayee.defaultCategory?.let { category = it }
                        payeeExpanded = false
                    }
                )
            }
        }
    }
    ExposedDropdownMenuBox(expanded = categoryExpanded, onExpandedChange = { categoryExpanded = !categoryExpanded }) {
        OutlinedTextField(
            value = category,
            onValueChange = { category = it; categoryExpanded = true },
            modifier = Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = true).fillMaxWidth(),
            label = { OneLineText("Varga") },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryExpanded) }
        )
        ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
            categories.filter { it.name.contains(category, ignoreCase = true) }.forEach { savedCategory ->
                DropdownMenuItem(text = { IconifiedCategoryLabel(savedCategory.name, savedCategory.iconKey) }, onClick = { category = savedCategory.name; categoryExpanded = false })
            }
        }
    }
    OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { OneLineText("Description") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), minLines = 2)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel) { DhanamActionText("Cancel") }
        Button(onClick = { onSave(amount, payee, category, description, direction) }) { DhanamActionText("Save") }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun NewVyavaharaDialog(categories: List<CategoryEntity>, payees: List<PayeeEntity>, accounts: List<AccountEntity>, dateFormatPreference: DateFormatPreference, recentAmounts: List<Long>, recentTransactions: List<TransactionEntity>, preferredAccountId: String?, preferredCategoryName: String?, onDismiss: () -> Unit, onSave: (String, String, String, String, TransactionDirection, String, Long) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var payee by remember { mutableStateOf("") }
    var category by remember(preferredCategoryName, categories) { mutableStateOf(preferredCategoryName ?: categories.firstOrNull()?.name ?: "Other") }
    var description by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf(TransactionDirection.DEBIT) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var payeeExpanded by remember { mutableStateOf(false) }
    var accountExpanded by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
    val liquidAccounts = accounts.filter { AccountRolePolicy.supportsTransactions(it.kind) }
    var accountId by remember(liquidAccounts, preferredAccountId) { mutableStateOf(preferredAccountId?.takeIf { id -> liquidAccounts.any { it.id == id } } ?: liquidAccounts.firstOrNull()?.id.orEmpty()) }
    val selectedAccount = liquidAccounts.firstOrNull { it.id == accountId }
    val recentPayees = recentTransactions.mapNotNull { it.payee?.takeIf(String::isNotBlank) }.distinct().take(4)
    val recentCategories = recentTransactions.mapNotNull { it.category?.takeIf(String::isNotBlank) }.distinct().take(4)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Vyavahara") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = direction == TransactionDirection.DEBIT, onClick = { direction = TransactionDirection.DEBIT }, label = { OneLineText("Vyaya", style = MaterialTheme.typography.labelMedium) })
                    FilterChip(selected = direction == TransactionDirection.CREDIT, onClick = { direction = TransactionDirection.CREDIT }, label = { OneLineText("Aaya", style = MaterialTheme.typography.labelMedium) })
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { OneLineText("Amount in ₹") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                if (recentAmounts.isNotEmpty()) {
                    Text("Recent amounts", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recentAmounts.size) { index ->
                            val paise = recentAmounts[index]
                            AssistChip(onClick = { amount = BigDecimal(paise).divide(BigDecimal(100)).stripTrailingZeros().toPlainString() }, label = { OneLineText(formatMoney(paise, "INR"), style = MaterialTheme.typography.labelMedium) })
                        }
                    }
                }
                OutlinedTextField(
                    value = datePickerState.selectedDateMillis?.let { formatDate(it, dateFormatPreference) }.orEmpty(),
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    label = { OneLineText("Transaction date") },
                    trailingIcon = { TextButton(onClick = { showDatePicker = true }) { DhanamActionText("Pick") } },
                    singleLine = true
                )
                ExposedDropdownMenuBox(expanded = accountExpanded, onExpandedChange = { accountExpanded = !accountExpanded }) {
                    OutlinedTextField(selectedAccount?.name.orEmpty(), {}, Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(), readOnly = true, label = { OneLineText("Khata") }, placeholder = { OneLineText("Choose a transactional Khata") }, singleLine = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(accountExpanded) })
                    ExposedDropdownMenu(expanded = accountExpanded, onDismissRequest = { accountExpanded = false }) {
                        liquidAccounts.forEach { account -> DropdownMenuItem(text = { OneLineText(account.name) }, onClick = { accountId = account.id; accountExpanded = false }) }
                    }
                }
                ExposedDropdownMenuBox(expanded = categoryExpanded, onExpandedChange = { categoryExpanded = !categoryExpanded }) {
                    OutlinedTextField(category, { category = it; categoryExpanded = true }, Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = true).fillMaxWidth(), label = { OneLineText("Varga") }, singleLine = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryExpanded) })
                    ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) { categories.filter { it.name.contains(category, ignoreCase = true) }.forEach { option -> DropdownMenuItem(text = { IconifiedCategoryLabel(option.name, option.iconKey) }, onClick = { category = option.name; categoryExpanded = false }) } }
                }
                if (recentCategories.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recentCategories.size) { index ->
                            val recentCategory = recentCategories[index]
                            val iconKey = categories.firstOrNull { it.name == recentCategory }?.iconKey
                            FilterChip(selected = category == recentCategory, onClick = { category = recentCategory }, label = { IconifiedCategoryLabel(recentCategory, iconKey, compact = true) })
                        }
                    }
                }
                ExposedDropdownMenuBox(expanded = payeeExpanded, onExpandedChange = { payeeExpanded = !payeeExpanded }) {
                    OutlinedTextField(payee, { payee = it; payeeExpanded = true }, Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = true).fillMaxWidth(), label = { OneLineText("Vyakti") }, singleLine = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(payeeExpanded) })
                    ExposedDropdownMenu(expanded = payeeExpanded, onDismissRequest = { payeeExpanded = false }) {
                        payees.filter { it.name.contains(payee, ignoreCase = true) }.forEach { savedPayee ->
                            DropdownMenuItem(text = { Column { Text(savedPayee.name); savedPayee.defaultCategory?.let { Text("Default Varga: $it", style = MaterialTheme.typography.labelSmall) } } }, onClick = { payee = savedPayee.name; savedPayee.defaultCategory?.let { category = it }; payeeExpanded = false })
                        }
                    }
                }
                if (recentPayees.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recentPayees.size) { index ->
                            val recentPayee = recentPayees[index]
                            AssistChip(onClick = { payee = recentPayee }, label = { OneLineText(recentPayee, style = MaterialTheme.typography.labelMedium) })
                        }
                    }
                }
                OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { OneLineText("Description") }, minLines = 2)
            }
        },
        confirmButton = { Button(onClick = { onSave(amount, payee, category, description, direction, accountId, datePickerState.selectedDateMillis ?: System.currentTimeMillis()) }, enabled = amount.isNotBlank() && accountId.isNotBlank()) { DhanamActionText("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { DhanamActionText("Cancel") } }
    )
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = { TextButton(onClick = { showDatePicker = false }) { DhanamActionText("OK") } },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { DhanamActionText("Cancel") } }
        ) { DatePicker(state = datePickerState) }
    }
}

enum class LedgerFilter(val label: String) { ALL("All"), SPENT("Spent"), INCOME("Income") }

enum class LedgerRange(val label: String, val description: String) {
    WEEK("7 days", "Last 7 days"),
    MONTH("This month", "This month"),
    QUARTER("3 months", "Last 3 months"),
    HALF_YEAR("6 months", "Last 6 months"),
    YEAR("This year", "This year"),
    RECENT("All", "All recorded activity");

    fun includes(epochMs: Long): Boolean {
        val date = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now()
        return when (this) {
            WEEK -> !date.isBefore(today.minusDays(6))
            MONTH -> date.year == today.year && date.month == today.month
            QUARTER -> !date.isBefore(today.minusMonths(3).plusDays(1))
            HALF_YEAR -> !date.isBefore(today.minusMonths(6).plusDays(1))
            YEAR -> date.year == today.year
            RECENT -> true
        }
    }
}

private fun LedgerRange.previousWindowIncludes(epochMs: Long): Boolean {
    val date = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (this) {
        LedgerRange.WEEK -> date in today.minusDays(13)..today.minusDays(7)
        LedgerRange.MONTH -> {
            val previous = today.minusMonths(1)
            date.year == previous.year && date.month == previous.month
        }
        LedgerRange.QUARTER -> date in today.minusMonths(6).plusDays(1)..today.minusMonths(3)
        LedgerRange.HALF_YEAR -> date in today.minusMonths(12).plusDays(1)..today.minusMonths(6)
        LedgerRange.YEAR -> date.year == today.year - 1
        LedgerRange.RECENT -> false
    }
}

@Composable
private fun LedgerMetric(label: String, value: String, color: Color) {
    Column {
        OneLineText(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        OneLineText(value, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

/** Category glyphs are deliberately label-backed, so their meaning never depends on colour alone. */
@Composable
internal fun IconifiedCategoryLabel(category: String, iconKey: String? = null, compact: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CategoryGlyph(iconKey ?: category, if (compact) 22.dp else 28.dp)
        OneLineText(category, style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyLarge)
    }
}

internal val categoryGlyphKeys = listOf("food", "transport", "bills", "health", "shopping", "education", "salary", "freelance", "investment", "gift", "other")

@Composable
internal fun CategoryGlyph(category: String, size: androidx.compose.ui.unit.Dp = 24.dp) {
    val glyph = when (category.lowercase()) {
        "food" -> "●"
        "transport" -> "→"
        "bills" -> "▤"
        "health" -> "+"
        "shopping" -> "◇"
        "education" -> "▣"
        "salary" -> "↑"
        "freelance" -> "✦"
        "investment", "investment contribution" -> "↗"
        "gift" -> "♡"
        else -> "•"
    }
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(glyph, style = if (size <= 22.dp) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall)
        }
    }
}

/** A local-only insight derived from the entries already visible in the encrypted ledger. */
@Composable
private fun SpendingMap(categories: List<Pair<String, Long>>, totalSpent: Long, mostUsedPayee: String?, mostUsedPayeeCount: Int, currencyCode: String) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("SPEND MAP", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            OneLineText("Where your money went", style = MaterialTheme.typography.titleMedium)
            categories.take(3).forEach { (category, amount) ->
                val share = if (totalSpent == 0L) 0f else (amount.toDouble() / totalSpent).toFloat()
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        IconifiedCategoryLabel(category, compact = true)
                        Text(formatMoney(amount, currencyCode), style = MaterialTheme.typography.labelLarge)
                    }
                    LinearProgressIndicator(
                        progress = { share },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
            if (mostUsedPayee != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Most visited: $mostUsedPayee · $mostUsedPayeeCount ${if (mostUsedPayeeCount == 1) "entry" else "entries"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyLedgerState(isFiltered: Boolean) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OneLineText(if (isFiltered) "Nothing matches this view" else "Your Vyavahara is ready", style = MaterialTheme.typography.titleMedium)
            Text(if (isFiltered) "Try a different search or filter." else "Record your first Aaya or Vyaya from Prarambha to begin your money story.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun ledgerDayLabel(date: LocalDate, preference: DateFormatPreference): String = when (date) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> formatDate(date, preference)
}

internal fun trendDelta(values: List<Long>): Long? =
    if (values.size >= 2) values.last() - values[values.lastIndex - 1] else null

