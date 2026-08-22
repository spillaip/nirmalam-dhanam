package com.nirmalamdhanam.domain.repository

import com.nirmalamdhanam.data.local.*
import kotlinx.coroutines.flow.Flow

interface FinanceRepository {
    fun observeCashPosition(): Flow<CashPosition>
    fun observeHoldingTank(now: Long): Flow<List<TransactionEntity>>
    fun observeSafeToSpend(envelopeId: String, dayStart: Long, dayEnd: Long): Flow<Long?>
    suspend fun saveTransaction(transaction: TransactionEntity, coolDownThresholdPaise: Long)
}
