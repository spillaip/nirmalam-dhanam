package com.nirmalamdhanam.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao interface ConfigDao {
    @Query("SELECT * FROM nirmalam_dhanam_config WHERE id = 1") fun observe(): Flow<ConfigEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(config: ConfigEntity)
    @Query("UPDATE nirmalam_dhanam_config SET neurodiverseModeEnabled = :enabled WHERE id = 1") suspend fun setNeurodiverseMode(enabled: Boolean): Int
}

@Dao interface AccountDao {
    @Query("SELECT * FROM accounts WHERE isArchived = 0 ORDER BY name") fun observeActive(): Flow<List<AccountEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(account: AccountEntity)
    @Query("""
        SELECT
          COALESCE(SUM(CASE WHEN a.kind = 'SPENDING' THEN a.openingBalancePaise + COALESCE(t.net, 0) ELSE 0 END), 0) AS spendingPaise,
          COALESCE(SUM(CASE WHEN a.kind = 'CREDIT' THEN MAX(0, -(a.openingBalancePaise + COALESCE(t.net, 0))) ELSE 0 END), 0) AS creditLiabilityPaise
        FROM accounts a
        LEFT JOIN (SELECT accountId, SUM(CASE WHEN direction = 'CREDIT' THEN amountPaise ELSE -amountPaise END) AS net FROM transactions WHERE isHoldingTank = 0 GROUP BY accountId) t ON t.accountId = a.id
        WHERE a.isArchived = 0
    """) fun observeCashPosition(): Flow<CashPosition>
}

@Dao interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(transaction: TransactionEntity)
    @Query("SELECT * FROM transactions WHERE isHoldingTank = 1 AND coolDownExpiryEpochMs > :now ORDER BY coolDownExpiryEpochMs") fun observeHoldingTank(now: Long): Flow<List<TransactionEntity>>
    @Query("""SELECT COALESCE(SUM(amountPaise), 0) FROM transactions
        WHERE direction = 'DEBIT' AND isHoldingTank = 0 AND occurredAtEpochMs >= :dayStart AND occurredAtEpochMs < :dayEnd""")
    fun observeSpentBetween(dayStart: Long, dayEnd: Long): Flow<Long>
    @Query("""SELECT COALESCE(SUM(amountPaise), 0) FROM transactions
        WHERE direction = 'DEBIT' AND isHoldingTank = 0 AND occurredAtEpochMs >= :start""")
    fun observeBurnSince(start: Long): Flow<Long>
    @Query("SELECT * FROM transactions WHERE accountId = :accountId ORDER BY occurredAtEpochMs DESC") fun observeForAccount(accountId: String): Flow<List<TransactionEntity>>
}

@Dao interface EnvelopeDao {
    @Query("SELECT * FROM budget_envelopes WHERE isActive = 1 ORDER BY name") fun observeActive(): Flow<List<EnvelopeEntity>>
    @Query("SELECT dailyLimitPaise FROM budget_envelopes WHERE id = :id") fun observeDailyLimit(id: String): Flow<Long?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(envelope: EnvelopeEntity)
}
