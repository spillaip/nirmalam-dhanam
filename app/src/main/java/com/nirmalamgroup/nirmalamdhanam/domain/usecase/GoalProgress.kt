package com.nirmalamgroup.nirmalamdhanam.domain.usecase

import com.nirmalamgroup.nirmalamdhanam.data.local.AccountBalance
import com.nirmalamgroup.nirmalamdhanam.data.local.AccountEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.AccountKind
import com.nirmalamgroup.nirmalamdhanam.data.local.GoalAllocationEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.GoalEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.InvestmentBalanceSnapshotEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionDirection
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.roundToLong

enum class GoalScheduleStatus {
    FUNDED,
    ON_TRACK,
    AT_RISK,
    OVERDUE,
    NO_TARGET_DATE,
    NOT_ENOUGH_HISTORY,
    NO_POSITIVE_PACE,
}

data class GoalFundingSource(
    val allocationId: String,
    val accountId: String,
    val accountName: String,
    val accountKind: AccountKind?,
    val allocationBps: Int,
    val sourceValuePaise: Long,
    val fundedPaise: Long,
    val latestValuationEpochDay: Long? = null,
    /** Total allocation of this same source across all active goals. More than 10,000 means over-allocated. */
    val totalActiveGoalAllocationBps: Int = allocationBps,
) {
    val allocationPercent: Double get() = allocationBps / 100.0
    val isSharedAcrossGoals: Boolean get() = totalActiveGoalAllocationBps > allocationBps
    val isOverAllocated: Boolean get() = totalActiveGoalAllocationBps > 10_000
}

object GoalAllocationPolicy {
    /** Active-goal allocation usage for each account. Values may exceed 10,000 for legacy/imported data so UI can flag it. */
    fun activeUsageBpsByAccount(
        goals: List<GoalEntity>,
        allocations: List<GoalAllocationEntity>,
    ): Map<String, Int> {
        val activeGoalIds = goals.filterNot { it.isArchived }.mapTo(mutableSetOf()) { it.id }
        return allocations
            .asSequence()
            .filter { it.goalId in activeGoalIds }
            .groupBy { it.accountId }
            .mapValues { (_, rows) -> rows.sumOf { it.allocationBps } }
    }

    /** Remaining capacity for [accountId] when editing [goalId], excluding that goal's current allocation. */
    fun maxAvailableBpsForGoal(
        accountId: String,
        goalId: String,
        goals: List<GoalEntity>,
        allocations: List<GoalAllocationEntity>,
    ): Int {
        val activeGoalIds = goals.filterNot { it.isArchived }.mapTo(mutableSetOf()) { it.id }
        val usedByOtherGoals = allocations
            .asSequence()
            .filter { it.accountId == accountId && it.goalId != goalId && it.goalId in activeGoalIds }
            .sumOf { it.allocationBps }
        return (10_000 - usedByOtherGoals).coerceIn(0, 10_000)
    }
}

data class GoalProgress(
    val goal: GoalEntity,
    val fundedPaise: Long,
    val targetPaise: Long,
    val progressPercent: Double,
    val remainingPaise: Long = (targetPaise - fundedPaise).coerceAtLeast(0L),
    val fundingSources: List<GoalFundingSource> = emptyList(),
    val scheduleStatus: GoalScheduleStatus = GoalScheduleStatus.NO_TARGET_DATE,
    val daysToTarget: Long? = null,
    val observedFundingPaise: Long? = null,
    val observedFundingDays: Int = 0,
    val observedMonthlyFundingPaise: Long? = null,
    val projectedCompletionDate: LocalDate? = null,
    val projectionExplanation: String = "Projection unavailable.",
    val nextAction: String = "Review this goal when you have more funding history.",
)

/**
 * Derives goal progress from existing Khata/Nivesha facts. A goal never owns a balance.
 *
 * Current funding = current linked source value × allocation percentage.
 * Projection = observed linked-source net funding/contributions over a rolling window.
 * Market movement is deliberately not extrapolated as future funding.
 */
