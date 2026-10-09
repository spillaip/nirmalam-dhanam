package com.nirmalamgroup.nirmalamdhanam.domain.usecase

import com.nirmalamgroup.nirmalamdhanam.data.local.AccountEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.AccountKind
import com.nirmalamgroup.nirmalamdhanam.data.local.InvestmentBalanceSnapshotEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionDirection
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.EnvelopeType
import java.time.LocalDate
import java.time.ZoneId

data class HistoricalFinancialSnapshot(
    val date: LocalDate,
    val cashAndReservesPaise: Long,
    val investmentValuePaise: Long,
    val liabilitiesPaise: Long,
    val netWorthPaise: Long,
    val incomeToDatePaise: Long,
    val expenseToDatePaise: Long
)

object FinancialTimeMachine {
    fun reconstruct(
        date: LocalDate,
        accounts: List<AccountEntity>,
        transactions: List<TransactionEntity>,
        investmentHistory: List<InvestmentBalanceSnapshotEntity>,
        zone: ZoneId = ZoneId.systemDefault()
    ): HistoricalFinancialSnapshot {
        val endExclusive = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val activeAccounts = accounts.filter { it.createdAtEpochMs < endExclusive }
        val activeAccountIds = activeAccounts.mapTo(mutableSetOf()) { it.id }
        val confirmed = transactions.filter {
            !it.isHoldingTank && it.occurredAtEpochMs < endExclusive && it.accountId in activeAccountIds
        }
        val netByAccount = confirmed.groupBy { it.accountId }.mapValues { (_, rows) ->
            rows.sumOf { if (it.direction == TransactionDirection.CREDIT) it.amountPaise else -it.amountPaise }
        }
        val balances = activeAccounts.associateWith { account -> account.openingBalancePaise + (netByAccount[account.id] ?: 0L) }
        val liquid = balances.filterKeys { it.kind == AccountKind.SPENDING || it.kind == AccountKind.SAVINGS || it.kind == AccountKind.EMERGENCY }
            .values.sumOf { it.coerceAtLeast(0) }
        val liabilities = balances.filterKeys { it.kind == AccountKind.CREDIT }.values.sumOf { (-it).coerceAtLeast(0) }
        val investments = activeAccounts.filter { it.kind == AccountKind.INVESTMENT }.sumOf { account ->
            investmentHistory.filter { it.accountId == account.id && it.asOfEpochDay <= date.toEpochDay() }
                .maxByOrNull { it.asOfEpochDay }?.currentValuePaise ?: 0L
        }
        return HistoricalFinancialSnapshot(
            date = date,
            cashAndReservesPaise = liquid,
            investmentValuePaise = investments,
            liabilitiesPaise = liabilities,
            netWorthPaise = liquid + investments - liabilities,
            incomeToDatePaise = confirmed.filter { it.direction == TransactionDirection.CREDIT }.sumOf { it.amountPaise },
            expenseToDatePaise = confirmed.filter { it.direction == TransactionDirection.DEBIT && it.envelopeType != EnvelopeType.INVESTMENT }.sumOf { it.amountPaise }
        )
    }
}
