package com.nirmalamgroup.nirmalamdhanam.data.ai

import com.nirmalamgroup.nirmalamdhanam.data.local.AccountEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.AccountKind
import com.nirmalamgroup.nirmalamdhanam.data.local.AccountProductType
import com.nirmalamgroup.nirmalamdhanam.data.local.AssetClass
import com.nirmalamgroup.nirmalamdhanam.data.local.InvestmentBalanceSnapshotEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class NirmalamAiContextTest {
    private val now = LocalDate.of(2026, 10, 7).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun portfolioDriftReceivesCurrentTargetAndGapEvidence() {
        val equity = AccountEntity(
            id = "equity",
            name = "Equity",
            kind = AccountKind.INVESTMENT,
            productType = AccountProductType.MUTUAL_FUNDS,
            assetClass = AssetClass.EQUITY,
            targetAllocationBps = 6000
        )
        val debt = AccountEntity(
            id = "debt",
            name = "Debt",
            kind = AccountKind.INVESTMENT,
            productType = AccountProductType.PPF,
            assetClass = AssetClass.DEBT,
            targetAllocationBps = 4000
        )
        val snapshots = listOf(
            InvestmentBalanceSnapshotEntity("e1", "equity", LocalDate.of(2026, 10, 1).toEpochDay(), 6_000_000, 7_000_000),
            InvestmentBalanceSnapshotEntity("d1", "debt", LocalDate.of(2026, 10, 1).toEpochDay(), 4_000_000, 3_000_000)
        )

        val prepared = prepareNirmalamAiContext(
            insight = NirmalamAiInsight.PORTFOLIO_DRIFT,
            cashPaise = 0,
            transactions = emptyList(),
            investmentHistory = snapshots,
            accounts = listOf(equity, debt),
            balances = emptyList(),
            categories = emptyList(),
            nowEpochMs = now,
            zoneId = ZoneOffset.UTC
        )

        assertTrue(prepared.available)
        assertTrue(prepared.summary.contains("EQUITY: current INR 70000.00 (70.00%), target 60.00%"))
        assertTrue(prepared.summary.contains("DEBT: current INR 30000.00 (30.00%), target 40.00%"))
        assertTrue(prepared.summary.contains("target gap INR -10000.00"))
        assertTrue(prepared.summary.contains("target gap INR 10000.00"))
    }

    @Test
    fun taxShieldRequiresCurrentFinancialYearCheckIns() {
        val ppf = AccountEntity(
            id = "ppf",
            name = "PPF",
            kind = AccountKind.INVESTMENT,
            productType = AccountProductType.PPF,
            assetClass = AssetClass.DEBT,
            targetAllocationBps = 10_000
        )

        val missing = prepareNirmalamAiContext(
            insight = NirmalamAiInsight.TAX_SHIELD,
            cashPaise = 0,
            transactions = emptyList(),
            investmentHistory = emptyList(),
            accounts = listOf(ppf),
            balances = emptyList(),
            categories = emptyList(),
            nowEpochMs = now,
            zoneId = ZoneOffset.UTC
        )
        assertFalse(missing.available)
        assertTrue(missing.missingData.any { it.contains("current-financial-year") })

        val supplied = prepareNirmalamAiContext(
            insight = NirmalamAiInsight.TAX_SHIELD,
            cashPaise = 0,
            transactions = emptyList(),
            investmentHistory = listOf(
                InvestmentBalanceSnapshotEntity(
                    id = "p1",
                    accountId = "ppf",
                    asOfEpochDay = LocalDate.of(2026, 5, 1).toEpochDay(),
                    totalCostPaise = 10_000_000,
                    currentValuePaise = 10_000_000,
                    netContributionPaise = 10_000_000
                )
            ),
            accounts = listOf(ppf),
            balances = emptyList(),
            categories = emptyList(),
            nowEpochMs = now,
            zoneId = ZoneOffset.UTC
        )
        assertTrue(supplied.available)
        assertTrue(supplied.summary.contains("Financial year 2026-2027"))
        assertTrue(supplied.summary.contains("PPF: positive cost deltas INR 100000.00"))
        assertTrue(supplied.summary.contains("not verified tax-qualified contributions"))
    }

    @Test
    fun tokenHealthIsDisabledUntilStructuredEvidenceExists() {
        val prepared = prepareNirmalamAiContext(
            insight = NirmalamAiInsight.TOKEN_HEALTH,
            cashPaise = 0,
            transactions = emptyList(),
            investmentHistory = emptyList(),
            accounts = emptyList(),
            balances = emptyList(),
            categories = emptyList(),
            nowEpochMs = now,
            zoneId = ZoneOffset.UTC
        )

        assertFalse(prepared.available)
        assertTrue(prepared.missingData.contains("asset-backing evidence"))
        assertTrue(prepared.missingData.contains("liquidity/haircut metadata"))
        assertTrue(prepared.missingData.contains("regulatory classification"))
    }
}
