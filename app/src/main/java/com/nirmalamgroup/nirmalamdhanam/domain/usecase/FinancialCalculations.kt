package com.nirmalamgroup.nirmalamdhanam.domain.usecase

import java.util.Currency
import java.util.Locale

/** Pure calculations shared by the presentation layer and unit tests. All amounts are paise. */
object FinancialCalculations {
    fun safeToSpend(dailyLimitPaise: Long, spentTodayPaise: Long): Long =
        (dailyLimitPaise - spentTodayPaise).coerceAtLeast(0)

    fun portfolioGain(valuePaise: Long, costPaise: Long): Long = valuePaise - costPaise

    fun netWorth(
        spendingPaise: Long,
        reservePaise: Long,
        investmentPaise: Long,
        liabilityPaise: Long
    ): Long = spendingPaise + reservePaise + investmentPaise - liabilityPaise

    /**
     * Annualised money-weighted return. Cash outflows are negative and the final current value
     * is positive. Returns null until a dated holding has both an investment and a later value.
     */
    fun xirrPercent(cashFlows: List<Pair<Long, Long>>): Double? {
        if (cashFlows.size < 2 || cashFlows.none { it.second < 0 } || cashFlows.none { it.second > 0 }) return null
        val firstDay = cashFlows.minOf { it.first }
        if (cashFlows.all { it.first == firstDay }) return null
        fun netPresentValue(rate: Double): Double = cashFlows.sumOf { (day, amount) ->
            amount.toDouble() / Math.pow(1.0 + rate, (day - firstDay) / 365.25)
        }
        var low = -0.9999
        var high = 10.0
        var lowValue = netPresentValue(low)
        val highValue = netPresentValue(high)
        if (!lowValue.isFinite() || !highValue.isFinite() || lowValue * highValue > 0.0) return null
        repeat(100) {
            val midpoint = (low + high) / 2.0
            val middleValue = netPresentValue(midpoint)
            if (!middleValue.isFinite()) return null
            if (kotlin.math.abs(middleValue) < 0.01) return midpoint * 100.0
            if (lowValue * middleValue <= 0.0) {
                high = midpoint
            } else {
                low = midpoint
                lowValue = middleValue
            }
        }
        return ((low + high) / 2.0) * 100.0
    }
}

object MoneyFormatter {
    fun format(paise: Long, currencyCode: String = "INR", includeSign: Boolean = false): String {
        val sign = if (includeSign && paise > 0) "+" else ""
        val locale = if (currencyCode == "INR") Locale("en", "IN") else Locale.getDefault()
        val amount = java.text.NumberFormat.getCurrencyInstance(locale).apply {
            currency = Currency.getInstance(currencyCode)
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(paise / 100.0)
        return "$sign$amount"
    }
}
