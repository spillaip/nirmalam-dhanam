package com.nirmalamgroup.nirmalamdhanam.domain.usecase

import com.nirmalamgroup.nirmalamdhanam.data.local.AccountBalance
import com.nirmalamgroup.nirmalamdhanam.data.local.AccountEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.AccountKind
import com.nirmalamgroup.nirmalamdhanam.data.local.AssetClass
import com.nirmalamgroup.nirmalamdhanam.data.local.CategoryEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.CategoryPriority
import com.nirmalamgroup.nirmalamdhanam.data.local.EnvelopeType
import com.nirmalamgroup.nirmalamdhanam.data.local.InvestmentBalanceSnapshotEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionDirection
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs

internal data class SpendingPace(
    val monthToDatePaise: Long,
    val projectedMonthPaise: Long,
    val priorMonthlyAveragePaise: Long?,
    val variancePercent: Double?,
    val elapsedDays: Int,
    val daysInMonth: Int,
)

internal enum class RecurringCadence { WEEKLY, MONTHLY }

internal data class RecurringMoneyItem(
    val label: String,
    val direction: TransactionDirection,
    val typicalAmountPaise: Long,
    val cadence: RecurringCadence,
    val occurrences: Int,
    val medianIntervalDays: Long,
    val confidencePercent: Int,
)

internal data class RecurringMoneyRadar(
    val items: List<RecurringMoneyItem>,
    val monthlyOutflowPaise: Long,
    val monthlyInflowPaise: Long,
)

internal data class ReserveRunway(
    val reservePaise: Long,
    val essentialMonthlyBurnPaise: Long?,
    val runwayMonths: Double?,
    val evidenceDays: Int,
)

internal data class PortfolioConcentration(
    val portfolioValuePaise: Long,
    val largestHoldingName: String?,
    val largestHoldingSharePercent: Double?,
    val largestAssetClass: AssetClass?,
    val largestAssetClassSharePercent: Double?,
    val targetDriftPercent: Double?,
)

internal enum class MoneyCompassTone { CALM, REVIEW, ACTION }

internal data class MoneyCompassItem(
    val title: String,
    val detail: String,
    val tone: MoneyCompassTone,
)

internal object MoneyIntelligenceEngine {
    fun spendingPace(
        transactions: List<TransactionEntity>,
        asOf: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): SpendingPace {
        val month = YearMonth.from(asOf)
        val confirmedExpenses = transactions.asSequence()
            .filter { !it.isHoldingTank && it.direction == TransactionDirection.DEBIT && it.envelopeType != EnvelopeType.INVESTMENT }
            .map { it to localDate(it, zoneId) }
            .filter { (_, date) -> !date.isAfter(asOf) }
            .toList()

        val monthToDate = confirmedExpenses
            .filter { (_, date) -> YearMonth.from(date) == month }
            .sumOf { (tx, _) -> tx.amountPaise }

        val elapsedDays = asOf.dayOfMonth.coerceAtLeast(1)
        val projected = ((monthToDate.toDouble() / elapsedDays) * month.lengthOfMonth()).toLong().coerceAtLeast(monthToDate)

        val priorMonths = (1..3).map { month.minusMonths(it.toLong()) }
        val priorTotals = priorMonths.map { prior ->
            confirmedExpenses.filter { (_, date) -> YearMonth.from(date) == prior }.sumOf { (tx, _) -> tx.amountPaise }
        }.filter { it > 0L }
        val priorAverage = priorTotals.takeIf { it.isNotEmpty() }?.average()?.toLong()
        val variance = priorAverage?.takeIf { it > 0L }?.let { (projected - it) * 100.0 / it }

        return SpendingPace(monthToDate, projected, priorAverage, variance, elapsedDays, month.lengthOfMonth())
    }

