package com.nirmalamgroup.nirmalamdhanam.data.local

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val DHANAM_CURRENT_FORMAT_VERSION = 3

/**
 * Portable, versioned `.dhanam` document shared with the Python package.
 * Monetary values are integer paise. This is an explicit user export and is NOT encrypted;
 * use `.ndf` for encrypted backup/restore.
 */
@Serializable
data class DhanamDocument(
    val format: String = "nirmalam-dhanam",
    val formatVersion: Int = DHANAM_CURRENT_FORMAT_VERSION,
    val exportedAtEpochMs: Long,
    val currencyCode: String = "INR",
    val monetaryUnit: String = "paise",
    val accounts: List<DhanamAccountRecord> = emptyList(),
    val categories: List<DhanamCategoryRecord> = emptyList(),
    val payees: List<DhanamPayeeRecord> = emptyList(),
    val transactions: List<DhanamTransactionRecord> = emptyList(),
    val investmentBalances: List<DhanamInvestmentBalanceRecord> = emptyList(),
    val netWorthSnapshots: List<DhanamNetWorthRecord> = emptyList(),
    val goals: List<DhanamGoalRecord> = emptyList(),
    val goalAllocations: List<DhanamGoalAllocationRecord> = emptyList()
) {
    companion object { const val CURRENT_FORMAT_VERSION = DHANAM_CURRENT_FORMAT_VERSION }
}

@Serializable data class DhanamAccountRecord(
    val id: String, val name: String, val kind: String, val productType: String, val assetClass: String,
    val targetAllocationBps: Int, val openingBalancePaise: Long, val currentMarketPaise: Long = 0,
    val intrinsicValuePaise: Long = 0, val benchmarkIndexName: String? = null,
    val benchmarkTrackingMethod: String = "NONE", val benchmarkIsTotalReturn: Boolean = true,
    val isArchived: Boolean = false, val createdAtEpochMs: Long = 0
)
@Serializable data class DhanamCategoryRecord(
    val id: String, val name: String, val direction: String, val isSystem: Boolean, val iconKey: String?,
    val priority: String = "NEED", val nature: String = "VARIABLE"
)
@Serializable data class DhanamPayeeRecord(val id: String, val name: String, val defaultCategory: String?, val lastUsedEpochMs: Long)
@Serializable data class DhanamTransactionRecord(
    val id: String, val accountId: String, val amountPaise: Long, val direction: String,
    val merchant: String? = null, val payee: String?, val category: String?, val description: String?,
    val envelopeType: String?, val occurredAtEpochMs: Long, val isHoldingTank: Boolean,
    val coolDownExpiryEpochMs: Long?, val note: String? = null,
    val sourceFingerprint: String? = null, val sourceKind: String? = null,
    val reconciledAtEpochMs: Long? = null
)
@Serializable data class DhanamInvestmentBalanceRecord(
    val id: String, val accountId: String, val asOfEpochDay: Long, val totalCostPaise: Long,
    val currentValuePaise: Long, val netContributionPaise: Long, val previousCostPaise: Long? = null,
    val previousValuePaise: Long? = null, val costDeltaPaise: Long = 0, val valueDeltaPaise: Long = 0,
    val marketMovementPaise: Long = 0, val note: String?, val createdAtEpochMs: Long = 0
)
@Serializable data class DhanamNetWorthRecord(
    val id: String, val asOfEpochDay: Long, val netWorthPaise: Long, val portfolioValuePaise: Long,
    val createdAtEpochMs: Long = 0
)
@Serializable data class DhanamGoalRecord(
    val id: String, val name: String, val targetAmountPaise: Long, val targetDateEpochDay: Long?,
    val note: String?, val isArchived: Boolean, val createdAtEpochMs: Long
)
@Serializable data class DhanamGoalAllocationRecord(val id: String, val goalId: String, val accountId: String, val allocationBps: Int)

data class DhanamImportSummary(
    val accounts: Int,
    val transactions: Int,
    val investmentBalances: Int,
    val goals: Int
) { val totalRecords: Int get() = accounts + transactions + investmentBalances + goals }

sealed interface DhanamFileResult {
    data class Exported(val uri: Uri, val recordCount: Int) : DhanamFileResult
    data class Imported(val summary: DhanamImportSummary) : DhanamFileResult
    data class Failure(val message: String, val cause: Throwable? = null) : DhanamFileResult
}

