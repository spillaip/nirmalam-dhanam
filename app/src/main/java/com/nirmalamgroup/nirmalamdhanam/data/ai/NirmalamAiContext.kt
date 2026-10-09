package com.nirmalamgroup.nirmalamdhanam.data.ai

import com.nirmalamgroup.nirmalamdhanam.data.local.AccountBalance
import com.nirmalamgroup.nirmalamdhanam.data.local.AccountEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.AccountKind
import com.nirmalamgroup.nirmalamdhanam.data.local.AccountProductType
import com.nirmalamgroup.nirmalamdhanam.data.local.AssetClass
import com.nirmalamgroup.nirmalamdhanam.data.local.CategoryEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.EnvelopeType
import com.nirmalamgroup.nirmalamdhanam.data.local.InvestmentBalanceSnapshotEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionDirection
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class NirmalamAiInsight(val title: String, val prompt: String) {
    PORTFOLIO_DRIFT(
        "Check Portfolio Drift",
        "Using only the supplied asset-class current and target allocations, describe the measured drift and the mechanical Rupee gap to the recorded targets. Do not recommend trades or products."
    ),
    SURPLUS_ROUTER(
        "Surplus Capital Router",
        "Using only the supplied current-month surplus and recorded target gaps, show a mechanical illustration of how that surplus maps to underweight targets. Treat this as arithmetic, not an investment recommendation."
    ),
    TAX_SHIELD(
        "Review Tax-Shield (80C/NPS)",
        "Summarize only the supplied current-financial-year, snapshot-derived contribution evidence by product. Do not claim tax eligibility, deductibility, statutory headroom, or tax advice; clearly distinguish recorded cost deltas from tax-qualified contributions."
    ),
    NET_WORTH_QUALITY(
        "Net Worth Health Score",
        "Assess the supplied net-worth composition using liquidity, liabilities, portfolio value, recorded contribution deltas, and recorded market movement. Explain the arithmetic and avoid financial recommendations."
    ),
    EMERGENCY_RUNWAY(
        "Emergency Buffer & Runway",
        "Measure the supplied liquid cash and reserves against the supplied trailing 90-day transaction-based burn rate. Report the arithmetic runway in months and the evidence window used."
    ),
    TOKEN_HEALTH(
        "Token Health Score (0-100)",
        "Score only when the supplied data includes asset-backing evidence, liquidity/haircut metadata, intrinsic versus market value, and regulatory classification. If any required evidence is missing, do not invent it or produce a score."
    )
}

data class NirmalamAiPreparedContext(
    val available: Boolean,
    val summary: String,
    val missingData: List<String> = emptyList()
) {
    val unavailableReason: String
        get() = if (missingData.isEmpty()) "Required data is not available." else "Needs: ${missingData.joinToString()}."
}

/**
 * Builds the minimum aggregate context needed for one AI preset.
 * Raw payees, descriptions, account names/IDs, and transaction text are never included.
 */
