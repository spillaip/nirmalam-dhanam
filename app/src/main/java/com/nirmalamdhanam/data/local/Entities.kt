package com.nirmalamgroup.nirmalamdhanam.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "nirmalam_dhanam_config")
data class ConfigEntity(
    @PrimaryKey val id: Int = 1,
    val hourlyRatePaise: Long,
    val impulseCoolDownThresholdPaise: Long,
    /** Simplifies presentation only; it never changes balances, budgets, or transaction rules. */
    val neurodiverseModeEnabled: Boolean = false,
    val currencyCode: String = "INR",
    /** Stops optional first-run suggestions and demo records from being restored after removal. */
    val starterDataRemoved: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

enum class AccountKind { SPENDING, CREDIT, SAVINGS, EMERGENCY, INVESTMENT }
enum class AccountProductType { CASH, BANK, CREDIT_CARD, LOAN, PPF, EPF, NPS, SUPERANNUATION, MUTUAL_FUNDS, EQUITY, STOCKS, BULLION }
enum class AssetClass { EQUITY, DEBT, RETIREMENT, CASH, GOLD, BULLION, OTHER }

@Entity(tableName = "accounts", indices = [Index("kind")])
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: AccountKind,
    /** Product grouping drives the cash ledger and portfolio views. */
    val productType: AccountProductType = AccountProductType.CASH,
    val assetClass: AssetClass = AssetClass.CASH,
    /** Target portfolio weight in basis points; 1% = 100 basis points. */
    val targetAllocationBps: Int = 0,
    val openingBalancePaise: Long = 0,
    val isArchived: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "categories", indices = [Index(value = ["name"], unique = true)])
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val transactionDirection: TransactionDirection,
    val isSystem: Boolean = false,
    /** A stable, label-backed glyph key chosen by the user. */
    val iconKey: String? = null
)

@Entity(tableName = "payees", indices = [Index(value = ["name"], unique = true)])
data class PayeeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val defaultCategory: String? = null,
    val lastUsedEpochMs: Long = System.currentTimeMillis()
)

/** A user-entered portfolio valuation. One record per asset and calendar date. */
@Entity(tableName = "investment_balance_snapshots", indices = [Index(value = ["accountId", "asOfEpochDay"], unique = true), Index("asOfEpochDay")])
data class InvestmentBalanceSnapshotEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    /** Local calendar date represented as [java.time.LocalDate.toEpochDay]. */
    val asOfEpochDay: Long,
    /** Cumulative purchase cost/principal still associated with this holding. */
    val totalCostPaise: Long,
    /** Statement balance or market value as of [asOfEpochDay]. */
    val currentValuePaise: Long,
    /** Net money added (positive) or withdrawn (negative) since the prior check-in. */
    val netContributionPaise: Long = 0,
    val note: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

data class AccountBalance(
    val accountId: String,
    val kind: AccountKind,
    val productType: AccountProductType,
    val balancePaise: Long
)

/** An immutable dated net-worth reading, refreshed whenever the user checks in an investment. */
@Entity(tableName = "net_worth_snapshots", indices = [Index(value = ["asOfEpochDay"], unique = true)])
data class NetWorthSnapshotEntity(
    @PrimaryKey val id: String,
    val asOfEpochDay: Long,
    val netWorthPaise: Long,
    val portfolioValuePaise: Long,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

enum class TransactionDirection { DEBIT, CREDIT }
enum class EnvelopeType { NEEDS, WANTS, SAVINGS, INVESTMENT }

@Entity(tableName = "transactions", indices = [Index("accountId"), Index("occurredAtEpochMs"), Index("envelopeType"), Index("isHoldingTank")])
data class TransactionEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val amountPaise: Long,
    val direction: TransactionDirection,
    val merchant: String? = null,
    /** A user-facing grouping such as Food, Transport, Bills, or Shopping. */
    val category: String? = null,
    /** The person, shop, or institution involved in the transaction. */
    val payee: String? = null,
    /** Optional private context for the user; never populated from raw SMS text. */
    val description: String? = null,
    val envelopeType: EnvelopeType? = null,
    val occurredAtEpochMs: Long = System.currentTimeMillis(),
    val isHoldingTank: Boolean = false,
    val coolDownExpiryEpochMs: Long? = null,
    val note: String? = null
)

@Entity(tableName = "budget_envelopes", indices = [Index("type")])
data class EnvelopeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: EnvelopeType,
    val dailyLimitPaise: Long,
    val allocatedPaise: Long = 0,
    val isActive: Boolean = true
)

data class CashPosition(val spendingPaise: Long, val creditLiabilityPaise: Long) {
    val trueAvailableCashPaise: Long get() = spendingPaise - creditLiabilityPaise
}
