package com.nirmalamgroup.nirmalamdhanam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nirmalamgroup.nirmalamdhanam.data.local.AccountEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.AccountKind
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionDirection
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import androidx.compose.material3.ExposedDropdownMenuAnchorType
private fun insightMoney(paise: Long, currencyCode: String): String = MoneyFormatter.format(paise, currencyCode)

@Composable
internal fun WhatChangedCard(state: MvpFinanceState) {
    val nowEpochMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(60_000L)
            value = System.currentTimeMillis()
        }
    }
    val summary = remember(
        state.accounts,
        state.accountBalances,
        state.allTransactions,
        state.investmentHistory,
        state.netWorthHistory,
        state.goals,
        state.goalAllocations,
        state.holdingTank,
        state.cashPaise,
        state.changesSinceEpochMs,
        nowEpochMs,
    ) {
        WhatChangedEngine.build(
            accounts = state.accounts,
            balances = state.accountBalances,
            transactions = state.allTransactions,
            investmentHistory = state.investmentHistory,
            netWorthHistory = state.netWorthHistory,
            goals = state.goals,
            goalAllocations = state.goalAllocations,
            holdingTank = state.holdingTank,
            availableCashPaise = state.cashPaise,
            sinceEpochMs = state.changesSinceEpochMs,
            nowEpochMs = nowEpochMs,
        )
    }
    if (summary.isEmpty) return

    var expanded by remember { mutableStateOf(false) }
    val visibleSections = if (expanded) {
        summary.sections
    } else {
        val attention = summary.sections.firstOrNull { it.kind == WhatChangedKind.ATTENTION }
        val normal = summary.sections.filterNot { it.kind == WhatChangedKind.ATTENTION }
        normal.take(if (attention == null) 3 else 2) + listOfNotNull(attention)
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "What changed?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (summary.eventCount > 0) {
                            "${summary.eventCount} new financial ${if (summary.eventCount == 1) "event" else "events"} since your last visit"
                        } else {
                            "No new ledger events; current attention items are shown"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (summary.attentionCount > 0) {
                    AssistChip(
                        onClick = { expanded = true },
                        label = { OneLineText("${summary.attentionCount} to review", style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }

            visibleSections.forEach { section ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                WhatChangedSectionSummary(
                    section = section,
                    currencyCode = state.currencyCode,
                    showItems = expanded,
                )
            }

            val hasHiddenContent = summary.sections.size > 3 || summary.sections.any { it.items.isNotEmpty() }
            if (hasHiddenContent) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.align(androidx.compose.ui.Alignment.End),
                ) {
                    DhanamActionText(if (expanded) "Less" else "Review")
                }
            }
        }
    }
}