    fun recurringMoneyRadar(
        transactions: List<TransactionEntity>,
        asOf: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): RecurringMoneyRadar {
        val cutoff = asOf.minusMonths(8)
        val groups = transactions.asSequence()
            .filter { !it.isHoldingTank && it.envelopeType != EnvelopeType.INVESTMENT }
            .map { it to localDate(it, zoneId) }
            .filter { (_, date) -> !date.isAfter(asOf) && !date.isBefore(cutoff) }
            .groupBy { (tx, _) -> Pair(tx.direction, recurringLabel(tx).lowercase()) }

        val items = groups.mapNotNull { (_, rows) ->
            if (rows.size < 3) return@mapNotNull null
            val ordered = rows.sortedBy { it.second }
            val intervals = ordered.zipWithNext { a, b -> b.second.toEpochDay() - a.second.toEpochDay() }
                .filter { it > 0 }
            if (intervals.size < 2) return@mapNotNull null
            val medianInterval = medianLong(intervals)
            val cadence = when (medianInterval) {
                in 6L..9L -> RecurringCadence.WEEKLY
                in 20L..40L -> RecurringCadence.MONTHLY
                else -> return@mapNotNull null
            }
            val amounts = ordered.map { it.first.amountPaise }.sorted()
            val typical = medianLong(amounts)
            if (typical <= 0L) return@mapNotNull null
            val meanDeviation = amounts.map { abs(it - typical).toDouble() / typical }.average()
            if (meanDeviation > 0.35) return@mapNotNull null
            val intervalDeviation = intervals.map { abs(it - medianInterval).toDouble() / medianInterval }.average()
            val confidence = (100.0 - (meanDeviation * 55.0 + intervalDeviation * 45.0) * 100.0)
                .toInt().coerceIn(55, 99)
            RecurringMoneyItem(
                label = recurringLabel(ordered.last().first),
                direction = ordered.last().first.direction,
                typicalAmountPaise = typical,
                cadence = cadence,
                occurrences = ordered.size,
                medianIntervalDays = medianInterval,
                confidencePercent = confidence,
            )
        }.sortedWith(compareByDescending<RecurringMoneyItem> { it.confidencePercent }.thenByDescending { it.typicalAmountPaise })

        fun monthlyEquivalent(item: RecurringMoneyItem): Long = when (item.cadence) {
            RecurringCadence.WEEKLY -> (item.typicalAmountPaise * 52L) / 12L
            RecurringCadence.MONTHLY -> item.typicalAmountPaise
        }

        return RecurringMoneyRadar(
            items = items,
            monthlyOutflowPaise = items.filter { it.direction == TransactionDirection.DEBIT }.sumOf(::monthlyEquivalent),
            monthlyInflowPaise = items.filter { it.direction == TransactionDirection.CREDIT }.sumOf(::monthlyEquivalent),
        )
    }

    fun reserveRunway(
        balances: List<AccountBalance>,
        transactions: List<TransactionEntity>,
        categories: List<CategoryEntity>,
        asOf: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): ReserveRunway {
        val reserve = balances.filter { it.kind == AccountKind.SAVINGS || it.kind == AccountKind.EMERGENCY }
            .sumOf { it.balancePaise.coerceAtLeast(0L) }
        val needCategories = categories.filter { it.priority == CategoryPriority.NEED }.map { it.name.lowercase() }.toSet()
        val start = asOf.minusDays(89)
        val essentialSpend = transactions.asSequence()
            .filter { !it.isHoldingTank && it.direction == TransactionDirection.DEBIT && it.envelopeType != EnvelopeType.INVESTMENT }
            .map { it to localDate(it, zoneId) }
            .filter { (_, date) -> !date.isBefore(start) && !date.isAfter(asOf) }
            .filter { (tx, _) -> tx.envelopeType == EnvelopeType.NEEDS || tx.category?.lowercase() in needCategories }
            .sumOf { (tx, _) -> tx.amountPaise }
        val monthlyBurn = essentialSpend.takeIf { it > 0L }?.let { (it * 30.4375 / 90.0).toLong() }
        val runway = monthlyBurn?.takeIf { it > 0L }?.let { reserve.toDouble() / it }
        return ReserveRunway(reserve, monthlyBurn, runway, evidenceDays = 90)
    }

