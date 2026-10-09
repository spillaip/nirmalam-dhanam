package com.nirmalamgroup.nirmalamdhanam.domain.usecase

import com.nirmalamgroup.nirmalamdhanam.data.local.InvestmentBalanceSnapshotEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class InvestmentDeltaEngineTest {
    @Test
    fun firstCheckInDerivesContributionAndGain() {
        val delta = InvestmentDeltaEngine.calculate(null, 100_000, 112_500)
        assertEquals(100_000, delta.netContributionPaise)
        assertEquals(12_500, delta.marketMovementPaise)
        assertEquals(12_500, delta.unrealizedGainPaise)
    }

    @Test
    fun subsequentCheckInSeparatesContributionFromMarketMovement() {
        val previous = InvestmentBalanceSnapshotEntity(
            id = "p", accountId = "a", asOfEpochDay = 1,
            totalCostPaise = 100_000, currentValuePaise = 112_500,
            netContributionPaise = 100_000
        )
        val delta = InvestmentDeltaEngine.calculate(previous, 120_000, 140_000)
        assertEquals(20_000, delta.netContributionPaise)
        assertEquals(27_500, delta.valueDeltaPaise)
        assertEquals(7_500, delta.marketMovementPaise)
    }
}
