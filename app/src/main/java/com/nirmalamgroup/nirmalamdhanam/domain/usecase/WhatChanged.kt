package com.nirmalamgroup.nirmalamdhanam.domain.usecase

import com.nirmalamgroup.nirmalamdhanam.data.local.AccountBalance
import com.nirmalamgroup.nirmalamdhanam.data.local.AccountEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.AccountKind
import com.nirmalamgroup.nirmalamdhanam.data.local.EnvelopeType
import com.nirmalamgroup.nirmalamdhanam.data.local.GoalAllocationEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.GoalEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.InvestmentBalanceSnapshotEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.NetWorthSnapshotEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionDirection
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A compact, deterministic digest of meaningful local financial changes.
 *
 * The engine never creates financial facts. It only projects the ledger, investment
 * check-ins, goals, and current attention state into a Home-screen summary.
 */
enum class WhatChangedKind {
    CASHFLOW,
    INVESTMENTS,
    SAMPADA,
    GOALS,
    ATTENTION,
}

enum class WhatChangedAmountTone {
    POSITIVE,
    NEGATIVE,
    NEUTRAL,
}

data class WhatChangedItem(
    val title: String,
    val detail: String,
    val amountPaise: Long? = null,
    val amountTone: WhatChangedAmountTone = WhatChangedAmountTone.NEUTRAL,
)

data class WhatChangedSection(
    val kind: WhatChangedKind,
    val title: String,
    val summary: String,
    val metricPaise: Long? = null,
    val metricTone: WhatChangedAmountTone = WhatChangedAmountTone.NEUTRAL,
    val items: List<WhatChangedItem> = emptyList(),
)

data class WhatChangedSummary(
    val eventCount: Int,
    val sections: List<WhatChangedSection>,
) {
    val isEmpty: Boolean get() = sections.isEmpty()
    val attentionCount: Int
        get() = sections.firstOrNull { it.kind == WhatChangedKind.ATTENTION }?.items?.size ?: 0
}

