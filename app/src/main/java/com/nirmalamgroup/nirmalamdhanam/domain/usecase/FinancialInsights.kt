package com.nirmalamgroup.nirmalamdhanam.domain.usecase

import com.nirmalamgroup.nirmalamdhanam.data.local.CategoryEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.EnvelopeType
import com.nirmalamgroup.nirmalamdhanam.data.local.InvestmentBalanceSnapshotEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionDirection
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Local insight plus concrete evidence references for an explicit evidence drill-down. */
data class FinancialInsight(
    val id: String,
    val title: String,
    val explanation: String,
    val reasoningSteps: List<String> = emptyList(),
    val evidenceTransactionIds: List<String> = emptyList(),
    val evidenceSnapshotIds: List<String> = emptyList(),
    val observedAtEpochMs: Long? = null,
    val timelineAmountPaise: Long? = null,
)

object ExplainableInsightEngine {
    private fun money(paise: Long): String = "₹%,.2f".format(paise / 100.0)

    fun build(
        transactions: List<TransactionEntity>,
        categories: List<CategoryEntity>,
        snapshots: List<InvestmentBalanceSnapshotEntity>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): List<FinancialInsight> {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
        val currentStart = today.minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli()
        val baselineStart = today.minusDays(119).atStartOfDay(zone).toInstant().toEpochMilli()
        val debits = transactions.filter {
            it.direction == TransactionDirection.DEBIT &&
                it.envelopeType != EnvelopeType.INVESTMENT &&
                !it.isHoldingTank
        }
        val current = debits.filter { it.occurredAtEpochMs >= currentStart }
        val baseline = debits.filter { it.occurredAtEpochMs in baselineStart until currentStart }
        val insights = mutableListOf<FinancialInsight>()

        val currentByCategory = current.groupBy { it.category ?: "Uncategorised" }
        val baselineByCategory = baseline.groupBy { it.category ?: "Uncategorised" }

        currentByCategory.entries
            .mapNotNull { (category, rows) ->
                val currentAmount = rows.sumOf { it.amountPaise }
                val baselineRows = baselineByCategory[category].orEmpty()
                val baselineAmount = baselineRows.sumOf { it.amountPaise }
                val monthlyBaseline = baselineAmount / 3.0
                if (baselineRows.isEmpty() || monthlyBaseline <= 0.0 || currentAmount <= monthlyBaseline * 1.25) null
                else Triple(category, currentAmount, monthlyBaseline)
            }
            .sortedByDescending { it.second - it.third }
            .take(3)
            .forEach { (category, amount, monthlyBaseline) ->
                val pct = ((amount - monthlyBaseline) * 100.0 / monthlyBaseline).toInt()
                val currentEvidence = currentByCategory[category].orEmpty().sortedByDescending { it.amountPaise }
                val baselineEvidence = baselineByCategory[category].orEmpty().sortedByDescending { it.occurredAtEpochMs }
                val aboveThousand = currentEvidence.count { it.amountPaise >= 100_000L }
                insights += FinancialInsight(
                    id = "category-rise-${category.lowercase().replace(Regex("[^a-z0-9]+"), "-")}",
                    title = "$category spending increased",
                    explanation = "Last 30 days are $pct% above your previous 90-day monthly average (${money(amount)} vs ${money(monthlyBaseline.toLong())})." +
                        if (aboveThousand > 0) " $aboveThousand transaction${if (aboveThousand == 1) "" else "s"} were above ₹1,000." else "",
                    reasoningSteps = listOf(
                        "Current window: ${currentEvidence.size} confirmed $category debit(s) totaling ${money(amount)}.",
                        "Baseline: ${baselineEvidence.size} confirmed debit(s) over the preceding 90 days, averaged to ${money(monthlyBaseline.toLong())} per 30 days.",
                        "Signal threshold: current spending must exceed that monthly baseline by at least 25%.",
                    ),
                    evidenceTransactionIds = (currentEvidence + baselineEvidence).map { it.id }.distinct(),
                    observedAtEpochMs = currentEvidence.maxOfOrNull { it.occurredAtEpochMs },
                )
            }

        val wants = categories.filter { it.priority.name == "WANT" }.map { it.name }.toSet()
        val wantRows = current.filter { it.category in wants }
        val wantSpend = wantRows.sumOf { it.amountPaise }
        val totalSpend = current.sumOf { it.amountPaise }
        if (totalSpend > 0 && wantSpend * 100 / totalSpend >= 35) {
            val share = wantSpend * 100 / totalSpend
            insights += FinancialInsight(
                id = "wants-share",
                title = "Lifestyle spending is a large share of recent expenses",
                explanation = "Want-category spending is $share% of confirmed debits in the last 30 days.",
                reasoningSteps = listOf(
                    "Want-category Vyaya: ${money(wantSpend)}.",
                    "All confirmed non-investment Vyaya: ${money(totalSpend)}.",
                    "Signal appears when the Want share reaches 35% or more.",
                ),
                evidenceTransactionIds = current.map { it.id },
                observedAtEpochMs = current.maxOfOrNull { it.occurredAtEpochMs },
            )
        }

        val recentNinetyDays = debits
            .filter { it.occurredAtEpochMs >= today.minusDays(89).atStartOfDay(zone).toInstant().toEpochMilli() }
            .sortedBy { it.amountPaise }
        if (recentNinetyDays.size >= 8) {
            val median = medianAmount(recentNinetyDays.map { it.amountPaise })
            if (median > 0L) {
                recentNinetyDays.asSequence()
                    .filter { it.amountPaise >= median * 3L }
                    .sortedByDescending { it.occurredAtEpochMs }
                    .take(3)
                    .forEach { transaction ->
                        val multiple = transaction.amountPaise.toDouble() / median
                        val label = transaction.payee ?: transaction.merchant ?: transaction.category ?: "Expense"
                        insights += FinancialInsight(
                            id = "unusual-expense-${transaction.id}",
                            title = "Unusual expense detected",
                            explanation = "$label was ${money(transaction.amountPaise)}, about ${"%.1f".format(multiple)}× your median confirmed debit of ${money(median)} over the last 90 days.",
                            reasoningSteps = listOf(
                                "Dhanam found ${recentNinetyDays.size} confirmed non-investment debits in the 90-day window.",
                                "Their median amount is ${money(median)}.",
                                "This transaction is shown because it is at least 3× that median; no universal rupee threshold is used.",
                            ),
                            evidenceTransactionIds = listOf(transaction.id) + recentNinetyDays.map { it.id },
                            observedAtEpochMs = transaction.occurredAtEpochMs,
                            timelineAmountPaise = -transaction.amountPaise,
                        )
                    }
            }
        }

        snapshots.groupBy { it.accountId }.forEach { (accountId, history) ->
            val comparable = history
                .filter { it.previousValuePaise != null && it.netContributionPaise >= 0L }
                .sortedByDescending { it.asOfEpochDay }
            if (comparable.size >= 3 && comparable.take(3).all { it.marketMovementPaise < 0 }) {
                val latest = comparable.first()
                insights += FinancialInsight(
                    id = "three-negative-$accountId",
                    title = "Three consecutive negative investment updates",
                    explanation = "The latest three comparable check-ins recorded negative market movement. Balance-only withdrawals are excluded because their redemption proceeds are not known.",
                    reasoningSteps = comparable.take(3).mapIndexed { index, row ->
                        "Check-in ${index + 1}: market movement ${money(row.marketMovementPaise)} on ${LocalDate.ofEpochDay(row.asOfEpochDay)}."
                    } + "All three comparable movements are below zero.",
                    evidenceSnapshotIds = comparable.take(3).map { it.id },
                    observedAtEpochMs = LocalDate.ofEpochDay(latest.asOfEpochDay).atStartOfDay(zone).toInstant().toEpochMilli(),
                )
            }
        }
        return insights
    }

    private fun medianAmount(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2L
    }
}
