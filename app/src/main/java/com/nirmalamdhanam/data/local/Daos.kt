package com.nirmalamgroup.nirmalamdhanam.data.local

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
    @Query("""
        SELECT a.id AS accountId, a.kind AS kind, a.productType AS productType,
          a.openingBalancePaise + COALESCE(t.net, 0) AS balancePaise
        FROM accounts a
        LEFT JOIN (SELECT accountId, SUM(CASE WHEN direction = 'CREDIT' THEN amountPaise ELSE -amountPaise END) AS net FROM transactions WHERE isHoldingTank = 0 GROUP BY accountId) t ON t.accountId = a.id
        WHERE a.isArchived = 0
    """) fun observeBalances(): Flow<List<AccountBalance>>
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
    @Query("SELECT * FROM transactions ORDER BY occurredAtEpochMs DESC LIMIT :limit") fun observeRecent(limit: Int = 100): Flow<List<TransactionEntity>>
    @Query("DELETE FROM transactions WHERE id = :transactionId") suspend fun delete(transactionId: String): Int
    @Query("UPDATE transactions SET isHoldingTank = 0, coolDownExpiryEpochMs = NULL WHERE id = :transactionId") suspend fun confirmHoldingTank(transactionId: String): Int
    @Query("DELETE FROM transactions WHERE id = :transactionId AND isHoldingTank = 1") suspend fun discardHoldingTank(transactionId: String): Int
}

@Dao interface EnvelopeDao {
    @Query("SELECT * FROM budget_envelopes WHERE isActive = 1 ORDER BY name") fun observeActive(): Flow<List<EnvelopeEntity>>
    @Query("SELECT dailyLimitPaise FROM budget_envelopes WHERE id = :id") fun observeDailyLimit(id: String): Flow<Long?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(envelope: EnvelopeEntity)
}

@Dao interface CategoryDao {
    @Query("SELECT * FROM categories WHERE transactionDirection = :direction ORDER BY isSystem DESC, name") fun observeFor(direction: TransactionDirection): Flow<List<CategoryEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(category: CategoryEntity)
}

@Dao interface PayeeDao {
    @Query("SELECT * FROM payees ORDER BY lastUsedEpochMs DESC, name LIMIT :limit") fun observeRecent(limit: Int = 12): Flow<List<PayeeEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(payee: PayeeEntity)
}

@Dao interface InvestmentBalanceSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(snapshot: InvestmentBalanceSnapshotEntity)
    @Query("""SELECT s.* FROM investment_balance_snapshots s
        INNER JOIN (SELECT accountId, MAX(asOfEpochDay) AS latestDay FROM investment_balance_snapshots GROUP BY accountId) latest
        ON s.accountId = latest.accountId AND s.asOfEpochDay = latest.latestDay""")
    fun observeLatestForAll(): Flow<List<InvestmentBalanceSnapshotEntity>>
    @Query("SELECT * FROM investment_balance_snapshots ORDER BY asOfEpochDay DESC") fun observeAll(): Flow<List<InvestmentBalanceSnapshotEntity>>
    @Query("SELECT * FROM investment_balance_snapshots") suspend fun getAll(): List<InvestmentBalanceSnapshotEntity>
    @Query("SELECT * FROM investment_balance_snapshots WHERE accountId = :accountId ORDER BY asOfEpochDay DESC LIMIT 1") suspend fun getLatest(accountId: String): InvestmentBalanceSnapshotEntity?
    @Query("SELECT * FROM investment_balance_snapshots WHERE accountId = :accountId ORDER BY asOfEpochDay DESC") fun observeForAccount(accountId: String): Flow<List<InvestmentBalanceSnapshotEntity>>
    @Query("DELETE FROM investment_balance_snapshots WHERE id = :snapshotId") suspend fun delete(snapshotId: String): Int
}

@Dao interface NetWorthSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(snapshot: NetWorthSnapshotEntity)
    @Query("SELECT * FROM net_worth_snapshots ORDER BY asOfEpochDay DESC") fun observeAll(): Flow<List<NetWorthSnapshotEntity>>
}
