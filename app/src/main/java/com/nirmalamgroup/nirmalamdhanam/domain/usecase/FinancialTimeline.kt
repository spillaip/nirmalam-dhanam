package com.nirmalamgroup.nirmalamdhanam.domain.usecase

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

/** The timeline is a derived view over source facts; no FinancialEvent is persisted. */
enum class FinancialEventKind {
    CASHFLOW,
    INVESTMENT,
    SAMPADA,
    GOAL,
    MILESTONE,
    INSIGHT,
}

sealed interface FinancialEvent {
    val id: String
    val sortEpochMs: Long
    val kind: FinancialEventKind
    val title: String
    val detail: String
    val amountPaise: Long?
    val isSignedAmount: Boolean

    data class Transaction(
        val source: TransactionEntity,
        override val title: String,
        override val detail: String,
    ) : FinancialEvent {
        override val id: String = "transaction:${source.id}"
        override val sortEpochMs: Long = source.occurredAtEpochMs
        override val kind: FinancialEventKind = FinancialEventKind.CASHFLOW
        override val amountPaise: Long = if (source.direction == TransactionDirection.CREDIT) {
            source.amountPaise
        } else {
            -source.amountPaise
        }
        override val isSignedAmount: Boolean = true
    }

    /** The user's dated valuation fact. */
    data class InvestmentCheckIn(
        val source: InvestmentBalanceSnapshotEntity,
        override val title: String,
        override val detail: String,
        private val zone: ZoneId,
    ) : FinancialEvent {
        override val id: String = "investment:${source.id}"
        override val sortEpochMs: Long = LocalDate.ofEpochDay(source.asOfEpochDay)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        override val kind: FinancialEventKind = FinancialEventKind.INVESTMENT
        override val amountPaise: Long = source.currentValuePaise
        override val isSignedAmount: Boolean = false
    }

    /** Performance derived from the same pair of snapshots, never another stored balance. */
    data class InvestmentPerformance(
        val snapshotId: String,
        val accountId: String,
        override val sortEpochMs: Long,
        override val title: String,
        override val detail: String,
        override val amountPaise: Long,
    ) : FinancialEvent {
        override val id: String = "investment-return:$snapshotId"
        override val kind: FinancialEventKind = FinancialEventKind.INVESTMENT
        override val isSignedAmount: Boolean = true
    }

    data class SampadaSnapshot(
        val source: NetWorthSnapshotEntity,
        val changePaise: Long?,
        override val detail: String,
        private val zone: ZoneId,
    ) : FinancialEvent {
        override val id: String = "sampada:${source.id}"
        override val sortEpochMs: Long = LocalDate.ofEpochDay(source.asOfEpochDay)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        override val kind: FinancialEventKind = FinancialEventKind.SAMPADA
        override val title: String = "Sampada snapshot"
        override val amountPaise: Long = changePaise ?: source.netWorthPaise
        override val isSignedAmount: Boolean = changePaise != null
    }

    data class GoalCreated(
        val source: GoalEntity,
        override val detail: String,
    ) : FinancialEvent {
        override val id: String = "goal:${source.id}"
        override val sortEpochMs: Long = source.createdAtEpochMs
        override val kind: FinancialEventKind = FinancialEventKind.GOAL
        override val title: String = "Goal created"
        override val amountPaise: Long = source.targetAmountPaise
        override val isSignedAmount: Boolean = false
    }

    data class GoalMilestone(
        val goalId: String,
        val percent: Int,
        override val sortEpochMs: Long,
        override val title: String,
        override val detail: String,
        override val amountPaise: Long,
    ) : FinancialEvent {
        override val id: String = "goal-milestone:$goalId:$percent"
        override val kind: FinancialEventKind = FinancialEventKind.MILESTONE
        override val isSignedAmount: Boolean = false
    }

    data class InsightSignal(
        val insight: FinancialInsight,
        override val sortEpochMs: Long,
    ) : FinancialEvent {
        override val id: String = "insight:${insight.id}"
        override val kind: FinancialEventKind = FinancialEventKind.INSIGHT
        override val title: String = insight.title
        override val detail: String = insight.explanation
        override val amountPaise: Long? = insight.timelineAmountPaise
        override val isSignedAmount: Boolean = insight.timelineAmountPaise != null
    }
}

object FinancialTimelineBuilder {
    private val goalMilestones = listOf(25, 50, 75, 80, 100)

    private fun money(paise: Long): String = "₹%,.2f".format(paise / 100.0)

