package com.nirmalamgroup.nirmalamdhanam

import com.nirmalamgroup.nirmalamdhanam.data.local.CashPosition
import com.nirmalamgroup.nirmalamdhanam.data.local.EnvelopeType
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionDirection
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionEntity
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.CoolDownTankInterceptorUseCase
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.FinancialCalculations
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.MoneyFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FinancialCalculationsTest {
    @Test fun moneyFormatting_usesIndianGroupingAndOptionalSign() {
        assertEquals("₹1,23,456.78", MoneyFormatter.format(12_345_678))
        assertEquals("+₹1,234.50", MoneyFormatter.format(123_450, includeSign = true))
        assertEquals("-₹12.34", MoneyFormatter.format(-1_234))
    }

    @Test fun cashPosition_subtractsCreditLiability() {
        assertEquals(75_000, CashPosition(spendingPaise = 100_000, creditLiabilityPaise = 25_000).trueAvailableCashPaise)
    }

    @Test fun safeToSpend_neverFallsBelowZero() {
        assertEquals(20_000, FinancialCalculations.safeToSpend(50_000, 30_000))
        assertEquals(0, FinancialCalculations.safeToSpend(50_000, 60_000))
    }

    @Test fun coolingTank_holdsOnlyWantsAboveThresholdFor48Hours() {
        val now = 1_000_000L
        val wants = TransactionEntity("want", "cash", 50_001, TransactionDirection.DEBIT, envelopeType = EnvelopeType.WANTS)
        val held = CoolDownTankInterceptorUseCase { now }(wants, 50_000)
        assertEquals(now + 48L * 60 * 60 * 1_000, held.coolDownExpiryEpochMs)
        val need = wants.copy(envelopeType = EnvelopeType.NEEDS)
        val immediate = CoolDownTankInterceptorUseCase { now }(need, 50_000)
        assertFalse(immediate.isHoldingTank)
        assertNull(immediate.coolDownExpiryEpochMs)
    }

    @Test fun portfolioGainAndNetWorth_handleAssetsAndLiabilities() {
        assertEquals(25_000, FinancialCalculations.portfolioGain(valuePaise = 125_000, costPaise = 100_000))
        assertEquals(260_000, FinancialCalculations.netWorth(spendingPaise = 100_000, reservePaise = 50_000, investmentPaise = 150_000, liabilityPaise = 40_000))
    }
}
