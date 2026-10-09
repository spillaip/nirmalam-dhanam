package com.nirmalamgroup.nirmalamdhanam

import com.nirmalamgroup.nirmalamdhanam.data.local.AccountBalance
import com.nirmalamgroup.nirmalamdhanam.data.local.AccountKind
import com.nirmalamgroup.nirmalamdhanam.data.local.InvestmentBalanceSnapshotEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.NetWorthSnapshotEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.NirmalamDatabase
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.InvestmentDeltaEngine

internal object InvestmentLedgerService {
    suspend fun recalculateDeltas(opened: NirmalamDatabase, accountId: String) {
        val dao = opened.investmentBalanceSnapshotDao()
        var previous: InvestmentBalanceSnapshotEntity? = null
        dao.getForAccount(accountId).forEach { raw ->
            val derived = InvestmentDeltaEngine.apply(raw, previous)
            if (derived != raw) dao.upsert(derived)
            previous = derived
        }
    }

    suspend fun refreshNetWorthSnapshot(
        opened: NirmalamDatabase,
        epochDay: Long,
        cashPaise: Long,
        accountBalances: List<AccountBalance>,
        cashAdjustmentPaise: Long = 0L,
    ) {
        val latestByAsset = opened.investmentBalanceSnapshotDao().getAll()
            .filter { it.asOfEpochDay <= epochDay }
            .groupBy { it.accountId }
            .mapValues { (_, values) -> values.maxBy { it.asOfEpochDay }.currentValuePaise }
        val portfolioValue = latestByAsset.values.sum()
        val reserves = accountBalances
            .filter { it.kind == AccountKind.SAVINGS || it.kind == AccountKind.EMERGENCY }
            .sumOf { it.balancePaise }
        opened.netWorthSnapshotDao().upsert(
            NetWorthSnapshotEntity(
                id = "net-worth-$epochDay",
                asOfEpochDay = epochDay,
                netWorthPaise = cashPaise + cashAdjustmentPaise + reserves + portfolioValue,
                portfolioValuePaise = portfolioValue,
            )
        )
    }
}
