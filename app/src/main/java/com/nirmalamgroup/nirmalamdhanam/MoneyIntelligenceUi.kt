package com.nirmalamgroup.nirmalamdhanam

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nirmalamgroup.nirmalamdhanam.data.local.AccountKind
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionDirection
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.*
import java.time.LocalDate

@Composable
internal fun MoneyIntelligenceOverview(state: MvpFinanceState) {
    val today = LocalDate.now()
    val latestInvestments = remember(state.investmentHistory) {
        state.investmentHistory.groupBy { it.accountId }.values.mapNotNull { rows -> rows.maxByOrNull { it.asOfEpochDay } }
    }
    val pace = remember(state.allTransactions, today) {
        MoneyIntelligenceEngine.spendingPace(state.allTransactions, today)
    }
    val recurring = remember(state.allTransactions, today) {
        MoneyIntelligenceEngine.recurringMoneyRadar(state.allTransactions, today)
    }
    val runway = remember(state.accountBalances, state.allTransactions, state.categories, today) {
        MoneyIntelligenceEngine.reserveRunway(state.accountBalances, state.allTransactions, state.categories, today)
    }
    val concentration = remember(state.accounts, latestInvestments) {
        MoneyIntelligenceEngine.portfolioConcentration(state.accounts, latestInvestments)
    }
    val latestByAccount = remember(latestInvestments) { latestInvestments.associateBy { it.accountId } }
    val overdueCheckIns = remember(state.accounts, latestByAccount, today) {
        state.accounts.count { account ->
            account.kind == AccountKind.INVESTMENT && !account.isArchived &&
                (latestByAccount[account.id]?.let { today.toEpochDay() - it.asOfEpochDay >= 30 } ?: true)
        }
    }
    val compass = remember(pace, recurring, runway, concentration, overdueCheckIns, state.holdingTank.size) {
        MoneyIntelligenceEngine.moneyCompass(
            spendingPace = pace,
            recurringRadar = recurring,
            runway = runway,
            concentration = concentration,
            overdueInvestmentCheckIns = overdueCheckIns,
            holdingTankCount = state.holdingTank.size,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MoneyCompassCard(compass)
        SpendingPaceCard(pace, state.currencyCode)
        RecurringMoneyRadarCard(recurring, state.currencyCode)
        ReserveRunwayCard(runway, state.currencyCode)
        PortfolioConcentrationCard(concentration, state.currencyCode)
    }
}

@Composable
private fun MoneyCompassCard(items: List<MoneyCompassItem>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OneLineText("Money Compass", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "A short, deterministic priority list from the financial facts already in Dhanam.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = when (item.tone) {
                            MoneyCompassTone.ACTION -> MaterialTheme.colorScheme.errorContainer
                            MoneyCompassTone.REVIEW -> MaterialTheme.colorScheme.tertiaryContainer
                            MoneyCompassTone.CALM -> MaterialTheme.colorScheme.secondaryContainer
                        },
                    ) {
                        Text("${index + 1}", modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelLarge)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        OneLineText(item.title, style = MaterialTheme.typography.titleSmall)
                        Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun SpendingPaceCard(pace: SpendingPace, currencyCode: String) {
    FeatureCard(
        eyebrow = "SPENDING PACE",
        title = "Are you spending faster than usual?",
        supporting = "Uses only confirmed non-investment expenses. Projection is a pace estimate, not a budget.",
    ) {
        MetricRow(
            leftLabel = "Month to date",
            leftValue = formatMoney(pace.monthToDatePaise, currencyCode),
            rightLabel = "At current pace",
            rightValue = formatMoney(pace.projectedMonthPaise, currencyCode),
        )
        pace.priorMonthlyAveragePaise?.let { average ->
            val variance = pace.variancePercent ?: 0.0
            Text(
                "Recent 3-month average ${formatMoney(average, currencyCode)} · projected ${if (variance >= 0) "+" else ""}${"%.0f".format(variance)}% vs average",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } ?: Text(
            "Build at least one complete prior month to create a useful baseline.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { (pace.elapsedDays.toFloat() / pace.daysInMonth).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("${pace.elapsedDays} of ${pace.daysInMonth} days elapsed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecurringMoneyRadarCard(radar: RecurringMoneyRadar, currencyCode: String) {
    FeatureCard(
        eyebrow = "RECURRING MONEY RADAR",
        title = "Patterns that repeat without a subscription list",
        supporting = "Detects weekly and monthly patterns from your own ledger history. Nothing is imported from a third party.",
    ) {
        MetricRow(
            leftLabel = "Likely monthly in",
            leftValue = formatMoney(radar.monthlyInflowPaise, currencyCode),
            rightLabel = "Likely monthly out",
            rightValue = formatMoney(radar.monthlyOutflowPaise, currencyCode),
        )
        if (radar.items.isEmpty()) {
            Text("No stable recurring pattern has enough evidence yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            radar.items.take(5).forEach { item ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        OneLineText(item.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${item.cadence.name.lowercase().replaceFirstChar { it.uppercase() }} · ${item.occurrences} observations · ${item.confidencePercent}% pattern confidence",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        formatMoney(if (item.direction == TransactionDirection.CREDIT) item.typicalAmountPaise else -item.typicalAmountPaise, currencyCode, includeSign = true),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReserveRunwayCard(runway: ReserveRunway, currencyCode: String) {
    FeatureCard(
        eyebrow = "RESERVE RUNWAY",
        title = "How much breathing room is recorded?",
        supporting = "Compares savings + emergency Khatas with the last ${runway.evidenceDays} days of NEED spending.",
    ) {
        MetricRow(
            leftLabel = "Recorded reserve",
            leftValue = formatMoney(runway.reservePaise, currencyCode),
            rightLabel = "Essential monthly burn",
            rightValue = runway.essentialMonthlyBurnPaise?.let { formatMoney(it, currencyCode) } ?: "—",
        )
        val months = runway.runwayMonths
        if (months == null) {
            Text("Not enough essential-spending evidence to calculate runway.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            OneLineText("About ${"%.1f".format(months)} months of runway", style = MaterialTheme.typography.titleMedium)
            Text("This is a resilience view, not a recommendation to hold a particular reserve size.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PortfolioConcentrationCard(concentration: PortfolioConcentration, currencyCode: String) {
    FeatureCard(
        eyebrow = "CONCENTRATION LENS",
        title = "See where portfolio dependence is clustered",
        supporting = "Uses the latest recorded investment check-in for each active Nivesha.",
    ) {
        if (concentration.portfolioValuePaise <= 0L) {
            Text("Add investment check-ins to build the concentration view.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            OneLineText(formatMoney(concentration.portfolioValuePaise, currencyCode), style = MaterialTheme.typography.headlineSmall)
            Text("Recorded portfolio value", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            concentration.largestHoldingSharePercent?.let { share ->
                DetailLine("Largest holding", "${concentration.largestHoldingName ?: "—"} · ${"%.0f".format(share)}%")
            }
            concentration.largestAssetClassSharePercent?.let { share ->
                DetailLine("Largest asset class", "${concentration.largestAssetClass?.name?.replace('_', ' ') ?: "—"} · ${"%.0f".format(share)}%")
            }
            concentration.targetDriftPercent?.let { drift ->
                DetailLine("Aggregate target drift", "${"%.1f".format(drift)} percentage points")
            }
            Text("Concentration is descriptive; Dhanam does not issue buy/sell or rebalance instructions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FeatureCard(
    eyebrow: String,
    title: String,
    supporting: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            OneLineText(eyebrow, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun MetricRow(leftLabel: String, leftValue: String, rightLabel: String, rightValue: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(leftLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OneLineText(leftValue, style = MaterialTheme.typography.titleSmall)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(rightLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OneLineText(rightValue, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