fun prepareNirmalamAiContext(
    insight: NirmalamAiInsight,
    cashPaise: Long,
    transactions: List<TransactionEntity>,
    investmentHistory: List<InvestmentBalanceSnapshotEntity>,
    accounts: List<AccountEntity>,
    balances: List<AccountBalance>,
    categories: List<CategoryEntity>,
    nowEpochMs: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault()
): NirmalamAiPreparedContext {
    val nowDate = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate()
    val confirmed = transactions.filterNot { it.isHoldingTank }
    val latestByAccount = investmentHistory
        .filter { it.asOfEpochDay <= nowDate.toEpochDay() }
        .groupBy { it.accountId }
        .mapValues { (_, values) -> values.maxBy { it.asOfEpochDay } }
    val activeInvestments = accounts.filter { !it.isArchived && it.kind == AccountKind.INVESTMENT }

    val monthStart = nowDate.withDayOfMonth(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    val nextMonthStart = nowDate.plusMonths(1).withDayOfMonth(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    val monthTransactions = confirmed.filter { it.occurredAtEpochMs >= monthStart && it.occurredAtEpochMs < nextMonthStart }
    val monthIncome = monthTransactions.filter { it.direction == TransactionDirection.CREDIT }.sumOf { it.amountPaise }
    val monthExpense = monthTransactions.filter {
        it.direction == TransactionDirection.DEBIT && it.envelopeType != EnvelopeType.INVESTMENT
    }.sumOf { it.amountPaise }
    val monthSurplus = monthIncome - monthExpense

    val portfolioValue = activeInvestments.sumOf { latestByAccount[it.id]?.currentValuePaise ?: 0L }
    val targetTotalBps = activeInvestments.sumOf { it.targetAllocationBps }
    val missingCurrentBalances = activeInvestments.count { latestByAccount[it.id] == null }
    val portfolioRows = activeInvestments
        .groupBy { it.assetClass }
        .map { (assetClass, grouped) ->
            val currentValue = grouped.sumOf { latestByAccount[it.id]?.currentValuePaise ?: 0L }
            val targetBps = grouped.sumOf { it.targetAllocationBps }
            val currentBps = if (portfolioValue > 0L) ((currentValue.toDouble() / portfolioValue) * 10_000.0).roundToInt() else 0
            val targetValue = if (portfolioValue > 0L) (portfolioValue.toDouble() * targetBps / 10_000.0).roundToLong() else 0L
            PortfolioAiRow(assetClass, currentValue, currentBps, targetBps, targetValue - currentValue)
        }
        .sortedBy { it.assetClass.name }

    fun portfolioMissingData(): List<String> = buildList {
        if (activeInvestments.isEmpty()) add("at least one active investment asset")
        if (missingCurrentBalances > 0) add("current balance check-ins for $missingCurrentBalances investment asset(s)")
        if (portfolioValue <= 0L) add("a positive current portfolio value")
        if (targetTotalBps != 10_000) add("portfolio targets totaling 100% (currently ${formatPercentBps(targetTotalBps)})")
    }

    val reserves = balances.filter { it.kind == AccountKind.SAVINGS || it.kind == AccountKind.EMERGENCY }
        .sumOf { it.balancePaise.coerceAtLeast(0) }
    val spendingAssets = balances.filter { it.kind == AccountKind.SPENDING }
        .sumOf { it.balancePaise.coerceAtLeast(0) }
    val liabilities = balances.filter { it.kind == AccountKind.CREDIT }
        .sumOf { (-it.balancePaise).coerceAtLeast(0) }
    val liquidAssets = spendingAssets + reserves
    val availableLiquidAfterCredit = (cashPaise + reserves).coerceAtLeast(0)
    val netWorth = liquidAssets + portfolioValue - liabilities

    val ninetyDaysAgo = nowEpochMs - 90L * 24L * 60L * 60L * 1000L
    val ninetyDayExpense = confirmed.filter {
        it.occurredAtEpochMs >= ninetyDaysAgo &&
            it.occurredAtEpochMs <= nowEpochMs &&
            it.direction == TransactionDirection.DEBIT &&
            it.envelopeType != EnvelopeType.INVESTMENT
    }.sumOf { it.amountPaise }
    val monthlyBurn = ninetyDayExpense / 3.0
    val runwayMonths = if (monthlyBurn > 0.0) availableLiquidAfterCredit.toDouble() / monthlyBurn else null

    val latestContributionDelta = activeInvestments.sumOf { latestByAccount[it.id]?.netContributionPaise ?: 0L }
    val latestMarketMovement = activeInvestments.sumOf { investment ->
        latestByAccount[investment.id]?.takeIf { it.netContributionPaise >= 0L }?.marketMovementPaise ?: 0L
    }
    val latestWithdrawalEstimates = activeInvestments.count { investment ->
        (latestByAccount[investment.id]?.netContributionPaise ?: 0L) < 0L
    }

    val categoryMeta = categories.associateBy { it.name }
    val spendingCategorySummary = monthTransactions
        .filter { it.direction == TransactionDirection.DEBIT && it.envelopeType != EnvelopeType.INVESTMENT }
        .groupBy { it.category?.ifBlank { null } ?: "Uncategorised" }
        .map { (name, rows) ->
            val amount = rows.sumOf { it.amountPaise }
            val priority = categoryMeta[name]?.priority?.name ?: "UNKNOWN"
            "$name=$priority:${formatInr(amount)}"
        }
        .sorted()
        .joinToString("; ")
        .ifBlank { "none" }

    return when (insight) {
        NirmalamAiInsight.PORTFOLIO_DRIFT -> {
            val missing = portfolioMissingData()
            NirmalamAiPreparedContext(
                available = missing.isEmpty(),
                missingData = missing,
                summary = buildString {
                    append("Currency INR. Current portfolio value ${formatInr(portfolioValue)}. Recorded target total ${formatPercentBps(targetTotalBps)}. ")
                    append("Asset-class allocation evidence: ${formatPortfolioRows(portfolioRows)}. ")
                    append("Positive target gap means below the recorded target; negative means above it. No account names, identifiers, or security names are supplied.")
                }
            )
        }

        NirmalamAiInsight.SURPLUS_ROUTER -> {
            val missing = portfolioMissingData().toMutableList()
            if (monthTransactions.isEmpty()) missing += "current-month income/expense transactions"
            NirmalamAiPreparedContext(
                available = missing.isEmpty(),
                missingData = missing,
                summary = buildString {
                    append("Currency INR. Current-month income ${formatInr(monthIncome)}; consumption expense ${formatInr(monthExpense)}; arithmetic surplus ${formatInr(monthSurplus)}. ")
                    append("Current portfolio value ${formatInr(portfolioValue)}. Asset-class target gaps: ${formatPortfolioRows(portfolioRows)}. ")
                    if (monthSurplus <= 0L) append("There is no positive current-month surplus to route. ")
                    append("Any allocation shown must be a mechanical illustration against recorded targets, not a recommendation.")
                }
            )
        }

        NirmalamAiInsight.TAX_SHIELD -> {
            val fiscalYearStart = if (nowDate.monthValue >= 4) LocalDate.of(nowDate.year, 4, 1) else LocalDate.of(nowDate.year - 1, 4, 1)
            val fiscalYearEnd = fiscalYearStart.plusYears(1)
            val taxProducts = setOf(AccountProductType.PPF, AccountProductType.EPF, AccountProductType.NPS)
            val taxAccounts = activeInvestments.filter { it.productType in taxProducts }
            val rows = taxAccounts.groupBy { it.productType }.map { (product, grouped) ->
                val ids = grouped.mapTo(mutableSetOf()) { it.id }
                val snapshots = investmentHistory.filter {
                    it.accountId in ids && it.asOfEpochDay >= fiscalYearStart.toEpochDay() && it.asOfEpochDay < fiscalYearEnd.toEpochDay()
                }
                TaxAiRow(
                    product = product,
                    positiveCostDeltaPaise = snapshots.sumOf { it.netContributionPaise.coerceAtLeast(0) },
                    withdrawalsPaise = snapshots.sumOf { (-it.netContributionPaise).coerceAtLeast(0) },
                    checkInCount = snapshots.size
                )
            }.sortedBy { it.product.name }
            val missing = buildList {
                if (taxAccounts.isEmpty()) add("a PPF, EPF, or NPS investment account")
                if (taxAccounts.isNotEmpty() && rows.sumOf { it.checkInCount } == 0) add("current-financial-year balance check-ins for tax-product accounts")
            }
            NirmalamAiPreparedContext(
                available = missing.isEmpty(),
                missingData = missing,
                summary = buildString {
                    append("Currency INR. Financial year ${fiscalYearStart.year}-${fiscalYearEnd.year}. ")
                    append("Snapshot-derived tax-product evidence: ${rows.joinToString("; ") { "${it.product}: positive cost deltas ${formatInr(it.positiveCostDeltaPaise)}, withdrawals ${formatInr(it.withdrawalsPaise)}, check-ins ${it.checkInCount}" }.ifBlank { "none" }}. ")
                    append("These are balance-snapshot cost deltas, not verified tax-qualified contributions. No deduction eligibility, salary payroll detail, taxable income, tax regime, or statutory headroom is supplied.")
                }
            )
        }

        NirmalamAiInsight.NET_WORTH_QUALITY -> NirmalamAiPreparedContext(
            available = balances.isNotEmpty() || portfolioValue > 0L,
            missingData = if (balances.isEmpty() && portfolioValue <= 0L) listOf("account balances or investment balance check-ins") else emptyList(),
            summary = buildString {
                append("Currency INR. Available spending cash after recorded credit liabilities ${formatInr(cashPaise)}; liquid assets ${formatInr(liquidAssets)}; liabilities ${formatInr(liabilities)}; portfolio value ${formatInr(portfolioValue)}; arithmetic net worth ${formatInr(netWorth)}. ")
                val liquidityRatio = if (liquidAssets + portfolioValue > 0L) liquidAssets.toDouble() / (liquidAssets + portfolioValue) else null
                val solvencyRatio = if (liabilities > 0L) (liquidAssets + portfolioValue).toDouble() / liabilities else null
                append("Liquidity ratio ${liquidityRatio?.let { formatRatio(it) } ?: "not measurable"}; assets-to-liabilities ratio ${solvencyRatio?.let { formatRatio(it) } ?: "no recorded credit liability"}. ")
                append("Latest recorded contribution delta ${formatInr(latestContributionDelta)}; comparable latest market movement ${formatInr(latestMarketMovement)}. ")
                if (latestWithdrawalEstimates > 0) append("$latestWithdrawalEstimates latest investment withdrawal(s) are excluded from exact market-movement evidence because redemption proceeds are not recorded. ")
                append("Spending categories this month: $spendingCategorySummary.")
            }
        )

        NirmalamAiInsight.EMERGENCY_RUNWAY -> {
            val missing = buildList {
                if (ninetyDayExpense <= 0L) add("consumption-expense history in the trailing 90 days")
                if (availableLiquidAfterCredit <= 0L) add("positive available cash or reserve balances after recorded credit liabilities")
            }
            NirmalamAiPreparedContext(
                available = missing.isEmpty(),
                missingData = missing,
                summary = "Currency INR. Available cash plus reserves after recorded credit liabilities ${formatInr(availableLiquidAfterCredit)}. Trailing-90-day consumption expense ${formatInr(ninetyDayExpense)}; three-month average monthly burn ${formatInr(monthlyBurn.roundToLong())}; arithmetic runway ${runwayMonths?.let { String.format(Locale.US, "%.1f months", it) } ?: "not measurable"}. Investment transfers are excluded from burn."
            )
        }

        NirmalamAiInsight.TOKEN_HEALTH -> {
            val tokenValuationEvidence = activeInvestments.filter { it.currentMarketPaise > 0L || it.intrinsicValuePaise > 0L }
            val missing = mutableListOf<String>()
            if (tokenValuationEvidence.isEmpty()) missing += "token/RWA market and intrinsic-value evidence"
            missing += "asset-backing evidence"
            missing += "liquidity/haircut metadata"
            missing += "regulatory classification"
            NirmalamAiPreparedContext(
                available = false,
                missingData = missing,
                summary = "Token/RWA scoring is intentionally disabled because the current Dhanam schema does not contain enough structured evidence to support the requested score without invention."
            )
        }
    }
}

/** Compatibility wrapper for callers that still need a generic summary. Prefer [prepareNirmalamAiContext]. */
fun buildNirmalamAiSummary(
    cashPaise: Long,
    transactions: List<TransactionEntity>,
    investmentHistory: List<InvestmentBalanceSnapshotEntity>,
    accounts: List<AccountEntity>,
    balances: List<AccountBalance>,
    categories: List<CategoryEntity>
): String = prepareNirmalamAiContext(
    insight = NirmalamAiInsight.NET_WORTH_QUALITY,
    cashPaise = cashPaise,
    transactions = transactions,
    investmentHistory = investmentHistory,
    accounts = accounts,
    balances = balances,
    categories = categories
).summary

private data class PortfolioAiRow(
    val assetClass: AssetClass,
    val currentValuePaise: Long,
    val currentBps: Int,
    val targetBps: Int,
    val targetGapPaise: Long
)

private data class TaxAiRow(
    val product: AccountProductType,
    val positiveCostDeltaPaise: Long,
    val withdrawalsPaise: Long,
    val checkInCount: Int
)

private fun formatPortfolioRows(rows: List<PortfolioAiRow>): String = rows.joinToString("; ") {
    "${it.assetClass}: current ${formatInr(it.currentValuePaise)} (${formatPercentBps(it.currentBps)}), target ${formatPercentBps(it.targetBps)}, target gap ${formatInr(it.targetGapPaise)}"
}.ifBlank { "none" }

private fun formatInr(paise: Long): String = "INR ${String.format(Locale.US, "%.2f", paise / 100.0)}"
private fun formatPercentBps(bps: Int): String = String.format(Locale.US, "%.2f%%", bps / 100.0)
private fun formatRatio(value: Double): String = String.format(Locale.US, "%.2f", value)
