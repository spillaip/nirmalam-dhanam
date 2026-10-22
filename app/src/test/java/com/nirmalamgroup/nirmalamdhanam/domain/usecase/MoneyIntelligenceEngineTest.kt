package com.nirmalamgroup.nirmalamdhanam.domain.usecase

import com.nirmalamgroup.nirmalamdhanam.data.local.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.time.LocalDate
import java.time.ZoneOffset

class MoneyIntelligenceEngineTest {
    private fun tx(
        date: LocalDate,
        amount: Long,
        direction: TransactionDirection = TransactionDirection.DEBIT,
        payee: String = "Payee",
        envelope: EnvelopeType? = EnvelopeType.NEEDS,
    ) = TransactionEntity(
        id = "$date-$amount-$direction-$payee",
        accountId = "cash",
        amountPaise = amount,
        direction = direction,
        payee = payee,
        category = if (direction == TransactionDirection.DEBIT) "Bills" else "Salary",
        envelopeType = envelope,
        occurredAtEpochMs = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
    )

    @Test
    fun spendingPace_excludesInvestmentTransfers() {
        val asOf = LocalDate.of(2026, 10, 10)
        val result = MoneyIntelligenceEngine.spendingPace(
            transactions = listOf(
                tx(LocalDate.of(2026, 10, 2), 10_000),
                tx(LocalDate.of(2026, 10, 4), 20_000),
                tx(LocalDate.of(2026, 10, 5), 1_000_000, envelope = EnvelopeType.INVESTMENT),
            ),
            asOf = asOf,
            zoneId = ZoneOffset.UTC,
        )
        assertEquals(30_000, result.monthToDatePaise)
        assertTrue(result.projectedMonthPaise > result.monthToDatePaise)
    }

    @Test
    fun recurringRadar_detectsMonthlyPattern() {
        val radar = MoneyIntelligenceEngine.recurringMoneyRadar(
            transactions = listOf(
                tx(LocalDate.of(2026, 7, 5), 50_000, payee = "Internet"),
                tx(LocalDate.of(2026, 8, 5), 50_000, payee = "Internet"),
                tx(LocalDate.of(2026, 9, 5), 51_000, payee = "Internet"),
                tx(LocalDate.of(2026, 10, 5), 50_000, payee = "Internet"),
            ),
            asOf = LocalDate.of(2026, 10, 20),
            zoneId = ZoneOffset.UTC,
        )
        assertEquals(1, radar.items.size)
        assertEquals(RecurringCadence.MONTHLY, radar.items.single().cadence)
        assertTrue(radar.monthlyOutflowPaise >= 50_000)
    }

    @Test
    fun reserveRunway_usesNeedSpendingAndReserveBalances() {
        val runway = MoneyIntelligenceEngine.reserveRunway(
            balances = listOf(AccountBalance("reserve", AccountKind.EMERGENCY, AccountProductType.BANK, 600_000)),
            transactions = listOf(
                tx(LocalDate.of(2026, 8, 1), 300_000),
                tx(LocalDate.of(2026, 9, 1), 300_000),
                tx(LocalDate.of(2026, 10, 1), 300_000),
            ),
            categories = listOf(CategoryEntity("b", "Bills", TransactionDirection.DEBIT, priority = CategoryPriority.NEED)),
            asOf = LocalDate.of(2026, 10, 20),
            zoneId = ZoneOffset.UTC,
        )
        assertNotNull(runway.runwayMonths)
        assertTrue(runway.runwayMonths!! > 1.5)
    }

    @Test
    fun portfolioConcentration_reportsLargestHoldingAndClass() {
        val accounts = listOf(
            AccountEntity("a", "Equity A", AccountKind.INVESTMENT, AccountProductType.MUTUAL_FUNDS, AssetClass.EQUITY),
            AccountEntity("b", "Debt B", AccountKind.INVESTMENT, AccountProductType.PPF, AssetClass.DEBT),
        )
        val snapshots = listOf(
            InvestmentBalanceSnapshotEntity("sa", "a", 1, 700_000, 700_000),
            InvestmentBalanceSnapshotEntity("sb", "b", 1, 300_000, 300_000),
        )
        val result = MoneyIntelligenceEngine.portfolioConcentration(accounts, snapshots)
        assertEquals("Equity A", result.largestHoldingName)
        assertEquals(70.0, result.largestHoldingSharePercent)
        assertEquals(AssetClass.EQUITY, result.largestAssetClass)
    }
}
