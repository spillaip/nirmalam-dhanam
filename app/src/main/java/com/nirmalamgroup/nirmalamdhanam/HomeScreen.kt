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
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.LocalDayClock
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
internal fun MvpHome(state: MvpFinanceState, onCreateAccount: (String, AccountProductType, AssetClass, String, String, AccountKind, (AccountEntity) -> Unit) -> Unit, onUpdateAccount: (String, String, AccountProductType, AssetClass, String, AccountKind) -> Unit, onArchiveAccount: (String) -> Unit, onSaveInvestmentBalance: (String, String, String, String, String) -> Unit, onUpdateInvestmentBalance: (String, String, String, String, String, String) -> Unit, onContributeToInvestment: (String, String, String) -> Unit, onDeleteInvestmentSnapshot: (String) -> Unit, onDeleteTransaction: (String) -> Unit, onUpdateTransaction: (String, String, String, String, String, TransactionDirection) -> Unit, onRecordTransaction: (String, String, String, String, TransactionDirection, String, Long) -> Unit, onNeurodiverseModeChanged: (Boolean) -> Unit, onCurrencyChanged: (String) -> Unit, onDateFormatPreferenceChanged: (DateFormatPreference) -> Unit, onSavedLedgerViewChanged: (LedgerRange, LedgerFilter, String?, String?) -> Unit, onSaveNirmalamAi: (String, String, String, Boolean) -> Unit, onDisableNirmalamAi: () -> Unit, onNirmalamAiInsight: (NirmalamAiInsight) -> Unit, onExportInterchange: (Uri) -> Unit, onImportInterchange: (Uri) -> Unit, onCreateGoal: (String, String, String, String?) -> Unit, onArchiveGoal: (String) -> Unit, onSetGoalAllocation: (String, String, String) -> Unit, onRemoveGoalAllocation: (String) -> Unit, onExportNdf: (Uri, String) -> Unit, onImportNdf: (Uri, String) -> Unit, onSaveCategory: (String, TransactionDirection, String, CategoryPriority, CategoryNature) -> Unit, onUpdateCategory: (String, String, TransactionDirection, String, CategoryPriority, CategoryNature) -> Unit, onDeleteCategory: (String) -> Unit, onSavePayee: (String, String?) -> Unit, onUpdatePayee: (String, String, String?) -> Unit, onDeletePayee: (String) -> Unit, onExportTransactionsCsv: (Uri) -> Unit, onImportTransactionsCsv: (Uri, String) -> Unit, onConfirmTransactionsCsvImport: (Map<Int, CsvImportAction>) -> Unit, onCancelTransactionsCsvImport: () -> Unit, onRemoveStarterData: () -> Unit, onConfirmPurchase: (TransactionEntity) -> Unit, onDiscardPurchase: (TransactionEntity) -> Unit, onShowInvestmentPerformance: (Boolean) -> Unit, onExportPerformancePdf: (List<InvestmentPerformanceMetric>) -> Unit, onDismissMessage: () -> Unit) {
    var showAccountSetup by remember { mutableStateOf(false) }
    var accountSetupProduct by remember { mutableStateOf(AccountProductType.CASH) }
    var addKhataMenuExpanded by remember { mutableStateOf(false) }
    var showInvestmentCheckIn by remember { mutableStateOf(false) }
    var createdInvestment by remember { mutableStateOf<AccountEntity?>(null) }
    var initialInvestmentCheckInId by remember { mutableStateOf<String?>(null) }
    var destination by remember { mutableStateOf(DhanamDestination.HOME) }
    var showContribution by remember { mutableStateOf(false) }

    if (state.showInvestmentPerformance) {
        InvestmentPerformanceReportScreen(
            state,
            onBack = { onShowInvestmentPerformance(false) },
            onExportPdf = { metrics -> onExportPerformancePdf(metrics) },
        )
        return
    }

    when (destination) {
        DhanamDestination.REPORTS -> {
            IncomeExpenseReportsScreen(state, onBack = { destination = DhanamDestination.HOME })
            return
        }
        DhanamDestination.TRANSACTIONS -> {
            TransactionHistoryScreen(
                state,
                onBack = { destination = DhanamDestination.HOME },
                onReports = { destination = DhanamDestination.REPORTS },
                onDelete = onDeleteTransaction,
                onUpdate = onUpdateTransaction,
                onRecord = onRecordTransaction,
                onSavedLedgerViewChanged = onSavedLedgerViewChanged,
            )
            return
        }
        DhanamDestination.INSIGHTS -> {
            InsightsScreen(
                state,
                onBack = { destination = DhanamDestination.HOME },
                onCreateGoal = onCreateGoal,
                onArchiveGoal = onArchiveGoal,
                onSetGoalAllocation = onSetGoalAllocation,
                onRemoveGoalAllocation = onRemoveGoalAllocation,
                onRecordTransaction = onRecordTransaction,
            )
            return
        }
        DhanamDestination.SETTINGS -> {
            SettingsScreen(
                state,
                onBack = { destination = DhanamDestination.HOME },
                onNeurodiverseModeChanged = onNeurodiverseModeChanged,
                onCurrencyChanged = onCurrencyChanged,
                onDateFormatPreferenceChanged = onDateFormatPreferenceChanged,
                onSaveNirmalamAi = onSaveNirmalamAi,
                onDisableNirmalamAi = onDisableNirmalamAi,
                onNirmalamAiInsight = onNirmalamAiInsight,
                onExportInterchange = onExportInterchange,
                onImportInterchange = onImportInterchange,
                onExportNdf = onExportNdf,
                onImportNdf = onImportNdf,
                onSaveCategory = onSaveCategory,
                onUpdateCategory = onUpdateCategory,
                onDeleteCategory = onDeleteCategory,
                onSavePayee = onSavePayee,
                onUpdatePayee = onUpdatePayee,
                onDeletePayee = onDeletePayee,
                onUpdateAccount = onUpdateAccount,
                onArchiveAccount = onArchiveAccount,
                onExportTransactionsCsv = onExportTransactionsCsv,
                onImportTransactionsCsv = onImportTransactionsCsv,
                onConfirmTransactionsCsvImport = onConfirmTransactionsCsvImport,
                onCancelTransactionsCsvImport = onCancelTransactionsCsvImport,
                onRemoveStarterData = onRemoveStarterData,
            )
            return
        }
        DhanamDestination.NET_WORTH -> {
            NetWorthDashboardScreen(
                state,
                onBack = { destination = DhanamDestination.HOME },
                onOpenPortfolio = { destination = DhanamDestination.INVESTMENTS },
            )
            return
        }
        DhanamDestination.INVESTMENTS -> {
            PortfolioAndNetWorthScreen(
                state,
                onBack = { destination = DhanamDestination.HOME },
                onOpenNetWorth = { destination = DhanamDestination.NET_WORTH },
                onOpenPerformanceReport = { onShowInvestmentPerformance(true) },
                onAddInvestment = {
                    accountSetupProduct = AccountProductType.MUTUAL_FUNDS
                    showAccountSetup = true
                    destination = DhanamDestination.HOME
                },
                onRecordBalance = { showInvestmentCheckIn = true },
                onDeleteSnapshot = onDeleteInvestmentSnapshot,
                onUpdateSnapshot = onUpdateInvestmentBalance,
                onUpdateInvestment = { id, name, product, assetClass, target ->
                    val existingKind = state.accounts.firstOrNull { it.id == id }?.kind ?: AccountKind.INVESTMENT
                    onUpdateAccount(id, name, product, assetClass, target, existingKind)
                },
                onArchiveInvestment = onArchiveAccount,
            )
            if (showInvestmentCheckIn) {
                InvestmentBalanceCheckInDialog(
                    state.accounts.filter { it.kind == AccountKind.INVESTMENT },
                    state.investmentHistory,
                    state.dateFormatPreference,
                    onDismiss = { showInvestmentCheckIn = false },
                    onSave = { accountId, date, cost, value, note ->
                        onSaveInvestmentBalance(accountId, date, cost, value, note)
                        showInvestmentCheckIn = false
                    },
                )
            }
            if (showContribution) {
                InvestmentContributionDialog(
                    state.accounts.filter { it.kind == AccountKind.INVESTMENT },
                    onDismiss = { showContribution = false },
                    onSave = { id, amount, payee ->
                        onContributeToInvestment(id, amount, payee)
                        showContribution = false
                    },
                )
            }
            return
        }
        DhanamDestination.HOME -> Unit
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        OneLineText("Nirmalam Dhanam", style = MaterialTheme.typography.titleLarge)
                        OneLineText("Your private money space", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = true, onClick = {}, icon = { NavigationGlyph("⌂") }, label = { OneLineText("Home", style = MaterialTheme.typography.labelSmall) })
                NavigationBarItem(selected = false, onClick = { destination = DhanamDestination.TRANSACTIONS }, icon = { NavigationGlyph("≡") }, label = { OneLineText("Transactions", style = MaterialTheme.typography.labelSmall) })
                NavigationBarItem(selected = false, onClick = { destination = DhanamDestination.INVESTMENTS }, icon = { NavigationGlyph("↗") }, label = { OneLineText("Investments", style = MaterialTheme.typography.labelSmall) })
                NavigationBarItem(selected = false, onClick = { destination = DhanamDestination.INSIGHTS }, icon = { NavigationGlyph("◎") }, label = { OneLineText("Insights", style = MaterialTheme.typography.labelSmall) })
                NavigationBarItem(selected = false, onClick = { destination = DhanamDestination.SETTINGS }, icon = { NavigationGlyph("•••") }, label = { OneLineText("More", style = MaterialTheme.typography.labelSmall) })
            }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OneLineText("AVAILABLE TO USE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        OneLineText(formatMoney(state.cashPaise, state.currencyCode), style = MaterialTheme.typography.displaySmall, color = if (state.cashPaise < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer)
                        HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.16f))
                        Text(if (state.cashPaise < 0) "You are using credit. A small reset today creates room tomorrow." else "After credit liabilities — the amount available for everyday decisions.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            item { WhatChangedCard(state) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ElevatedCard(Modifier.weight(1f), shape = MaterialTheme.shapes.large) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { OneLineText("SAFE TODAY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); OneLineText(formatMoney(state.safeToSpendTodayPaise, state.currencyCode), style = MaterialTheme.typography.headlineSmall); OneLineText("${formatMoney(state.todaySpentPaise, state.currencyCode)} spent", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    ElevatedCard(Modifier.weight(1f), shape = MaterialTheme.shapes.large) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { OneLineText("COOLING TANK", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); OneLineText("${state.holdingTank.size}", style = MaterialTheme.typography.headlineSmall); OneLineText(if (state.holdingTank.isEmpty()) "None waiting" else "Decision waiting", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                }
            }
            item { HomeMoneyPulse(state.recentTransactions, state.currencyCode) }
            item { PrarambhaBalanceCharts(state) }
            item {
                ElevatedCard(onClick = { destination = DhanamDestination.INVESTMENTS }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val investmentAccounts = state.accounts.filter { it.kind == AccountKind.INVESTMENT }
                        val snapshotsByAccount = state.investmentSnapshots.associateBy { it.accountId }
                        val portfolioValue = state.investmentSnapshots.sumOf { it.currentValuePaise }
                        val portfolioCost = state.investmentSnapshots.sumOf { it.totalCostPaise }
                        val gain = portfolioValue - portfolioCost
                        val returnPercent = if (portfolioCost == 0L) 0.0 else gain * 100.0 / portfolioCost
                        val liquidAndReserve = state.accountBalances.filter { it.kind == AccountKind.SAVINGS || it.kind == AccountKind.EMERGENCY }.sumOf { it.balancePaise }
                        val netWorth = state.cashPaise + liquidAndReserve + portfolioValue
                        val latestCheckIn = state.investmentSnapshots.maxOfOrNull { it.asOfEpochDay }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            OneLineText("NIVESHA · PORTFOLIO", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Box {
                                IconButton(onClick = { addKhataMenuExpanded = true }) { OneLineText("+", style = MaterialTheme.typography.headlineSmall) }
                                DropdownMenu(expanded = addKhataMenuExpanded, onDismissRequest = { addKhataMenuExpanded = false }) {
                                    DropdownMenuItem(text = { OneLineText("Mutual fund or ETF") }, onClick = { accountSetupProduct = AccountProductType.MUTUAL_FUNDS; addKhataMenuExpanded = false; showAccountSetup = true })
                                    DropdownMenuItem(text = { OneLineText("Direct stocks") }, onClick = { accountSetupProduct = AccountProductType.STOCKS; addKhataMenuExpanded = false; showAccountSetup = true })
                                    DropdownMenuItem(text = { OneLineText("Bullion — gold or silver") }, onClick = { accountSetupProduct = AccountProductType.BULLION; addKhataMenuExpanded = false; showAccountSetup = true })
                                    DropdownMenuItem(text = { OneLineText("Real estate") }, onClick = { accountSetupProduct = AccountProductType.REAL_ESTATE; addKhataMenuExpanded = false; showAccountSetup = true })
                                    DropdownMenuItem(text = { OneLineText("Retirement investment") }, onClick = { accountSetupProduct = AccountProductType.PPF; addKhataMenuExpanded = false; showAccountSetup = true })
                                }
                            }
                        }
                        OneLineText(formatMoney(portfolioValue, state.currencyCode), style = MaterialTheme.typography.headlineLarge)
                        OneLineText(latestCheckIn?.let { "Valued ${LocalDate.ofEpochDay(it)}" } ?: "First check-in needed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PortfolioValueChart(state.investmentHistory, state.currencyCode, compact = true)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { OneLineText("INVESTED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(formatMoney(portfolioCost, state.currencyCode), style = MaterialTheme.typography.titleSmall) }
                            Column(horizontalAlignment = Alignment.End) { OneLineText("RETURN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${formatMoney(gain, state.currencyCode, includeSign = true)} · ${"%.1f".format(returnPercent)}%", style = MaterialTheme.typography.titleSmall, color = if (gain < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column { OneLineText("SAMPADA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(formatMoney(netWorth, state.currencyCode), style = MaterialTheme.typography.titleMedium) }
                            Text("Cash, reserves & Nivesha", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (investmentAccounts.isEmpty()) {
                            ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    OneLineText("No Nivesha yet", style = MaterialTheme.typography.titleSmall)
                                    Text("Add an investment holding for periodic cost-and-value check-ins. Daily cash and liabilities remain separate.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TextButton(onClick = { accountSetupProduct = AccountProductType.MUTUAL_FUNDS; showAccountSetup = true }) { DhanamActionText("Add Nivesha") }
                                }
                            }
                        } else investmentAccounts.take(4).forEach { account ->
                            val snapshot = snapshotsByAccount[account.id]
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) { Text(account.name, style = MaterialTheme.typography.bodyMedium); Text(account.productType.name.replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                OneLineText(snapshot?.let { formatMoney(it.currentValuePaise, state.currencyCode) } ?: "Check-in due", style = MaterialTheme.typography.bodyMedium, color = if (snapshot == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        val overdueCheckIns = investmentAccounts.count { account -> snapshotsByAccount[account.id]?.let { LocalDate.now().toEpochDay() - it.asOfEpochDay >= 30 } ?: true }
                        if (overdueCheckIns > 0) AssistChip(onClick = { showInvestmentCheckIn = true }, label = { OneLineText("$overdueCheckIns check-in${if (overdueCheckIns == 1) "" else "s"} due", style = MaterialTheme.typography.labelMedium) })
                        if (investmentAccounts.isNotEmpty()) Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(onClick = { showInvestmentCheckIn = true }, modifier = Modifier.weight(1f)) { DhanamActionText("Check-in") }
                            TextButton(onClick = { showContribution = true }) { DhanamActionText("Contribute") }
                        }
                        Text("Tap this card for Nivesha and Sampada.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.accounts.isEmpty()) item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OneLineText("No Khatas yet", style = MaterialTheme.typography.titleMedium)
                        Text("Start with a daily cash or bank Khata. Add liabilities and investment holdings separately when you need them.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { accountSetupProduct = AccountProductType.CASH; showAccountSetup = true }, modifier = Modifier.fillMaxWidth()) { DhanamActionText("Set up Khata") }
                    }
                }
            }
            if (!state.neurodiverseModeEnabled) item { OneLineText("Intentional purchases", style = MaterialTheme.typography.titleMedium) }
            items(state.holdingTank.size) { index ->
                CoolDownTankCard(state.holdingTank[index], onConfirm = onConfirmPurchase, onDismiss = onDiscardPurchase)
            }
            item { NeurodiverseModeToggle(state.neurodiverseModeEnabled, onNeurodiverseModeChanged) }
            if (state.neurodiverseModeEnabled) item { Text("Focus mode prioritizes your next essential action. Calculations and data remain unchanged.", style = MaterialTheme.typography.bodyMedium) }
            state.message?.let { message -> item { AssistChip(onClick = onDismissMessage, label = { OneLineText(message, style = MaterialTheme.typography.labelMedium) }) } }
        }
    }
    if (showAccountSetup) AccountSetupDialog(initialProduct = accountSetupProduct, onDismiss = { showAccountSetup = false }, onSave = { name, type, assetClass, target, openingBalance, cashKind -> onCreateAccount(name, type, assetClass, target, openingBalance, cashKind) { account -> showAccountSetup = false; if (account.kind == AccountKind.INVESTMENT) createdInvestment = account } })
    createdInvestment?.let { account ->
        AlertDialog(
            onDismissRequest = { createdInvestment = null },
            title = { Text("Nivesha created") },
            text = { Text("${account.name} is ready. Record its first dated cost and current value now to begin portfolio tracking.") },
            confirmButton = { Button(onClick = { initialInvestmentCheckInId = account.id; createdInvestment = null; showInvestmentCheckIn = true }) { DhanamActionText("First check-in") } },
            dismissButton = { TextButton(onClick = { createdInvestment = null }) { DhanamActionText("Done") } }
        )
    }
    if (showInvestmentCheckIn) InvestmentBalanceCheckInDialog(state.accounts.filter { it.kind == AccountKind.INVESTMENT }, state.investmentHistory, state.dateFormatPreference, initialAccountId = initialInvestmentCheckInId, onDismiss = { showInvestmentCheckIn = false; initialInvestmentCheckInId = null }, onSave = { accountId, date, cost, value, note -> onSaveInvestmentBalance(accountId, date, cost, value, note); showInvestmentCheckIn = false; initialInvestmentCheckInId = null })
    if (showContribution) InvestmentContributionDialog(state.accounts.filter { it.kind == AccountKind.INVESTMENT }, onDismiss = { showContribution = false }, onSave = { id, amount, payee -> onContributeToInvestment(id, amount, payee); showContribution = false })
}
