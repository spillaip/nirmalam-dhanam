package com.nirmalamgroup.nirmalamdhanam.domain.usecase

import com.nirmalamgroup.nirmalamdhanam.data.local.EnvelopeType
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
    operator fun invoke(draft: TransactionEntity, thresholdPaise: Long): TransactionEntity =
        if (draft.envelopeType == EnvelopeType.WANTS && draft.amountPaise > thresholdPaise)
            draft.copy(isHoldingTank = true, coolDownExpiryEpochMs = clock() + 48L * 60 * 60 * 1000)
        else draft.copy(isHoldingTank = false, coolDownExpiryEpochMs = null)
}

class DaysOfAutonomyRunwayUseCase {
    operator fun invoke(spendingBalancePaise: Long, emergencyBalancePaise: Long, ninetyDayDebitPaise: Long): Double? {
        if (ninetyDayDebitPaise <= 0) return null
        return (spendingBalancePaise + emergencyBalancePaise).toDouble() / (ninetyDayDebitPaise / 90.0)
    }
}
