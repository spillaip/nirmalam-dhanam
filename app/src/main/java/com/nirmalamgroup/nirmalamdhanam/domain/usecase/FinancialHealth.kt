package com.nirmalamgroup.nirmalamdhanam.domain.usecase

import com.nirmalamgroup.nirmalamdhanam.data.local.AccountBalance
import com.nirmalamgroup.nirmalamdhanam.data.local.AccountEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.AccountKind
import com.nirmalamgroup.nirmalamdhanam.data.local.AssetClass
import com.nirmalamgroup.nirmalamdhanam.data.local.EnvelopeType
import com.nirmalamgroup.nirmalamdhanam.data.local.InvestmentBalanceSnapshotEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionDirection
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class HealthBand { STRONG, STEADY, WATCH, ATTENTION, NOT_SCORED }
enum class HealthTrend { IMPROVING, STABLE, DECLINING, UNKNOWN }

data class HealthEvidence(val label: String, val value: String? = null, val amountPaise: Long? = null)

data class HealthComponent(
    val id: String,
    val name: String,
    val score: Int,
    val explanation: String,
    val calculation: String,
    val evidence: List<HealthEvidence>,
    val nextAction: String,
    val band: HealthBand,
    val trend: HealthTrend,
    val trendExplanation: String,
    val isScorable: Boolean = true,
)

data class HealthGraphNode(
    val id: String,
    val label: String,
    val amountPaise: Long,
    val detail: String,
)

data class HealthGraphEdge(val fromId: String, val toId: String, val label: String)

data class FinancialHealthGraph(
    val nodes: List<HealthGraphNode>,
    val edges: List<HealthGraphEdge>,
)

data class FinancialHealth(
    val overallScore: Int,
    val components: List<HealthComponent>,
    val scoredComponentCount: Int,
    val graph: FinancialHealthGraph,
) {
    val overallBand: HealthBand
        get() = if (scoredComponentCount == 0) HealthBand.NOT_SCORED else healthBand(overallScore)
}

object FinancialHealthCalculator {
    private const val WINDOW_DAYS = 90L

