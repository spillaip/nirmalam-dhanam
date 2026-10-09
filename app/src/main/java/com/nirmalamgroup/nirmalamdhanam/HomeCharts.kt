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

private data class DailyMoneyPulse(val date: LocalDate, val incomePaise: Long, val spentPaise: Long)
internal data class PortfolioChartPoint(val date: LocalDate, val costPaise: Long, val valuePaise: Long)

internal fun portfolioChartPoints(history: List<InvestmentBalanceSnapshotEntity>): List<PortfolioChartPoint> {
    val byAccount = history.groupBy { it.accountId }.mapValues { (_, items) -> items.sortedBy { it.asOfEpochDay } }
    return history.map { it.asOfEpochDay }.distinct().sorted().takeLast(12).map { day ->
        val snapshots = byAccount.values.mapNotNull { items -> items.lastOrNull { it.asOfEpochDay <= day } }
        PortfolioChartPoint(LocalDate.ofEpochDay(day), snapshots.sumOf { it.totalCostPaise }, snapshots.sumOf { it.currentValuePaise })
    }
}

@Composable
internal fun PrarambhaBalanceCharts(state: MvpFinanceState) {
    val liquid = state.accountBalances.filter { it.kind == AccountKind.SPENDING }.sumOf { it.balancePaise }
    val reserves = state.accountBalances.filter { it.kind == AccountKind.SAVINGS || it.kind == AccountKind.EMERGENCY }.sumOf { it.balancePaise }
    val investments = state.investmentSnapshots.sumOf { it.currentValuePaise }
    val liabilities = state.accountBalances.filter { it.kind == AccountKind.CREDIT }.sumOf { (-it.balancePaise).coerceAtLeast(0) }
    val netWorthTrend = state.netWorthHistory.sortedBy { it.asOfEpochDay }.takeLast(12).map { it.netWorthPaise }
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("BALANCE PICTURE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            BalanceCompositionChart(liquid, reserves, investments, liabilities, state.currencyCode)
            if (netWorthTrend.size >= 2) {
                Text("Sampada trend", style = MaterialTheme.typography.labelMedium)
                NetWorthSparkline(netWorthTrend)
            } else Text("Your Sampada trend appears after two dated Nivesha check-ins.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun BalanceCompositionChart(cash: Long, reserves: Long, investments: Long, liabilities: Long, currencyCode: String) {
    val parts = listOf("Cash" to cash.coerceAtLeast(0L), "Reserves" to reserves.coerceAtLeast(0L), "Nivesha" to investments.coerceAtLeast(0L))
    val assetTotal = parts.sumOf { it.second }
    val totalForChart = assetTotal.coerceAtLeast(1L)
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary)
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        var x = 0f
        parts.forEachIndexed { index, (_, amount) ->
            val width = size.width * amount / totalForChart
            if (width > 0f) drawRect(colors[index], topLeft = androidx.compose.ui.geometry.Offset(x, 0f), size = androidx.compose.ui.geometry.Size(width, size.height))
            x += width
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Assets ${formatMoney(assetTotal, currencyCode)}", style = MaterialTheme.typography.labelSmall)
        Text("Liabilities ${formatMoney(liabilities, currencyCode)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        parts.forEachIndexed { index, (name, amount) -> Text("$name ${formatMoney(amount, currencyCode)}", style = MaterialTheme.typography.labelSmall, color = colors[index]) }
    }
}

@Composable
internal fun HomeMoneyPulse(transactions: List<TransactionEntity>, currencyCode: String) {
    val today = LocalDate.now()
    val days = (6L downTo 0L).map { day ->
        val date = today.minusDays(day)
        val entries = transactions.filter { transaction ->
            !transaction.isHoldingTank && Instant.ofEpochMilli(transaction.occurredAtEpochMs).atZone(ZoneId.systemDefault()).toLocalDate() == date
        }
        DailyMoneyPulse(
            date = date,
            incomePaise = entries.filter { it.direction == TransactionDirection.CREDIT }.sumOf { it.amountPaise },
            spentPaise = entries.filter { it.direction == TransactionDirection.DEBIT && it.envelopeType != EnvelopeType.INVESTMENT }.sumOf { it.amountPaise }
        )
    }
    val income = days.sumOf { it.incomePaise }
    val spent = days.sumOf { it.spentPaise }
    val net = income - spent
    val topCategory = transactions.asSequence()
        .filter { !it.isHoldingTank && it.direction == TransactionDirection.DEBIT && it.envelopeType != EnvelopeType.INVESTMENT && it.occurredAtEpochMs >= today.minusDays(6).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        .groupBy { it.category?.ifBlank { "Uncategorised" } ?: "Uncategorised" }
        .maxByOrNull { (_, entries) -> entries.sumOf { it.amountPaise } }

    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("MONEY PULSE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text("Last 7 days", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(formatMoney(net, currencyCode, includeSign = true), style = MaterialTheme.typography.titleLarge, color = if (net < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PulseMetric("IN", formatMoney(income, currencyCode), MaterialTheme.colorScheme.primary)
                PulseMetric("OUT", formatMoney(spent, currencyCode), MaterialTheme.colorScheme.error)
                PulseMetric("DAILY AVG", formatMoney(spent / 7, currencyCode), MaterialTheme.colorScheme.onSurface)
            }
            DailyCashflowChart(days)
            topCategory?.let { (name, entries) ->
                Text("Most used Varga: $name · ${formatMoney(entries.sumOf { it.amountPaise }, currencyCode)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } ?: Text("Add your first Vyavahara to start a local seven-day pulse.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun PulseMetric(label: String, value: String, color: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, color = color)
    }
}

@Composable
private fun DailyCashflowChart(days: List<DailyMoneyPulse>) {
    val positive = MaterialTheme.colorScheme.primary
    val negative = MaterialTheme.colorScheme.error
    val divider = MaterialTheme.colorScheme.outlineVariant
    val maxMagnitude = days.maxOfOrNull { maxOf(it.incomePaise, it.spentPaise) }?.coerceAtLeast(1L) ?: 1L
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(80.dp)) {
            val baseline = size.height / 2f
            drawLine(divider, start = androidx.compose.ui.geometry.Offset(0f, baseline), end = androidx.compose.ui.geometry.Offset(size.width, baseline), strokeWidth = 1f)
            val step = size.width / days.size
            val barWidth = step * 0.28f
            days.forEachIndexed { index, day ->
                val center = step * index + step / 2f
                val incomeHeight = day.incomePaise.toFloat() / maxMagnitude * (size.height * 0.42f)
                val spendHeight = day.spentPaise.toFloat() / maxMagnitude * (size.height * 0.42f)
                if (incomeHeight > 0f) drawRect(positive, topLeft = androidx.compose.ui.geometry.Offset(center - barWidth - 2f, baseline - incomeHeight), size = androidx.compose.ui.geometry.Size(barWidth, incomeHeight))
                if (spendHeight > 0f) drawRect(negative, topLeft = androidx.compose.ui.geometry.Offset(center + 2f, baseline), size = androidx.compose.ui.geometry.Size(barWidth, spendHeight))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            days.forEach { day -> Text(day.date.dayOfWeek.name.take(1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
internal fun PortfolioValueChart(history: List<InvestmentBalanceSnapshotEntity>, currencyCode: String, compact: Boolean = false) {
    val points = portfolioChartPoints(history)
    if (points.size < 2) {
        Text("Record two dated balances to see cost and value trend.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val valueColor = MaterialTheme.colorScheme.primary
    val costColor = MaterialTheme.colorScheme.tertiary
    val allValues = points.flatMap { listOf(it.costPaise, it.valuePaise) }
    val min = allValues.minOrNull() ?: 0L
    val max = allValues.maxOrNull() ?: (min + 1L)
    val spread = (max - min).coerceAtLeast(1L).toFloat()
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("PORTFOLIO TREND", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Cost ${formatMoney(points.last().costPaise, currencyCode)} · Value ${formatMoney(points.last().valuePaise, currencyCode)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().height(if (compact) 76.dp else 130.dp)) {
            fun line(values: List<Long>, color: Color) {
                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = size.width * index / (values.size - 1)
                    val y = size.height - ((value - min) / spread * size.height)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = if (compact) 4f else 5f, cap = StrokeCap.Round))
            }
            line(points.map { it.costPaise }, costColor)
            line(points.map { it.valuePaise }, valueColor)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(points.first().date.month.name.take(3), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(points.last().date.month.name.take(3), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("— Cost", style = MaterialTheme.typography.labelSmall, color = costColor)
            Text("— Value", style = MaterialTheme.typography.labelSmall, color = valueColor)
        }
    }
}

@Composable
internal fun NavigationGlyph(glyph: String) {
    Text(glyph, style = MaterialTheme.typography.titleMedium)
}

