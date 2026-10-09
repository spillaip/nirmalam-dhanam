package com.nirmalamgroup.nirmalamdhanam

import com.nirmalamgroup.nirmalamdhanam.data.local.*

internal data class MvpFinanceState(
    val isUnlocked: Boolean = false,
    val isLoading: Boolean = false,
    val cashPaise: Long = 0,
    val accounts: List<AccountEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val payees: List<PayeeEntity> = emptyList(),
    val currencyCode: String = "INR",
    val dateFormatPreference: DateFormatPreference = DateFormatPreference.DEVICE_LOCALE,
    val savedLedgerRange: String = "MONTH",
    val savedLedgerFilter: String = "ALL",
    val savedLedgerAccountId: String? = null,
    val savedLedgerCategoryName: String? = null,
    val neurodiverseModeEnabled: Boolean = false,
    val hourlyRatePaise: Long = 10_000,
    val safeToSpendTodayPaise: Long = 50_000,
    val todaySpentPaise: Long = 0,
    val holdingTank: List<TransactionEntity> = emptyList(),
    val accountBalances: List<AccountBalance> = emptyList(),
    val investmentSnapshots: List<InvestmentBalanceSnapshotEntity> = emptyList(),
    val investmentHistory: List<InvestmentBalanceSnapshotEntity> = emptyList(),
    val netWorthHistory: List<NetWorthSnapshotEntity> = emptyList(),
    /** Capped convenience feed for Home/autocomplete only; never use for financial calculations. */
    val recentTransactions: List<TransactionEntity> = emptyList(),
    /** Authoritative full ledger used by reports, trends, insights, time-machine, copilot, and AI summaries. */
    val allTransactions: List<TransactionEntity> = emptyList(),
    val goals: List<GoalEntity> = emptyList(),
    val goalAllocations: List<GoalAllocationEntity> = emptyList(),
    val changesSinceEpochMs: Long = 0L,
    val showInvestmentPerformance: Boolean = false,
    val nirmalamAiReady: Boolean = false,
    val nirmalamAiLoading: Boolean = false,
    val nirmalamAiResponse: String? = null,
    val csvImportPlan: CsvImportPlan? = null,
    val message: String? = null
)

internal val investmentProductTypes = setOf(AccountProductType.PPF, AccountProductType.EPF, AccountProductType.NPS, AccountProductType.SUPERANNUATION, AccountProductType.MUTUAL_FUNDS, AccountProductType.EQUITY, AccountProductType.STOCKS, AccountProductType.BULLION, AccountProductType.REAL_ESTATE)
internal const val PrivacyPolicyUrl = "https://www.nirmalamgroup.in/home/privacypolicy"
internal const val SupportEmail = "spillaip@gmail.com"
internal const val SupportWebsiteUrl = "https://www.nirmalamgroup.in/"
internal const val GithubRepositoryUrl = "https://github.com/spillaip/nirmalam-dhanam"

internal fun ledgerRangeFromConfig(value: String?): LedgerRange =
    LedgerRange.entries.firstOrNull { it.name == value } ?: LedgerRange.MONTH

internal fun ledgerFilterFromConfig(value: String?): LedgerFilter =
    LedgerFilter.entries.firstOrNull { it.name == value } ?: LedgerFilter.ALL

internal fun suggestedAssetClass(productType: AccountProductType): AssetClass = when (productType) {
    AccountProductType.CASH, AccountProductType.BANK, AccountProductType.CREDIT_CARD, AccountProductType.LOAN -> AssetClass.CASH
    AccountProductType.PPF -> AssetClass.DEBT
    AccountProductType.EPF, AccountProductType.NPS, AccountProductType.SUPERANNUATION -> AssetClass.RETIREMENT
    AccountProductType.MUTUAL_FUNDS, AccountProductType.EQUITY, AccountProductType.STOCKS -> AssetClass.EQUITY
    AccountProductType.BULLION -> AssetClass.BULLION
    AccountProductType.REAL_ESTATE -> AssetClass.REAL_ESTATE
}