class DhanamPortableFileManager(private val context: Context, private val database: NirmalamDatabase) {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    suspend fun exportTo(destination: Uri): DhanamFileResult = withContext(Dispatchers.IO) {
        DatabaseAccessGate.writeLock.withLock {
            runCatching {
                val config = database.configDao().observe().first()
                val accounts = database.accountDao().getAll()
                val categories = database.categoryDao().getAll()
                val payees = database.payeeDao().getAll()
                val transactions = database.transactionDao().getAll()
                val balances = database.investmentBalanceSnapshotDao().getAll()
                val netWorth = database.netWorthSnapshotDao().getAll()
                val goals = database.goalDao().getAll()
                val allocations = database.goalAllocationDao().getAll()
                val document = DhanamDocument(
                    exportedAtEpochMs = System.currentTimeMillis(),
                    currencyCode = config?.currencyCode ?: "INR",
                    accounts = accounts.map { DhanamAccountRecord(it.id, it.name, it.kind.name, it.productType.name, it.assetClass.name, it.targetAllocationBps, it.openingBalancePaise, it.currentMarketPaise, it.intrinsicValuePaise, it.benchmarkIndexName, it.benchmarkTrackingMethod.name, it.benchmarkIsTotalReturn, it.isArchived, it.createdAtEpochMs) },
                    categories = categories.map { DhanamCategoryRecord(it.id, it.name, it.transactionDirection.name, it.isSystem, it.iconKey, it.priority.name, it.nature.name) },
                    payees = payees.map { DhanamPayeeRecord(it.id, it.name, it.defaultCategory, it.lastUsedEpochMs) },
                    transactions = transactions.map { DhanamTransactionRecord(it.id, it.accountId, it.amountPaise, it.direction.name, it.merchant, it.payee, it.category, it.description, it.envelopeType?.name, it.occurredAtEpochMs, it.isHoldingTank, it.coolDownExpiryEpochMs, it.note, it.sourceFingerprint, it.sourceKind, it.reconciledAtEpochMs) },
                    investmentBalances = balances.map { DhanamInvestmentBalanceRecord(it.id, it.accountId, it.asOfEpochDay, it.totalCostPaise, it.currentValuePaise, it.netContributionPaise, it.previousCostPaise, it.previousValuePaise, it.costDeltaPaise, it.valueDeltaPaise, it.marketMovementPaise, it.note, it.createdAtEpochMs) },
                    netWorthSnapshots = netWorth.map { DhanamNetWorthRecord(it.id, it.asOfEpochDay, it.netWorthPaise, it.portfolioValuePaise, it.createdAtEpochMs) },
                    goals = goals.map { DhanamGoalRecord(it.id, it.name, it.targetAmountPaise, it.targetDateEpochDay, it.note, it.isArchived, it.createdAtEpochMs) },
                    goalAllocations = allocations.map { DhanamGoalAllocationRecord(it.id, it.goalId, it.accountId, it.allocationBps) }
                )
                context.contentResolver.openOutputStream(destination, "wt")?.use { it.write(json.encodeToString(document).encodeToByteArray()) }
                    ?: error("Unable to open the selected .dhanam destination.")
                val count = accounts.size + categories.size + payees.size + transactions.size + balances.size + netWorth.size + goals.size + allocations.size
                DhanamFileResult.Exported(destination, count)
            }.getOrElse { DhanamFileResult.Failure("Portable .dhanam export failed; local data was not changed.", it) }
        }
    }