object GoalProgressCalculator {
    private const val DEFAULT_PACE_WINDOW_DAYS = 90L
    private const val MIN_PROJECTION_HISTORY_DAYS = 30
    private const val MAX_PROJECTION_DAYS = 3650L
    private const val AVERAGE_DAYS_PER_MONTH = 30.4375

    fun calculate(
        goals: List<GoalEntity>,
        allocations: List<GoalAllocationEntity>,
        balances: List<AccountBalance>,
        latestInvestments: List<InvestmentBalanceSnapshotEntity>,
        accounts: List<AccountEntity> = emptyList(),
        transactions: List<TransactionEntity> = emptyList(),
        investmentHistory: List<InvestmentBalanceSnapshotEntity> = latestInvestments,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        paceWindowDays: Long = DEFAULT_PACE_WINDOW_DAYS,
    ): List<GoalProgress> {
        val activeGoals = goals.filterNot { it.isArchived }
        val accountById = accounts.associateBy { it.id }
        val balanceByAccount = balances.associate { it.accountId to it.balancePaise.coerceAtLeast(0L) }
        val latestByInvestment = latestInvestments.associateBy { it.accountId }
        val currentValues = balanceByAccount.toMutableMap().apply {
            latestByInvestment.forEach { (accountId, snapshot) ->
                this[accountId] = snapshot.currentValuePaise.coerceAtLeast(0L)
            }
        }
        val allocationsByGoal = allocations.groupBy { it.goalId }
        val totalAllocationByAccount = GoalAllocationPolicy.activeUsageBpsByAccount(activeGoals, allocations)

        return activeGoals.map { goal ->
            val goalAllocations = allocationsByGoal[goal.id].orEmpty()
            val sources = goalAllocations.map { allocation ->
                val account = accountById[allocation.accountId]
                val accountKind = account?.kind ?: balances.firstOrNull { it.accountId == allocation.accountId }?.kind
                val sourceValue = if (accountKind == AccountKind.CREDIT) 0L else currentValues[allocation.accountId] ?: 0L
                GoalFundingSource(
                    allocationId = allocation.id,
                    accountId = allocation.accountId,
                    accountName = account?.name ?: "Linked Khata",
                    accountKind = accountKind,
                    allocationBps = allocation.allocationBps.coerceIn(0, 10_000),
                    sourceValuePaise = sourceValue,
                    fundedPaise = allocatedAmount(sourceValue, allocation.allocationBps),
                    latestValuationEpochDay = latestByInvestment[allocation.accountId]?.asOfEpochDay,
                    totalActiveGoalAllocationBps = totalAllocationByAccount[allocation.accountId] ?: allocation.allocationBps,
                )
            }
            val funded = sources.sumOf { it.fundedPaise }.coerceAtLeast(0L)
            val target = goal.targetAmountPaise.coerceAtLeast(0L)
            val remaining = (target - funded).coerceAtLeast(0L)
            val percent = if (target <= 0L) 0.0 else (funded * 100.0 / target).coerceIn(0.0, 100.0)

            val targetDate = goal.targetDateEpochDay?.let(LocalDate::ofEpochDay)
            val daysToTarget = targetDate?.let { ChronoUnit.DAYS.between(today, it) }
            val createdDate = Instant.ofEpochMilli(goal.createdAtEpochMs).atZone(zone).toLocalDate()
            val rollingStart = today.minusDays((paceWindowDays - 1L).coerceAtLeast(0L))
            val paceStart = maxOf(rollingStart, createdDate)
            val observedDays = (ChronoUnit.DAYS.between(paceStart, today) + 1L)
                .coerceAtLeast(1L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()

            val observedFunding = if (goalAllocations.isEmpty()) {
                0L
            } else {
                goalAllocations.sumOf { allocation ->
                    val kind = accountById[allocation.accountId]?.kind
                        ?: balances.firstOrNull { it.accountId == allocation.accountId }?.kind
                    val netChange = when (kind) {
                        AccountKind.CREDIT -> 0L
                        AccountKind.INVESTMENT -> investmentContributionInWindow(
                            accountId = allocation.accountId,
                            history = investmentHistory,
                            from = paceStart,
                            through = today,
                        )
                        else -> transactionalNetFundingInWindow(
                            accountId = allocation.accountId,
                            transactions = transactions,
                            from = paceStart,
                            through = today,
                            zone = zone,
                        )
                    }
                    allocatedAmount(netChange, allocation.allocationBps)
                }
            }

            val hasEnoughHistory = observedDays >= MIN_PROJECTION_HISTORY_DAYS
            val positiveObservedFunding = observedFunding.coerceAtLeast(0L)
            val monthlyPace = if (hasEnoughHistory && positiveObservedFunding > 0L) {
                (positiveObservedFunding.toDouble() / observedDays * AVERAGE_DAYS_PER_MONTH).roundToLong()
                    .coerceAtLeast(1L)
            } else {
                null
            }
            val projectedDate = when {
                remaining <= 0L -> today
                monthlyPace == null || monthlyPace <= 0L -> null
                else -> {
                    val dailyPace = positiveObservedFunding.toDouble() / observedDays
                    val daysNeeded = ceil(remaining / dailyPace).toLong()
                    if (daysNeeded in 0L..MAX_PROJECTION_DAYS) today.plusDays(daysNeeded) else null
                }
            }

            val status = scheduleStatus(
                funded = funded,
                target = target,
                targetDate = targetDate,
                today = today,
                hasEnoughHistory = hasEnoughHistory,
                positiveObservedFunding = positiveObservedFunding,
                projectedDate = projectedDate,
            )
            val projectionExplanation = projectionExplanation(
                status = status,
                observedDays = observedDays,
                observedFunding = observedFunding,
                projectedDate = projectedDate,
                targetDate = targetDate,
            )
            val nextAction = nextAction(
                status = status,
                hasSources = sources.isNotEmpty(),
                hasOverAllocation = sources.any { it.isOverAllocated },
            )

            GoalProgress(
                goal = goal,
                fundedPaise = funded,
                targetPaise = target,
                progressPercent = percent,
                remainingPaise = remaining,
                fundingSources = sources,
                scheduleStatus = status,
                daysToTarget = daysToTarget,
                observedFundingPaise = if (sources.isEmpty()) null else observedFunding,
                observedFundingDays = observedDays,
                observedMonthlyFundingPaise = monthlyPace,
                projectedCompletionDate = projectedDate,
                projectionExplanation = projectionExplanation,
                nextAction = nextAction,
            )
        }
    }

    private fun allocatedAmount(valuePaise: Long, allocationBps: Int): Long =
        valuePaise * allocationBps.coerceIn(0, 10_000) / 10_000L

    private fun transactionalNetFundingInWindow(
        accountId: String,
        transactions: List<TransactionEntity>,
        from: LocalDate,
        through: LocalDate,
        zone: ZoneId,
    ): Long = transactions.asSequence()
        .filter { it.accountId == accountId && !it.isHoldingTank }
        .filter {
            val date = Instant.ofEpochMilli(it.occurredAtEpochMs).atZone(zone).toLocalDate()
            !date.isBefore(from) && !date.isAfter(through)
        }
        .sumOf { if (it.direction == TransactionDirection.CREDIT) it.amountPaise else -it.amountPaise }

    private fun investmentContributionInWindow(
        accountId: String,
        history: List<InvestmentBalanceSnapshotEntity>,
        from: LocalDate,
        through: LocalDate,
    ): Long = history.asSequence()
        .filter { it.accountId == accountId }
        .filter {
            val date = LocalDate.ofEpochDay(it.asOfEpochDay)
            !date.isBefore(from) && !date.isAfter(through)
        }
        .sumOf { it.netContributionPaise }

    private fun scheduleStatus(
        funded: Long,
        target: Long,
        targetDate: LocalDate?,
        today: LocalDate,
        hasEnoughHistory: Boolean,
        positiveObservedFunding: Long,
        projectedDate: LocalDate?,
    ): GoalScheduleStatus = when {
        target > 0L && funded >= target -> GoalScheduleStatus.FUNDED
        targetDate != null && targetDate.isBefore(today) -> GoalScheduleStatus.OVERDUE
        targetDate == null -> GoalScheduleStatus.NO_TARGET_DATE
        !hasEnoughHistory -> GoalScheduleStatus.NOT_ENOUGH_HISTORY
        positiveObservedFunding <= 0L -> GoalScheduleStatus.NO_POSITIVE_PACE
        projectedDate == null -> GoalScheduleStatus.AT_RISK
        !projectedDate.isAfter(targetDate) -> GoalScheduleStatus.ON_TRACK
        else -> GoalScheduleStatus.AT_RISK
    }

    private fun projectionExplanation(
        status: GoalScheduleStatus,
        observedDays: Int,
        observedFunding: Long,
        projectedDate: LocalDate?,
        targetDate: LocalDate?,
    ): String = when (status) {
        GoalScheduleStatus.FUNDED -> "The linked sources already cover the recorded target amount."
        GoalScheduleStatus.OVERDUE -> "The target date has passed while the goal remains below its recorded target."
        GoalScheduleStatus.NOT_ENOUGH_HISTORY ->
            "Dhanam needs at least $MIN_PROJECTION_HISTORY_DAYS days of linked-source history before estimating a completion date; $observedDays day${if (observedDays == 1) " is" else "s are"} available."
        GoalScheduleStatus.NO_POSITIVE_PACE ->
            "No positive net funding pace was observed in the available window, so Dhanam will not invent a completion date."
        GoalScheduleStatus.NO_TARGET_DATE -> when {
            projectedDate != null -> "At the observed funding pace, the remaining amount would be reached around $projectedDate. No target date is set."
            observedDays < MIN_PROJECTION_HISTORY_DAYS -> "No target date is set. Dhanam will show an optional completion estimate after at least $MIN_PROJECTION_HISTORY_DAYS days of linked-source history."
            observedFunding <= 0L -> "No target date is set, and no positive net funding pace is currently visible. Progress still follows the linked source values."
            else -> "No target date is set. Progress follows the linked source values without forcing a deadline."
        }
        GoalScheduleStatus.ON_TRACK ->
            "At the observed funding pace, the remaining amount is projected around $projectedDate, on or before the target date $targetDate."
        GoalScheduleStatus.AT_RISK -> when {
            projectedDate != null && targetDate != null ->
                "At the observed funding pace, completion is projected around $projectedDate, after the target date $targetDate."
            observedFunding <= 0L -> "Recent linked-source funding has not increased, so the target date is at risk."
            else -> "The available evidence does not support reaching the target date at the current observed pace."
        }
    }

    private fun nextAction(
        status: GoalScheduleStatus,
        hasSources: Boolean,
        hasOverAllocation: Boolean,
    ): String = when {
        !hasSources -> "Link an existing Khata or Nivesha source so progress can be derived without creating a second balance."
        hasOverAllocation -> "Review shared goal allocations: at least one linked source is allocated above 100% across active goals."
        status == GoalScheduleStatus.FUNDED -> "Review whether this goal should stay active or be archived now that its recorded target is funded."
        status == GoalScheduleStatus.OVERDUE -> "Review the target date or increase linked funding; Dhanam will keep the original financial facts unchanged."
        status == GoalScheduleStatus.AT_RISK -> "Increase regular funding, lower the target, or move the target date if that matches your plan."
        status == GoalScheduleStatus.NO_POSITIVE_PACE -> "Build a positive funding pattern in the linked source before relying on a completion estimate."
        status == GoalScheduleStatus.NOT_ENOUGH_HISTORY -> "Keep recording normal transactions/check-ins; the projection will appear after enough history accumulates."
        status == GoalScheduleStatus.NO_TARGET_DATE -> "Set a target date only if it is useful; the goal can remain amount-based without one."
        else -> "Keep the linked funding pattern consistent and review progress periodically."
    }
}
