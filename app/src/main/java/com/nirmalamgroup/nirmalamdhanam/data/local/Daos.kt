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
    @Query("UPDATE nirmalam_dhanam_config SET currencyCode = :currencyCode WHERE id = 1") suspend fun setCurrencyCode(currencyCode: String): Int
    @Query("UPDATE nirmalam_dhanam_config SET dateFormatPreference = :preference WHERE id = 1") suspend fun setDateFormatPreference(preference: DateFormatPreference): Int
    @Query("UPDATE nirmalam_dhanam_config SET savedLedgerRange = :range, savedLedgerFilter = :filter, savedLedgerAccountId = :accountId, savedLedgerCategoryName = :categoryName WHERE id = 1")
    suspend fun setSavedLedgerView(range: String, filter: String, accountId: String?, categoryName: String?): Int
    @Query("UPDATE nirmalam_dhanam_config SET starterDataRemoved = :removed WHERE id = 1") suspend fun setStarterDataRemoved(removed: Boolean): Int
}

@Dao interface AccountDao {
    @Query("SELECT * FROM accounts WHERE isArchived = 0 ORDER BY name") fun observeActive(): Flow<List<AccountEntity>>
    @Query("SELECT * FROM accounts WHERE isArchived = 0 ORDER BY name") suspend fun getActive(): List<AccountEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(account: AccountEntity)
    @Query("UPDATE accounts SET isArchived = 1 WHERE id = :accountId") suspend fun archive(accountId: String): Int
    @Query("DELETE FROM accounts WHERE id LIKE 'demo-%'") suspend fun deleteDemoAccounts(): Int
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
    @Query("SELECT * FROM transactions WHERE id = :transactionId LIMIT 1") suspend fun getById(transactionId: String): TransactionEntity?
    @Query("SELECT * FROM transactions WHERE isHoldingTank = 1 AND coolDownExpiryEpochMs > :now ORDER BY coolDownExpiryEpochMs") fun observeHoldingTank(now: Long): Flow<List<TransactionEntity>>
    @Query("""SELECT COALESCE(SUM(amountPaise), 0) FROM transactions
        WHERE direction = 'DEBIT' AND isHoldingTank = 0 AND occurredAtEpochMs >= :dayStart AND occurredAtEpochMs < :dayEnd""")
    fun observeSpentBetween(dayStart: Long, dayEnd: Long): Flow<Long>
    @Query("""SELECT COALESCE(SUM(amountPaise), 0) FROM transactions
        WHERE direction = 'DEBIT' AND isHoldingTank = 0 AND occurredAtEpochMs >= :start""")
    fun observeBurnSince(start: Long): Flow<Long>
    @Query("SELECT * FROM transactions WHERE accountId = :accountId ORDER BY occurredAtEpochMs DESC") fun observeForAccount(accountId: String): Flow<List<TransactionEntity>>
    @Query("SELECT * FROM transactions ORDER BY occurredAtEpochMs DESC LIMIT :limit") fun observeRecent(limit: Int = 100): Flow<List<TransactionEntity>>
    @Query("SELECT * FROM transactions ORDER BY occurredAtEpochMs ASC, id ASC") suspend fun getAll(): List<TransactionEntity>
    @Query("DELETE FROM transactions WHERE id = :transactionId") suspend fun delete(transactionId: String): Int
    @Query("DELETE FROM transactions WHERE id LIKE 'demo-%'") suspend fun deleteDemoTransactions(): Int
    @Query("UPDATE transactions SET category = :newName WHERE category = :oldName") suspend fun renameCategoryReferences(oldName: String, newName: String): Int
    @Query("UPDATE transactions SET payee = :newName, merchant = :newName WHERE payee = :oldName") suspend fun renamePayeeReferences(oldName: String, newName: String): Int
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
    @Query("SELECT * FROM categories ORDER BY transactionDirection, isSystem DESC, name") fun observeAll(): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM categories ORDER BY transactionDirection, isSystem DESC, name") suspend fun getAll(): List<CategoryEntity>
    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1") suspend fun getByName(name: String): CategoryEntity?
    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1") suspend fun getById(id: String): CategoryEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(category: CategoryEntity)
    @Query("DELETE FROM categories WHERE id = :id AND isSystem = 0") suspend fun deleteUserCreated(id: String): Int
    @Query("DELETE FROM categories WHERE id LIKE 'system-varga-%' OR id LIKE 'expense-%' OR id LIKE 'income-%'") suspend fun deleteStarterCategories(): Int
}

@Dao interface PayeeDao {
    @Query("SELECT * FROM payees ORDER BY lastUsedEpochMs DESC, name LIMIT :limit") fun observeRecent(limit: Int = 12): Flow<List<PayeeEntity>>
    @Query("SELECT * FROM payees ORDER BY name") fun observeAll(): Flow<List<PayeeEntity>>
    @Query("SELECT * FROM payees ORDER BY name") suspend fun getAll(): List<PayeeEntity>
    @Query("SELECT * FROM payees WHERE name = :name LIMIT 1") suspend fun getByName(name: String): PayeeEntity?
    @Query("UPDATE payees SET defaultCategory = :newName WHERE defaultCategory = :oldName") suspend fun renameDefaultCategory(oldName: String, newName: String): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(payee: PayeeEntity)
    @Query("DELETE FROM payees WHERE id = :id") suspend fun delete(id: String): Int
    @Query("DELETE FROM payees WHERE id LIKE 'system-vyakti-%' OR id LIKE 'demo-payee-%'") suspend fun deleteStarterPayees(): Int
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
    @Query("SELECT * FROM investment_balance_snapshots WHERE accountId = :accountId AND asOfEpochDay = :epochDay LIMIT 1") suspend fun getForAccountAndDay(accountId: String, epochDay: Long): InvestmentBalanceSnapshotEntity?
    @Query("SELECT * FROM investment_balance_snapshots WHERE accountId = :accountId ORDER BY asOfEpochDay DESC") fun observeForAccount(accountId: String): Flow<List<InvestmentBalanceSnapshotEntity>>
    @Query("DELETE FROM investment_balance_snapshots WHERE id = :snapshotId") suspend fun delete(snapshotId: String): Int
    @Query("DELETE FROM investment_balance_snapshots WHERE id LIKE 'demo-%'") suspend fun deleteDemoSnapshots(): Int
}

@Dao interface NetWorthSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(snapshot: NetWorthSnapshotEntity)
    @Query("SELECT * FROM net_worth_snapshots ORDER BY asOfEpochDay DESC") fun observeAll(): Flow<List<NetWorthSnapshotEntity>>
    @Query("SELECT * FROM net_worth_snapshots ORDER BY asOfEpochDay ASC") suspend fun getAll(): List<NetWorthSnapshotEntity>
    @Query("DELETE FROM net_worth_snapshots WHERE id LIKE 'demo-%'") suspend fun deleteDemoSnapshots(): Int
}