object WhatChangedEngine {
    fun build(
        accounts: List<AccountEntity>,
        balances: List<AccountBalance>,
        transactions: List<TransactionEntity>,
        investmentHistory: List<InvestmentBalanceSnapshotEntity>,
        netWorthHistory: List<NetWorthSnapshotEntity>,
        goals: List<GoalEntity>,
        goalAllocations: List<GoalAllocationEntity>,
        holdingTank: List<TransactionEntity>,
        availableCashPaise: Long,
        sinceEpochMs: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): WhatChangedSummary {
        val cutoff = sinceEpochMs.coerceAtMost(nowEpochMs)
        val accountNames = accounts.associate { it.id to it.name }
        val sections = mutableListOf<WhatChangedSection>()

        val recentTransactions = transactions
            .asSequence()
            .filterNot { it.isHoldingTank }
            .filter { it.occurredAtEpochMs in cutoff..nowEpochMs }
            .toList()

        val recentCashflow = recentTransactions.filter { it.envelopeType != EnvelopeType.INVESTMENT }
        if (recentCashflow.isNotEmpty()) {
            val income = recentCashflow.filter { it.direction == TransactionDirection.CREDIT }.sumOf { it.amountPaise }
            val expense = recentCashflow.filter { it.direction == TransactionDirection.DEBIT }.sumOf { it.amountPaise }
            val net = income - expense
            val largest = recentCashflow
                .sortedByDescending { it.amountPaise }
                .take(3)
                .map { transaction ->
                    val signed = if (transaction.direction == TransactionDirection.CREDIT) transaction.amountPaise else -transaction.amountPaise
                    WhatChangedItem(
                        title = transaction.payee?.takeIf(String::isNotBlank)
                            ?: transaction.category?.takeIf(String::isNotBlank)
                            ?: if (transaction.direction == TransactionDirection.CREDIT) "Income" else "Expense",
                        detail = listOfNotNull(
                            transaction.category?.takeIf(String::isNotBlank),
                            accountNames[transaction.accountId],
                        ).distinct().joinToString(" · "),
                        amountPaise = signed,
                        amountTone = toneForSigned(signed),
                    )
                }
            sections += WhatChangedSection(
                kind = WhatChangedKind.CASHFLOW,
                title = "Cashflow",
                summary = "${recentCashflow.size} confirmed ${plural(recentCashflow.size, "entry", "entries")} since your last visit",
                metricPaise = net,
                metricTone = toneForSigned(net),
                items = largest,
            )
        }

        // createdAtEpochMs is intentional here: a check-in entered today with an older
        // as-of date still counts as something that changed since the previous visit.
        val recentCheckIns = investmentHistory
            .filter { it.createdAtEpochMs in cutoff..nowEpochMs }
            .sortedByDescending { it.createdAtEpochMs }
        if (recentCheckIns.isNotEmpty()) {
            val marketMovement = recentCheckIns.sumOf { it.marketMovementPaise }
            val contribution = recentCheckIns.sumOf { it.netContributionPaise }
            val hasWithdrawalEstimate = recentCheckIns.any { it.netContributionPaise < 0L }
            val items = buildList {
                if (contribution != 0L) {
                    add(
                        WhatChangedItem(
                            title = if (contribution > 0) "Net contributions" else "Net withdrawal basis",
                            detail = "Across recent investment check-ins",
                            amountPaise = contribution,
                            amountTone = WhatChangedAmountTone.NEUTRAL,
                        )
                    )
                }
                recentCheckIns.take(3).forEach { snapshot ->
                    val detail = when {
                        snapshot.previousValuePaise == null -> "Opening valuation at ${LocalDate.ofEpochDay(snapshot.asOfEpochDay)}"
                        snapshot.netContributionPaise < 0L -> "Balance-return estimate at ${LocalDate.ofEpochDay(snapshot.asOfEpochDay)} · redemption proceeds not recorded"
                        else -> "Market movement at ${LocalDate.ofEpochDay(snapshot.asOfEpochDay)}"
                    }
                    add(
                        WhatChangedItem(
                            title = accountNames[snapshot.accountId] ?: "Investment",
                            detail = detail,
                            amountPaise = if (snapshot.previousValuePaise == null) snapshot.currentValuePaise else snapshot.marketMovementPaise,
                            amountTone = if (snapshot.previousValuePaise == null) WhatChangedAmountTone.NEUTRAL else toneForSigned(snapshot.marketMovementPaise),
                        )
                    )
                }
            }
            sections += WhatChangedSection(
                kind = WhatChangedKind.INVESTMENTS,
                title = "Investments",
                summary = "${recentCheckIns.size} balance ${plural(recentCheckIns.size, "check-in", "check-ins")}" +
                    if (hasWithdrawalEstimate) " · withdrawal return shown as an estimate" else "",
                metricPaise = marketMovement,
                metricTone = toneForSigned(marketMovement),
                items = items,
            )
        }

        val netWorthByCreation = netWorthHistory.sortedBy { it.createdAtEpochMs }
        val recentNetWorth = netWorthByCreation.filter { it.createdAtEpochMs in cutoff..nowEpochMs }
        if (recentNetWorth.isNotEmpty()) {
            val latest = recentNetWorth.last()
            val baseline = netWorthByCreation.lastOrNull { it.createdAtEpochMs < cutoff }
                ?: recentNetWorth.dropLast(1).lastOrNull()
            val delta = baseline?.let { latest.netWorthPaise - it.netWorthPaise }
            sections += WhatChangedSection(
                kind = WhatChangedKind.SAMPADA,
                title = "Sampada",
                summary = if (delta == null) "Latest net-worth snapshot recorded" else "Net worth moved after your latest valuation",
                metricPaise = delta ?: latest.netWorthPaise,
                metricTone = if (delta == null) WhatChangedAmountTone.NEUTRAL else toneForSigned(delta),
                items = listOf(
                    WhatChangedItem(
                        title = "Current recorded net worth",
                        detail = "Snapshot dated ${LocalDate.ofEpochDay(latest.asOfEpochDay)}",
                        amountPaise = latest.netWorthPaise,
                        amountTone = WhatChangedAmountTone.NEUTRAL,
                    )
                ),
            )
        }

        val latestInvestments = investmentHistory
            .groupBy { it.accountId }
            .values
            .mapNotNull { rows -> rows.maxByOrNull { it.asOfEpochDay } }
        val goalProgress = GoalProgressCalculator.calculate(goals, goalAllocations, balances, latestInvestments)
            .associateBy { it.goal.id }
        val allocationsByGoal = goalAllocations.groupBy { it.goalId }
        val changedAccountIds = buildSet {
            recentTransactions.forEach { add(it.accountId) }
            recentCheckIns.forEach { add(it.accountId) }
        }
        val newGoalIds = goals.filter { it.createdAtEpochMs in cutoff..nowEpochMs }.mapTo(mutableSetOf()) { it.id }
        val impactedGoals = goals
            .filterNot { it.isArchived }
            .filter { goal ->
                goal.id in newGoalIds || allocationsByGoal[goal.id].orEmpty().any { it.accountId in changedAccountIds }
            }
            .sortedWith(compareBy<GoalEntity> { it.id !in newGoalIds }.thenBy { it.targetDateEpochDay ?: Long.MAX_VALUE })
        if (impactedGoals.isNotEmpty()) {
            val items = impactedGoals.take(4).map { goal ->
                val progress = goalProgress[goal.id]
                val percent = progress?.progressPercent ?: 0.0
                WhatChangedItem(
                    title = goal.name,
                    detail = if (goal.id in newGoalIds) {
                        "New goal · ${formatPercent(percent)} funded"
                    } else {
                        "Recent linked-account activity · now ${formatPercent(percent)} funded"
                    },
                    amountPaise = progress?.fundedPaise,
                    amountTone = WhatChangedAmountTone.NEUTRAL,
                )
            }
            sections += WhatChangedSection(
                kind = WhatChangedKind.GOALS,
                title = "Goals",
                summary = "${impactedGoals.size} ${plural(impactedGoals.size, "goal", "goals")} affected by recent activity",
                items = items,
            )
        }

        val attentionItems = buildAttentionItems(
            accounts = accounts,
            investmentHistory = investmentHistory,
            goals = goals,
            goalProgressById = goalProgress,
            holdingTank = holdingTank,
            availableCashPaise = availableCashPaise,
            nowEpochMs = nowEpochMs,
            zone = zone,
        )
        if (attentionItems.isNotEmpty()) {
            sections += WhatChangedSection(
                kind = WhatChangedKind.ATTENTION,
                title = "Needs attention",
                summary = "${attentionItems.size} ${plural(attentionItems.size, "item", "items")} worth a quick review",
                items = attentionItems,
            )
        }

        return WhatChangedSummary(
            eventCount = recentCashflow.size + recentCheckIns.size + newGoalIds.size,
            sections = sections,
        )
    }

