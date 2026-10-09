package com.nirmalamgroup.nirmalamdhanam.domain.usecase

import com.nirmalamgroup.nirmalamdhanam.data.local.CategoryPriority
import com.nirmalamgroup.nirmalamdhanam.data.local.EnvelopeType
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionDirection
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionEntity
import kotlin.math.roundToLong

class LaborHourConversionUseCase {
    operator fun invoke(amountPaise: Long, hourlyRatePaise: Long): String {
        require(amountPaise >= 0 && hourlyRatePaise > 0)
        val totalMinutes = (amountPaise.toDouble() / hourlyRatePaise * 60).roundToLong()
        return "${totalMinutes / 60}h ${totalMinutes % 60}m of Labor"
    }
}

class CoolDownTankInterceptorUseCase(private val clock: () -> Long = System::currentTimeMillis) {
    /**
     * Applies category policy at the domain boundary. Cooling is driven by the category's
     * WANT/NEED metadata, never by a category label such as "Shopping" and never solely
     * by a pre-populated envelopeType supplied by a caller.
     *
     * Investment transfers remain INVESTMENT transactions and credits never enter the tank.
     */
    operator fun invoke(
        draft: TransactionEntity,
        thresholdPaise: Long,
        categoryPriority: CategoryPriority = CategoryPriority.NEED
    ): TransactionEntity {
        if (draft.envelopeType == EnvelopeType.INVESTMENT) {
            return draft.copy(isHoldingTank = false, coolDownExpiryEpochMs = null)
        }

        if (draft.direction == TransactionDirection.CREDIT) {
            return draft.copy(envelopeType = null, isHoldingTank = false, coolDownExpiryEpochMs = null)
        }

        val envelope = if (categoryPriority == CategoryPriority.WANT) EnvelopeType.WANTS else EnvelopeType.NEEDS
        val shouldCool = categoryPriority == CategoryPriority.WANT && draft.amountPaise > thresholdPaise
        val expiry = if (shouldCool) {
            draft.coolDownExpiryEpochMs?.takeIf { draft.isHoldingTank }
                ?: clock() + 48L * 60 * 60 * 1000
        } else null
        return draft.copy(
            envelopeType = envelope,
            isHoldingTank = shouldCool,
            coolDownExpiryEpochMs = expiry
        )
    }
}

class DaysOfAutonomyRunwayUseCase {
    operator fun invoke(spendingBalancePaise: Long, emergencyBalancePaise: Long, ninetyDayDebitPaise: Long): Double? {
        if (ninetyDayDebitPaise <= 0) return null
        return (spendingBalancePaise + emergencyBalancePaise).toDouble() / (ninetyDayDebitPaise / 90.0)
    }
}
