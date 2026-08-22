package com.nirmalamgroup.nirmalamdhanam.data.repository

import androidx.room.withTransaction
import com.nirmalamgroup.nirmalamdhanam.data.local.*
import com.nirmalamgroup.nirmalamdhanam.domain.repository.FinanceRepository
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.CoolDownTankInterceptorUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

class RoomFinanceRepository(private val db: NirmalamDatabase, private val io: CoroutineDispatcher, private val coolDown: CoolDownTankInterceptorUseCase) : FinanceRepository {
    override fun observeCashPosition(): Flow<CashPosition> = db.accountDao().observeCashPosition()
    override fun observeHoldingTank(now: Long): Flow<List<TransactionEntity>> = db.transactionDao().observeHoldingTank(now)
    override fun observeSafeToSpend(envelopeId: String, dayStart: Long, dayEnd: Long): Flow<Long?> = combine(db.envelopeDao().observeDailyLimit(envelopeId), db.transactionDao().observeSpentBetween(dayStart, dayEnd)) { limit, spent -> limit?.minus(spent) }
    override suspend fun saveTransaction(transaction: TransactionEntity, coolDownThresholdPaise: Long) = withContext(io) { DatabaseAccessGate.writeLock.withLock { db.withTransaction { db.transactionDao().upsert(coolDown(transaction, coolDownThresholdPaise)) } } }
}
