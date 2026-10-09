package com.nirmalamgroup.nirmalamdhanam.domain.usecase

import com.nirmalamgroup.nirmalamdhanam.data.local.InvestmentBalanceSnapshotEntity

/**
 * Describes whether a realised gain can be stated from the available evidence.
 *
 * Balance-only check-ins expose cost basis and market value, but a fall in cost basis does not
 * reveal the cash proceeds of a redemption. Dhanam therefore refuses to invent realised gains.
 */
enum class RealizedGainStatus {
    /** No redemption is implied by the cost-basis change, so realised gain for this interval is 0. */
    NOT_APPLICABLE,

    /** A signed external cash flow was supplied, so redemption proceeds and realised gain are known. */
    DETERMINED_FROM_CASH_FLOW,

    /** Cost basis fell, but redemption proceeds are not present in the balance-only check-in. */
    REDEMPTION_PROCEEDS_REQUIRED,
}

/**
 * Auditable delta derived from two investment balance snapshots.
 *
 * [knownExternalCashFlowPaise] follows the portfolio convention used by the engine:
 * positive = money added to the investment, negative = money taken out of the investment.
 * The normal balance check-in UI does not ask for this amount. It is optional evidence that can be
 * supplied by a future transfer/import path when the exact redemption proceeds are known.
 */
data class InvestmentDelta(
    val previousCostPaise: Long?,
    val previousValuePaise: Long?,
    val costDeltaPaise: Long,
    val valueDeltaPaise: Long,
    val netContributionPaise: Long,
    /** Best available movement after external-flow adjustment. */
    val marketMovementPaise: Long,
    /** True when a redemption was inferred from cost basis but its cash proceeds are unknown. */
    val marketMovementIsEstimated: Boolean,
    val previousUnrealizedGainPaise: Long?,
    val unrealizedGainPaise: Long,
    val unrealizedGainDeltaPaise: Long,
    /** Realised gain is nullable rather than fabricated when redemption proceeds are unavailable. */
    val realizedGainPaise: Long?,
    val realizedGainStatus: RealizedGainStatus,
    /** Money return for the interval after the best available external-flow adjustment. */
    val totalReturnSincePreviousPaise: Long?,
    /** Modified-Dietz style interval return. Null for the first check-in or an invalid denominator. */
    val totalReturnSincePreviousPercent: Double?,
    /** True when the period return uses cost-basis withdrawal as a proxy for unknown proceeds. */
    val totalReturnIsEstimated: Boolean,
) {
    val contributionPaise: Long get() = netContributionPaise.coerceAtLeast(0)
    val withdrawalCostBasisPaise: Long get() = (-netContributionPaise).coerceAtLeast(0)
    val withdrawalPaise: Long get() = withdrawalCostBasisPaise
    val hasContribution: Boolean get() = contributionPaise > 0L
    val hasWithdrawal: Boolean get() = withdrawalCostBasisPaise > 0L
}

