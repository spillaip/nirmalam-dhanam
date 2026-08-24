package com.nirmalamgroup.nirmalamdhanam.ui

import com.nirmalamgroup.nirmalamdhanam.data.local.CashPosition
import com.nirmalamgroup.nirmalamdhanam.data.local.EnvelopeEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionEntity

/** Shared read model for the tablet dashboard; populated by the active app ViewModel. */
data class FinanceDashboardState(val cash: CashPosition = CashPosition(0, 0), val envelopes: List<EnvelopeEntity> = emptyList(), val holdingTransactions: List<TransactionEntity> = emptyList(), val safeToSpendPaise: Long? = null, val autonomyDays: Double? = null, val neurodiverseModeEnabled: Boolean = false)
