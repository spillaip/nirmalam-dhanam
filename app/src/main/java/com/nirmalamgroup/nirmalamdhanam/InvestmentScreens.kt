package com.nirmalamgroup.nirmalamdhanam

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nirmalamgroup.nirmalamdhanam.data.local.AccountEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.AccountKind
import com.nirmalamgroup.nirmalamdhanam.data.local.AccountProductType
import com.nirmalamgroup.nirmalamdhanam.data.local.AssetClass
import com.nirmalamgroup.nirmalamdhanam.data.local.DateFormatPreference
import com.nirmalamgroup.nirmalamdhanam.data.local.InvestmentBalanceSnapshotEntity
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.AccountRolePolicy
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.FinancialCalculations
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.InvestmentDelta
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.InvestmentDeltaEngine
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.RealizedGainStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset


@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun NetWorthDashboardScreen(state: MvpFinanceState, onBack: () -> Unit, onOpenPortfolio: () -> Unit) {
    val latestByInvestment = state.investmentHistory.groupBy { it.accountId }.mapValues { (_, values) -> values.maxBy { it.asOfEpochDay } }
    val spending = state.accountBalances.filter { it.kind == AccountKind.SPENDING }.sumOf { it.balancePaise }
    val reserves = state.accountBalances.filter { it.kind == AccountKind.SAVINGS || it.kind == AccountKind.EMERGENCY }.sumOf { it.balancePaise }
    val liabilities = state.accountBalances.filter { it.kind == AccountKind.CREDIT }.sumOf { (-it.balancePaise).coerceAtLeast(0) }
    val investments = latestByInvestment.values.sumOf { it.currentValuePaise }
    val netWorth = spending + reserves + investments - liabilities
    val assetTotal = spending + reserves + investments
    val accountById = state.accounts.associateBy { it.id }
    val allocationByClass = latestByInvestment.entries.groupBy { (id, _) -> accountById[id]?.assetClass ?: AssetClass.OTHER }
        .mapValues { (_, entries) -> entries.sumOf { it.value.currentValuePaise } }
    val trend = state.netWorthHistory.sortedBy { it.asOfEpochDay }.map { it.netWorthPaise }.let { history -> if (history.lastOrNull() == netWorth) history else history + netWorth }
    val latestTrendDelta = trendDelta(trend)
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            DhanamScreenTopBar(
                title = "Sampada",
                subtitle = "Your net worth dashboard",
                onBack = onBack,
                actions = {
                    TextButton(onClick = onOpenPortfolio) { DhanamActionText("Nivesha") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("CURRENT NET WORTH", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(formatMoney(netWorth, state.currencyCode), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Assets ${formatMoney(assetTotal, state.currencyCode)} · Liabilities ${formatMoney(liabilities, state.currencyCode)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OneLineText("Nivesha cost & value", style = MaterialTheme.typography.titleMedium)
                        Text("Your dated check-ins show invested cost against current value.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PortfolioValueChart(state.investmentHistory, state.currencyCode)
                    }
                }
            }
            latestTrendDelta?.let { delta ->
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                OneLineText("Latest Sampada move", style = MaterialTheme.typography.titleMedium)
                                Text("Compared with the previous dated snapshot", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(12.dp))
                            OneLineText(
                                formatMoney(delta, state.currencyCode, includeSign = true),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (delta < 0) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ElevatedCard(Modifier.weight(1f)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { OneLineText("LIQUID", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(formatMoney(spending, state.currencyCode), style = MaterialTheme.typography.titleLarge); Text("Cash & bank", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    ElevatedCard(Modifier.weight(1f)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { OneLineText("RESERVES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(formatMoney(reserves, state.currencyCode), style = MaterialTheme.typography.titleLarge); Text("Savings & emergency", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OneLineText("Nivesha & liabilities", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { OneLineText("Investment assets"); Text(formatMoney(investments, state.currencyCode), style = MaterialTheme.typography.titleSmall) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { OneLineText("Credit & loans"); Text(formatMoney(-liabilities, state.currencyCode), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = onOpenPortfolio, modifier = Modifier.fillMaxWidth()) { DhanamActionText("Manage Nivesha") }
                } }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OneLineText("Sampada trend", style = MaterialTheme.typography.titleMedium)
                    if (trend.size >= 2) NetWorthSparkline(trend) else Text("Your trend grows as you record monthly Nivesha balances.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
            item { OneLineText("Nivesha by asset class", style = MaterialTheme.typography.titleMedium) }
            if (allocationByClass.isEmpty()) item { ElevatedCard(Modifier.fillMaxWidth()) { Text("Add an investment asset and its first check-in to see allocation here.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) } }
            items(allocationByClass.entries.sortedByDescending { it.value }.size) { index ->
                val (assetClass, amount) = allocationByClass.entries.sortedByDescending { it.value }[index]
                val share = if (investments == 0L) 0f else (amount.toDouble() / investments).toFloat()
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { OneLineText(assetClass.name.replace('_', ' '), style = MaterialTheme.typography.titleSmall); OneLineText(formatMoney(amount, state.currencyCode), style = MaterialTheme.typography.titleSmall) }
                    LinearProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth())
                    Text("${"%.1f".format(share * 100)}% of Nivesha", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
            item { OneLineText("How Sampada is calculated", style = MaterialTheme.typography.titleMedium) }
            item { Text("Liquid cash + reserves + latest Nivesha values − credit and loan liabilities. Dated Nivesha check-ins provide the historical trend; everyday cashflow updates the current total instantly.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PortfolioAndNetWorthScreen(state: MvpFinanceState, onBack: () -> Unit, onOpenNetWorth: () -> Unit, onOpenPerformanceReport: () -> Unit, onAddInvestment: () -> Unit, onRecordBalance: () -> Unit, onDeleteSnapshot: (String) -> Unit, onUpdateSnapshot: (String, String, String, String, String, String) -> Unit, onUpdateInvestment: (String, String, AccountProductType, AssetClass, String) -> Unit, onArchiveInvestment: (String) -> Unit) {
    var editingSnapshotId by remember { mutableStateOf<String?>(null) }
    var editingInvestmentId by remember { mutableStateOf<String?>(null) }
    var investmentToArchive by remember { mutableStateOf<AccountEntity?>(null) }
    val latestByAsset = state.investmentHistory.groupBy { it.accountId }.mapValues { (_, snapshots) -> snapshots.maxBy { it.asOfEpochDay } }
    val historyByInvestment = state.investmentHistory.groupBy { it.accountId }
    val portfolioValue = latestByAsset.values.sumOf { it.currentValuePaise }
    val portfolioCost = latestByAsset.values.sumOf { it.totalCostPaise }
    val portfolioAbsolutePaise = portfolioValue - portfolioCost
    val portfolioAbsolutePercent = portfolioCost.takeIf { it != 0L }?.let { portfolioAbsolutePaise * 100.0 / it }
    val portfolioXirr = FinancialCalculations.xirrPercent(historyByInvestment.values.flatMap(::cashFlowsForXirr))
    val portfolioTrendDelta = trendDelta(portfolioChartPoints(state.investmentHistory).map { it.valuePaise })
    val netWorth = state.netWorthHistory.maxByOrNull { it.asOfEpochDay }?.netWorthPaise ?: run {
        val reserves = state.accountBalances.filter { it.kind == AccountKind.SAVINGS || it.kind == AccountKind.EMERGENCY }.sumOf { it.balancePaise }
        state.cashPaise + reserves + portfolioValue
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            DhanamScreenTopBar(
                title = "Nivesha & Sampada",
                subtitle = "Investments and overall wealth",
                onBack = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                InvestmentNavigationTabs(
                    selectedTab = InvestmentSection.OVERVIEW,
                    onOverview = {},
                    onPerformance = onOpenPerformanceReport,
                )
            }
            item {
                InvestmentQuickActions(
                    onAddInvestment = onAddInvestment,
                    onRecordBalance = onRecordBalance,
                )
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OneLineText("SAMPADA · NET WORTH", style = MaterialTheme.typography.labelLarge)
                        OneLineText(formatMoney(netWorth, state.currencyCode), style = MaterialTheme.typography.displaySmall)
                        HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { OneLineText("NIVESHA VALUE", style = MaterialTheme.typography.labelSmall); Text(formatMoney(portfolioValue, state.currencyCode), style = MaterialTheme.typography.titleMedium) }
                            Column(horizontalAlignment = Alignment.End) { OneLineText("TOTAL RETURN", style = MaterialTheme.typography.labelSmall); Text("${formatMoney(portfolioAbsolutePaise, state.currencyCode, includeSign = true)} · ABS ${formatPercent(portfolioAbsolutePercent)}", style = MaterialTheme.typography.titleSmall, color = if (portfolioAbsolutePaise < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer); Text("XIRR ${formatPercent(portfolioXirr)}", style = MaterialTheme.typography.labelSmall) }
                        }
                        TextButton(
                            onClick = onOpenNetWorth,
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            DhanamActionText("Sampada")
                        }
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OneLineText("Sampada trend", style = MaterialTheme.typography.titleMedium)
                    if (state.netWorthHistory.size >= 2) NetWorthSparkline(state.netWorthHistory.sortedBy { it.asOfEpochDay }.map { it.netWorthPaise }) else Text("Your trend appears after two dated investment check-ins.", style = MaterialTheme.typography.bodySmall)
                } }
            }
            portfolioTrendDelta?.let { delta ->
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                OneLineText("Latest Nivesha move", style = MaterialTheme.typography.titleMedium)
                                Text("Change from the previous portfolio check-in", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(12.dp))
                            OneLineText(
                                formatMoney(delta, state.currencyCode, includeSign = true),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (delta < 0) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            item { OneLineText("Nivesha allocation", style = MaterialTheme.typography.titleMedium) }
            items(latestByAsset.size) { index ->
                val (accountId, snapshot) = latestByAsset.entries.sortedByDescending { it.value.currentValuePaise }[index]
                val account = state.accounts.firstOrNull { it.id == accountId }
                val allocation = if (portfolioValue == 0L) 0.0 else snapshot.currentValuePaise * 100.0 / portfolioValue
                val performance = investmentPerformance(historyByInvestment[accountId].orEmpty())
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { OneLineText(account?.name ?: "Investment", style = MaterialTheme.typography.titleSmall); OneLineText(account?.productType?.name?.replace('_', ' ') ?: "Asset", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); account?.benchmarkIndexName?.let { Text("Benchmark · $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }; Text("Cost ${formatMoney(snapshot.totalCostPaise, state.currencyCode)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Column(horizontalAlignment = Alignment.End) { OneLineText(formatMoney(snapshot.currentValuePaise, state.currencyCode), style = MaterialTheme.typography.titleMedium); OneLineText("${"%.1f".format(allocation)}% · ABS ${formatPercent(performance?.absolutePercent)}", style = MaterialTheme.typography.bodySmall, color = if ((performance?.absolutePaise ?: 0L) < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary); Text("XIRR ${formatPercent(performance?.xirrPercent)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    account?.let { investment ->
                        if (editingInvestmentId == investment.id) InlineInvestmentEditor(investment, onCancel = { editingInvestmentId = null }, onSave = { name, product, assetClass, target -> onUpdateInvestment(investment.id, name, product, assetClass, target); editingInvestmentId = null })
                        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { editingInvestmentId = investment.id }) { DhanamActionText("Edit") }; TextButton(onClick = { investmentToArchive = investment }) { DhanamActionText("Archive") } }
                    }
                } }
            }
            val unvaluedInvestments = state.accounts.filter { it.kind == AccountKind.INVESTMENT && it.id !in latestByAsset }
            if (unvaluedInvestments.isNotEmpty()) item { OneLineText("Awaiting first check-in", style = MaterialTheme.typography.titleMedium) }
            items(unvaluedInvestments.size) { index ->
                val investment = unvaluedInvestments[index]
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OneLineText(investment.name, style = MaterialTheme.typography.titleSmall)
                    Text("${investment.productType.name.replace('_', ' ')} · no valuation recorded yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (editingInvestmentId == investment.id) InlineInvestmentEditor(investment, onCancel = { editingInvestmentId = null }, onSave = { name, product, assetClass, target -> onUpdateInvestment(investment.id, name, product, assetClass, target); editingInvestmentId = null })
                    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { editingInvestmentId = investment.id }) { DhanamActionText("Edit") }; TextButton(onClick = { investmentToArchive = investment }) { DhanamActionText("Archive") } }
                } }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OneLineText("Nivesha allocation vs target", style = MaterialTheme.typography.titleMedium)
                    AssetClass.entries.filter { assetClass -> state.accounts.any { it.assetClass == assetClass && it.targetAllocationBps > 0 } }.forEach { assetClass ->
                        val actualValue = latestByAsset.entries.filter { (accountId, _) -> state.accounts.firstOrNull { it.id == accountId }?.assetClass == assetClass }.sumOf { it.value.currentValuePaise }
                        val actual = if (portfolioValue == 0L) 0.0 else actualValue * 100.0 / portfolioValue
                        val target = state.accounts.filter { it.assetClass == assetClass }.sumOf { it.targetAllocationBps } / 100.0
                        val difference = actual - target
                        Text("${assetClass.name}: %.1f%% / %.1f%% target · %s %.1f%%".format(actual, target, if (difference >= 0) "over" else "under", kotlin.math.abs(difference)), style = MaterialTheme.typography.bodySmall)
                    }
                } }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    OneLineText("Nivesha history", style = MaterialTheme.typography.titleMedium)
                    Text("Dated cost and value check-ins", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val history = state.investmentHistory.sortedWith(compareByDescending<InvestmentBalanceSnapshotEntity> { it.asOfEpochDay }.thenByDescending { it.createdAtEpochMs })
            items(history.size) { index ->
                val snapshot = history[index]
                val account = state.accounts.firstOrNull { it.id == snapshot.accountId }
                val assetHistory = historyByInvestment[snapshot.accountId].orEmpty()
                val performance = investmentPerformance(assetHistory.filter { it.asOfEpochDay <= snapshot.asOfEpochDay })
                val priorSnapshot = assetHistory
                    .filter { it.asOfEpochDay < snapshot.asOfEpochDay }
                    .maxByOrNull { it.asOfEpochDay }
                val snapshotDelta = InvestmentDeltaEngine.calculate(
                    previous = priorSnapshot,
                    currentCostPaise = snapshot.totalCostPaise,
                    currentValuePaise = snapshot.currentValuePaise,
                )
                val gain = performance?.absolutePaise
                    ?: (snapshot.currentValuePaise - snapshot.totalCostPaise)
                val gainColor = if (gain < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            OneLineText(account?.name ?: "Investment", style = MaterialTheme.typography.titleSmall)
                            Text(account?.productType?.name?.replace('_', ' ') ?: "Nivesha holding", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            account?.benchmarkIndexName?.let { benchmark -> Text("Benchmark · $benchmark", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("AS ON", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(formatDate(LocalDate.ofEpochDay(snapshot.asOfEpochDay), state.dateFormatPreference), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Box(Modifier.weight(1f)) { InvestmentHistoryMetric("COST", formatMoney(snapshot.totalCostPaise, state.currencyCode)) }
                        Box(Modifier.weight(1f)) { InvestmentHistoryMetric("VALUE", formatMoney(snapshot.currentValuePaise, state.currencyCode), alignment = Alignment.CenterHorizontally) }
                        Box(Modifier.weight(1f)) { InvestmentHistoryMetric("GAIN / LOSS", formatMoney(gain, state.currencyCode, includeSign = true), gainColor, Alignment.End) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("ABS ${formatPercent(performance?.absolutePercent)}", style = MaterialTheme.typography.labelMedium, color = gainColor)
                        Text("XIRR ${formatPercent(performance?.xirrPercent)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        when {
                            snapshotDelta.contributionPaise > 0L -> Text(
                                "Fresh investment ${formatMoney(snapshotDelta.contributionPaise, state.currencyCode, includeSign = true)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            snapshotDelta.withdrawalCostBasisPaise > 0L -> Text(
                                "Withdrawal / redemption basis ${formatMoney(snapshotDelta.withdrawalCostBasisPaise, state.currencyCode)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            else -> Text(
                                "No contribution or withdrawal",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        snapshotDelta.totalReturnSincePreviousPercent?.let { periodReturn ->
                            OneLineText(
                                "${if (snapshotDelta.totalReturnIsEstimated) "Est. return" else "Period return"} ${formatPercent(periodReturn)} · " +
                                    formatMoney(snapshotDelta.totalReturnSincePreviousPaise ?: 0L, state.currencyCode, includeSign = true),
                                style = MaterialTheme.typography.labelMedium,
                                color = if ((snapshotDelta.totalReturnSincePreviousPaise ?: 0L) < 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            )
                        }

                        if (snapshotDelta.realizedGainStatus == RealizedGainStatus.REDEMPTION_PROCEEDS_REQUIRED) {
                            OneLineText(
                                "Realised gain unavailable · add redemption proceeds",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    snapshot.note?.takeIf { it.isNotBlank() }?.let { note ->
                        Text(note, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (editingSnapshotId == snapshot.id) {
                        InlineInvestmentSnapshotEditor(
                            snapshot = snapshot,
                            investmentHistory = state.investmentHistory,
                            dateFormatPreference = state.dateFormatPreference,
                            onCancel = { editingSnapshotId = null },
                            onSave = { date, cost, value, note ->
                                onUpdateSnapshot(snapshot.id, snapshot.accountId, date, cost, value, note)
                                editingSnapshotId = null
                            }
                        )
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { editingSnapshotId = snapshot.id }) { DhanamActionText("Edit") }
                            TextButton(onClick = { onDeleteSnapshot(snapshot.id) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { DhanamActionText("Delete") }
                        }
                    }
                } }
            }
        }
    }
    investmentToArchive?.let { account -> AlertDialog(onDismissRequest = { investmentToArchive = null }, title = { Text("Archive Nivesha?") }, text = { Text("${account.name} will be hidden from active portfolio tracking. Its dated history remains safely in this encrypted database.") }, confirmButton = { Button(onClick = { onArchiveInvestment(account.id); investmentToArchive = null }) { DhanamActionText("Archive") } }, dismissButton = { TextButton(onClick = { investmentToArchive = null }) { DhanamActionText("Cancel") } }) }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun InvestmentNavigationTabs(
    selectedTab: InvestmentSection,
    onOverview: () -> Unit,
    onPerformance: () -> Unit,
) {
    val selectedIndex = when (selectedTab) {
        InvestmentSection.OVERVIEW -> 0
        InvestmentSection.PERFORMANCE -> 1
    }

    SecondaryTabRow(
        selectedTabIndex = selectedIndex,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Tab(
            selected = selectedTab == InvestmentSection.OVERVIEW,
            onClick = onOverview,
            text = { OneLineText("Overview", style = MaterialTheme.typography.labelLarge) },
        )
        Tab(
            selected = selectedTab == InvestmentSection.PERFORMANCE,
            onClick = onPerformance,
            text = { OneLineText("Performance", style = MaterialTheme.typography.labelLarge) },
        )
    }
}

@Composable
private fun InvestmentQuickActions(
    onAddInvestment: () -> Unit,
    onRecordBalance: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onRecordBalance,
            modifier = Modifier.weight(1f),
        ) {
            DhanamActionText("Check-in")
        }
        TextButton(onClick = onAddInvestment) {
            DhanamActionText("Add asset")
        }
    }
}

@Composable
private fun InvestmentHistoryMetric(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface, alignment: Alignment.Horizontal = Alignment.Start) {
    Column(horizontalAlignment = alignment) {
        OneLineText(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OneLineText(value, style = MaterialTheme.typography.titleSmall, color = color)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun InlineInvestmentEditor(account: AccountEntity, onCancel: () -> Unit, onSave: (String, AccountProductType, AssetClass, String) -> Unit) {
    var name by remember(account.id) { mutableStateOf(account.name) }
    var product by remember(account.id) { mutableStateOf(account.productType) }
    var assetClass by remember(account.id) { mutableStateOf(account.assetClass) }
    var target by remember(account.id) { mutableStateOf((account.targetAllocationBps / 100.0).toString()) }
    var productExpanded by remember(account.id) { mutableStateOf(false) }
    var classExpanded by remember(account.id) { mutableStateOf(false) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("Modify Nivesha", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { OneLineText("Nivesha name") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
    ExposedDropdownMenuBox(productExpanded, { productExpanded = !productExpanded }) {
        OutlinedTextField(product.name.replace('_', ' '), {}, Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(), readOnly = true, label = { OneLineText("Product type") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(productExpanded) })
        ExposedDropdownMenu(productExpanded, { productExpanded = false }) { investmentProductTypes.forEach { type -> DropdownMenuItem(text = { OneLineText(type.name.replace('_', ' ')) }, onClick = { product = type; assetClass = suggestedAssetClass(type); productExpanded = false }) } }
    }
    ExposedDropdownMenuBox(classExpanded, { classExpanded = !classExpanded }) {
        OutlinedTextField(assetClass.name, {}, Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(), readOnly = true, label = { OneLineText("Asset class") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(classExpanded) })
        ExposedDropdownMenu(classExpanded, { classExpanded = false }) { AssetClass.entries.forEach { type -> DropdownMenuItem(text = { OneLineText(type.name) }, onClick = { assetClass = type; classExpanded = false }) } }
    }
    OutlinedTextField(target, { target = it }, Modifier.fillMaxWidth(), label = { OneLineText("Target %") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onCancel) { DhanamActionText("Cancel") }; Button(onClick = { onSave(name, product, assetClass, target) }, enabled = name.isNotBlank()) { DhanamActionText("Save") } }
}

@Composable
private fun InvestmentDeltaSummary(
    delta: InvestmentDelta,
    currencyCode: String,
    compact: Boolean = false,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(if (compact) 10.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Calculated automatically", style = MaterialTheme.typography.labelMedium)

            when {
                delta.contributionPaise > 0L -> Text(
                    "Fresh investment: ${formatMoney(delta.contributionPaise, currencyCode, includeSign = true)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                delta.withdrawalCostBasisPaise > 0L -> Text(
                    "Withdrawal / redemption basis: ${formatMoney(delta.withdrawalCostBasisPaise, currencyCode)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> Text("No contribution or withdrawal detected", style = MaterialTheme.typography.bodySmall)
            }

            if (delta.previousValuePaise != null) {
                val movementLabel = if (delta.marketMovementIsEstimated) "Movement estimate" else "Market movement"
                Text(
                    "$movementLabel: ${formatMoney(delta.marketMovementPaise, currencyCode, includeSign = true)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                val returnLabel = if (delta.totalReturnIsEstimated) "Return since previous (est.)" else "Return since previous"
                Text(
                    "$returnLabel: ${formatPercent(delta.totalReturnSincePreviousPercent)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text(
                "Unrealised gain/loss: ${formatMoney(delta.unrealizedGainPaise, currencyCode, includeSign = true)}",
                style = MaterialTheme.typography.bodySmall,
            )

            when (delta.realizedGainStatus) {
                RealizedGainStatus.DETERMINED_FROM_CASH_FLOW -> Text(
                    "Realised gain/loss: ${formatMoney(delta.realizedGainPaise ?: 0L, currencyCode, includeSign = true)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                RealizedGainStatus.REDEMPTION_PROCEEDS_REQUIRED -> Text(
                    "Realised gain: not determinable from cost/value alone; redemption proceeds are required.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RealizedGainStatus.NOT_APPLICABLE -> Unit
            }

            if (delta.marketMovementIsEstimated) {
                Text(
                    "A lower cost basis proves that money left the holding, but not the cash proceeds. Dhanam uses the basis reduction only as a return estimate and does not invent realised gain.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun InlineInvestmentSnapshotEditor(snapshot: InvestmentBalanceSnapshotEntity, investmentHistory: List<InvestmentBalanceSnapshotEntity>, dateFormatPreference: DateFormatPreference, onCancel: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var date by remember(snapshot.id) { mutableStateOf(LocalDate.ofEpochDay(snapshot.asOfEpochDay)) }
    var cost by remember(snapshot.id) { mutableStateOf(BigDecimal(snapshot.totalCostPaise).movePointLeft(2).toPlainString()) }
    var value by remember(snapshot.id) { mutableStateOf(BigDecimal(snapshot.currentValuePaise).movePointLeft(2).toPlainString()) }
    var note by remember(snapshot.id) { mutableStateOf(snapshot.note.orEmpty()) }
    val priorSnapshot = remember(snapshot.accountId, date, investmentHistory) {
        investmentHistory.filter { it.accountId == snapshot.accountId && it.asOfEpochDay < date.toEpochDay() && it.id != snapshot.id }.maxByOrNull { it.asOfEpochDay }
    }
    fun rupeesToPaise(text: String) = runCatching { BigDecimal(text.trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
    val calculatedDelta = remember(cost, value, priorSnapshot) {
        val c = rupeesToPaise(cost); val v = rupeesToPaise(value)
        if (c != null && v != null && c >= 0 && v >= 0) InvestmentDeltaEngine.calculate(priorSnapshot, c, v) else null
    }
    var showDatePicker by remember(snapshot.id) { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = LocalDate.ofEpochDay(snapshot.asOfEpochDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("Modify check-in", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    OutlinedTextField(value = formatDate(date, dateFormatPreference), onValueChange = {}, modifier = Modifier.fillMaxWidth(), readOnly = true, label = { OneLineText("As-on date") }, trailingIcon = { TextButton(onClick = { showDatePicker = true }) { DhanamActionText("Pick") } }, singleLine = true)
    OutlinedTextField(cost, { cost = it }, Modifier.fillMaxWidth(), label = { OneLineText("Total cost in ₹") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
    OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { OneLineText("Current value in ₹") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
    calculatedDelta?.let { delta ->
        InvestmentDeltaSummary(delta = delta, currencyCode = "INR", compact = true)
    }
    OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { OneLineText("Statement / note") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onCancel) { DhanamActionText("Cancel") }; Button(onClick = { onSave(date.toString(), cost, value, note) }) { DhanamActionText("Save") } }
    if (showDatePicker) {
        DatePickerDialog(onDismissRequest = { showDatePicker = false }, confirmButton = { TextButton(onClick = { datePickerState.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; showDatePicker = false }) { DhanamActionText("OK") } }, dismissButton = { TextButton(onClick = { showDatePicker = false }) { DhanamActionText("Cancel") } }) { DatePicker(state = datePickerState) }
    }
}

@Composable
internal fun NetWorthSparkline(values: List<Long>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    Canvas(Modifier.fillMaxWidth().height(88.dp)) {
        val min = values.minOrNull() ?: 0L
        val max = values.maxOrNull() ?: (min + 1L)
        val spread = (max - min).coerceAtLeast(1L).toFloat()
        val path = Path()
        val fill = Path()
        values.forEachIndexed { index, value ->
            val x = if (values.size == 1) 0f else size.width * index / (values.size - 1)
            val y = size.height - ((value - min) / spread * size.height)
            if (index == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, size.height)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(size.width, size.height)
        fill.close()
        drawPath(fill, fillColor)
        drawPath(path, lineColor, style = Stroke(width = 5f, cap = StrokeCap.Round))
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun AccountSetupDialog(initialProduct: AccountProductType = AccountProductType.CASH, onDismiss: () -> Unit, onSave: (String, AccountProductType, AssetClass, String, String, AccountKind) -> Unit) {
    var name by remember { mutableStateOf("") }
    var openingBalance by remember { mutableStateOf("") }
    var product by remember(initialProduct) { mutableStateOf(initialProduct) }
    var assetClass by remember(initialProduct) { mutableStateOf(suggestedAssetClass(initialProduct)) }
    var expanded by remember { mutableStateOf(false) }
    var assetClassExpanded by remember { mutableStateOf(false) }
    var targetPercent by remember { mutableStateOf("0") }
    var cashKind by remember { mutableStateOf(AccountKind.SPENDING) }
    val isInvestment = product in investmentProductTypes
    val purpose = when {
        isInvestment -> "Investment holding: record dated cost and value check-ins in Nivesha. It does not become daily Aaya or Vyaya."
        product == AccountProductType.CREDIT_CARD || product == AccountProductType.LOAN -> "Liability Khata: record what you owe. Its balance reduces true available cash."
        cashKind == AccountKind.SAVINGS -> "Savings Khata: keep planned reserves separate from day-to-day spending while retaining them in liquidity and health reports."
        cashKind == AccountKind.EMERGENCY -> "Emergency Khata: ring-fence emergency reserves so runway and financial-health reports can identify them explicitly."
        else -> "Daily Khata: use this cash or bank place for Aaya, Vyaya, and your everyday balance."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set up Khata") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Set up a daily Khata, a liability, or an investment holding.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { OneLineText("Khata name") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
                ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
                    OutlinedTextField(product.name.replace('_', ' '), {}, Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(), readOnly = true, label = { OneLineText("Khata type") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
                    ExposedDropdownMenu(expanded, { expanded = false }) {
                        Text("DAILY KHATAS", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall)
                        listOf(AccountProductType.CASH, AccountProductType.BANK).forEach { type -> DropdownMenuItem(text = { OneLineText(type.name.replace('_', ' ')) }, onClick = { product = type; assetClass = suggestedAssetClass(type); expanded = false }) }
                        HorizontalDivider()
                        Text("LIABILITIES", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall)
                        listOf(AccountProductType.CREDIT_CARD, AccountProductType.LOAN).forEach { type -> DropdownMenuItem(text = { OneLineText(type.name.replace('_', ' ')) }, onClick = { product = type; assetClass = suggestedAssetClass(type); expanded = false }) }
                        HorizontalDivider()
                        Text("INVESTMENT HOLDINGS", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall)
                        investmentProductTypes.forEach { type -> DropdownMenuItem(text = { OneLineText(type.name.replace('_', ' ')) }, onClick = { product = type; assetClass = suggestedAssetClass(type); expanded = false }) }
                    }
                }
                ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(purpose, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (product == AccountProductType.CASH || product == AccountProductType.BANK) {
                    Text("Purpose", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AccountRolePolicy.cashAccountKinds.map { kind -> kind to when (kind) { AccountKind.SPENDING -> "Daily"; AccountKind.SAVINGS -> "Savings"; AccountKind.EMERGENCY -> "Emergency"; else -> kind.name } }.forEach { (kind, label) ->
                            FilterChip(selected = cashKind == kind, onClick = { cashKind = kind }, label = { OneLineText(label, style = MaterialTheme.typography.labelMedium) })
                        }
                    }
                }
                if (isInvestment) {
                    ExposedDropdownMenuBox(assetClassExpanded, { assetClassExpanded = !assetClassExpanded }) {
                        OutlinedTextField(assetClass.name, {}, Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(), readOnly = true, label = { OneLineText("Asset class") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(assetClassExpanded) })
                        ExposedDropdownMenu(assetClassExpanded, { assetClassExpanded = false }) {
                            AssetClass.entries.forEach { type -> DropdownMenuItem(text = { OneLineText(type.name) }, onClick = { assetClass = type; assetClassExpanded = false }) }
                        }
                    }
                    OutlinedTextField(targetPercent, { targetPercent = it }, Modifier.fillMaxWidth(), label = { OneLineText("Target %") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                }
                OutlinedTextField(openingBalance, { openingBalance = it }, Modifier.fillMaxWidth(), label = { OneLineText(if (isInvestment) "Starting value ₹" else if (product == AccountProductType.CREDIT_CARD || product == AccountProductType.LOAN) "Amount owed ₹" else "Opening balance ₹") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            }
        },
        confirmButton = { Button(onClick = { onSave(name, product, assetClass, targetPercent, openingBalance, cashKind) }, enabled = name.isNotBlank()) { DhanamActionText("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { DhanamActionText("Cancel") } }
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun InvestmentBalanceCheckInDialog(accounts: List<AccountEntity>, investmentHistory: List<InvestmentBalanceSnapshotEntity>, dateFormatPreference: DateFormatPreference, initialAccountId: String? = null, onDismiss: () -> Unit, onSave: (String, String, String, String, String) -> Unit) {
    if (accounts.isEmpty()) return
    var account by remember(accounts, initialAccountId) { mutableStateOf(accounts.firstOrNull { it.id == initialAccountId } ?: accounts.first()) }
    var accountExpanded by remember { mutableStateOf(false) }
    var asOfDate by remember { mutableStateOf(LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    var cost by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val priorSnapshot = remember(account, asOfDate, investmentHistory) { investmentHistory.filter { it.accountId == account.id && it.asOfEpochDay < asOfDate.toEpochDay() }.maxByOrNull { it.asOfEpochDay } }
    fun rupeesToPaise(text: String) = runCatching { BigDecimal(text.trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
    val calculatedDelta = remember(cost, value, priorSnapshot) {
        val c = rupeesToPaise(cost); val v = rupeesToPaise(value)
        if (c != null && v != null && c >= 0 && v >= 0) InvestmentDeltaEngine.calculate(priorSnapshot, c, v) else null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Investment balance check-in") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Enter current cost and current value. Dhanam derives fresh investment or withdrawal, unrealised gain/loss, movement, and return since the previous check-in. It never invents realised gain when redemption proceeds are unknown.", style = MaterialTheme.typography.bodySmall)
            ExposedDropdownMenuBox(accountExpanded, { accountExpanded = !accountExpanded }) {
                OutlinedTextField(account.name, {}, Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(), readOnly = true, label = { OneLineText("Nivesha") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(accountExpanded) })
                ExposedDropdownMenu(accountExpanded, { accountExpanded = false }) { accounts.forEach { item -> DropdownMenuItem(text = { OneLineText("${item.productType.name.replace('_', ' ')} · ${item.name}") }, onClick = { account = item; accountExpanded = false }) } }
            }
            OutlinedTextField(value = formatDate(asOfDate, dateFormatPreference), onValueChange = {}, modifier = Modifier.fillMaxWidth(), readOnly = true, label = { OneLineText("As-on date") }, trailingIcon = { TextButton(onClick = { showDatePicker = true }) { DhanamActionText("Pick") } }, singleLine = true)
            OutlinedTextField(cost, { cost = it }, Modifier.fillMaxWidth(), label = { OneLineText("Current cost ₹") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { OneLineText("Current value in ₹") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            calculatedDelta?.let { delta ->
                InvestmentDeltaSummary(delta = delta, currencyCode = "INR")
            }
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { OneLineText("Note") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
        } },
        confirmButton = { Button(onClick = { onSave(account.id, asOfDate.toString(), cost, value, note) }, enabled = cost.isNotBlank() && value.isNotBlank()) { DhanamActionText("Save balance") } },
        dismissButton = { TextButton(onClick = onDismiss) { DhanamActionText("Cancel") } }
    )
    if (showDatePicker) {
        DatePickerDialog(onDismissRequest = { showDatePicker = false }, confirmButton = { TextButton(onClick = { datePickerState.selectedDateMillis?.let { selected -> asOfDate = Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate() }; showDatePicker = false }) { DhanamActionText("OK") } }, dismissButton = { TextButton(onClick = { showDatePicker = false }) { DhanamActionText("Cancel") } }) { DatePicker(state = datePickerState) }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun InvestmentContributionDialog(accounts: List<AccountEntity>, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var account by remember(accounts) { mutableStateOf(accounts.first()) }
    var expanded by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var payee by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Contribute to investment") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This records Vyaya from your cash/bank Khata and increases the selected Nivesha cost and value today.", style = MaterialTheme.typography.bodySmall)
            ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
                OutlinedTextField(account.name, {}, Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(), readOnly = true, label = { OneLineText("Nivesha") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
                ExposedDropdownMenu(expanded, { expanded = false }) { accounts.forEach { item -> DropdownMenuItem(text = { OneLineText(item.name) }, onClick = { account = item; expanded = false }) } }
            }
            OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { OneLineText("Contribution in ₹") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            OutlinedTextField(payee, { payee = it }, Modifier.fillMaxWidth(), label = { OneLineText("Provider / payee") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
        } },
        confirmButton = { Button(onClick = { onSave(account.id, amount, payee) }, enabled = amount.isNotBlank()) { DhanamActionText("Record") } },
        dismissButton = { TextButton(onClick = onDismiss) { DhanamActionText("Cancel") } }
    )
}