    private fun buildAttentionItems(
        accounts: List<AccountEntity>,
        investmentHistory: List<InvestmentBalanceSnapshotEntity>,
        goals: List<GoalEntity>,
        goalProgressById: Map<String, GoalProgress>,
        holdingTank: List<TransactionEntity>,
        availableCashPaise: Long,
        nowEpochMs: Long,
        zone: ZoneId,
    ): List<WhatChangedItem> {
        val result = mutableListOf<WhatChangedItem>()
        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()

        val readyCooling = holdingTank.filter { (it.coolDownExpiryEpochMs ?: Long.MAX_VALUE) <= nowEpochMs }
        if (readyCooling.isNotEmpty()) {
            result += WhatChangedItem(
                title = "Cooling decisions ready",
                detail = "${readyCooling.size} ${plural(readyCooling.size, "purchase", "purchases")} can now be confirmed or discarded",
            )
        }

        if (availableCashPaise < 0L) {
            result += WhatChangedItem(
                title = "Available cash is below zero",
                detail = "Review cash balances and credit liabilities",
                amountPaise = availableCashPaise,
                amountTone = WhatChangedAmountTone.NEGATIVE,
            )
        }

        val latestByInvestment = investmentHistory.groupBy { it.accountId }
            .mapValues { (_, rows) -> rows.maxByOrNull { it.asOfEpochDay } }
        val overdue = accounts
            .filter { it.kind == AccountKind.INVESTMENT && !it.isArchived }
            .mapNotNull { account ->
                val latest = latestByInvestment[account.id]
                val days = latest?.let { today.toEpochDay() - it.asOfEpochDay }
                when {
                    latest == null -> account.name to null
                    days != null && days >= 30L -> account.name to days
                    else -> null
                }
            }
        if (overdue.isNotEmpty()) {
            val first = overdue.first()
            val detail = if (first.second == null) {
                "${first.first} has no valuation yet"
            } else {
                "${first.first} was last checked ${first.second} days ago"
            }
            result += WhatChangedItem(
                title = "${overdue.size} investment ${plural(overdue.size, "check-in", "check-ins")} due",
                detail = detail,
            )
        }

        val dueSoon = goals
            .filterNot { it.isArchived }
            .mapNotNull { goal ->
                val target = goal.targetDateEpochDay ?: return@mapNotNull null
                val days = target - today.toEpochDay()
                val progress = goalProgressById[goal.id]?.progressPercent ?: 0.0
                if (days <= 30L && progress < 100.0) Triple(goal, days, progress) else null
            }
            .sortedBy { it.second }
        dueSoon.firstOrNull()?.let { (goal, days, progress) ->
            result += WhatChangedItem(
                title = "Goal date approaching",
                detail = "${goal.name} · ${formatPercent(progress)} funded · ${when {
                    days < 0L -> "overdue by ${-days} days"
                    days == 0L -> "due today"
                    else -> "$days days left"
                }}",
                amountPaise = goalProgressById[goal.id]?.fundedPaise,
                amountTone = WhatChangedAmountTone.NEUTRAL,
            )
        }

        return result
    }

    private fun toneForSigned(value: Long): WhatChangedAmountTone = when {
        value > 0L -> WhatChangedAmountTone.POSITIVE
        value < 0L -> WhatChangedAmountTone.NEGATIVE
        else -> WhatChangedAmountTone.NEUTRAL
    }

    private fun plural(count: Int, singular: String, plural: String): String = if (count == 1) singular else plural

    private fun formatPercent(value: Double): String = "%.0f%%".format(value.coerceIn(0.0, 100.0))
}
