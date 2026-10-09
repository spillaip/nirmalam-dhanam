package com.nirmalamgroup.nirmalamdhanam.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "nirmalam_dhanam_config")
data class ConfigEntity(
    @PrimaryKey val id: Int = 1,
    val hourlyRatePaise: Long,
    val impulseCoolDownThresholdPaise: Long,
    /** Simplifies presentation only; it never changes balances, budgets, or transaction rules. */
    @ColumnInfo(defaultValue = "0") val neurodiverseModeEnabled: Boolean = false,
    val currencyCode: String = "INR",
    @ColumnInfo(defaultValue = "DEVICE_LOCALE") val dateFormatPreference: DateFormatPreference = DateFormatPreference.DEVICE_LOCALE,
    @ColumnInfo(defaultValue = "MONTH") val savedLedgerRange: String = "MONTH",
    @ColumnInfo(defaultValue = "ALL") val savedLedgerFilter: String = "ALL",
    val savedLedgerAccountId: String? = null,
    val savedLedgerCategoryName: String? = null,
    /** Stops optional first-run suggestions and demo records from being restored after removal. */
    @ColumnInfo(defaultValue = "0") val starterDataRemoved: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

enum class DateFormatPreference { DEVICE_LOCALE, DD_MMM_YYYY, DD_MM_YYYY, MM_DD_YYYY, YYYY_MM_DD }

enum class AccountKind { SPENDING, CREDIT, SAVINGS, EMERGENCY, INVESTMENT }
enum class AccountProductType { CASH, BANK, CREDIT_CARD, LOAN, PPF, EPF, NPS, SUPERANNUATION, MUTUAL_FUNDS, EQUITY, STOCKS, BULLION, REAL_ESTATE }
enum class AssetClass { EQUITY, DEBT, RETIREMENT, CASH, GOLD, BULLION, REAL_ESTATE, OTHER }
enum class CategoryPriority { NEED, WANT }
enum class CategoryNature { FIXED, VARIABLE }
/** How a Nivesha should be compared with its declared benchmark. */
enum class BenchmarkTrackingMethod { NONE, INDEX_TRACKING, ACTIVE_BENCHMARK, MANUAL }

@Entity(tableName = "accounts", indices = [Index("kind")])
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: AccountKind,
    /** Product grouping drives the cash ledger and portfolio views. */
    @ColumnInfo(defaultValue = "CASH") val productType: AccountProductType = AccountProductType.CASH,
    @ColumnInfo(defaultValue = "CASH") val assetClass: AssetClass = AssetClass.CASH,
    /** Target portfolio weight in basis points; 1% = 100 basis points. */
    @ColumnInfo(defaultValue = "0") val targetAllocationBps: Int = 0,
    val openingBalancePaise: Long = 0,
    /** Market balance or token pool balance for RWAs. */
    val currentMarketPaise: Long = 0,
    /** Fundamental/intrinsic value for token scoring. */
    val intrinsicValuePaise: Long = 0,
    /** Official AMC/issuer benchmark. This is user-confirmed; names are never treated as authoritative. */
    val benchmarkIndexName: String? = null,
    @ColumnInfo(defaultValue = "NONE") val benchmarkTrackingMethod: BenchmarkTrackingMethod = BenchmarkTrackingMethod.NONE,
    @ColumnInfo(defaultValue = "1") val benchmarkIsTotalReturn: Boolean = true,
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
    val iconKey: String? = null,
    @ColumnInfo(defaultValue = "NEED") val priority: CategoryPriority = CategoryPriority.NEED,
    @ColumnInfo(defaultValue = "VARIABLE") val nature: CategoryNature = CategoryNature.VARIABLE
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
    /** Derived from the cost change versus the previous check-in; positive = contribution, negative = withdrawal. */
    val netContributionPaise: Long = 0,
    /** Previous check-in values are stored so file exports remain independently auditable. */
    val previousCostPaise: Long? = null,
    val previousValuePaise: Long? = null,
    /** Difference versus the prior check-in. */
    @ColumnInfo(defaultValue = "0") val costDeltaPaise: Long = 0,
    @ColumnInfo(defaultValue = "0") val valueDeltaPaise: Long = 0,
    /**
     * Best available movement after external-flow adjustment. Exact when no redemption is
     * inferred; for balance-only withdrawals it is a cost-basis-flow estimate. Richer
     * determinability metadata is derived by InvestmentDeltaEngine rather than duplicated here.
     */
    @ColumnInfo(defaultValue = "0") val marketMovementPaise: Long = 0,
    val note: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    val unrealizedGainPaise: Long get() = currentValuePaise - totalCostPaise
}

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

@Entity(tableName = "transactions", indices = [Index("accountId"), Index("occurredAtEpochMs"), Index("envelopeType"), Index("isHoldingTank"), Index("sourceFingerprint")])
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
    val note: String? = null,
    /** Stable provenance fingerprint for imported records; null for ordinary manual entries. */
    val sourceFingerprint: String? = null,
    /** Local import channel such as CSV or DHANAM. Never contains account credentials or raw source text. */
    val sourceKind: String? = null,
    /** Set only after an imported record has been explicitly committed/reconciled into the ledger. */
    val reconciledAtEpochMs: Long? = null
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

/** User-defined financial objective. Funding is linked to existing accounts/assets, never duplicated. */
@Entity(tableName = "goals", indices = [Index("targetDateEpochDay")])
data class GoalEntity(
    @PrimaryKey val id: String,
    val name: String,
    val targetAmountPaise: Long,
    val targetDateEpochDay: Long? = null,
    val note: String? = null,
    val isArchived: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

/** Percentage of an account/asset that contributes to a goal. 10,000 bps = 100%. */
@Entity(tableName = "goal_allocations", indices = [Index(value = ["goalId", "accountId"], unique = true), Index("accountId")])
data class GoalAllocationEntity(
    @PrimaryKey val id: String,
    val goalId: String,
    val accountId: String,
    val allocationBps: Int = 10_000
)

data class CashPosition(val spendingPaise: Long, val creditLiabilityPaise: Long) {
    val trueAvailableCashPaise: Long get() = spendingPaise - creditLiabilityPaise
}
