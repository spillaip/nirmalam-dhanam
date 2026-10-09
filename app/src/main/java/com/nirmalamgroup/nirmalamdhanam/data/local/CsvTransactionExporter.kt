package com.nirmalamgroup.nirmalamdhanam.data.local

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.AccountRolePolicy
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.ReconciliationStatus
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.TransactionReconciliationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.security.MessageDigest
import java.util.UUID

enum class CsvImportAction { KEEP, SKIP, REPLACE }

data class CsvReconciliationItem(
    val rowNumber: Int,
    val candidate: TransactionEntity,
    val status: ReconciliationStatus,
    val matchedTransaction: TransactionEntity?,
    val matchedExistingRecord: Boolean,
    val reason: String,
    val suggestedAction: CsvImportAction
) {
    val canReplace: Boolean
        get() = status != ReconciliationStatus.ALREADY_RECONCILED &&
            matchedExistingRecord && matchedTransaction != null && !matchedTransaction.isHoldingTank
}

data class CsvImportPlan(
    val accountId: String,
    val accountName: String,
    val items: List<CsvReconciliationItem>
) {
    val newCount: Int get() = items.count { it.status == ReconciliationStatus.NEW }
    val probableDuplicateCount: Int get() = items.count { it.status == ReconciliationStatus.PROBABLE_DUPLICATE }
    val possibleMatchCount: Int get() = items.count { it.status == ReconciliationStatus.POSSIBLE_MATCH }
    val conflictCount: Int get() = items.count { it.status == ReconciliationStatus.CONFLICT }
    val alreadyReconciledCount: Int get() = items.count { it.status == ReconciliationStatus.ALREADY_RECONCILED }
    val attentionCount: Int get() = items.size - newCount
}

sealed interface CsvImportPreviewResult {
    data class Ready(val plan: CsvImportPlan) : CsvImportPreviewResult
    data class Failure(val message: String, val cause: Throwable? = null) : CsvImportPreviewResult
}

sealed interface CsvImportResult {
    data class Success(
        val imported: Int,
        val replaced: Int,
        val skipped: Int,
        val probableDuplicates: Int,
        val possibleMatches: Int,
        val conflicts: Int,
        val alreadyReconciled: Int = 0,
    ) : CsvImportResult

    data class Failure(val message: String, val cause: Throwable? = null) : CsvImportResult
}

/** RFC-4180 compatible transaction CSV export/import with preview-first reconciliation and atomic writes. */
class CsvTransactionExporter(private val context: Context) {
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun canonicalSourceKey(
        accountId: String,
        date: LocalDate,
        direction: TransactionDirection,
        amountPaise: Long,
        category: String,
        payee: String,
        description: String?,
    ): String = listOf(
        accountId.trim().lowercase(),
        date.toString(),
        direction.name,
        amountPaise.toString(),
        category.trim().lowercase().replace(Regex("\\s+"), " "),
        payee.trim().lowercase().replace(Regex("\\s+"), " "),
        description.orEmpty().trim().lowercase().replace(Regex("\\s+"), " "),
    ).joinToString("|")

    private fun csvFingerprint(canonicalKey: String, occurrence: Int): String =
        "csv:${sha256("$canonicalKey|occurrence=$occurrence")}"

