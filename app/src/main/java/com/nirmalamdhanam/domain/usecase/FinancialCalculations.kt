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
