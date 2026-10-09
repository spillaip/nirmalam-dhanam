package com.nirmalamgroup.nirmalamdhanam.domain.usecase

import com.nirmalamgroup.nirmalamdhanam.data.local.AccountEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionDirection
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.EnvelopeType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

sealed interface CopilotResult {
    data class Answer(val text: String, val evidenceTransactionIds: List<String> = emptyList()) : CopilotResult
    data class TransactionPreview(
        val amountPaise: Long,
        val direction: TransactionDirection,
        val accountId: String,
        val accountName: String,
        val payee: String,
        val category: String,
        val occurredAtEpochMs: Long
    ) : CopilotResult
    data class Unsupported(val message: String) : CopilotResult
}

/**
 * A private deterministic command layer. Read commands stay on device. Write commands return a
 * preview and require a second explicit confirmation before any Room write is performed.
 */
object LocalFinancialCopilot {
    fun interpret(
        input: String,
        transactions: List<TransactionEntity>,
        accounts: List<AccountEntity>,
        currencyCode: String,
        zone: ZoneId = ZoneId.systemDefault()
    ): CopilotResult {
        val query = input.trim()
        if (query.isBlank()) return CopilotResult.Unsupported("Ask about spending, income, or create a transaction preview.")
        val lower = query.lowercase()

        val categoryMatch = Regex("(?:spent|spend|expense).*?(?:on|for)\\s+(.+?)(?:\\s+this\\s+(year|month))?$", RegexOption.IGNORE_CASE).find(query)
        if (categoryMatch != null) {
            val category = categoryMatch.groupValues[1].trim()
            val period = categoryMatch.groupValues.getOrNull(2)?.lowercase()
            val today = LocalDate.now(zone)
            val start = when (period) {
                "year" -> today.withDayOfYear(1)
                else -> today.withDayOfMonth(1)
            }.atStartOfDay(zone).toInstant().toEpochMilli()
            val matches = transactions.filter {
                it.direction == TransactionDirection.DEBIT && it.envelopeType != EnvelopeType.INVESTMENT && !it.isHoldingTank && it.occurredAtEpochMs >= start &&
                    (it.category?.contains(category, true) == true || it.payee?.contains(category, true) == true)
            }
            val total = matches.sumOf { it.amountPaise }
            return CopilotResult.Answer("Recorded $currencyCode ${"%.2f".format(total / 100.0)} for '$category' in this ${period ?: "month"}.", matches.map { it.id })
        }

        if ("income" in lower && ("month" in lower || "this month" in lower)) {
            val start = LocalDate.now(zone).withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val matches = transactions.filter { it.direction == TransactionDirection.CREDIT && !it.isHoldingTank && it.occurredAtEpochMs >= start }
            return CopilotResult.Answer("This month's recorded income is $currencyCode ${"%.2f".format(matches.sumOf { it.amountPaise } / 100.0)}.", matches.map { it.id })
        }

        val create = Regex("(?:add|create|record)\\s+(?:a\\s+)?(?:₹|rs\\.?|inr)?\\s*([0-9]+(?:\\.[0-9]{1,2})?)\\s+(expense|income)(?:\\s+(?:from|to|at)\\s+(.+?))?(?:\\s+(?:in|from)\\s+(.+))?$", RegexOption.IGNORE_CASE).find(query)
        if (create != null) {
            val amountPaise = BigDecimal(create.groupValues[1]).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
            val direction = if (create.groupValues[2].equals("income", true)) TransactionDirection.CREDIT else TransactionDirection.DEBIT
            val payee = create.groupValues.getOrNull(3)?.trim()?.ifBlank { if (direction == TransactionDirection.CREDIT) "Income" else "Expense" }
                ?: if (direction == TransactionDirection.CREDIT) "Income" else "Expense"
            val requestedAccount = create.groupValues.getOrNull(4)?.trim().orEmpty()
            val account = accounts.firstOrNull { requestedAccount.isNotBlank() && it.name.contains(requestedAccount, true) }
                ?: accounts.firstOrNull { AccountRolePolicy.supportsTransactions(it.kind) }
                ?: return CopilotResult.Unsupported("Create a transactional cash/bank or credit Khata first.")
            return CopilotResult.TransactionPreview(
                amountPaise = amountPaise,
                direction = direction,
                accountId = account.id,
                accountName = account.name,
                payee = payee,
                category = if (direction == TransactionDirection.CREDIT) "Income" else "Uncategorised",
                occurredAtEpochMs = System.currentTimeMillis()
            )
        }

        return CopilotResult.Unsupported("I can answer local spending/income questions or prepare a transaction preview. No financial record is changed until you confirm.")
    }
}
