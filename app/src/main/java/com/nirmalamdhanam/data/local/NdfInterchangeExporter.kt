package com.nirmalamgroup.nirmalamdhanam.data.local

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Plain JSON interchange for read-only analysis in Python, Java, and similar tools.
 * It is intentionally not encrypted: callers must obtain an explicit user-selected URI and
 * explain that this report may contain sensitive financial data. Use [NdfBackupManager] for
 * encrypted backup and restore instead.
 */
@Serializable
data class NdfInterchangeDocument(
    val format: String = "nirmalam-dhanam-interchange",
    val formatVersion: Int = 1,
    val exportedAtEpochMs: Long,
    val currencyCode: String,
    /** Monetary values are integer paise, avoiding floating-point ambiguity across languages. */
    val monetaryUnit: String = "paise",
    val accounts: List<NdfAccountRecord>,
    val categories: List<NdfCategoryRecord>,
    val payees: List<NdfPayeeRecord>,
    val transactions: List<NdfTransactionRecord>,
    val investmentBalances: List<NdfInvestmentBalanceRecord>,
    val netWorthSnapshots: List<NdfNetWorthRecord>
)

@Serializable data class NdfAccountRecord(val id: String, val name: String, val kind: String, val productType: String, val assetClass: String, val targetAllocationBps: Int, val openingBalancePaise: Long)
@Serializable data class NdfCategoryRecord(val id: String, val name: String, val direction: String, val isSystem: Boolean, val iconKey: String?)
@Serializable data class NdfPayeeRecord(val id: String, val name: String, val defaultCategory: String?, val lastUsedEpochMs: Long)
@Serializable data class NdfTransactionRecord(val id: String, val accountId: String, val amountPaise: Long, val direction: String, val payee: String?, val category: String?, val description: String?, val envelopeType: String?, val occurredAtEpochMs: Long, val isHoldingTank: Boolean, val coolDownExpiryEpochMs: Long?)
@Serializable data class NdfInvestmentBalanceRecord(val id: String, val accountId: String, val asOfEpochDay: Long, val totalCostPaise: Long, val currentValuePaise: Long, val netContributionPaise: Long, val note: String?)
@Serializable data class NdfNetWorthRecord(val id: String, val asOfEpochDay: Long, val netWorthPaise: Long, val portfolioValuePaise: Long)

sealed interface NdfInterchangeResult {
    data class Exported(val uri: Uri, val recordCount: Int) : NdfInterchangeResult
    data class Failure(val message: String, val cause: Throwable? = null) : NdfInterchangeResult
}

class NdfInterchangeExporter(private val context: Context, private val database: NirmalamDatabase) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    suspend fun exportTo(destination: Uri): NdfInterchangeResult = withContext(Dispatchers.IO) {
        DatabaseAccessGate.writeLock.withLock {
            try {
                val config = database.configDao().observe().first()
                val accounts = database.accountDao().getActive()
                val categories = database.categoryDao().getAll()
                val payees = database.payeeDao().getAll()
                val transactions = database.transactionDao().getAll()
                val balances = database.investmentBalanceSnapshotDao().getAll()
                val netWorth = database.netWorthSnapshotDao().getAll()
                val document = NdfInterchangeDocument(
                    exportedAtEpochMs = System.currentTimeMillis(),
                    currencyCode = config?.currencyCode ?: "INR",
                    accounts = accounts.map { NdfAccountRecord(it.id, it.name, it.kind.name, it.productType.name, it.assetClass.name, it.targetAllocationBps, it.openingBalancePaise) },
                    categories = categories.map { NdfCategoryRecord(it.id, it.name, it.transactionDirection.name, it.isSystem, it.iconKey) },
                    payees = payees.map { NdfPayeeRecord(it.id, it.name, it.defaultCategory, it.lastUsedEpochMs) },
                    transactions = transactions.map { NdfTransactionRecord(it.id, it.accountId, it.amountPaise, it.direction.name, it.payee, it.category, it.description, it.envelopeType?.name, it.occurredAtEpochMs, it.isHoldingTank, it.coolDownExpiryEpochMs) },
                    investmentBalances = balances.map { NdfInvestmentBalanceRecord(it.id, it.accountId, it.asOfEpochDay, it.totalCostPaise, it.currentValuePaise, it.netContributionPaise, it.note) },
                    netWorthSnapshots = netWorth.map { NdfNetWorthRecord(it.id, it.asOfEpochDay, it.netWorthPaise, it.portfolioValuePaise) }
                )
                context.contentResolver.openOutputStream(destination, "wt")?.use { output -> output.write(json.encodeToString(document).encodeToByteArray()) }
                    ?: error("Unable to open the selected JSON export destination.")
                NdfInterchangeResult.Exported(destination, accounts.size + categories.size + payees.size + transactions.size + balances.size + netWorth.size)
            } catch (t: Throwable) {
                NdfInterchangeResult.Failure("JSON report export failed; local data was not changed.", t)
            }
        }
    }
}
