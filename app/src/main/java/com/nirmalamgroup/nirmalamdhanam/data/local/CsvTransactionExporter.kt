package com.nirmalamgroup.nirmalamdhanam.data.local

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Handles CSV export and import for income/expense transactions (Vyavahara).
 */
class CsvTransactionExporter(private val context: Context) {

    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    suspend fun exportTo(destination: Uri, transactions: List<TransactionEntity>, accounts: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                output.write("Date,Type,Amount,Category,Payee,Account,Notes\n".encodeToByteArray())
                transactions.forEach { t ->
                    val date = Instant.ofEpochMilli(t.occurredAtEpochMs).atZone(ZoneId.systemDefault()).toLocalDate().format(dateFormatter)
                    val type = if (t.direction == TransactionDirection.CREDIT) "Aaya" else "Vyaya"
                    val amount = BigDecimal(t.amountPaise).movePointLeft(2).toPlainString()
                    val accountName = accounts[t.accountId] ?: "Unknown"
                    val line = "\"$date\",\"$type\",\"$amount\",\"${t.category.orEmpty()}\",\"${t.payee.orEmpty()}\",\"$accountName\",\"${t.description.orEmpty()}\"\n"
                    output.write(line.encodeToByteArray())
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun importFrom(source: Uri, accountId: String, database: NirmalamDatabase): Int = withContext(Dispatchers.IO) {
        var count = 0
        try {
            context.contentResolver.openInputStream(source)?.use { input ->
                val reader = BufferedReader(InputStreamReader(input))
                reader.readLine() // Skip header
                var line = reader.readLine()
                while (line != null) {
                    val parts = parseCsvLine(line)
                    if (parts.size >= 3) {
                        val date = runCatching { LocalDate.parse(parts[0], dateFormatter) }.getOrNull()
                        val type = parts.getOrNull(1)?.trim()?.removeSurrounding("\"")
                        val amountPaise = runCatching { BigDecimal(parts[2].trim().removeSurrounding("\"")).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
                        val direction = if (type?.equals("Aaya", ignoreCase = true) == true) TransactionDirection.CREDIT else TransactionDirection.DEBIT
                        val categoryName = parts.getOrNull(3)?.trim()?.removeSurrounding("\"")
                        val payeeName = parts.getOrNull(4)?.trim()?.removeSurrounding("\"")
                        // parts[5] is Account Name, which we ignore in favor of the selected accountId
                        val description = parts.getOrNull(6)?.trim()?.removeSurrounding("\"")

                        if (date != null && amountPaise != null) {
                            val epochMs = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            
                            database.withTransaction {
                                // Ensure Category exists
                                val resolvedCategory = categoryName?.ifBlank { null } ?: if (direction == TransactionDirection.CREDIT) "Income" else "Uncategorised"
                                val existingCat = database.categoryDao().getByName(resolvedCategory)
                                if (existingCat == null) {
                                    database.categoryDao().upsert(CategoryEntity(
                                        id = "user-imported-${UUID.randomUUID()}",
                                        name = resolvedCategory,
                                        transactionDirection = direction
                                    ))
                                }

                                // Ensure Payee exists
                                val resolvedPayee = payeeName?.ifBlank { null } ?: if (direction == TransactionDirection.CREDIT) "Unlabelled Aaya" else "Unlabelled Vyaya"
                                val existingPayee = database.payeeDao().getByName(resolvedPayee)
                                if (existingPayee == null) {
                                    database.payeeDao().upsert(PayeeEntity(
                                        id = "payee-imported-${UUID.randomUUID()}",
                                        name = resolvedPayee,
                                        defaultCategory = resolvedCategory
                                    ))
                                }

                                // Insert Transaction
                                database.transactionDao().upsert(TransactionEntity(
                                    id = UUID.randomUUID().toString(),
                                    accountId = accountId,
                                    amountPaise = amountPaise,
                                    direction = direction,
                                    merchant = resolvedPayee,
                                    payee = resolvedPayee,
                                    category = resolvedCategory,
                                    description = description,
                                    occurredAtEpochMs = epochMs
                                ))
                            }
                            count++
                        }
                    }
                    line = reader.readLine()
                }
            }
        } catch (_: Exception) {
            // Log or handle error
        }
        count
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        for (char in line) {
            if (char == '\"') {
                inQuotes = !inQuotes
            } else if (char == ',' && !inQuotes) {
                result.add(current.toString().trim())
                current = StringBuilder()
            } else {
                current.append(char)
            }
        }
        result.add(current.toString().trim())
        return result
    }
}