    fun build(
        transactions: List<TransactionEntity>,
        snapshots: List<InvestmentBalanceSnapshotEntity>,
        accounts: List<AccountEntity>,
        sinceEpochMs: Long? = null,
        netWorthSnapshots: List<NetWorthSnapshotEntity> = emptyList(),
        goals: List<GoalEntity> = emptyList(),
        goalAllocations: List<GoalAllocationEntity> = emptyList(),
        insights: List<FinancialInsight> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<FinancialEvent> {
        val accountNames = accounts.associate { it.id to it.name }

        val txEvents = transactions.asSequence()
            .filterNot { it.isHoldingTank }
            .filter { sinceEpochMs == null || it.occurredAtEpochMs >= sinceEpochMs }
            .map { transaction ->
                val searchText = listOfNotNull(
                    transaction.payee,
                    transaction.merchant,
                    transaction.category,
                    transaction.description,
                ).joinToString(" ").lowercase()
                val title = when {
                    transaction.envelopeType == EnvelopeType.INVESTMENT && "sip" in searchText -> "SIP invested"
                    transaction.envelopeType == EnvelopeType.INVESTMENT -> "Investment transfer"
                    transaction.direction == TransactionDirection.CREDIT &&
                        ("salary" in searchText || "wage" in searchText || "payroll" in searchText) -> "Salary received"
                    transaction.direction == TransactionDirection.CREDIT -> "Income received"
                    else -> "Expense recorded"
                }
                FinancialEvent.Transaction(
                    source = transaction,
                    title = title,
                    detail = listOfNotNull(
                        transaction.payee ?: transaction.merchant,
                        transaction.category,
                        accountNames[transaction.accountId],
                    ).joinToString(" · "),
                )
            }

        val snapshotFacts = snapshots.asSequence().flatMap { snapshot ->
            val accountName = accountNames[snapshot.accountId] ?: "Investment"
            val dayEpochMs = LocalDate.ofEpochDay(snapshot.asOfEpochDay)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
            val flow = when {
                snapshot.previousValuePaise == null -> "Opening valuation"
                snapshot.netContributionPaise > 0 -> "Fresh investment +${money(snapshot.netContributionPaise)}"
                snapshot.netContributionPaise < 0 -> "Withdrawal basis ${money(-snapshot.netContributionPaise)}"
                else -> "No external flow"
            }
            val checkIn = FinancialEvent.InvestmentCheckIn(
                source = snapshot,
                title = "$accountName value updated",
                detail = "$flow · value ${money(snapshot.currentValuePaise)} · cost ${money(snapshot.totalCostPaise)}",
                zone = zone,
            )

            val returnEvent = if (snapshot.previousValuePaise != null && snapshot.marketMovementPaise != 0L) {
                val denominator = snapshot.previousValuePaise.toDouble() + snapshot.netContributionPaise.toDouble() * 0.5
                val returnPercent = if (denominator > 0.5) snapshot.marketMovementPaise * 100.0 / denominator else null
                val movementEstimated = snapshot.netContributionPaise < 0L
                val flowTimingEstimated = snapshot.netContributionPaise != 0L
                val quality = when {
                    movementEstimated -> "balance-return estimate; redemption proceeds not recorded"
                    flowTimingEstimated -> "period return estimated because exact contribution timing is not recorded"
                    else -> "market movement since previous check-in"
                }
                val detail = buildString {
                    append(quality)
                    returnPercent?.let {
                        append(" · ")
                        append(if (it >= 0.0) "+" else "")
                        append("%.1f%%".format(it))
                    }
                }
                FinancialEvent.InvestmentPerformance(
                    snapshotId = snapshot.id,
                    accountId = snapshot.accountId,
                    sortEpochMs = dayEpochMs,
                    title = if (snapshot.marketMovementPaise > 0L) "$accountName gained" else "$accountName declined",
                    detail = detail,
                    amountPaise = snapshot.marketMovementPaise,
                )
            } else {
                null
            }
            sequenceOf(checkIn, returnEvent).filterNotNull()
        }.filter { sinceEpochMs == null || it.sortEpochMs >= sinceEpochMs }

        val sortedSampada = netWorthSnapshots.sortedBy { it.asOfEpochDay }
        val sampadaEvents = sortedSampada.asSequence()
            .mapIndexed { index, snapshot ->
                val prior = sortedSampada.getOrNull(index - 1)
                val change = prior?.let { snapshot.netWorthPaise - it.netWorthPaise }
                FinancialEvent.SampadaSnapshot(
                    source = snapshot,
                    changePaise = change,
                    detail = buildString {
                        append("Net worth ${money(snapshot.netWorthPaise)}")
                        append(" · Nivesha ${money(snapshot.portfolioValuePaise)}")
                        if (change != null) {
                            append(" · change ")
                            if (change > 0) append("+")
                            append(money(change))
                        }
                    },
                    zone = zone,
                )
            }
            .filter { sinceEpochMs == null || it.sortEpochMs >= sinceEpochMs }

        val goalEvents = goals.asSequence()
            .map { goal ->
                FinancialEvent.GoalCreated(
                    source = goal,
                    detail = buildString {
                        append(goal.name)
                        goal.targetDateEpochDay?.let { targetDay ->
                            append(" · target ")
                            append(LocalDate.ofEpochDay(targetDay))
                        }
                    },
                )
            }
            .filter { sinceEpochMs == null || it.sortEpochMs >= sinceEpochMs }

        val milestoneEvents = buildGoalMilestones(
            transactions = transactions,
            snapshots = snapshots,
            accounts = accounts,
            goals = goals,
            allocations = goalAllocations,
            zone = zone,
        ).asSequence().filter { sinceEpochMs == null || it.sortEpochMs >= sinceEpochMs }

        val timelineInsights = (insights + historicalUnusualExpenseInsights(transactions))
            .distinctBy { it.id }
        val insightEvents = timelineInsights.asSequence()
            .mapNotNull { insight ->
                insight.observedAtEpochMs?.let { observed ->
                    FinancialEvent.InsightSignal(insight = insight, sortEpochMs = observed)
                }
            }
            .filter { sinceEpochMs == null || it.sortEpochMs >= sinceEpochMs }

        return (txEvents + snapshotFacts + sampadaEvents + goalEvents + milestoneEvents + insightEvents)
            .sortedWith(compareByDescending<FinancialEvent> { it.sortEpochMs }.thenBy { it.id })
            .toList()
    }

    /**
     * Reconstructs goal funding only at source-fact dates and emits a milestone the first time a
     * threshold is crossed. This keeps milestones derived and auditable without a goal ledger.
     */
    private fun buildGoalMilestones(
        transactions: List<TransactionEntity>,
        snapshots: List<InvestmentBalanceSnapshotEntity>,
        accounts: List<AccountEntity>,
        goals: List<GoalEntity>,
        allocations: List<GoalAllocationEntity>,
        zone: ZoneId,
    ): List<FinancialEvent.GoalMilestone> {
        val accountById = accounts.associateBy { it.id }
        val transactionsByAccount = transactions
            .filterNot { it.isHoldingTank }
            .groupBy { it.accountId }
        val snapshotsByAccount = snapshots.groupBy { it.accountId }
        val allocationsByGoal = allocations.groupBy { it.goalId }
        val result = mutableListOf<FinancialEvent.GoalMilestone>()

        goals.filterNot { it.isArchived || it.targetAmountPaise <= 0L }.forEach { goal ->
            val goalAllocations = allocationsByGoal[goal.id].orEmpty()
            if (goalAllocations.isEmpty()) return@forEach

            val goalCreatedDate = Instant.ofEpochMilli(goal.createdAtEpochMs).atZone(zone).toLocalDate()
            val eventEpochByDate = linkedMapOf<LocalDate, Long>()
            eventEpochByDate[goalCreatedDate] = goal.createdAtEpochMs

            goalAllocations.forEach { allocation ->
                transactionsByAccount[allocation.accountId].orEmpty().forEach { transaction ->
                    val date = Instant.ofEpochMilli(transaction.occurredAtEpochMs).atZone(zone).toLocalDate()
                    if (!date.isBefore(goalCreatedDate)) {
                        eventEpochByDate[date] = maxOf(eventEpochByDate[date] ?: Long.MIN_VALUE, transaction.occurredAtEpochMs)
                    }
                }
                snapshotsByAccount[allocation.accountId].orEmpty().forEach { snapshot ->
                    val date = LocalDate.ofEpochDay(snapshot.asOfEpochDay)
                    if (!date.isBefore(goalCreatedDate)) {
                        val epoch = date.atStartOfDay(zone).toInstant().toEpochMilli()
                        eventEpochByDate[date] = maxOf(eventEpochByDate[date] ?: Long.MIN_VALUE, epoch)
                    }
                }
            }

            var previousPercent = 0.0
            val emitted = mutableSetOf<Int>()
            eventEpochByDate.keys.sorted().forEach { date ->
                val funded = goalAllocations.sumOf { allocation ->
                    val account = accountById[allocation.accountId] ?: return@sumOf 0L
                    val sourceValue = historicalSourceValue(
                        account = account,
                        date = date,
                        transactions = transactionsByAccount[account.id].orEmpty(),
                        snapshots = snapshotsByAccount[account.id].orEmpty(),
                        zone = zone,
                    )
                    sourceValue * allocation.allocationBps.coerceIn(0, 10_000) / 10_000L
                }.coerceAtLeast(0L)
                val currentPercent = funded * 100.0 / goal.targetAmountPaise
                val crossed = goalMilestones
                    .filter { it !in emitted && previousPercent < it && currentPercent >= it }
                    .maxOrNull()
                if (crossed != null) {
                    emitted += goalMilestones.filter { it <= crossed }
                    val title = if (crossed == 100) {
                        "${goal.name} fully funded"
                    } else {
                        "${goal.name} reached $crossed%"
                    }
                    result += FinancialEvent.GoalMilestone(
                        goalId = goal.id,
                        percent = crossed,
                        sortEpochMs = eventEpochByDate.getValue(date),
                        title = title,
                        detail = "${money(funded)} of ${money(goal.targetAmountPaise)} linked from existing Khata/Nivesha values",
                        amountPaise = funded,
                    )
                }
                previousPercent = currentPercent
            }
        }
        return result
    }

    /**
     * Detect unusual debits at the time they occurred using only the preceding 90 days. This makes
     * the all-time story reproducible; it does not depend on today's rolling insight window.
     */
    private fun historicalUnusualExpenseInsights(
        transactions: List<TransactionEntity>,
    ): List<FinancialInsight> {
        val debits = transactions
            .asSequence()
            .filter {
                it.direction == TransactionDirection.DEBIT &&
                    it.envelopeType != EnvelopeType.INVESTMENT &&
                    !it.isHoldingTank
            }
            .sortedBy { it.occurredAtEpochMs }
            .toList()
        if (debits.size < 9) return emptyList()

        val windowMs = 90L * 24L * 60L * 60L * 1000L
        return debits.mapNotNull { transaction ->
            val prior = debits
                .asSequence()
                .filter {
                    it.occurredAtEpochMs < transaction.occurredAtEpochMs &&
                        it.occurredAtEpochMs >= transaction.occurredAtEpochMs - windowMs
                }
                .map { it.amountPaise }
                .sorted()
                .toList()
            if (prior.size < 8) return@mapNotNull null
            val middle = prior.size / 2
            val median = if (prior.size % 2 == 1) {
                prior[middle]
            } else {
                (prior[middle - 1] + prior[middle]) / 2L
            }
            if (median <= 0L || transaction.amountPaise < median * 3L) return@mapNotNull null

            val label = transaction.payee
                ?: transaction.merchant
                ?: transaction.category
                ?: "Expense"
            FinancialInsight(
                id = "unusual-expense-${transaction.id}",
                title = "Unusual expense detected",
                explanation = "$label was ${money(transaction.amountPaise)}, about ${"%.1f".format(transaction.amountPaise.toDouble() / median)}× the median of your preceding 90-day confirmed debits (${money(median)}).",
                evidenceTransactionIds = listOf(transaction.id),
                observedAtEpochMs = transaction.occurredAtEpochMs,
                timelineAmountPaise = -transaction.amountPaise,
            )
        }
    }

    private fun historicalSourceValue(
        account: AccountEntity,
        date: LocalDate,
        transactions: List<TransactionEntity>,
        snapshots: List<InvestmentBalanceSnapshotEntity>,
        zone: ZoneId,
    ): Long {
        if (account.kind == AccountKind.CREDIT) return 0L
        val accountCreatedDate = Instant.ofEpochMilli(account.createdAtEpochMs).atZone(zone).toLocalDate()
        if (date.isBefore(accountCreatedDate)) return 0L

        if (account.kind == AccountKind.INVESTMENT) {
            return snapshots
                .asSequence()
                .filter { it.asOfEpochDay <= date.toEpochDay() }
                .maxByOrNull { it.asOfEpochDay }
                ?.currentValuePaise
                ?.coerceAtLeast(0L)
                ?: 0L
        }

        val movement = transactions.asSequence()
            .filter {
                val txDate = Instant.ofEpochMilli(it.occurredAtEpochMs).atZone(zone).toLocalDate()
                !txDate.isAfter(date)
            }
            .sumOf { transaction ->
                if (transaction.direction == TransactionDirection.CREDIT) transaction.amountPaise else -transaction.amountPaise
            }
        return (account.openingBalancePaise + movement).coerceAtLeast(0L)
    }
}