    fun calculate(
        accounts: List<AccountEntity>,
        balances: List<AccountBalance>,
        transactions: List<TransactionEntity>,
        latestInvestments: List<InvestmentBalanceSnapshotEntity>,
        investmentHistory: List<InvestmentBalanceSnapshotEntity> = latestInvestments,
        nowEpochMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): FinancialHealth {
        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate()
        val currentStart = today.minusDays(WINDOW_DAYS - 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val currentEnd = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val previousStart = today.minusDays((WINDOW_DAYS * 2) - 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val confirmed = transactions.filterNot { it.isHoldingTank || it.envelopeType == EnvelopeType.INVESTMENT }
        val current = confirmed.filter { it.occurredAtEpochMs in currentStart until currentEnd }
        val previous = confirmed.filter { it.occurredAtEpochMs in previousStart until currentStart }

        fun income(rows: List<TransactionEntity>) = rows.filter { it.direction == TransactionDirection.CREDIT }.sumOf { it.amountPaise }
        fun expense(rows: List<TransactionEntity>) = rows.filter { it.direction == TransactionDirection.DEBIT }.sumOf { it.amountPaise }
        val currentIncome = income(current)
        val currentExpense = expense(current)
        val previousIncome = income(previous)
        val previousExpense = expense(previous)
        val currentRate = savingsRate(currentIncome, currentExpense)
        val previousRate = savingsRate(previousIncome, previousExpense)

        val cashFlowScorable = currentIncome > 0L
        val cashScore = currentRate?.let(::savingsRateScore) ?: 50
        val cashTrend = scoreTrend(currentRate, previousRate, 2.0)
        val cashFlow = HealthComponent(
            id = "cash_flow", name = "Cash flow", score = cashScore,
            explanation = if (cashFlowScorable) "Shows whether confirmed Aaya is covering non-investment Vyaya with room left over." else "Confirmed recent Aaya is needed before Dhanam can score cash flow.",
            calculation = "Savings rate = (Aaya − Vyaya) ÷ Aaya over 90 calendar days. 35% or more maps to 100/100; zero or negative savings maps to 0/100.",
            evidence = listOf(HealthEvidence("Aaya · 90 days", amountPaise = currentIncome), HealthEvidence("Vyaya · 90 days", amountPaise = currentExpense), HealthEvidence("Savings rate", value = currentRate?.let(::formatPercent) ?: "Not enough income data")),
            nextAction = if ((currentRate ?: 0.0) >= 10.0) "Protect the positive cash margin and direct surplus deliberately." else "Review the largest repeat Vyaya before adding new discretionary commitments.",
            band = if (cashFlowScorable) healthBand(cashScore) else HealthBand.NOT_SCORED,
            trend = cashTrend,
            trendExplanation = trendText(cashTrend, currentRate, previousRate, "savings rate"),
            isScorable = cashFlowScorable,
        )

        val completedMonths = (1..3).map { offset ->
            val monthEnd = today.withDayOfMonth(1).minusMonths((offset - 1).toLong())
            val monthStart = monthEnd.minusMonths(1)
            val start = monthStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = monthEnd.atStartOfDay(zoneId).toInstant().toEpochMilli()
            confirmed.filter { it.occurredAtEpochMs in start until end }
        }
        val monthRates = completedMonths.mapNotNull { rows -> savingsRate(income(rows), expense(rows)) }
        val savingsScorable = monthRates.size >= 2
        val positiveMonths = monthRates.count { it > 0.0 }
        val averageMonthlyRate = monthRates.takeIf { it.isNotEmpty() }?.average()
        val consistencyScore = if (monthRates.isEmpty()) 0 else (positiveMonths * 100.0 / monthRates.size).roundToInt()
        val disciplineScore = if (savingsScorable) ((savingsRateScore(averageMonthlyRate ?: 0.0) + consistencyScore) / 2.0).roundToInt() else 50
        val savingsDiscipline = HealthComponent(
            id = "savings_discipline", name = "Savings discipline", score = disciplineScore,
            explanation = "Measures whether positive saving is recurring across completed calendar months, not just present in one good window.",
            calculation = "50% comes from the average savings rate of the last three completed months with Aaya; 50% comes from the share of those months with positive savings. At least two months with Aaya are required.",
            evidence = listOf(HealthEvidence("Months with usable Aaya", value = monthRates.size.toString()), HealthEvidence("Positive-saving months", value = "$positiveMonths/${monthRates.size}"), HealthEvidence("Average monthly savings rate", value = averageMonthlyRate?.let(::formatPercent) ?: "Not enough evidence")),
            nextAction = if (disciplineScore >= 65) "Keep the recurring surplus intentional; consistency matters more than one unusually strong month." else "Aim first for repeatable positive months rather than a one-off large saving event.",
            band = if (savingsScorable) healthBand(disciplineScore) else HealthBand.NOT_SCORED,
            trend = HealthTrend.UNKNOWN,
            trendExplanation = "This component summarizes the last three completed months; a longer rolling trend is intentionally not inferred from too little history.",
            isScorable = savingsScorable,
        )

        val reserves = balances.filter { it.kind == AccountKind.EMERGENCY || it.kind == AccountKind.SAVINGS }.sumOf { it.balancePaise.coerceAtLeast(0L) }
        val spendingCash = balances.filter { it.kind == AccountKind.SPENDING }.sumOf { it.balancePaise.coerceAtLeast(0L) }
        val liquidBuffer = reserves + spendingCash
        val monthlyBurn = currentExpense / 3.0
        val previousBurn = previousExpense / 3.0
        val reserveMonths = if (currentExpense > 0L) liquidBuffer / monthlyBurn else null
        val previousReserveMonths = if (previousExpense > 0L) liquidBuffer / previousBurn else null
        val reserveScore = reserveMonths?.let { (it / 6.0 * 100).coerceIn(0.0, 100.0).roundToInt() } ?: 50
        val reserve = HealthComponent(
            id = "emergency_reserve", name = "Emergency reserve", score = reserveScore,
            explanation = "Estimates how long current spending cash, savings and emergency balances could cover observed Vyaya.",
            calculation = "Runway = liquid buffer ÷ average monthly non-investment Vyaya over 90 days. Six months maps to 100/100.",
            evidence = listOf(HealthEvidence("Liquid buffer", amountPaise = liquidBuffer), HealthEvidence("Observed monthly Vyaya", amountPaise = monthlyBurn.roundToLong()), HealthEvidence("Estimated runway", value = reserveMonths?.let { "%.1f months".format(it) } ?: "Not enough spending data")),
            nextAction = if ((reserveMonths ?: 0.0) >= 3.0) "Keep the reserve purpose clear and accessible." else "Consider directing part of future surplus to an emergency or savings Khata.",
            band = if (reserveMonths != null) healthBand(reserveScore) else HealthBand.NOT_SCORED,
            trend = scoreTrend(reserveMonths, previousReserveMonths, 0.25),
            trendExplanation = if (reserveMonths == null || previousReserveMonths == null) "A comparable prior spending window is required for a runway trend." else "Runway is %.1f months now versus %.1f months using the prior 90-day burn rate.".format(reserveMonths, previousReserveMonths),
            isScorable = reserveMonths != null,
        )

        val investmentAccounts = accounts.filter { it.kind == AccountKind.INVESTMENT && !it.isArchived }
        val latestByAccount = latestInvestments.associateBy { it.accountId }
        val investmentValues = investmentAccounts.mapNotNull { account -> latestByAccount[account.id]?.currentValuePaise?.coerceAtLeast(0L)?.let { account to it } }.filter { it.second > 0L }
        val investmentTotal = investmentValues.sumOf { it.second }
        val diversificationScorable = investmentValues.size >= 2 && investmentTotal > 0L
        val largestShare = if (investmentTotal > 0L) investmentValues.maxOfOrNull { it.second }?.times(100.0)?.div(investmentTotal) else null
        val classValues = investmentValues.groupBy { it.first.assetClass }.mapValues { (_, rows) -> rows.sumOf { it.second } }.filterKeys { it != AssetClass.CASH }
        val activeClasses = classValues.count { it.value > 0L }
        val concentrationScore = largestShare?.let { ((80.0 - it) / 45.0 * 100.0).coerceIn(0.0, 100.0).roundToInt() } ?: 0
        val classScore = when { activeClasses >= 3 -> 100; activeClasses == 2 -> 65; activeClasses == 1 -> 30; else -> 0 }
        val diversificationScore = if (diversificationScorable) ((concentrationScore + classScore) / 2.0).roundToInt() else 50
        val diversification = HealthComponent(
            id = "investment_diversification", name = "Investment diversification", score = diversificationScore,
            explanation = "Looks at concentration across recorded Nivesha holdings and how many non-cash asset classes currently carry value.",
            calculation = "Half the score is holding concentration (100 at ≤35% largest holding, falling to 0 at ≥80%); half is asset-class breadth (1 class=30, 2=65, 3+=100). At least two valued holdings are required.",
            evidence = listOf(HealthEvidence("Valued holdings", value = investmentValues.size.toString()), HealthEvidence("Nivesha value", amountPaise = investmentTotal), HealthEvidence("Largest holding share", value = largestShare?.let(::formatPercent) ?: "Not enough data"), HealthEvidence("Active asset classes", value = activeClasses.toString())),
            nextAction = if (diversificationScore >= 65) "Keep diversification intentional; do not rebalance solely to optimize a score." else "Inspect concentration and asset-class exposure before adding to the largest holding.",
            band = if (diversificationScorable) healthBand(diversificationScore) else HealthBand.NOT_SCORED,
            trend = HealthTrend.UNKNOWN,
            trendExplanation = "Diversification is evaluated from current recorded holdings; Dhanam does not infer historical composition without comparable dated portfolio snapshots.",
            isScorable = diversificationScorable,
        )

        val targetable = investmentAccounts.filter { it.targetAllocationBps > 0 }
        val currentValues = latestInvestments.associate { it.accountId to it.currentValuePaise.coerceAtLeast(0L) }
        val targetedTotal = targetable.sumOf { currentValues[it.id] ?: 0L }
        val drift = if (targetable.isNotEmpty() && targetedTotal > 0L) allocationDriftBps(targetable, currentValues) else null
        val alignmentScore = drift?.let { (100.0 - it / 100.0).coerceIn(0.0, 100.0).roundToInt() } ?: 50
        val portfolioAlignment = HealthComponent(
            id = "portfolio_alignment", name = "Portfolio alignment", score = alignmentScore,
            explanation = "Compares current Nivesha weights with the target percentages you explicitly set.",
            calculation = "Aggregate drift is half the sum of absolute differences between current and target weights. Each 1 percentage point of drift reduces the score by 1 point.",
            evidence = listOf(HealthEvidence("Targeted holdings", value = targetable.size.toString()), HealthEvidence("Targeted value", amountPaise = targetedTotal), HealthEvidence("Aggregate drift", value = drift?.let { formatPercent(it / 100.0) } ?: "Targets/current values required")),
            nextAction = if ((drift ?: 0.0) <= 500.0) "Current weights are reasonably close to your declared targets." else "Review which holdings explain the drift and confirm the targets still match your plan before rebalancing.",
            band = if (drift != null) healthBand(alignmentScore) else HealthBand.NOT_SCORED,
            trend = HealthTrend.UNKNOWN,
            trendExplanation = "The score is based on current declared targets; comparable historical target sets are not stored.",
            isScorable = drift != null,
        )

        val liabilities = balances.filter { it.kind == AccountKind.CREDIT }.sumOf { (-it.balancePaise).coerceAtLeast(0L) }
        val liquidAssets = balances.filter { it.kind != AccountKind.CREDIT && it.kind != AccountKind.INVESTMENT }.sumOf { it.balancePaise.coerceAtLeast(0L) }
        val solvencyScorable = accounts.isNotEmpty()
        val solvencyScore = if (!solvencyScorable) 50 else if (liabilities == 0L) 100 else (liquidAssets * 100.0 / (liquidAssets + liabilities)).coerceIn(0.0, 100.0).roundToInt()
        val solvency = HealthComponent(
            id = "solvency", name = "Liability resilience", score = solvencyScore,
            explanation = "Compares recorded liquid non-investment assets with recorded credit and loan liabilities.",
            calculation = "Score = liquid assets ÷ (liquid assets + recorded liabilities). With no recorded liability, the score is 100/100.",
            evidence = listOf(HealthEvidence("Liquid non-investment assets", amountPaise = liquidAssets), HealthEvidence("Recorded liabilities", amountPaise = liabilities)),
            nextAction = if (liabilities == 0L) "Keep liability Khatas current so this reading remains evidence-based." else "Review the most expensive or near-term liabilities before treating long-term Nivesha as available cash.",
            band = if (solvencyScorable) healthBand(solvencyScore) else HealthBand.NOT_SCORED,
            trend = HealthTrend.UNKNOWN,
            trendExplanation = "Historical Khata balance snapshots are not stored, so Dhanam does not invent a liability trend.",
            isScorable = solvencyScorable,
        )

        val netWorth = liquidAssets + investmentTotal - liabilities
        val graph = FinancialHealthGraph(
            nodes = listOf(
                HealthGraphNode("income", "Income", currentIncome, "Confirmed Aaya · 90 days"),
                HealthGraphNode("spending", "Spending", currentExpense, "Confirmed Vyaya · 90 days"),
                HealthGraphNode("savings", "Savings", currentIncome - currentExpense, "90-day cash-flow surplus"),
                HealthGraphNode("reserve", "Emergency reserve", reserves, "Savings + emergency Khatas"),
                HealthGraphNode("liabilities", "Liabilities", liabilities, "Recorded credit/loan balances"),
                HealthGraphNode("investments", "Investments", investmentTotal, "Latest Nivesha values"),
                HealthGraphNode("networth", "Net worth", netWorth, "Liquid assets + Nivesha − liabilities"),
            ),
            edges = listOf(
                HealthGraphEdge("income", "spending", "funds"),
                HealthGraphEdge("income", "savings", "surplus"),
                HealthGraphEdge("savings", "reserve", "buffer"),
                HealthGraphEdge("savings", "investments", "capital"),
                HealthGraphEdge("reserve", "networth", "+"),
                HealthGraphEdge("investments", "networth", "+"),
                HealthGraphEdge("liabilities", "networth", "−"),
            ),
        )

        val components = listOf(cashFlow, savingsDiscipline, reserve, diversification, portfolioAlignment, solvency)
        val scored = components.filter { it.isScorable }
        val overall = if (scored.isEmpty()) 0 else scored.map { it.score }.average().roundToInt()
        return FinancialHealth(overall, components, scored.size, graph)
    }

    private fun savingsRate(income: Long, expense: Long): Double? = if (income <= 0L) null else (income - expense) * 100.0 / income
    private fun savingsRateScore(rate: Double): Int = (rate.coerceIn(0.0, 35.0) / 35.0 * 100.0).roundToInt()
    private fun allocationDriftBps(accounts: List<AccountEntity>, values: Map<String, Long>): Double {
        val total = accounts.sumOf { values[it.id] ?: 0L }
        if (total <= 0L) return 10_000.0
        return accounts.sumOf { account -> abs((values[account.id] ?: 0L) * 10_000.0 / total - account.targetAllocationBps) } / 2.0
    }
    private fun scoreTrend(current: Double?, previous: Double?, stableThreshold: Double): HealthTrend {
        if (current == null || previous == null) return HealthTrend.UNKNOWN
        return when { current - previous > stableThreshold -> HealthTrend.IMPROVING; current - previous < -stableThreshold -> HealthTrend.DECLINING; else -> HealthTrend.STABLE }
    }
    private fun trendText(trend: HealthTrend, current: Double?, previous: Double?, unit: String): String = when {
        current == null -> "Trend unavailable until the current $unit can be calculated."
        previous == null -> "No comparable prior 90-day $unit is available yet."
        else -> "Current $unit is ${formatPercent(current)} versus ${formatPercent(previous)} in the previous 90-day window (${trend.name.lowercase()})."
    }
    private fun formatPercent(value: Double): String = "%.1f%%".format(value)
}

internal fun healthBand(score: Int): HealthBand = when {
    score >= 80 -> HealthBand.STRONG
    score >= 65 -> HealthBand.STEADY
    score >= 45 -> HealthBand.WATCH
    else -> HealthBand.ATTENTION
}