    /** Merge-by-id import. The whole document is validated before one atomic Room transaction. */
    suspend fun importFrom(source: Uri): DhanamFileResult = withContext(Dispatchers.IO) {
        DatabaseAccessGate.writeLock.withLock {
            runCatching {
                val text = context.contentResolver.openInputStream(source)?.bufferedReader()?.use { it.readText() }
                    ?: error("Unable to read the selected .dhanam file.")
                require(text.length <= 64 * 1024 * 1024) { "Portable file exceeds the 64 MB safety limit." }
                val document = json.decodeFromString(DhanamDocument.serializer(), text)
                require(document.format == "nirmalam-dhanam") { "Not a Nirmalam Dhanam portable file." }
                require(document.formatVersion in 1..DhanamDocument.CURRENT_FORMAT_VERSION) { "Unsupported .dhanam format version ${document.formatVersion}." }
                require(document.monetaryUnit == "paise") { "Unsupported monetary unit." }

                val accounts = document.accounts.map { row ->
                    require(row.name.isNotBlank() && row.targetAllocationBps in 0..10_000)
                    AccountEntity(
                        id = row.id, name = row.name, kind = AccountKind.valueOf(row.kind),
                        productType = AccountProductType.valueOf(row.productType), assetClass = AssetClass.valueOf(row.assetClass),
                        targetAllocationBps = row.targetAllocationBps, openingBalancePaise = row.openingBalancePaise,
                        currentMarketPaise = row.currentMarketPaise, intrinsicValuePaise = row.intrinsicValuePaise,
                        benchmarkIndexName = row.benchmarkIndexName, benchmarkTrackingMethod = BenchmarkTrackingMethod.valueOf(row.benchmarkTrackingMethod),
                        benchmarkIsTotalReturn = row.benchmarkIsTotalReturn, isArchived = row.isArchived,
                        createdAtEpochMs = row.createdAtEpochMs.takeIf { it > 0 } ?: System.currentTimeMillis()
                    )
                }
                val accountIds = accounts.map { it.id }.toSet() + database.accountDao().getAll().map { it.id }
                val categories = document.categories.map { row ->
                    CategoryEntity(row.id, row.name, TransactionDirection.valueOf(row.direction), row.isSystem, row.iconKey, CategoryPriority.valueOf(row.priority), CategoryNature.valueOf(row.nature))
                }
                val payees = document.payees.map { PayeeEntity(it.id, it.name, it.defaultCategory, it.lastUsedEpochMs) }
                val transactions = document.transactions.map { row ->
                    require(row.accountId in accountIds && row.amountPaise > 0) { "Transaction ${row.id} references an unknown account or invalid amount." }
                    TransactionEntity(row.id, row.accountId, row.amountPaise, TransactionDirection.valueOf(row.direction), row.merchant, row.category, row.payee, row.description, row.envelopeType?.let(EnvelopeType::valueOf), row.occurredAtEpochMs, row.isHoldingTank, row.coolDownExpiryEpochMs, row.note, row.sourceFingerprint, row.sourceKind, row.reconciledAtEpochMs)
                }
                val investments = document.investmentBalances.map { row ->
                    require(row.accountId in accountIds && row.totalCostPaise >= 0 && row.currentValuePaise >= 0)
                    InvestmentBalanceSnapshotEntity(row.id, row.accountId, row.asOfEpochDay, row.totalCostPaise, row.currentValuePaise, row.netContributionPaise, row.previousCostPaise, row.previousValuePaise, row.costDeltaPaise, row.valueDeltaPaise, row.marketMovementPaise, row.note, row.createdAtEpochMs.takeIf { it > 0 } ?: System.currentTimeMillis())
                }
                val netWorth = document.netWorthSnapshots.map { NetWorthSnapshotEntity(it.id, it.asOfEpochDay, it.netWorthPaise, it.portfolioValuePaise, it.createdAtEpochMs.takeIf { value -> value > 0 } ?: System.currentTimeMillis()) }
                val goals = document.goals.map { GoalEntity(it.id, it.name, it.targetAmountPaise, it.targetDateEpochDay, it.note, it.isArchived, it.createdAtEpochMs) }
                val goalIds = goals.map { it.id }.toSet() + database.goalDao().getAll().map { it.id }
                val allocations = document.goalAllocations.map {
                    require(it.goalId in goalIds && it.accountId in accountIds && it.allocationBps in 0..10_000)
                    GoalAllocationEntity(it.id, it.goalId, it.accountId, it.allocationBps)
                }

                database.withTransaction {
                    accounts.forEach { database.accountDao().upsert(it) }
                    categories.forEach { database.categoryDao().upsert(it) }
                    payees.forEach { database.payeeDao().upsert(it) }
                    database.transactionDao().upsertAll(transactions)
                    investments.forEach { database.investmentBalanceSnapshotDao().upsert(it) }
                    netWorth.forEach { database.netWorthSnapshotDao().upsert(it) }
                    goals.forEach { database.goalDao().upsert(it) }
                    allocations.forEach { database.goalAllocationDao().upsert(it) }
                }
                DhanamFileResult.Imported(DhanamImportSummary(accounts.size, transactions.size, investments.size, goals.size))
            }.getOrElse { DhanamFileResult.Failure("Portable .dhanam import failed; no partial changes were committed.", it) }
        }
    }
}