    fun portfolioConcentration(
        accounts: List<AccountEntity>,
        latestInvestments: List<InvestmentBalanceSnapshotEntity>,
    ): PortfolioConcentration {
        val accountById = accounts.associateBy { it.id }
        val valued = latestInvestments.mapNotNull { snapshot ->
            accountById[snapshot.accountId]?.takeIf { !it.isArchived && it.kind == AccountKind.INVESTMENT }
                ?.let { Triple(it, snapshot, snapshot.currentValuePaise.coerceAtLeast(0L)) }
        }.filter { it.third > 0L }
        val total = valued.sumOf { it.third }
        if (total <= 0L) return PortfolioConcentration(0L, null, null, null, null, null)

        val largestHolding = valued.maxByOrNull { it.third }
        val classTotals = valued.groupBy { it.first.assetClass }.mapValues { (_, rows) -> rows.sumOf { it.third } }
        val largestClass = classTotals.maxByOrNull { it.value }

        val targeted = valued.filter { it.first.targetAllocationBps > 0 }
        val targetDrift = if (targeted.isNotEmpty()) {
            val targetTotal = targeted.sumOf { it.third }
            if (targetTotal > 0L) targeted.sumOf { (account, _, value) ->
                abs(value * 10_000.0 / targetTotal - account.targetAllocationBps)
            } / 2.0 / 100.0 else null
        } else null

        return PortfolioConcentration(
            portfolioValuePaise = total,
            largestHoldingName = largestHolding?.first?.name,
            largestHoldingSharePercent = largestHolding?.third?.times(100.0)?.div(total),
            largestAssetClass = largestClass?.key,
            largestAssetClassSharePercent = largestClass?.value?.times(100.0)?.div(total),
            targetDriftPercent = targetDrift,
        )
    }

    fun moneyCompass(
        spendingPace: SpendingPace,
        recurringRadar: RecurringMoneyRadar,
        runway: ReserveRunway,
        concentration: PortfolioConcentration,
        overdueInvestmentCheckIns: Int,
        holdingTankCount: Int,
    ): List<MoneyCompassItem> {
        val candidates = mutableListOf<Pair<Int, MoneyCompassItem>>()

        if (holdingTankCount > 0) candidates += 100 to MoneyCompassItem(
            "Review the cooling tank",
            "$holdingTankCount purchase ${if (holdingTankCount == 1) "decision is" else "decisions are"} waiting for a deliberate choice.",
            MoneyCompassTone.ACTION,
        )
        if (overdueInvestmentCheckIns > 0) candidates += 90 to MoneyCompassItem(
            "Refresh investment values",
            "$overdueInvestmentCheckIns investment ${if (overdueInvestmentCheckIns == 1) "check-in is" else "check-ins are"} at least 30 days old or missing.",
            MoneyCompassTone.REVIEW,
        )
        runway.runwayMonths?.let { months ->
            if (months < 3.0) candidates += 85 to MoneyCompassItem(
                "Strengthen your reserve",
                "Recorded savings and emergency balances cover about %.1f months of recent essential spending.".format(months),
                MoneyCompassTone.ACTION,
            )
        }
        spendingPace.variancePercent?.let { variance ->
            if (variance >= 20.0) candidates += 75 to MoneyCompassItem(
                "Check this month's spending pace",
                "At the current pace, spending projects about %.0f%% above the recent monthly average.".format(variance),
                MoneyCompassTone.REVIEW,
            )
        }
        concentration.largestHoldingSharePercent?.let { share ->
            if (share >= 50.0) candidates += 65 to MoneyCompassItem(
                "Inspect portfolio concentration",
                "${concentration.largestHoldingName ?: "One holding"} represents %.0f%% of the recorded investment portfolio.".format(share),
                MoneyCompassTone.REVIEW,
            )
        }
        if (recurringRadar.monthlyOutflowPaise > 0L && recurringRadar.items.isNotEmpty()) candidates += 45 to MoneyCompassItem(
            "Know your recurring baseline",
            "Dhanam detected ${recurringRadar.items.count { it.direction == TransactionDirection.DEBIT }} recurring outflow pattern(s) from your own ledger history.",
            MoneyCompassTone.CALM,
        )

        if (candidates.isEmpty()) return listOf(
            MoneyCompassItem(
                "Nothing urgent detected",
                "Your current records do not surface a high-priority action. Keep recording real financial events and review when something materially changes.",
                MoneyCompassTone.CALM,
            )
        )
        return candidates.sortedByDescending { it.first }.take(3).map { it.second }
    }

    private fun recurringLabel(tx: TransactionEntity): String =
        tx.payee?.trim()?.takeIf { it.isNotBlank() }
            ?: tx.merchant?.trim()?.takeIf { it.isNotBlank() }
            ?: tx.category?.trim()?.takeIf { it.isNotBlank() }
            ?: "Unlabelled"

    private fun localDate(tx: TransactionEntity, zoneId: ZoneId): LocalDate =
        Instant.ofEpochMilli(tx.occurredAtEpochMs).atZone(zoneId).toLocalDate()

    private fun medianLong(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2L
    }
}