@Composable
private fun WhatChangedSectionSummary(
    section: WhatChangedSection,
    currencyCode: String,
    showItems: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (section.kind == WhatChangedKind.ATTENTION) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                Text(
                    text = section.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            section.metricPaise?.let { metric ->
                Spacer(Modifier.width(12.dp))
                Text(
                    text = whatChangedMoney(metric, currencyCode, section.metricTone),
                    style = MaterialTheme.typography.titleSmall,
                    color = whatChangedAmountColor(section.metricTone),
                    maxLines = 1,
                )
            }
        }

        if (showItems) {
            section.items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.bodyMedium)
                        if (item.detail.isNotBlank()) {
                            Text(
                                item.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item.amountPaise?.let { amount ->
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = whatChangedMoney(amount, currencyCode, item.amountTone),
                            style = MaterialTheme.typography.labelLarge,
                            color = whatChangedAmountColor(item.amountTone),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private fun whatChangedMoney(
    paise: Long,
    currencyCode: String,
    tone: WhatChangedAmountTone,
): String = MoneyFormatter.format(
    paise = paise,
    currencyCode = currencyCode,
    includeSign = tone != WhatChangedAmountTone.NEUTRAL,
)

@Composable
private fun whatChangedAmountColor(tone: WhatChangedAmountTone) = when (tone) {
    WhatChangedAmountTone.POSITIVE -> MaterialTheme.colorScheme.primary
    WhatChangedAmountTone.NEGATIVE -> MaterialTheme.colorScheme.error
    WhatChangedAmountTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun FinancialHealthCard(
    health: FinancialHealth,
    currencyCode: String,
) {
    var expandedComponentId by remember { mutableStateOf<String?>(null) }
    val overallLabel = healthBandLabel(health.overallBand)

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Financial health",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Local, explainable signals from your own records",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (health.scoredComponentCount == 0) "—" else "${health.overallScore}/100",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = overallLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = healthBandColor(health.overallBand),
                    )
                }
            }

            if (health.scoredComponentCount < health.components.size) {
                Text(
                    text = "${health.scoredComponentCount} of ${health.components.size} dimensions currently have enough evidence to contribute to the overall score.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            FinancialHealthGraphView(health.graph, currencyCode)

            health.components.forEach { component ->
                val expanded = expandedComponentId == component.id
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                OneLineText(component.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = healthBandLabel(component.band),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = healthBandColor(component.band),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = if (component.isScorable) "${component.score}/100" else "Not scored",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (component.isScorable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        LinearProgressIndicator(
                            progress = { if (component.isScorable) (component.score / 100f).coerceIn(0f, 1f) else 0f },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (component.isScorable) healthBandColor(component.band) else MaterialTheme.colorScheme.outline,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeCap = StrokeCap.Round,
                        )

                        Text(
                            text = component.explanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AssistChip(
                                onClick = { expandedComponentId = component.id },
                                label = { OneLineText(healthTrendLabel(component.trend), style = MaterialTheme.typography.labelMedium) },
                            )
                            TextButton(onClick = { expandedComponentId = if (expanded) null else component.id }) {
                                DhanamActionText(if (expanded) "Hide" else "Details")
                            }
                        }

                        if (expanded) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            Text("How it is calculated", style = MaterialTheme.typography.labelLarge)
                            Text(
                                component.calculation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Text("Evidence", style = MaterialTheme.typography.labelLarge)
                            component.evidence.forEach { evidence ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Text(
                                        evidence.label,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = evidence.amountPaise?.let { insightMoney(it, currencyCode) }
                                            ?: evidence.value.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }

                            Text("Trend", style = MaterialTheme.typography.labelLarge)
                            Text(
                                component.trendExplanation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        "Practical next step",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                    Text(
                                        component.nextAction,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Text(
                text = "The score is a local reflection, not a credit score or financial recommendation. Missing evidence is shown as not scored rather than guessed.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun FinancialHealthGraphView(
    graph: FinancialHealthGraph,
    currencyCode: String,
) {
    val nodes = graph.nodes.associateBy { it.id }
    fun node(id: String) = nodes.getValue(id)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OneLineText("How your money connects", style = MaterialTheme.typography.titleSmall)
            Text(
                "Income → spending/savings → reserves & investments → net worth; liabilities reduce net worth.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HealthGraphRow(listOf(node("income"), node("spending"), node("savings")), currencyCode)
            Text("↓                         ↓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            HealthGraphRow(listOf(node("reserve"), node("investments"), node("liabilities")), currencyCode)
            Text("                  ↓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            HealthGraphRow(listOf(node("networth")), currencyCode)
        }
    }
}

@Composable
private fun HealthGraphRow(nodes: List<HealthGraphNode>, currencyCode: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        nodes.forEachIndexed { index, node ->
            Surface(
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(node.label, style = MaterialTheme.typography.labelSmall)
                    Text(insightMoney(node.amountPaise, currencyCode), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    Text(node.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
            if (index < nodes.lastIndex) Text("→", color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun InsightEvidenceDetails(
    insight: FinancialInsight,
    state: MvpFinanceState,
) {
    val transactions = remember(insight.evidenceTransactionIds, state.allTransactions) {
        val ids = insight.evidenceTransactionIds.toSet()
        state.allTransactions.filter { it.id in ids }.sortedByDescending { it.occurredAtEpochMs }
    }
    val snapshots = remember(insight.evidenceSnapshotIds, state.investmentHistory) {
        val ids = insight.evidenceSnapshotIds.toSet()
        state.investmentHistory.filter { it.id in ids }.sortedByDescending { it.asOfEpochDay }
    }
    val accountNames = remember(state.accounts) { state.accounts.associate { it.id to it.name } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("Why am I seeing this?", style = MaterialTheme.typography.labelLarge)
        insight.reasoningSteps.forEach { step ->
            Text("• $step", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (transactions.isNotEmpty()) {
            Text("Underlying transactions", style = MaterialTheme.typography.labelLarge)
            transactions.take(12).forEach { tx ->
                val signed = if (tx.direction == TransactionDirection.CREDIT) tx.amountPaise else -tx.amountPaise
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(tx.payee ?: tx.merchant ?: tx.category ?: "Transaction", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${formatDate(tx.occurredAtEpochMs, state.dateFormatPreference)} · ${tx.category ?: "Uncategorised"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        tx.description?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                    Text(insightMoney(signed, state.currencyCode), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
            }
            if (transactions.size > 12) Text("+ ${transactions.size - 12} more supporting transactions", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (snapshots.isNotEmpty()) {
            Text("Underlying investment check-ins", style = MaterialTheme.typography.labelLarge)
            snapshots.forEach { snapshot ->
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("${accountNames[snapshot.accountId] ?: "Nivesha"} · ${LocalDate.ofEpochDay(snapshot.asOfEpochDay)}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Value ${insightMoney(snapshot.currentValuePaise, state.currencyCode)} · cost ${insightMoney(snapshot.totalCostPaise, state.currencyCode)} · movement ${insightMoney(snapshot.marketMovementPaise, state.currencyCode)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            "All reasoning is derived locally from these structured records; no hidden score or external model is used.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun healthBandColor(band: HealthBand) = when (band) {
    HealthBand.STRONG -> MaterialTheme.colorScheme.primary
    HealthBand.STEADY -> MaterialTheme.colorScheme.tertiary
    HealthBand.WATCH -> MaterialTheme.colorScheme.secondary
    HealthBand.ATTENTION -> MaterialTheme.colorScheme.error
    HealthBand.NOT_SCORED -> MaterialTheme.colorScheme.outline
}

private fun healthBandLabel(band: HealthBand): String = when (band) {
    HealthBand.STRONG -> "Strong"
    HealthBand.STEADY -> "Steady"
    HealthBand.WATCH -> "Worth watching"
    HealthBand.ATTENTION -> "Needs attention"
    HealthBand.NOT_SCORED -> "Not enough evidence"
}

private fun healthTrendLabel(trend: HealthTrend): String = when (trend) {
    HealthTrend.IMPROVING -> "Improving"
    HealthTrend.STABLE -> "Stable"
    HealthTrend.DECLINING -> "Declining"
    HealthTrend.UNKNOWN -> "Trend unavailable"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InsightsScreen(
    state: MvpFinanceState,
    onBack: () -> Unit,
    onCreateGoal: (String, String, String, String?) -> Unit,
    onArchiveGoal: (String) -> Unit,
    onSetGoalAllocation: (String, String, String) -> Unit,
    onRemoveGoalAllocation: (String) -> Unit,
    onRecordTransaction: (String, String, String, String, TransactionDirection, String, Long) -> Unit
) {
    val latest = remember(state.investmentHistory) {
        state.investmentHistory.groupBy { it.accountId }.values.mapNotNull { rows -> rows.maxByOrNull { it.asOfEpochDay } }
    }
    val health = remember(state.accounts, state.accountBalances, state.allTransactions, state.investmentHistory, latest) {
        FinancialHealthCalculator.calculate(
            accounts = state.accounts,
            balances = state.accountBalances,
            transactions = state.allTransactions,
            latestInvestments = latest,
            investmentHistory = state.investmentHistory,
        )
    }
    val insights = remember(state.allTransactions, state.categories, state.investmentHistory) {
        ExplainableInsightEngine.build(state.allTransactions, state.categories, state.investmentHistory)
    }
    val goalProgress = remember(
        state.goals,
        state.goalAllocations,
        state.accountBalances,
        state.accounts,
        state.allTransactions,
        state.investmentHistory,
        latest,
    ) {
        GoalProgressCalculator.calculate(
            goals = state.goals,
            allocations = state.goalAllocations,
            balances = state.accountBalances,
            latestInvestments = latest,
            accounts = state.accounts,
            transactions = state.allTransactions,
            investmentHistory = state.investmentHistory,
        )
    }
    val activeAllocationUsageBps = remember(state.goals, state.goalAllocations) {
        GoalAllocationPolicy.activeUsageBpsByAccount(state.goals, state.goalAllocations)
    }
    val timeline = remember(
        state.allTransactions,
        state.investmentHistory,
        state.netWorthHistory,
        state.goals,
        state.goalAllocations,
        state.accounts,
        insights,
    ) {
        FinancialTimelineBuilder.build(
            transactions = state.allTransactions,
            snapshots = state.investmentHistory,
            accounts = state.accounts,
            netWorthSnapshots = state.netWorthHistory,
            goals = state.goals,
            goalAllocations = state.goalAllocations,
            insights = insights,
        )
    }
    var expandedInsight by remember { mutableStateOf<String?>(null) }
    var showGoalDialog by remember { mutableStateOf(false) }
    var selectedGoalId by remember { mutableStateOf<String?>(null) }
    var timeMachineDate by remember { mutableStateOf(LocalDate.now().toString()) }
    val historical = remember(timeMachineDate, state.accounts, state.allTransactions, state.investmentHistory) {
        runCatching { LocalDate.parse(timeMachineDate) }.getOrNull()?.let {
            FinancialTimeMachine.reconstruct(it, state.accounts, state.allTransactions, state.investmentHistory)
        }
    }
    var copilotInput by remember { mutableStateOf("") }
    var copilotResult by remember { mutableStateOf<CopilotResult?>(null) }
    var showTimelineStory by remember { mutableStateOf(false) }

    if (showTimelineStory) {
        FinancialTimelineStoryScreen(
            events = timeline,
            currencyCode = state.currencyCode,
            dateFormatPreference = state.dateFormatPreference,
            onBack = { showTimelineStory = false },
        )
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            DhanamScreenTopBar(
                title = "Insights",
                subtitle = "Financial health and intelligence",
                onBack = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 20.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                FinancialHealthCard(
                    health = health,
                    currencyCode = state.currencyCode,
                )
            }

            item {
                OneLineText("Explainable signals", style = MaterialTheme.typography.titleMedium)
                if (insights.isEmpty()) Text("No unusual pattern needs attention right now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    insights.take(5).forEach { insight ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                OneLineText(insight.title, style = MaterialTheme.typography.titleSmall)
                                Text(insight.explanation, style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { expandedInsight = if (expandedInsight == insight.id) null else insight.id }) { DhanamActionText("Why?") }
                                if (expandedInsight == insight.id) {
                                    InsightEvidenceDetails(
                                        insight = insight,
                                        state = state,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        OneLineText("Goals", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Progress reuses linked Khata and Nivesha values — goals never own a second balance.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { showGoalDialog = true }) { DhanamActionText("Add goal") }
                }

                if (goalProgress.isEmpty()) {
                    Text(
                        text = "Link an existing account or investment to a goal; Dhanam reuses the same balance instead of creating another ledger.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        goalProgress.forEach { progress ->
                            GoalProgressCard(
                                progress = progress,
                                currencyCode = state.currencyCode,
                                dateFormatPreference = state.dateFormatPreference,
                                onDetails = { selectedGoalId = progress.goal.id },
                            )
                        }
                    }
                }
            }

            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OneLineText("Financial time machine", style = MaterialTheme.typography.titleMedium)
                        Text("Reconstruct balances from your ledger and the latest investment check-in on or before a date.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(timeMachineDate, { timeMachineDate = it }, Modifier.fillMaxWidth(), label = { OneLineText("Date · YYYY-MM-DD") }, singleLine = true)
                        historical?.let { snapshot ->
                            OneLineText("Net worth  ${insightMoney(snapshot.netWorthPaise, state.currencyCode)}", style = MaterialTheme.typography.titleSmall)
                            Text("Cash & reserves ${insightMoney(snapshot.cashAndReservesPaise, state.currencyCode)} · Investments ${insightMoney(snapshot.investmentValuePaise, state.currencyCode)} · Liabilities ${insightMoney(snapshot.liabilitiesPaise, state.currencyCode)}", style = MaterialTheme.typography.bodySmall)
                        } ?: Text("Enter a valid date.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OneLineText("Private local copilot", style = MaterialTheme.typography.titleMedium)
                        Text("Read questions and command parsing stay on device. A write is only a preview until you confirm it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = copilotInput,
                            onValueChange = { copilotInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { OneLineText("Ask Dhanam") },
                            placeholder = { OneLineText("Travel spend this year") },
                            singleLine = true,
                        )
                        Button(onClick = { copilotResult = LocalFinancialCopilot.interpret(copilotInput, state.allTransactions, state.accounts, state.currencyCode) }, enabled = copilotInput.isNotBlank()) { DhanamActionText("Ask") }
                        when (val result = copilotResult) {
                            is CopilotResult.Answer -> Text(result.text)
                            is CopilotResult.Unsupported -> Text(result.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            is CopilotResult.TransactionPreview -> {
                                Text("Preview only", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text("${if (result.direction == TransactionDirection.CREDIT) "Income" else "Expense"} ${insightMoney(result.amountPaise, state.currencyCode)} · ${result.payee} · ${result.accountName}")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        onRecordTransaction(BigDecimal(result.amountPaise).movePointLeft(2).toPlainString(), result.payee, result.category, "Created from confirmed local copilot preview", result.direction, result.accountId, result.occurredAtEpochMs)
                                        copilotResult = CopilotResult.Answer("Confirmed. The transaction was submitted through the normal Dhanam transaction rules.")
                                    }) { DhanamActionText("Confirm") }
                                    TextButton(onClick = { copilotResult = null }) { DhanamActionText("Cancel") }
                                }
                            }
                            null -> Unit
                        }
                    }
                }
            }

            item {
                UnifiedFinancialTimelineCard(
                    events = timeline,
                    currencyCode = state.currencyCode,
                    dateFormatPreference = state.dateFormatPreference,
                    onOpenStory = { showTimelineStory = true },
                )
            }
        }
    }

    selectedGoalId?.let { goalId ->
        goalProgress.firstOrNull { it.goal.id == goalId }?.let { progress ->
            GoalDetailSheet(
                progress = progress,
                currencyCode = state.currencyCode,
                dateFormatPreference = state.dateFormatPreference,
                linkableAccounts = state.accounts.filterNot { it.kind == AccountKind.CREDIT || it.isArchived },
                activeAllocationUsageBps = activeAllocationUsageBps,
                onSetAllocation = onSetGoalAllocation,
                onRemoveAllocation = onRemoveGoalAllocation,
                onDismiss = { selectedGoalId = null },
                onArchive = {
                    onArchiveGoal(progress.goal.id)
                    selectedGoalId = null
                },
            )
        }
    }

    if (showGoalDialog) GoalDialog(state, onDismiss = { showGoalDialog = false }) { name, target, date, accountId ->
        onCreateGoal(name, target, date, accountId)
        showGoalDialog = false
    }
}

@Composable
private fun GoalProgressCard(
    progress: GoalProgress,
    currencyCode: String,
    dateFormatPreference: com.nirmalamgroup.nirmalamdhanam.data.local.DateFormatPreference,
    onDetails: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    OneLineText(progress.goal.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        goalScheduleLabel(progress.scheduleStatus),
                        style = MaterialTheme.typography.labelMedium,
                        color = goalScheduleColor(progress.scheduleStatus),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "${progress.progressPercent.toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            LinearProgressIndicator(
                progress = { (progress.progressPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Round,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${insightMoney(progress.fundedPaise, currencyCode)} of ${insightMoney(progress.targetPaise, currencyCode)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (progress.remainingPaise > 0L) {
                    Text(
                        "${insightMoney(progress.remainingPaise, currencyCode)} left",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val targetDate = progress.goal.targetDateEpochDay?.let(LocalDate::ofEpochDay)
            if (targetDate != null) {
                Text(
                    text = goalTargetLine(progress, targetDate, dateFormatPreference),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            progress.observedMonthlyFundingPaise?.let { monthly ->
                Text(
                    text = "Observed funding pace · ${insightMoney(monthly, currencyCode)} / month",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${progress.fundingSources.size} linked ${if (progress.fundingSources.size == 1) "source" else "sources"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onDetails) { DhanamActionText("Details") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalDetailSheet(
    progress: GoalProgress,
    currencyCode: String,
    dateFormatPreference: com.nirmalamgroup.nirmalamdhanam.data.local.DateFormatPreference,
    linkableAccounts: List<AccountEntity>,
    activeAllocationUsageBps: Map<String, Int>,
    onSetAllocation: (String, String, String) -> Unit,
    onRemoveAllocation: (String) -> Unit,
    onDismiss: () -> Unit,
    onArchive: () -> Unit,
) {
    var fundingMenuExpanded by remember(progress.goal.id) { mutableStateOf(false) }
    var selectedFundingAccountId by remember(progress.goal.id, linkableAccounts) {
        mutableStateOf(linkableAccounts.firstOrNull()?.id)
    }
    var allocationPercent by remember(progress.goal.id) { mutableStateOf("100") }

    val selectedFundingAccount = linkableAccounts.firstOrNull { it.id == selectedFundingAccountId }
    val currentSelectedSource = progress.fundingSources.firstOrNull { it.accountId == selectedFundingAccountId }
    val usedByAllActiveGoalsBps = selectedFundingAccountId?.let { activeAllocationUsageBps[it] } ?: 0
    val usedByOtherGoalsBps = (usedByAllActiveGoalsBps - (currentSelectedSource?.allocationBps ?: 0)).coerceAtLeast(0)
    val maxAvailableBps = (10_000 - usedByOtherGoalsBps).coerceIn(0, 10_000)
    val enteredBps = runCatching {
        java.math.BigDecimal(allocationPercent.trim())
            .multiply(java.math.BigDecimal(100))
            .setScale(0, java.math.RoundingMode.HALF_UP)
            .intValueExact()
    }.getOrNull()
    val allocationValid = selectedFundingAccount != null && enteredBps != null && enteredBps in 1..maxAvailableBps

    LaunchedEffect(selectedFundingAccountId, currentSelectedSource?.allocationBps, maxAvailableBps) {
        allocationPercent = currentSelectedSource?.allocationPercent?.let { "%.1f".format(it) }
            ?: "%.1f".format(maxAvailableBps / 100.0)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OneLineText(progress.goal.name, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        goalScheduleLabel(progress.scheduleStatus),
                        style = MaterialTheme.typography.labelLarge,
                        color = goalScheduleColor(progress.scheduleStatus),
                    )
                    progress.goal.note?.takeIf { it.isNotBlank() }?.let { note ->
                        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("FUNDED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OneLineText(insightMoney(progress.fundedPaise, currencyCode), style = MaterialTheme.typography.titleLarge)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("TARGET", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OneLineText(insightMoney(progress.targetPaise, currencyCode), style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        LinearProgressIndicator(
                            progress = { (progress.progressPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeCap = StrokeCap.Round,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${progress.progressPercent.toInt()}% complete", style = MaterialTheme.typography.labelLarge)
                            Text(
                                if (progress.remainingPaise == 0L) "Target covered" else "${insightMoney(progress.remainingPaise, currencyCode)} remaining",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OneLineText("Linked funding", style = MaterialTheme.typography.titleMedium)
                    if (progress.fundingSources.isEmpty()) {
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Text(
                                "No Khata or Nivesha source is linked. This goal therefore has no derived funding balance.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        progress.fundingSources.forEach { source ->
                            GoalFundingSourceCard(
                                source = source,
                                currencyCode = currencyCode,
                                dateFormatPreference = dateFormatPreference,
                                onUnlink = { onRemoveAllocation(source.allocationId) },
                            )
                        }
                    }
                }
            }

            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OneLineText("Manage linked funding", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Linking only assigns a percentage of an existing source to this goal. It never moves money or creates another balance.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (linkableAccounts.isEmpty()) {
                            Text(
                                "No active non-liability Khata or Nivesha source is available to link.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            ExposedDropdownMenuBox(
                                expanded = fundingMenuExpanded,
                                onExpandedChange = { fundingMenuExpanded = !fundingMenuExpanded },
                            ) {
                                OutlinedTextField(
                                    value = selectedFundingAccount?.name.orEmpty(),
                                    onValueChange = {},
                                    modifier = Modifier
                                        .menuAnchor(
                                            type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                            enabled = true,
                                        )
                                        .fillMaxWidth(),
                                    readOnly = true,
                                    label = { OneLineText("Funding source") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(fundingMenuExpanded) },
                                    singleLine = true,
                                )
                                ExposedDropdownMenu(
                                    expanded = fundingMenuExpanded,
                                    onDismissRequest = { fundingMenuExpanded = false },
                                ) {
                                    linkableAccounts.forEach { account ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(account.name)
                                                    Text(
                                                        goalAccountKindLabel(account.kind),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            },
                                            onClick = {
                                                selectedFundingAccountId = account.id
                                                fundingMenuExpanded = false
                                            },
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = allocationPercent,
                                onValueChange = { allocationPercent = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { OneLineText("Allocation (%)") },
                                supportingText = {
                                    Text(
                                        "Up to ${"%.1f".format(maxAvailableBps / 100.0)}% is available for this source across active goals.",
                                    )
                                },
                                isError = enteredBps != null && enteredBps !in 1..maxAvailableBps,
                                singleLine = true,
                            )

                            Button(
                                onClick = {
                                    selectedFundingAccountId?.let { accountId ->
                                        onSetAllocation(progress.goal.id, accountId, allocationPercent)
                                    }
                                },
                                enabled = allocationValid,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                DhanamActionText(if (currentSelectedSource == null) "Link" else "Update")
                            }
                        }
                    }
                }
            }

            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OneLineText("Target & projection", style = MaterialTheme.typography.titleMedium)
                        progress.goal.targetDateEpochDay?.let { targetEpochDay ->
                            val targetDate = LocalDate.ofEpochDay(targetEpochDay)
                            GoalDetailRow("Target date", formatDate(targetDate, dateFormatPreference))
                            GoalDetailRow("Date status", goalDaysLabel(progress.daysToTarget))
                        } ?: GoalDetailRow("Target date", "No date set")

                        progress.observedFundingPaise?.let { observed ->
                            GoalDetailRow(
                                "Observed funding",
                                "${insightMoney(observed, currencyCode)} over ${progress.observedFundingDays} days",
                            )
                        }
                        progress.observedMonthlyFundingPaise?.let { pace ->
                            GoalDetailRow("Observed monthly pace", insightMoney(pace, currencyCode))
                        }
                        progress.projectedCompletionDate?.let { date ->
                            GoalDetailRow("Projected completion", formatDate(date, dateFormatPreference))
                        }
                        Text(
                            progress.projectionExplanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OneLineText("Practical next step", style = MaterialTheme.typography.titleMedium)
                        Text(progress.nextAction, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OneLineText("How Dhanam calculates this", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Current goal funding is each linked source's current value multiplied by its allocation percentage. " +
                            "The completion projection uses observed net confirmed transaction movement in linked cash accounts and Nivesha contributions from recent history. " +
                            "Investment market movement is not extrapolated into the future, and no separate goal ledger is created.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                OutlinedButton(
                    onClick = onArchive,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    DhanamActionText("Archive goal")
                }
            }
        }
    }
}

@Composable
private fun GoalFundingSourceCard(
    source: GoalFundingSource,
    currencyCode: String,
    dateFormatPreference: com.nirmalamgroup.nirmalamdhanam.data.local.DateFormatPreference,
    onUnlink: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    OneLineText(source.accountName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${"%.1f".format(source.allocationPercent)}% linked${source.accountKind?.let { " · ${goalAccountKindLabel(it)}" } ?: ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                OneLineText(insightMoney(source.fundedPaise, currencyCode), style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "Source value ${insightMoney(source.sourceValuePaise, currencyCode)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            source.latestValuationEpochDay?.let { epochDay ->
                Text(
                    "Latest Nivesha valuation ${formatDate(LocalDate.ofEpochDay(epochDay), dateFormatPreference)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (source.isSharedAcrossGoals) {
                Text(
                    "This source is shared across active goals · ${"%.1f".format(source.totalActiveGoalAllocationBps / 100.0)}% allocated in total.",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (source.isOverAllocated) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onUnlink, modifier = Modifier.align(Alignment.End)) {
                DhanamActionText("Unlink")
            }
        }
    }
}

@Composable
private fun GoalDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2)
    }
}

private fun goalTargetLine(
    progress: GoalProgress,
    targetDate: LocalDate,
    preference: com.nirmalamgroup.nirmalamdhanam.data.local.DateFormatPreference,
): String = "Target ${formatDate(targetDate, preference)} · ${goalDaysLabel(progress.daysToTarget)}"

private fun goalDaysLabel(days: Long?): String = when {
    days == null -> "No target date"
    days < 0L -> "Overdue by ${-days} day${if (days == -1L) "" else "s"}"
    days == 0L -> "Due today"
    days == 1L -> "1 day left"
    else -> "$days days left"
}

private fun goalScheduleLabel(status: GoalScheduleStatus): String = when (status) {
    GoalScheduleStatus.FUNDED -> "Funded"
    GoalScheduleStatus.ON_TRACK -> "On track"
    GoalScheduleStatus.AT_RISK -> "At risk"
    GoalScheduleStatus.OVERDUE -> "Overdue"
    GoalScheduleStatus.NO_TARGET_DATE -> "Amount goal"
    GoalScheduleStatus.NOT_ENOUGH_HISTORY -> "Building history"
    GoalScheduleStatus.NO_POSITIVE_PACE -> "No positive funding pace"
}

@Composable
private fun goalScheduleColor(status: GoalScheduleStatus) = when (status) {
    GoalScheduleStatus.FUNDED, GoalScheduleStatus.ON_TRACK -> MaterialTheme.colorScheme.primary
    GoalScheduleStatus.AT_RISK, GoalScheduleStatus.OVERDUE -> MaterialTheme.colorScheme.error
    GoalScheduleStatus.NO_TARGET_DATE,
    GoalScheduleStatus.NOT_ENOUGH_HISTORY,
    GoalScheduleStatus.NO_POSITIVE_PACE -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun goalAccountKindLabel(kind: AccountKind): String = when (kind) {
    AccountKind.SPENDING -> "Daily"
    AccountKind.SAVINGS -> "Savings"
    AccountKind.EMERGENCY -> "Emergency"
    AccountKind.CREDIT -> "Liability"
    AccountKind.INVESTMENT -> "Nivesha"
}

private enum class TimelineKindFilter(
    val label: String,
    val kind: FinancialEventKind?,
) {
    ALL("All", null),
    CASHFLOW("Cashflow", FinancialEventKind.CASHFLOW),
    INVESTMENTS("Nivesha", FinancialEventKind.INVESTMENT),
    SAMPADA("Sampada", FinancialEventKind.SAMPADA),
    GOALS("Goals", FinancialEventKind.GOAL),
    MILESTONES("Milestones", FinancialEventKind.MILESTONE),
    SIGNALS("Signals", FinancialEventKind.INSIGHT),
}

private enum class TimelineRange(
    val label: String,
    val days: Long?,
) {
    THIRTY_DAYS("30 days", 30),
    NINETY_DAYS("90 days", 90),
    ONE_YEAR("1 year", 365),
    ALL("All time", null),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinancialTimelineStoryScreen(
    events: List<FinancialEvent>,
    currencyCode: String,
    dateFormatPreference: com.nirmalamgroup.nirmalamdhanam.data.local.DateFormatPreference,
    onBack: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            DhanamScreenTopBar(
                title = "Story of my money",
                subtitle = "One chronology of financial facts, milestones, and explainable signals",
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
                TimelineStorySummary(events)
            }
            item {
                UnifiedFinancialTimelineCard(
                    events = events,
                    currencyCode = currencyCode,
                    dateFormatPreference = dateFormatPreference,
                    onOpenStory = null,
                )
            }
        }
    }
}

@Composable
private fun TimelineStorySummary(events: List<FinancialEvent>) {
    val financialFacts = events.count {
        it.kind == FinancialEventKind.CASHFLOW ||
            it.kind == FinancialEventKind.INVESTMENT ||
            it.kind == FinancialEventKind.SAMPADA ||
            it.kind == FinancialEventKind.GOAL
    }
    val milestones = events.count { it.kind == FinancialEventKind.MILESTONE }
    val signals = events.count { it.kind == FinancialEventKind.INSIGHT }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OneLineText("Your financial story", style = MaterialTheme.typography.titleMedium)
            Text(
                "The timeline is rebuilt from your ledger, investment check-ins, Sampada snapshots, goals, and local rules. It never stores a second history.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TimelineSummaryMetric("Facts", financialFacts)
                TimelineSummaryMetric("Milestones", milestones)
                TimelineSummaryMetric("Signals", signals)
            }
        }
    }
}

@Composable
private fun TimelineSummaryMetric(label: String, count: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        OneLineText(count.toString(), style = MaterialTheme.typography.titleLarge)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun UnifiedFinancialTimelineCard(
    events: List<FinancialEvent>,
    currencyCode: String,
    dateFormatPreference: com.nirmalamgroup.nirmalamdhanam.data.local.DateFormatPreference,
    onOpenStory: (() -> Unit)? = null,
) {
    var kindFilter by remember { mutableStateOf(TimelineKindFilter.ALL) }
    var range by remember { mutableStateOf(TimelineRange.NINETY_DAYS) }
    var visibleCount by remember(kindFilter, range) { mutableIntStateOf(12) }

    val today = LocalDate.now()
    val filtered = remember(events, kindFilter, range, today) {
        val cutoff = range.days?.let { today.minusDays(it - 1) }
        events.filter { event ->
            val eventDate = Instant.ofEpochMilli(event.sortEpochMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            (kindFilter.kind == null || event.kind == kindFilter.kind) &&
                (cutoff == null || !eventDate.isBefore(cutoff))
        }
    }
    val visible = filtered.take(visibleCount)

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    OneLineText("Financial timeline", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Transactions, Nivesha check-ins and returns, Sampada snapshots, goals, milestones, and explainable signals in one chronology.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                onOpenStory?.let { open ->
                    TextButton(onClick = open) { DhanamActionText("Story") }
                }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimelineKindFilter.entries.forEach { option ->
                    item {
                        val count = if (option.kind == null) events.size else events.count { it.kind == option.kind }
                        FilterChip(
                            selected = kindFilter == option,
                            onClick = { kindFilter = option },
                            label = { OneLineText("${option.label} $count", style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimelineRange.entries.forEach { option ->
                    item {
                        FilterChip(
                            selected = range == option,
                            onClick = { range = option },
                            label = { OneLineText(option.label, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
            }

            Text(
                text = "${filtered.size} ${if (filtered.size == 1) "event" else "events"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (filtered.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        "No financial facts match this timeline filter.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                var previousDate: LocalDate? = null
                visible.forEach { event ->
                    val eventDate = Instant.ofEpochMilli(event.sortEpochMs)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                    if (eventDate != previousDate) {
                        Text(
                            text = timelineDateLabel(eventDate, dateFormatPreference),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        previousDate = eventDate
                    }
                    TimelineEventRow(event, currencyCode)
                }

                if (visible.size < filtered.size) {
                    OutlinedButton(
                        onClick = { visibleCount += 20 },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        DhanamActionText("More · ${filtered.size - visible.size}")
                    }
                } else if (visibleCount > 12 && filtered.size > 12) {
                    TextButton(
                        onClick = { visibleCount = 12 },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        DhanamActionText("Less")
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineEventRow(
    event: FinancialEvent,
    currencyCode: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = timelineKindContainerColor(event.kind),
            ) {
                Text(
                    text = timelineKindShortLabel(event.kind),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = timelineKindContentColor(event.kind),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(event.title, style = MaterialTheme.typography.labelLarge)
                if (event.detail.isNotBlank()) {
                    Text(
                        event.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            event.amountPaise?.let { amount ->
                Text(
                    text = MoneyFormatter.format(
                        paise = amount,
                        currencyCode = currencyCode,
                        includeSign = event.isSignedAmount,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        !event.isSignedAmount -> MaterialTheme.colorScheme.onSurface
                        amount < 0 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun timelineKindContainerColor(kind: FinancialEventKind) = when (kind) {
    FinancialEventKind.CASHFLOW -> MaterialTheme.colorScheme.secondaryContainer
    FinancialEventKind.INVESTMENT -> MaterialTheme.colorScheme.primaryContainer
    FinancialEventKind.SAMPADA -> MaterialTheme.colorScheme.tertiaryContainer
    FinancialEventKind.GOAL -> MaterialTheme.colorScheme.surfaceVariant
    FinancialEventKind.MILESTONE -> MaterialTheme.colorScheme.primaryContainer
    FinancialEventKind.INSIGHT -> MaterialTheme.colorScheme.errorContainer
}

@Composable
private fun timelineKindContentColor(kind: FinancialEventKind) = when (kind) {
    FinancialEventKind.CASHFLOW -> MaterialTheme.colorScheme.onSecondaryContainer
    FinancialEventKind.INVESTMENT -> MaterialTheme.colorScheme.onPrimaryContainer
    FinancialEventKind.SAMPADA -> MaterialTheme.colorScheme.onTertiaryContainer
    FinancialEventKind.GOAL -> MaterialTheme.colorScheme.onSurfaceVariant
    FinancialEventKind.MILESTONE -> MaterialTheme.colorScheme.onPrimaryContainer
    FinancialEventKind.INSIGHT -> MaterialTheme.colorScheme.onErrorContainer
}

private fun timelineKindShortLabel(kind: FinancialEventKind): String = when (kind) {
    FinancialEventKind.CASHFLOW -> "Cash"
    FinancialEventKind.INVESTMENT -> "Nivesha"
    FinancialEventKind.SAMPADA -> "Sampada"
    FinancialEventKind.GOAL -> "Goal"
    FinancialEventKind.MILESTONE -> "Milestone"
    FinancialEventKind.INSIGHT -> "Signal"
}

private fun timelineDateLabel(
    date: LocalDate,
    preference: com.nirmalamgroup.nirmalamdhanam.data.local.DateFormatPreference,
): String = when (date) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> formatDate(date, preference)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalDialog(state: MvpFinanceState, onDismiss: () -> Unit, onSave: (String, String, String, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val usedAllocationByAccount = GoalAllocationPolicy.activeUsageBpsByAccount(state.goals, state.goalAllocations)
    // New goals created from this compact dialog link 100% of one source. Partially used
    // sources can still be linked later at an available percentage from Goal details.
    val linkable = state.accounts
        .filterNot { it.kind == AccountKind.CREDIT || it.isArchived }
        .filter { (usedAllocationByAccount[it.id] ?: 0) == 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add financial goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { OneLineText("Goal") }, singleLine = true)
                OutlinedTextField(target, { target = it }, Modifier.fillMaxWidth(), label = { OneLineText("Target amount") }, singleLine = true)
                OutlinedTextField(date, { date = it }, Modifier.fillMaxWidth(), label = { OneLineText("Target date") }, singleLine = true)
                ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
                    OutlinedTextField(
                        value = linkable.firstOrNull { it.id == accountId }?.name ?: "Link later",
                        onValueChange = {},
                        modifier = Modifier
                            .menuAnchor(
                                type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                enabled = true
                            )
                            .fillMaxWidth(),
                        readOnly = true,
                        label = { OneLineText("Funded by") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
                    )
                    ExposedDropdownMenu(expanded, { expanded = false }) {
                        DropdownMenuItem(text = { OneLineText("Link later") }, onClick = { accountId = null; expanded = false })
                        linkable.forEach { account -> DropdownMenuItem(text = { OneLineText(account.name) }, onClick = { accountId = account.id; expanded = false }) }
                    }
                }
                if (state.accounts.any { account ->
                        !account.isArchived &&
                            account.kind != AccountKind.CREDIT &&
                            (usedAllocationByAccount[account.id] ?: 0) > 0
                    }) {
                    Text(
                        "Sources already shared with another active goal can be linked later at an available percentage from Goal details.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(name, target, date, accountId) }, enabled = name.isNotBlank() && target.isNotBlank()) { DhanamActionText("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { DhanamActionText("Cancel") } }
    )
}
