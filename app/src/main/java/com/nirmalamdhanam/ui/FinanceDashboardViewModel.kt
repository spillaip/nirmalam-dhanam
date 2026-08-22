package com.nirmalamdhanam.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nirmalamdhanam.data.local.CashPosition
import com.nirmalamdhanam.data.local.EnvelopeEntity
import com.nirmalamdhanam.data.local.TransactionEntity
import com.nirmalamdhanam.domain.repository.FinanceRepository
import kotlinx.coroutines.flow.*

data class FinanceDashboardState(val cash: CashPosition = CashPosition(0, 0), val envelopes: List<EnvelopeEntity> = emptyList(), val holdingTransactions: List<TransactionEntity> = emptyList(), val safeToSpendPaise: Long? = null, val autonomyDays: Double? = null, val neurodiverseModeEnabled: Boolean = false)
class FinanceDashboardViewModel(repository: FinanceRepository, envelopes: Flow<List<EnvelopeEntity>>, autonomyDays: Flow<Double?>, wantsEnvelopeId: String, todayStart: Long, tomorrowStart: Long, neurodiverseMode: Flow<Boolean> = flowOf(false)) : ViewModel() {
    private val financialState = combine(repository.observeCashPosition(), envelopes, repository.observeHoldingTank(System.currentTimeMillis()), repository.observeSafeToSpend(wantsEnvelopeId, todayStart, tomorrowStart), autonomyDays) { cash, env, holding, safe, runway -> FinanceDashboardState(cash, env, holding, safe, runway) }
    val state: StateFlow<FinanceDashboardState> = combine(financialState, neurodiverseMode) { base, enabled -> base.copy(neurodiverseModeEnabled = enabled) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FinanceDashboardState())
}