    suspend fun exportTo(
        destination: Uri,
        transactions: List<TransactionEntity>,
        accounts: Map<String, String>
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(destination, "wt")
                ?.bufferedWriter(Charsets.UTF_8)
                ?.use { writer ->
                    writer.write(CsvCodec.encodeRecord(listOf("Date", "Type", "Amount", "Category", "Payee", "Account", "Notes", "TransactionId", "SourceFingerprint", "SourceKind")))
                    writer.write("\r\n")
                    transactions.forEach { transaction ->
                        val date = Instant.ofEpochMilli(transaction.occurredAtEpochMs)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                            .format(dateFormatter)
                        val type = if (transaction.direction == TransactionDirection.CREDIT) "Aaya" else "Vyaya"
                        val amount = BigDecimal(transaction.amountPaise).movePointLeft(2).toPlainString()
                        val fields = listOf(
                            date,
                            type,
                            amount,
                            transaction.category.orEmpty(),
                            transaction.payee.orEmpty(),
                            accounts[transaction.accountId] ?: "Unknown",
                            transaction.description.orEmpty(),
                            transaction.id,
                            transaction.sourceFingerprint.orEmpty(),
                            transaction.sourceKind.orEmpty(),
                        )
                        writer.write(CsvCodec.encodeRecord(fields))
                        writer.write("\r\n")
                    }
                } ?: error("Unable to open CSV destination.")
        }.isSuccess
    }

    /**
     * Parses and validates the whole CSV without writing anything.
     * Every row receives a deterministic reconciliation classification and a conservative suggested action.
     */
    suspend fun previewImport(
        source: Uri,
        accountId: String,
        database: NirmalamDatabase
    ): CsvImportPreviewResult = withContext(Dispatchers.IO) {
        runCatching {
            val account = database.accountDao().getById(accountId)
                ?: error("Choose a valid destination account.")
            require(AccountRolePolicy.supportsTransactions(account.kind)) {
                "Choose a transactional cash/bank Khata for CSV import."
            }

            val text = context.contentResolver.openInputStream(source)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: error("Unable to read the selected CSV file.")
            require(text.length <= 32 * 1024 * 1024) { "CSV exceeds the 32 MB safety limit." }

            val records = CsvCodec.parse(text.removePrefix("\uFEFF"))
            require(records.isNotEmpty()) { "CSV is empty." }
            val header = records.first().map { it.trim().lowercase() }
            fun requiredColumn(name: String): Int = header.indexOf(name).also {
                require(it >= 0) { "Missing CSV column: $name" }
            }

            val dateCol = requiredColumn("date")
            val typeCol = requiredColumn("type")
            val amountCol = requiredColumn("amount")
            val categoryCol = header.indexOf("category")
            val payeeCol = header.indexOf("payee")
            val notesCol = header.indexOf("notes")
            val transactionIdCol = header.indexOf("transactionid")
            val sourceFingerprintCol = header.indexOf("sourcefingerprint")
            val sourceKindCol = header.indexOf("sourcekind")

            val existing = database.transactionDao().getForAccount(accountId)
            val existingIds = existing.mapTo(mutableSetOf()) { it.id }
            val existingCategories = database.categoryDao().getAll()
                .associateBy { it.name.trim().lowercase() }
            val comparisonPool = existing.toMutableList()
            val items = mutableListOf<CsvReconciliationItem>()
            val fingerprintOccurrences = mutableMapOf<String, Int>()

            records.drop(1)
                .filterNot { row -> row.all { it.isBlank() } }
                .forEachIndexed { index, row ->
                    val rowNumber = index + 2
                    fun value(column: Int): String =
                        if (column >= 0) row.getOrNull(column).orEmpty().trim() else ""

                    val date = runCatching { LocalDate.parse(value(dateCol), dateFormatter) }.getOrNull()
                        ?: error("Row $rowNumber: invalid Date; expected dd/MM/yyyy.")
                    val rawType = value(typeCol)
                    val direction = when {
                        rawType.equals("Aaya", true) || rawType.equals("Income", true) || rawType.equals("Credit", true) -> TransactionDirection.CREDIT
                        rawType.equals("Vyaya", true) || rawType.equals("Expense", true) || rawType.equals("Debit", true) -> TransactionDirection.DEBIT
                        else -> error("Row $rowNumber: Type must be Aaya/Vyaya (or Income/Expense).")
                    }
                    val amountPaise = runCatching {
                        BigDecimal(value(amountCol))
                            .movePointRight(2)
                            .setScale(0, RoundingMode.HALF_UP)
                            .longValueExact()
                    }.getOrNull()?.takeIf { it > 0 }
                        ?: error("Row $rowNumber: invalid positive Amount.")

                    val category = value(categoryCol).ifBlank {
                        if (direction == TransactionDirection.CREDIT) "Income" else "Uncategorised"
                    }
                    val payee = value(payeeCol).ifBlank {
                        if (direction == TransactionDirection.CREDIT) "Unlabelled Aaya" else "Unlabelled Vyaya"
                    }
                    val description = value(notesCol).ifBlank { null }
                    val epochMs = date.atTime(12, 0)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                    val categoryPriority = existingCategories[category.trim().lowercase()]?.priority
                        ?: CategoryPriority.NEED
                    val envelope = when {
                        direction == TransactionDirection.CREDIT -> null
                        categoryPriority == CategoryPriority.WANT -> EnvelopeType.WANTS
                        else -> EnvelopeType.NEEDS
                    }

                    val canonicalKey = canonicalSourceKey(
                        accountId = accountId,
                        date = date,
                        direction = direction,
                        amountPaise = amountPaise,
                        category = category,
                        payee = payee,
                        description = description,
                    )
                    val occurrence = (fingerprintOccurrences[canonicalKey] ?: 0) + 1
                    fingerprintOccurrences[canonicalKey] = occurrence
                    val importedFingerprint = value(sourceFingerprintCol).ifBlank {
                        csvFingerprint(canonicalKey, occurrence)
                    }
                    val importedSourceKind = value(sourceKindCol).ifBlank { "CSV" }
                    val importedId = value(transactionIdCol).ifBlank { UUID.randomUUID().toString() }

                    // Historical imports never enter the behavioural cooling tank. Provenance is
                    // carried through preview, but reconciledAtEpochMs is set only on commit.
                    val candidate = TransactionEntity(
                        id = importedId,
                        accountId = accountId,
                        amountPaise = amountPaise,
                        direction = direction,
                        merchant = payee,
                        category = category,
                        payee = payee,
                        description = description,
                        envelopeType = envelope,
                        occurredAtEpochMs = epochMs,
                        isHoldingTank = false,
                        coolDownExpiryEpochMs = null,
                        sourceFingerprint = importedFingerprint,
                        sourceKind = importedSourceKind,
                    )

                    val decision = TransactionReconciliationEngine.classify(candidate, comparisonPool)
                    val matched = decision.matchedTransactionId?.let { id ->
                        comparisonPool.firstOrNull { it.id == id }
                    }
                    val matchedExisting = matched?.id?.let(existingIds::contains) == true
                    val suggestedAction = when (decision.status) {
                        ReconciliationStatus.NEW -> CsvImportAction.KEEP
                        ReconciliationStatus.PROBABLE_DUPLICATE,
                        ReconciliationStatus.POSSIBLE_MATCH,
                        ReconciliationStatus.CONFLICT,
                        ReconciliationStatus.ALREADY_RECONCILED -> CsvImportAction.SKIP
                    }
                    val reason = if (matched != null && !matchedExisting) {
                        "${decision.reason} It matches another row in this CSV preview."
                    } else {
                        decision.reason
                    }

                    items += CsvReconciliationItem(
                        rowNumber = rowNumber,
                        candidate = candidate,
                        status = decision.status,
                        matchedTransaction = matched,
                        matchedExistingRecord = matchedExisting,
                        reason = reason,
                        suggestedAction = suggestedAction
                    )
                    comparisonPool += candidate
                }

            require(items.isNotEmpty()) { "CSV contains no transaction rows." }
            CsvImportPreviewResult.Ready(
                CsvImportPlan(
                    accountId = account.id,
                    accountName = account.name,
                    items = items
                )
            )
        }.getOrElse { error ->
            val detail = error.message?.takeIf { it.isNotBlank() } ?: "Unknown CSV error."
            CsvImportPreviewResult.Failure(
                "CSV reconciliation preview failed: $detail No changes were committed.",
                error
            )
        }
    }

    /**
     * Commits the user's row-by-row decisions in one Room transaction.
     * REPLACE reuses the matched existing transaction id; it is never allowed for cooling-tank rows.
     */
    suspend fun commitImport(
        plan: CsvImportPlan,
        actions: Map<Int, CsvImportAction>,
        database: NirmalamDatabase
    ): CsvImportResult = withContext(Dispatchers.IO) {
        runCatching {
            val account = database.accountDao().getById(plan.accountId)
                ?: error("The destination Khata no longer exists.")
            require(AccountRolePolicy.supportsTransactions(account.kind)) {
                "The destination Khata is no longer available for transactions."
            }

            val toWrite = mutableListOf<TransactionEntity>()
            var replaced = 0
            var skipped = 0
            val reconciledAt = System.currentTimeMillis()

            plan.items.forEach { item ->
                val action = actions[item.rowNumber] ?: item.suggestedAction
                when (action) {
                    CsvImportAction.KEEP -> toWrite += item.candidate.copy(reconciledAtEpochMs = reconciledAt)
                    CsvImportAction.SKIP -> skipped++
                    CsvImportAction.REPLACE -> {
                        require(item.canReplace) {
                            "Row ${item.rowNumber} cannot replace its matched record."
                        }
                        val matched = requireNotNull(item.matchedTransaction)
                        toWrite += item.candidate.copy(id = matched.id, reconciledAtEpochMs = reconciledAt)
                        replaced++
                    }
                }
            }

            // Only create supporting metadata for rows that the user actually chose to write.
            val categories = toWrite.associate { transaction ->
                transaction.category.orEmpty() to transaction.direction
            }
            val payees = toWrite.associate { transaction ->
                transaction.payee.orEmpty() to transaction.category
            }

            if (toWrite.isNotEmpty()) {
                database.withTransaction {
                    categories.forEach { (name, direction) ->
                        if (name.isNotBlank() && database.categoryDao().getByName(name) == null) {
                            database.categoryDao().upsert(
                                CategoryEntity(
                                    id = "user-imported-${UUID.randomUUID()}",
                                    name = name,
                                    transactionDirection = direction
                                )
                            )
                        }
                    }
                    payees.forEach { (name, category) ->
                        if (name.isNotBlank() && database.payeeDao().getByName(name) == null) {
                            database.payeeDao().upsert(
                                PayeeEntity(
                                    id = "payee-imported-${UUID.randomUUID()}",
                                    name = name,
                                    defaultCategory = category
                                )
                            )
                        }
                    }
                    database.transactionDao().upsertAll(toWrite)
                }
            }

            CsvImportResult.Success(
                imported = toWrite.size - replaced,
                replaced = replaced,
                skipped = skipped,
                probableDuplicates = plan.probableDuplicateCount,
                possibleMatches = plan.possibleMatchCount,
                conflicts = plan.conflictCount,
                alreadyReconciled = plan.alreadyReconciledCount,
            )
        }.getOrElse { error ->
            val detail = error.message?.takeIf { it.isNotBlank() } ?: "Unknown CSV error."
            CsvImportResult.Failure(
                "CSV import failed: $detail No partial changes were committed.",
                error
            )
        }
    }

    /**
     * Backward-compatible non-interactive entry point. It applies conservative suggested actions:
     * new rows are kept and anything needing review is skipped.
     */
    suspend fun importFrom(
        source: Uri,
        accountId: String,
        database: NirmalamDatabase
    ): CsvImportResult = when (val preview = previewImport(source, accountId, database)) {
        is CsvImportPreviewResult.Failure -> CsvImportResult.Failure(preview.message, preview.cause)
        is CsvImportPreviewResult.Ready -> commitImport(
            preview.plan,
            preview.plan.items.associate { it.rowNumber to it.suggestedAction },
            database
        )
    }
}