object InvestmentDeltaEngine {
    /**
     * Derive contribution/withdrawal and performance from a new cost/value check-in.
     *
     * When cost basis falls and exact redemption proceeds are not known, the stored movement and
     * period return use the cost-basis reduction as the conservative cash-flow proxy and are marked
     * as estimates. Realised gain remains null. This is preferable to presenting a fabricated gain.
     */
    fun calculate(
        previous: InvestmentBalanceSnapshotEntity?,
        currentCostPaise: Long,
        currentValuePaise: Long,
        knownExternalCashFlowPaise: Long? = null,
    ): InvestmentDelta {
        require(currentCostPaise >= 0) { "Cost cannot be negative." }
        require(currentValuePaise >= 0) { "Current value cannot be negative." }

        val previousCost = previous?.totalCostPaise
        val previousValue = previous?.currentValuePaise
        val previousUnrealized = previous?.let { it.currentValuePaise - it.totalCostPaise }
        val currentUnrealized = currentValuePaise - currentCostPaise

        if (previous == null) {
            return InvestmentDelta(
                previousCostPaise = null,
                previousValuePaise = null,
                costDeltaPaise = currentCostPaise,
                valueDeltaPaise = currentValuePaise,
                netContributionPaise = currentCostPaise,
                marketMovementPaise = currentUnrealized,
                marketMovementIsEstimated = false,
                previousUnrealizedGainPaise = null,
                unrealizedGainPaise = currentUnrealized,
                unrealizedGainDeltaPaise = currentUnrealized,
                realizedGainPaise = 0L,
                realizedGainStatus = RealizedGainStatus.NOT_APPLICABLE,
                totalReturnSincePreviousPaise = null,
                totalReturnSincePreviousPercent = null,
                totalReturnIsEstimated = false,
            )
        }

        val costDelta = currentCostPaise - previous.totalCostPaise
        val valueDelta = currentValuePaise - previous.currentValuePaise
        val withdrawalBasis = (-costDelta).coerceAtLeast(0L)
        val hasWithdrawal = withdrawalBasis > 0L

        // A positive cost-basis change is a fresh contribution. For a negative change, the cost
        // basis tells us how much basis left the holding, not necessarily the cash proceeds.
        val inferredCashFlow = costDelta
        val usableCashFlow = knownExternalCashFlowPaise ?: inferredCashFlow
        if (knownExternalCashFlowPaise != null) {
            require(
                !hasWithdrawal || knownExternalCashFlowPaise <= 0L
            ) { "A cost-basis withdrawal requires a non-positive external cash flow." }
        }

        val exactRedemptionEvidence = hasWithdrawal && knownExternalCashFlowPaise != null
        val movementIsEstimated = hasWithdrawal && !exactRedemptionEvidence
        val marketMovement = valueDelta - usableCashFlow
        val unrealizedDelta = currentUnrealized - (previousUnrealized ?: 0L)

        val realizedGain = when {
            !hasWithdrawal -> 0L
            exactRedemptionEvidence -> (-knownExternalCashFlowPaise!!) - withdrawalBasis
            else -> null
        }
        val realizedStatus = when {
            !hasWithdrawal -> RealizedGainStatus.NOT_APPLICABLE
            exactRedemptionEvidence -> RealizedGainStatus.DETERMINED_FROM_CASH_FLOW
            else -> RealizedGainStatus.REDEMPTION_PROCEEDS_REQUIRED
        }

        // Modified Dietz with a midpoint flow weight is transparent and stable when exact flow
        // timing is unavailable. For balance-only redemptions the flow amount itself is only a
        // cost-basis proxy, so the result is explicitly marked as estimated.
        val denominator = previous.currentValuePaise.toDouble() + usableCashFlow.toDouble() * 0.5
        val intervalReturnPercent = if (denominator <= 0.5) {
            null
        } else {
            marketMovement.toDouble() * 100.0 / denominator
        }

        return InvestmentDelta(
            previousCostPaise = previousCost,
            previousValuePaise = previousValue,
            costDeltaPaise = costDelta,
            valueDeltaPaise = valueDelta,
            netContributionPaise = costDelta,
            marketMovementPaise = marketMovement,
            marketMovementIsEstimated = movementIsEstimated,
            previousUnrealizedGainPaise = previousUnrealized,
            unrealizedGainPaise = currentUnrealized,
            unrealizedGainDeltaPaise = unrealizedDelta,
            realizedGainPaise = realizedGain,
            realizedGainStatus = realizedStatus,
            totalReturnSincePreviousPaise = marketMovement,
            totalReturnSincePreviousPercent = intervalReturnPercent,
            totalReturnIsEstimated = movementIsEstimated || usableCashFlow != 0L,
        )
    }

    /**
     * Persist the auditable balance fields already present in the Room schema.
     * Richer metrics remain derived to avoid duplicating source-of-truth data or requiring a schema
     * migration merely for calculated values.
     */
    fun apply(
        base: InvestmentBalanceSnapshotEntity,
        previous: InvestmentBalanceSnapshotEntity?,
    ): InvestmentBalanceSnapshotEntity {
        val delta = calculate(previous, base.totalCostPaise, base.currentValuePaise)
        return base.copy(
            netContributionPaise = delta.netContributionPaise,
            previousCostPaise = delta.previousCostPaise,
            previousValuePaise = delta.previousValuePaise,
            costDeltaPaise = delta.costDeltaPaise,
            valueDeltaPaise = delta.valueDeltaPaise,
            marketMovementPaise = delta.marketMovementPaise,
        )
    }
}
