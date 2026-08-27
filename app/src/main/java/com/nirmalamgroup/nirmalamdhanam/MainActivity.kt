@file:Suppress("DEPRECATION") // Material 3 version bundled with this project exposes only menuAnchor().

package com.nirmalamgroup.nirmalamdhanam

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import com.nirmalamgroup.nirmalamdhanam.data.ai.NirmalamAiClient
import com.nirmalamgroup.nirmalamdhanam.data.ai.NirmalamAiInsight
import com.nirmalamgroup.nirmalamdhanam.data.ai.NirmalamAiPreferences
import com.nirmalamgroup.nirmalamdhanam.data.ai.buildNirmalamAiSummary
import com.nirmalamgroup.nirmalamdhanam.data.local.*
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.CoolDownTankInterceptorUseCase
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.MoneyFormatter
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.FinancialCalculations
import com.nirmalamgroup.nirmalamdhanam.ui.components.CoolDownTankCard
import com.nirmalamgroup.nirmalamdhanam.ui.components.NeurodiverseModeToggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NirmalamMvpApp() }
    }
}

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
    val safeToSpendTodayPaise: Long = 50_000,
    val todaySpentPaise: Long = 0,
    val holdingTank: List<TransactionEntity> = emptyList(),
    val accountBalances: List<AccountBalance> = emptyList(),
    val investmentSnapshots: List<InvestmentBalanceSnapshotEntity> = emptyList(),
    val investmentHistory: List<InvestmentBalanceSnapshotEntity> = emptyList(),
    val netWorthHistory: List<NetWorthSnapshotEntity> = emptyList(),
    val recentTransactions: List<TransactionEntity> = emptyList(),
    val nirmalamAiReady: Boolean = false,
    val nirmalamAiLoading: Boolean = false,
    val nirmalamAiResponse: String? = null,
    val message: String? = null
)

private data class DayDetails(val envelopes: List<EnvelopeEntity>, val spent: Long, val holding: List<TransactionEntity>, val recent: List<TransactionEntity>)
private data class AccountDirectory(val accounts: List<AccountEntity>, val balances: List<AccountBalance>, val categories: List<CategoryEntity>, val payees: List<PayeeEntity>)
private val investmentProductTypes = setOf(AccountProductType.PPF, AccountProductType.EPF, AccountProductType.NPS, AccountProductType.SUPERANNUATION, AccountProductType.MUTUAL_FUNDS, AccountProductType.EQUITY, AccountProductType.STOCKS, AccountProductType.BULLION)
private const val PrivacyPolicyUrl = "https://www.nirmalamgroup.in/home/privacypolicy"
private const val SupportEmail = "spillaip@gmail.com"
private const val SupportWebsiteUrl = "https://www.nirmalamgroup.in/"
private const val GithubRepositoryUrl = "https://github.com/spillaip/nirmalam-dhanam"

private fun ledgerRangeFromConfig(value: String?): LedgerRange =
    LedgerRange.entries.firstOrNull { it.name == value } ?: LedgerRange.MONTH

private fun ledgerFilterFromConfig(value: String?): LedgerFilter =
    LedgerFilter.entries.firstOrNull { it.name == value } ?: LedgerFilter.ALL

private fun suggestedAssetClass(productType: AccountProductType): AssetClass = when (productType) {
    AccountProductType.CASH, AccountProductType.BANK, AccountProductType.CREDIT_CARD, AccountProductType.LOAN -> AssetClass.CASH
    AccountProductType.PPF -> AssetClass.DEBT
    AccountProductType.EPF, AccountProductType.NPS, AccountProductType.SUPERANNUATION -> AssetClass.RETIREMENT
    AccountProductType.MUTUAL_FUNDS, AccountProductType.EQUITY, AccountProductType.STOCKS -> AssetClass.EQUITY
    AccountProductType.BULLION -> AssetClass.BULLION
}

private data class BenchmarkSuggestion(val indexName: String, val method: BenchmarkTrackingMethod)

/** Conservative offline suggestions only. The user-visible holding name is never treated as an authoritative source. */
private fun suggestedBenchmark(holdingName: String, productType: AccountProductType): BenchmarkSuggestion? {
    if (productType !in setOf(AccountProductType.MUTUAL_FUNDS, AccountProductType.EQUITY, AccountProductType.STOCKS)) return null
    val name = holdingName.lowercase()
    val index = when {
        "nifty next 50" in name -> "Nifty Next 50 TRI"
        "nifty 50" in name -> "Nifty 50 TRI"
        "sensex" in name -> "S&P BSE SENSEX TRI"
        "midcap" in name -> "Nifty Midcap 150 TRI"
        "smallcap" in name -> "Nifty Smallcap 250 TRI"
        "nifty 500" in name -> "Nifty 500 TRI"
        "gold" in name -> "Domestic gold price benchmark"
        else -> return null
    }
    return BenchmarkSuggestion(index, BenchmarkTrackingMethod.INDEX_TRACKING)
}

/**
 * Public because Android's default ViewModel factory creates it through reflection.
 * Keeping this type private prevents the launcher activity from being created at runtime.
 */
@Suppress("StaticFieldLeak")
class NirmalamMvpViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.applicationContext
    private val _state = MutableStateFlow(MvpFinanceState())
    internal val state: StateFlow<MvpFinanceState> = _state.asStateFlow()
    private var database: NirmalamDatabase? = null
    private var observation: Job? = null
    private val coolDown = CoolDownTankInterceptorUseCase()

    fun unlock(passphrase: String) {
        if (passphrase.length < 8) { _state.update { it.copy(message = "Use at least 8 characters for your passphrase.") }; return }
        _state.update { it.copy(isLoading = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val opened = NirmalamDatabase.create(app, passphrase.toCharArray())
                opened.withTransaction {
                    var config = opened.configDao().observe().first()
                    if (config == null) {
                        opened.configDao().save(ConfigEntity(hourlyRatePaise = 10_000, impulseCoolDownThresholdPaise = 50_000))
                        config = opened.configDao().observe().first()
                    }
                    if (opened.envelopeDao().observeActive().first().none { it.type == EnvelopeType.WANTS }) {
                        opened.envelopeDao().upsert(EnvelopeEntity("daily-wants", "Daily spending", EnvelopeType.WANTS, dailyLimitPaise = 50_000))
                    }
                    if (config?.starterDataRemoved != true) {
                        seedReferenceData(opened)
                        seedDemoDataIfEmpty(opened)
                    }
                }
                database = opened
                observation?.cancel()
                observation = viewModelScope.launch {
                    val dayStart = System.currentTimeMillis() / 86_400_000L * 86_400_000L
                    val dayDetails = combine(
                        opened.envelopeDao().observeActive(),
                        opened.transactionDao().observeSpentBetween(dayStart, dayStart + 86_400_000L),
                        opened.transactionDao().observeHoldingTank(System.currentTimeMillis()),
                        opened.transactionDao().observeRecent()
                    ) { envelopes, spent, holding, recent -> DayDetails(envelopes, spent, holding, recent) }
                    val accountDetails = combine(opened.accountDao().observeActive(), opened.accountDao().observeBalances(), opened.categoryDao().observeAll(), opened.payeeDao().observeAll()) { accounts, balances, categories, payees -> AccountDirectory(accounts, balances, categories, payees) }
                    val portfolioDetails = combine(opened.investmentBalanceSnapshotDao().observeLatestForAll(), opened.investmentBalanceSnapshotDao().observeAll(), opened.netWorthSnapshotDao().observeAll()) { latest, history, netWorth -> Triple(latest, history, netWorth) }
                    combine(opened.accountDao().observeCashPosition(), accountDetails, opened.configDao().observe(), dayDetails, portfolioDetails) { cash, accountDetailsValue, config, day, portfolio ->
                        val dailyLimit = day.envelopes.firstOrNull { it.type == EnvelopeType.WANTS }?.dailyLimitPaise ?: 50_000
                        MvpFinanceState(isUnlocked = true, isLoading = false, cashPaise = cash.trueAvailableCashPaise, accounts = accountDetailsValue.accounts, categories = accountDetailsValue.categories, payees = accountDetailsValue.payees, currencyCode = config?.currencyCode ?: "INR", dateFormatPreference = config?.dateFormatPreference ?: DateFormatPreference.DEVICE_LOCALE, savedLedgerRange = config?.savedLedgerRange ?: "MONTH", savedLedgerFilter = config?.savedLedgerFilter ?: "ALL", savedLedgerAccountId = config?.savedLedgerAccountId, savedLedgerCategoryName = config?.savedLedgerCategoryName, neurodiverseModeEnabled = config?.neurodiverseModeEnabled ?: false, safeToSpendTodayPaise = FinancialCalculations.safeToSpend(dailyLimit, day.spent), todaySpentPaise = day.spent, holdingTank = day.holding, accountBalances = accountDetailsValue.balances, investmentSnapshots = portfolio.first, investmentHistory = portfolio.second, netWorthHistory = portfolio.third, recentTransactions = day.recent, nirmalamAiReady = NirmalamAiPreferences(app).isReady(), nirmalamAiLoading = _state.value.nirmalamAiLoading, nirmalamAiResponse = _state.value.nirmalamAiResponse)
                    }.catch { error -> emit(MvpFinanceState(message = "Could not read the encrypted database: ${error.message}")) }
                        .collect { _state.value = it }
                }
            } catch (_: Throwable) {
                _state.update { it.copy(isLoading = false, message = "Unable to unlock this database. Check your passphrase.") }
            }
        }
    }

    /** Gives a new local database a useful, entirely offline starting story for phone and tablet previews. */
    private suspend fun seedDemoDataIfEmpty(opened: NirmalamDatabase) {
        if (opened.accountDao().observeActive().first().isNotEmpty()) return

        val bankId = "demo-bank"
        val emergencyId = "demo-emergency"
        val creditId = "demo-credit"
        val ppfId = "demo-ppf"
        val fundId = "demo-fund"
        val equityId = "demo-equity"
        val today = LocalDate.now()
        fun atDay(daysAgo: Long) = today.minusDays(daysAgo).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun entry(id: String, daysAgo: Long, amountPaise: Long, direction: TransactionDirection, payee: String, varga: String, description: String, type: EnvelopeType? = if (direction == TransactionDirection.DEBIT) EnvelopeType.NEEDS else null) =
            TransactionEntity(id, bankId, amountPaise, direction, merchant = payee, payee = payee, category = varga, description = description, envelopeType = type, occurredAtEpochMs = atDay(daysAgo))

        listOf(
            AccountEntity(bankId, "Nirmala Bank", AccountKind.SPENDING, AccountProductType.BANK, AssetClass.CASH, openingBalancePaise = 85_000_00),
            AccountEntity(emergencyId, "Emergency Vault", AccountKind.EMERGENCY, AccountProductType.BANK, AssetClass.CASH, openingBalancePaise = 3_00_000_00),
            AccountEntity(creditId, "Sattva Credit Card", AccountKind.CREDIT, AccountProductType.CREDIT_CARD, AssetClass.CASH, openingBalancePaise = -12_500_00),
            AccountEntity(ppfId, "PPF", AccountKind.INVESTMENT, AccountProductType.PPF, AssetClass.RETIREMENT, targetAllocationBps = 2_000),
            AccountEntity(fundId, "Nifty 50 Index Fund", AccountKind.INVESTMENT, AccountProductType.MUTUAL_FUNDS, AssetClass.EQUITY, targetAllocationBps = 4_500),
            AccountEntity(equityId, "Indian Equity", AccountKind.INVESTMENT, AccountProductType.EQUITY, AssetClass.EQUITY, targetAllocationBps = 3_500)
        ).forEach { opened.accountDao().upsert(it) }

        listOf(
            PayeeEntity("demo-payee-employer", "Aarohan Systems", "Salary & wages"),
            PayeeEntity("demo-payee-grocer", "Nirmal Grocers", "Groceries"),
            PayeeEntity("demo-payee-rent", "Ananya Homes", "Rent & housing"),
            PayeeEntity("demo-payee-metro", "Namma Metro", "Transport & fuel"),
            PayeeEntity("demo-payee-fund", "Nifty 50 Index Fund", "Interest & dividends")
        ).forEach { opened.payeeDao().upsert(it) }

        listOf(
            entry("demo-salary", 28, 1_35_000_00, TransactionDirection.CREDIT, "Aarohan Systems", "Salary & wages", "Monthly salary"),
            entry("demo-rent", 27, 28_000_00, TransactionDirection.DEBIT, "Ananya Homes", "Rent & housing", "Home rent"),
            entry("demo-groceries-1", 22, 2_840_00, TransactionDirection.DEBIT, "Nirmal Grocers", "Groceries", "Weekly groceries"),
            entry("demo-metro-1", 19, 620_00, TransactionDirection.DEBIT, "Namma Metro", "Transport & fuel", "Commute top-up"),
            entry("demo-electricity", 16, 1_480_00, TransactionDirection.DEBIT, "BESCOM", "Utilities & mobile", "Electricity bill"),
            entry("demo-health", 12, 890_00, TransactionDirection.DEBIT, "Wellness Pharmacy", "Health & pharmacy", "Health essentials"),
            entry("demo-internet", 9, 999_00, TransactionDirection.DEBIT, "Airtel", "Internet & subscriptions", "Home internet"),
            entry("demo-groceries-2", 6, 2_165_00, TransactionDirection.DEBIT, "Nirmal Grocers", "Groceries", "Weekly groceries"),
            entry("demo-coffee", 2, 260_00, TransactionDirection.DEBIT, "Sankalp Cafe", "Food & dining", "Coffee with a friend"),
            entry("demo-salary-previous", 58, 1_35_000_00, TransactionDirection.CREDIT, "Aarohan Systems", "Salary & wages", "Monthly salary"),
            entry("demo-rent-previous", 57, 28_000_00, TransactionDirection.DEBIT, "Ananya Homes", "Rent & housing", "Home rent"),
            entry("demo-groceries-previous", 50, 3_120_00, TransactionDirection.DEBIT, "Nirmal Grocers", "Groceries", "Weekly groceries")
        ).forEach { opened.transactionDao().upsert(it) }
        opened.transactionDao().upsert(entry("demo-cooling", 0, 4_500_00, TransactionDirection.DEBIT, "Aurelia Store", "Shopping", "A considered purchase", EnvelopeType.WANTS).copy(isHoldingTank = true, coolDownExpiryEpochMs = System.currentTimeMillis() + 36 * 60 * 60 * 1_000L))

        fun snapshot(id: String, accountId: String, daysAgo: Long, cost: Long, value: Long, contribution: Long, note: String) =
            InvestmentBalanceSnapshotEntity(id, accountId, today.minusDays(daysAgo).toEpochDay(), cost, value, contribution, note)
        listOf(
            snapshot("demo-ppf-old", ppfId, 90, 2_00_000_00, 2_11_000_00, 0, "Quarterly statement"),
            snapshot("demo-ppf-now", ppfId, 0, 2_05_000_00, 2_19_500_00, 5_000_00, "Monthly check-in"),
            snapshot("demo-fund-old", fundId, 90, 3_60_000_00, 3_78_000_00, 0, "Quarterly check-in"),
            snapshot("demo-fund-now", fundId, 0, 3_75_000_00, 4_12_400_00, 15_000_00, "Monthly check-in"),
            snapshot("demo-equity-old", equityId, 90, 1_45_000_00, 1_51_000_00, 0, "Quarterly check-in"),
            snapshot("demo-equity-now", equityId, 0, 1_50_000_00, 1_68_600_00, 5_000_00, "Monthly check-in")
        ).forEach { opened.investmentBalanceSnapshotDao().upsert(it) }
        opened.netWorthSnapshotDao().upsert(NetWorthSnapshotEntity("demo-net-worth", today.toEpochDay(), 12_11_656_00, 8_00_500_00))
    }

    /**
     * A Varga describes the purpose of a movement, not whether money came in or went out.
     * The persisted direction is retained only for migration compatibility with older databases.
     */
    private suspend fun seedReferenceData(opened: NirmalamDatabase) {
        val categories = listOf(
            "Food & dining" to "food", "Groceries" to "food", "Quick commerce" to "shopping",
            "Transport & fuel" to "transport", "Travel" to "transport", "Rent & housing" to "bills",
            "Utilities & mobile" to "bills", "Internet & subscriptions" to "bills", "EMI & insurance" to "bills",
            "Health & pharmacy" to "health", "Education" to "education", "Shopping" to "shopping",
            "Home & family" to "gift", "Entertainment" to "gift", "Investments & savings" to "investment",
            "Salary & wages" to "salary", "Freelance & business" to "freelance", "Interest & dividends" to "investment",
            "Gifts & transfers" to "gift", "Transfers & banking" to "other", "Refunds & cashback" to "gift", "Taxes & fees" to "bills",
            "Cash withdrawal" to "other", "Other" to "other"
        )
        categories.forEachIndexed { index, (name, icon) ->
            if (opened.categoryDao().getByName(name) == null) {
                opened.categoryDao().upsert(CategoryEntity("system-varga-$index", name, TransactionDirection.DEBIT, isSystem = true, iconKey = icon))
            }
        }

        // Suggestions only: no payee is selected until the user chooses it for a Vyavahara.
        val payees = listOf(
            "Amazon" to "Shopping", "Flipkart" to "Shopping", "Myntra" to "Shopping",
            "Swiggy" to "Food & dining", "Zomato" to "Food & dining",
            "Blinkit" to "Quick commerce", "Zepto" to "Quick commerce", "Swiggy Instamart" to "Quick commerce",
            "BigBasket" to "Groceries", "DMart" to "Groceries",
            "Uber" to "Transport & fuel", "Ola" to "Transport & fuel", "Rapido" to "Transport & fuel",
            "IndianOil" to "Transport & fuel", "HP Pay" to "Transport & fuel", "BPCL" to "Transport & fuel",
            "IndianOil Indane" to "Utilities & mobile", "Bharatgas" to "Utilities & mobile", "HP Gas" to "Utilities & mobile",
            "Mahanagar Gas" to "Utilities & mobile", "Adani Gas" to "Utilities & mobile",
            "State Bank of India" to "Transfers & banking", "HDFC Bank" to "Transfers & banking",
            "ICICI Bank" to "Transfers & banking", "Axis Bank" to "Transfers & banking",
            "Kotak Mahindra Bank" to "Transfers & banking", "Bank of Baroda" to "Transfers & banking",
            "Punjab National Bank" to "Transfers & banking", "Canara Bank" to "Transfers & banking",
            "IndusInd Bank" to "Transfers & banking", "IDFC FIRST Bank" to "Transfers & banking",
            "Jio" to "Utilities & mobile", "Airtel" to "Utilities & mobile", "Vi" to "Utilities & mobile",
            "Tata Power" to "Utilities & mobile", "Adani Electricity" to "Utilities & mobile",
            "BSES Rajdhani" to "Utilities & mobile", "BSES Yamuna" to "Utilities & mobile",
            "BESCOM" to "Utilities & mobile", "MSEDCL" to "Utilities & mobile",
            "TANGEDCO" to "Utilities & mobile", "KSEB" to "Utilities & mobile",
            "CESC" to "Utilities & mobile", "WBSEDCL" to "Utilities & mobile",
            "TSSPDCL" to "Utilities & mobile", "APSPDCL" to "Utilities & mobile",
            "IRCTC" to "Travel", "MakeMyTrip" to "Travel", "Redbus" to "Travel",
            "Apollo Pharmacy" to "Health & pharmacy", "Tata 1mg" to "Health & pharmacy",
            "Netflix" to "Internet & subscriptions", "Spotify" to "Internet & subscriptions",
            "Employer" to "Salary & wages", "Freelance client" to "Freelance & business",
            "Bank interest" to "Interest & dividends", "Dividend" to "Interest & dividends",
            "Tenant" to "Rent & housing", "UPI transfer" to "Gifts & transfers", "Refund" to "Refunds & cashback"
        )
        payees.forEachIndexed { index, (name, category) ->
            if (opened.payeeDao().getByName(name) == null) {
                opened.payeeDao().upsert(PayeeEntity("system-vyakti-$index", name, category))
            }
        }
    }

    fun createAccount(name: String, productType: AccountProductType, assetClass: AssetClass, targetPercentText: String, openingBalanceText: String, onCreated: (AccountEntity) -> Unit = {}) = viewModelScope.launch(Dispatchers.IO) {
        val balance = runCatching { BigDecimal(openingBalanceText.trim().ifBlank { "0" }).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
        val targetBps = runCatching { BigDecimal(targetPercentText.trim().ifBlank { "0" }).movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact() }.getOrNull()
        if (balance == null || balance < 0 || targetBps == null || targetBps !in 0..10_000 || name.isBlank()) { _state.update { it.copy(message = "Enter a name, valid opening balance, and target between 0% and 100%.") }; return@launch }
        val kind = when (productType) {
            AccountProductType.CASH, AccountProductType.BANK -> AccountKind.SPENDING
            AccountProductType.CREDIT_CARD, AccountProductType.LOAN -> AccountKind.CREDIT
            AccountProductType.PPF, AccountProductType.EPF, AccountProductType.NPS, AccountProductType.SUPERANNUATION, AccountProductType.MUTUAL_FUNDS, AccountProductType.EQUITY, AccountProductType.STOCKS, AccountProductType.BULLION -> AccountKind.INVESTMENT
        }
        val storedBalance = if (productType == AccountProductType.CREDIT_CARD || productType == AccountProductType.LOAN) -balance else balance
        val benchmark = suggestedBenchmark(name, productType)
        val account = AccountEntity(UUID.randomUUID().toString(), name.trim(), kind, productType, assetClass, targetBps, storedBalance, benchmarkIndexName = benchmark?.indexName, benchmarkTrackingMethod = benchmark?.method ?: BenchmarkTrackingMethod.NONE)
        val opened = database ?: return@launch
        opened.accountDao().upsert(account)
        viewModelScope.launch { onCreated(account) }
    }
    fun updateInvestmentAccount(accountId: String, name: String, productType: AccountProductType, assetClass: AssetClass, targetPercentText: String) = viewModelScope.launch(Dispatchers.IO) {
        val targetBps = runCatching { BigDecimal(targetPercentText.trim().ifBlank { "0" }).movePointRight(2).intValueExact() }.getOrNull()
        val opened = database ?: return@launch
        val account = state.value.accounts.firstOrNull { it.id == accountId } ?: return@launch
        if (name.isBlank() || targetBps == null || targetBps !in 0..10_000 || productType !in investmentProductTypes) { _state.update { it.copy(message = "Use a name, an investment product, and a target between 0% and 100%.") }; return@launch }
        val benchmark = suggestedBenchmark(name, productType)
        opened.accountDao().upsert(account.copy(name = name.trim(), kind = AccountKind.INVESTMENT, productType = productType, assetClass = assetClass, targetAllocationBps = targetBps, benchmarkIndexName = benchmark?.indexName, benchmarkTrackingMethod = benchmark?.method ?: BenchmarkTrackingMethod.NONE))
    }
    fun archiveInvestmentAccount(accountId: String) = viewModelScope.launch(Dispatchers.IO) { database?.accountDao()?.archive(accountId) }
    fun exportInterchangeReport(destination: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val opened = database ?: return@launch
        when (val result = NdfInterchangeExporter(app, opened).exportTo(destination)) {
            is NdfInterchangeResult.Exported -> _state.update { it.copy(message = "JSON interchange report exported (${result.recordCount} records).") }
            is NdfInterchangeResult.Failure -> _state.update { it.copy(message = result.message) }
        }
    }

    fun saveInvestmentBalance(accountId: String, asOfDate: String, costText: String, valueText: String, contributionText: String, note: String) = viewModelScope.launch(Dispatchers.IO) {
        val date = runCatching { LocalDate.parse(asOfDate.trim()) }.getOrNull()
        fun rupeesToPaise(text: String) = runCatching { BigDecimal(text.trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
        val cost = rupeesToPaise(costText)
        val value = rupeesToPaise(valueText)
        val contribution = rupeesToPaise(contributionText.ifBlank { "0" })
        if (date == null || cost == null || value == null || contribution == null || cost < 0 || value < 0) {
            _state.update { it.copy(message = "Use YYYY-MM-DD and valid non-negative cost and value amounts.") }; return@launch
        }
        val opened = database ?: return@launch
        val snapshot = InvestmentBalanceSnapshotEntity(UUID.randomUUID().toString(), accountId, date.toEpochDay(), cost, value, contribution, note.ifBlank { null })
        opened.withTransaction {
            opened.investmentBalanceSnapshotDao().upsert(snapshot)
            val latestByAsset = (opened.investmentBalanceSnapshotDao().getAll().filterNot { it.accountId == accountId && it.asOfEpochDay == snapshot.asOfEpochDay } + snapshot)
                .groupBy { it.accountId }.mapValues { (_, values) -> values.maxBy { it.asOfEpochDay }.currentValuePaise }
            val portfolioValue = latestByAsset.values.sum()
            val reserves = state.value.accountBalances.filter { it.kind == AccountKind.SAVINGS || it.kind == AccountKind.EMERGENCY }.sumOf { it.balancePaise }
            opened.netWorthSnapshotDao().upsert(NetWorthSnapshotEntity(UUID.randomUUID().toString(), snapshot.asOfEpochDay, state.value.cashPaise + reserves + portfolioValue, portfolioValue))
        }
    }

    fun updateInvestmentBalance(snapshotId: String, accountId: String, asOfDate: String, costText: String, valueText: String, contributionText: String, note: String) = viewModelScope.launch(Dispatchers.IO) {
        val date = runCatching { LocalDate.parse(asOfDate.trim()) }.getOrNull()
        fun rupeesToPaise(text: String) = runCatching { BigDecimal(text.trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
        val cost = rupeesToPaise(costText)
        val value = rupeesToPaise(valueText)
        val contribution = rupeesToPaise(contributionText.ifBlank { "0" })
        if (date == null || cost == null || value == null || contribution == null || cost < 0 || value < 0) {
            _state.update { it.copy(message = "Choose a valid date and enter non-negative cost and value amounts.") }; return@launch
        }
        val opened = database ?: return@launch
        val collision = opened.investmentBalanceSnapshotDao().getForAccountAndDay(accountId, date.toEpochDay())
        if (collision != null && collision.id != snapshotId) {
            _state.update { it.copy(message = "A balance check-in already exists for this Nivesha on that date.") }; return@launch
        }
        val snapshot = InvestmentBalanceSnapshotEntity(snapshotId, accountId, date.toEpochDay(), cost, value, contribution, note.trim().ifBlank { null })
        opened.withTransaction {
            opened.investmentBalanceSnapshotDao().upsert(snapshot)
            val latestByAsset = opened.investmentBalanceSnapshotDao().getAll().groupBy { it.accountId }.mapValues { (_, values) -> values.maxBy { it.asOfEpochDay }.currentValuePaise }
            val portfolioValue = latestByAsset.values.sum()
            val reserves = state.value.accountBalances.filter { it.kind == AccountKind.SAVINGS || it.kind == AccountKind.EMERGENCY }.sumOf { it.balancePaise }
            opened.netWorthSnapshotDao().upsert(NetWorthSnapshotEntity(UUID.randomUUID().toString(), snapshot.asOfEpochDay, state.value.cashPaise + reserves + portfolioValue, portfolioValue))
        }
    }

    fun contributeToInvestment(accountId: String, amountText: String, payee: String) = viewModelScope.launch(Dispatchers.IO) {
        val amount = runCatching { BigDecimal(amountText.trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
        val cashAccount = state.value.accounts.firstOrNull { it.kind == AccountKind.SPENDING }
        if (amount == null || amount <= 0 || cashAccount == null) { _state.update { it.copy(message = "Create a cash/bank account and enter a valid contribution.") }; return@launch }
        val opened = database ?: return@launch
        val today = LocalDate.now().toEpochDay()
        opened.withTransaction {
            val label = payee.ifBlank { "Investment contribution" }
            opened.transactionDao().upsert(TransactionEntity(UUID.randomUUID().toString(), cashAccount.id, amount, TransactionDirection.DEBIT, merchant = label, category = "Investment contribution", payee = label, envelopeType = EnvelopeType.INVESTMENT))
            val previous = opened.investmentBalanceSnapshotDao().getLatest(accountId)
            val sameDay = previous?.asOfEpochDay == today
            val priorContribution = if (sameDay) requireNotNull(previous).netContributionPaise else 0
            val snapshot = InvestmentBalanceSnapshotEntity(UUID.randomUUID().toString(), accountId, today, (previous?.totalCostPaise ?: 0) + amount, (previous?.currentValuePaise ?: 0) + amount, priorContribution + amount, "Contribution")
            opened.investmentBalanceSnapshotDao().upsert(snapshot)
            val portfolioValue = opened.investmentBalanceSnapshotDao().getAll().groupBy { it.accountId }.values.sumOf { items -> items.maxBy { it.asOfEpochDay }.currentValuePaise }
            val reserves = state.value.accountBalances.filter { it.kind == AccountKind.SAVINGS || it.kind == AccountKind.EMERGENCY }.sumOf { it.balancePaise }
            opened.netWorthSnapshotDao().upsert(NetWorthSnapshotEntity(UUID.randomUUID().toString(), today, (state.value.cashPaise - amount) + reserves + portfolioValue, portfolioValue))
        }
    }

    fun recordTransaction(amountText: String, payee: String, category: String, description: String, direction: TransactionDirection, accountId: String, occurredAtEpochMs: Long) {
        val paise = runCatching { BigDecimal(amountText.trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
        val account = state.value.accounts.firstOrNull { it.id == accountId && (it.kind == AccountKind.SPENDING || it.kind == AccountKind.CREDIT) }
        if (paise == null || paise <= 0 || account == null) { _state.update { it.copy(message = "Create a cash account and enter a valid amount.") }; return }
        viewModelScope.launch(Dispatchers.IO) {
            val opened = database ?: return@launch
            val config = opened.configDao().observe().first() ?: return@launch
            val resolvedPayee = payee.ifBlank { if (direction == TransactionDirection.CREDIT) "Unlabelled income" else "Unlabelled expense" }
            val resolvedCategory = category.trim().ifBlank { if (direction == TransactionDirection.CREDIT) "Income" else "Uncategorised" }
            val envelope = when {
                direction == TransactionDirection.CREDIT -> null
                resolvedCategory == "Shopping" -> EnvelopeType.WANTS
                else -> EnvelopeType.NEEDS
            }
            val transaction = coolDown(
                TransactionEntity(
                    id = UUID.randomUUID().toString(), accountId = account.id, amountPaise = paise,
                    direction = direction, merchant = resolvedPayee, category = resolvedCategory,
                    payee = resolvedPayee, description = description.ifBlank { null }, envelopeType = envelope,
                    occurredAtEpochMs = occurredAtEpochMs
                ),
                config.impulseCoolDownThresholdPaise
            )
            opened.withTransaction {
                opened.transactionDao().upsert(transaction)
                if (opened.categoryDao().getByName(resolvedCategory) == null) opened.categoryDao().upsert(CategoryEntity("user-${direction.name.lowercase()}-${resolvedCategory.lowercase().replace(Regex("[^a-z0-9]+"), "-")}", resolvedCategory, direction))
                if (opened.payeeDao().getByName(resolvedPayee) == null) opened.payeeDao().upsert(PayeeEntity("payee-${resolvedPayee.lowercase().replace(Regex("[^a-z0-9]+"), "-")}", resolvedPayee, resolvedCategory))
            }
            if (transaction.isHoldingTank) _state.update { it.copy(message = "This purchase is in the 48-hour cooling tank.") }
        }
    }

    fun setNeurodiverseMode(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) { database?.configDao()?.setNeurodiverseMode(enabled) }
    fun setCurrency(currencyCode: String) = viewModelScope.launch(Dispatchers.IO) {
        if (currencyCode == "INR") database?.configDao()?.setCurrencyCode(currencyCode)
    }
    fun setDateFormatPreference(preference: DateFormatPreference) = viewModelScope.launch(Dispatchers.IO) {
        database?.configDao()?.setDateFormatPreference(preference)
    }
    fun setSavedLedgerView(range: LedgerRange, filter: LedgerFilter, accountId: String?, categoryName: String?) = viewModelScope.launch(Dispatchers.IO) {
        database?.configDao()?.setSavedLedgerView(range.name, filter.name, accountId, categoryName)
    }
    fun saveNirmalamAi(endpoint: String, model: String, apiKey: String, enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { NirmalamAiPreferences(app).save(endpoint, model, apiKey, enabled) }
            .onSuccess { _state.update { it.copy(nirmalamAiReady = enabled, message = "Nirmalam AI is ready for preset insights.") } }
            .onFailure { error -> _state.update { it.copy(message = error.message ?: "Could not save Nirmalam AI setup.") } }
    }
    fun disableNirmalamAi() = viewModelScope.launch(Dispatchers.IO) {
        NirmalamAiPreferences(app).disable()
        _state.update { it.copy(nirmalamAiReady = false, nirmalamAiResponse = null, message = "Nirmalam AI is disabled. No data will be sent.") }
    }
    fun requestNirmalamAiInsight(insight: NirmalamAiInsight) = viewModelScope.launch(Dispatchers.IO) {
        val preferences = NirmalamAiPreferences(app)
        val settings = preferences.settings()
        val apiKey = preferences.apiKey()
        if (!preferences.isReady() || apiKey == null) { _state.update { it.copy(message = "Set up and enable Nirmalam AI first.") }; return@launch }
        val snapshot = state.value
        _state.update { it.copy(nirmalamAiLoading = true, nirmalamAiResponse = null) }
        NirmalamAiClient.request(settings, apiKey, insight, buildNirmalamAiSummary(snapshot.cashPaise, snapshot.recentTransactions, snapshot.investmentHistory))
            .onSuccess { response -> _state.update { it.copy(nirmalamAiLoading = false, nirmalamAiResponse = response) } }
            .onFailure { error -> _state.update { it.copy(nirmalamAiLoading = false, message = error.message ?: "Nirmalam AI could not generate an insight.") } }
    }
    fun exportNdfBackup(destination: Uri, passphrase: String) = viewModelScope.launch(Dispatchers.IO) {
        val opened = database ?: return@launch
        when (val result = NdfBackupManager(app, opened) {}.exportTo(destination, passphrase.toCharArray())) {
            is NdfResult.Exported -> _state.update { it.copy(message = "Encrypted .ndf backup exported.") }
            is NdfResult.Failure -> _state.update { it.copy(message = result.message) }
            else -> Unit
        }
    }
    fun importNdfBackup(source: Uri, passphrase: String) = viewModelScope.launch(Dispatchers.IO) {
        val opened = database ?: return@launch
        when (val result = NdfBackupManager(app, opened) {
            observation?.cancel()
            database?.close()
            database = null
        }.importFrom(source, passphrase.toCharArray())) {
            is NdfResult.Imported -> _state.value = MvpFinanceState(message = "Backup imported. Unlock using that backup's passphrase.")
            is NdfResult.Failure -> _state.update { it.copy(message = result.message) }
            else -> Unit
        }
    }
    fun removeStarterData() = viewModelScope.launch(Dispatchers.IO) {
        val opened = database ?: return@launch
        opened.withTransaction {
            opened.transactionDao().deleteDemoTransactions()
            opened.investmentBalanceSnapshotDao().deleteDemoSnapshots()
            opened.netWorthSnapshotDao().deleteDemoSnapshots()
            opened.accountDao().deleteDemoAccounts()
            opened.payeeDao().deleteStarterPayees()
            opened.categoryDao().deleteStarterCategories()
            opened.configDao().setStarterDataRemoved(true)
        }
        _state.update { it.copy(message = "Starter suggestions and demo records were removed. Your own records remain.") }
    }
    fun saveCategory(name: String, direction: TransactionDirection, iconKey: String) = viewModelScope.launch(Dispatchers.IO) {
        val clean = name.trim()
        if (clean.isBlank()) { _state.update { it.copy(message = "Enter a category name.") }; return@launch }
        val dao = database?.categoryDao() ?: return@launch
        val current = dao.getByName(clean)
        dao.upsert(current?.copy(transactionDirection = direction, iconKey = iconKey) ?: CategoryEntity("user-${UUID.randomUUID()}", clean, direction, iconKey = iconKey))
    }
    fun updateCategory(id: String, name: String, direction: TransactionDirection, iconKey: String) = viewModelScope.launch(Dispatchers.IO) {
        val clean = name.trim()
        val opened = database ?: return@launch
        val existing = opened.categoryDao().getById(id) ?: return@launch
        val duplicate = opened.categoryDao().getByName(clean)
        if (clean.isBlank() || (duplicate != null && duplicate.id != id)) { _state.update { it.copy(message = "Choose a unique Varga name.") }; return@launch }
        opened.withTransaction {
            opened.categoryDao().upsert(existing.copy(name = clean, transactionDirection = direction, iconKey = iconKey))
            if (existing.name != clean) {
                opened.transactionDao().renameCategoryReferences(existing.name, clean)
                opened.payeeDao().renameDefaultCategory(existing.name, clean)
            }
        }
    }
    fun deleteCategory(id: String) = viewModelScope.launch(Dispatchers.IO) { database?.categoryDao()?.deleteUserCreated(id) }
    fun savePayee(name: String, defaultCategory: String?) = viewModelScope.launch(Dispatchers.IO) {
        val clean = name.trim()
        if (clean.isBlank()) { _state.update { it.copy(message = "Enter a payee name.") }; return@launch }
        val dao = database?.payeeDao() ?: return@launch
        val current = dao.getByName(clean)
        dao.upsert(current?.copy(defaultCategory = defaultCategory, lastUsedEpochMs = System.currentTimeMillis()) ?: PayeeEntity("payee-${UUID.randomUUID()}", clean, defaultCategory))
    }
    fun updatePayee(id: String, name: String, defaultCategory: String?) = viewModelScope.launch(Dispatchers.IO) {
        val clean = name.trim()
        val opened = database ?: return@launch
        val existing = opened.payeeDao().observeAll().first().firstOrNull { it.id == id } ?: return@launch
        val duplicate = opened.payeeDao().getByName(clean)
        if (clean.isBlank() || (duplicate != null && duplicate.id != id)) { _state.update { it.copy(message = "Choose a unique Vyakti name.") }; return@launch }
        opened.withTransaction {
            opened.payeeDao().upsert(existing.copy(name = clean, defaultCategory = defaultCategory?.trim()?.ifBlank { null }, lastUsedEpochMs = System.currentTimeMillis()))
            if (existing.name != clean) opened.transactionDao().renamePayeeReferences(existing.name, clean)
        }
    }
    fun deletePayee(id: String) = viewModelScope.launch(Dispatchers.IO) { database?.payeeDao()?.delete(id) }
    fun confirmPurchase(transaction: TransactionEntity) = viewModelScope.launch(Dispatchers.IO) { database?.transactionDao()?.confirmHoldingTank(transaction.id) }
    fun discardPurchase(transaction: TransactionEntity) = viewModelScope.launch(Dispatchers.IO) { database?.transactionDao()?.discardHoldingTank(transaction.id) }
    fun deleteInvestmentSnapshot(snapshotId: String) = viewModelScope.launch(Dispatchers.IO) { database?.investmentBalanceSnapshotDao()?.delete(snapshotId) }
    fun deleteTransaction(transactionId: String) = viewModelScope.launch(Dispatchers.IO) { database?.transactionDao()?.delete(transactionId) }
    fun updateTransaction(transactionId: String, amountText: String, payee: String, category: String, description: String, direction: TransactionDirection) = viewModelScope.launch(Dispatchers.IO) {
        val amount = runCatching { BigDecimal(amountText.trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
        if (amount == null || amount <= 0) { _state.update { it.copy(message = "Enter a valid amount.") }; return@launch }
        val opened = database ?: return@launch
        val dao = opened.transactionDao()
        val existing = dao.getById(transactionId) ?: return@launch
        val resolvedPayee = payee.trim().ifBlank { if (direction == TransactionDirection.CREDIT) "Unlabelled Aaya" else "Unlabelled Vyaya" }
        val resolvedCategory = category.trim().ifBlank { if (direction == TransactionDirection.CREDIT) "Income" else "Uncategorised" }
        opened.withTransaction {
            dao.upsert(existing.copy(amountPaise = amount, direction = direction, merchant = resolvedPayee, payee = resolvedPayee, category = resolvedCategory, description = description.trim().ifBlank { null }, envelopeType = if (direction == TransactionDirection.DEBIT && resolvedCategory == "Shopping") EnvelopeType.WANTS else existing.envelopeType))
            if (opened.categoryDao().getByName(resolvedCategory) == null) opened.categoryDao().upsert(CategoryEntity("user-${UUID.randomUUID()}", resolvedCategory, direction))
            if (opened.payeeDao().getByName(resolvedPayee) == null) opened.payeeDao().upsert(PayeeEntity("payee-${UUID.randomUUID()}", resolvedPayee, resolvedCategory))
        }
    }
    fun clearMessage() { _state.update { it.copy(message = null) } }
    override fun onCleared() { observation?.cancel(); database?.close() }
}

@Composable
private fun NirmalamMvpApp(viewModel: NirmalamMvpViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            if (androidx.compose.foundation.isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme) {
        Surface(Modifier.fillMaxSize()) {
            if (state.isUnlocked) MvpHome(state, { name, product, assetClass, target, openingBalance, onCreated -> viewModel.createAccount(name, product, assetClass, target, openingBalance, onCreated) }, viewModel::updateInvestmentAccount, viewModel::archiveInvestmentAccount, viewModel::saveInvestmentBalance, viewModel::updateInvestmentBalance, viewModel::contributeToInvestment, viewModel::deleteInvestmentSnapshot, viewModel::deleteTransaction, viewModel::updateTransaction, viewModel::recordTransaction, viewModel::setNeurodiverseMode, viewModel::setCurrency, viewModel::setDateFormatPreference, viewModel::setSavedLedgerView, viewModel::saveNirmalamAi, viewModel::disableNirmalamAi, viewModel::requestNirmalamAiInsight, viewModel::exportInterchangeReport, viewModel::exportNdfBackup, viewModel::importNdfBackup, viewModel::saveCategory, viewModel::updateCategory, viewModel::deleteCategory, viewModel::savePayee, viewModel::updatePayee, viewModel::deletePayee, viewModel::removeStarterData, viewModel::confirmPurchase, viewModel::discardPurchase, viewModel::clearMessage)
            else UnlockScreen(state.isLoading, state.message, viewModel::unlock, viewModel::clearMessage)
        }
    }
}

private fun formatMoney(paise: Long, currencyCode: String = "INR", includeSign: Boolean = false): String =
    MoneyFormatter.format(paise, currencyCode, includeSign)

private fun dateFormatter(preference: DateFormatPreference): DateTimeFormatter = when (preference) {
    DateFormatPreference.DEVICE_LOCALE -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
    DateFormatPreference.DD_MMM_YYYY -> DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault())
    DateFormatPreference.DD_MM_YYYY -> DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.getDefault())
    DateFormatPreference.MM_DD_YYYY -> DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.getDefault())
    DateFormatPreference.YYYY_MM_DD -> DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
}

private fun formatDate(date: LocalDate, preference: DateFormatPreference): String = date.format(dateFormatter(preference))

private fun formatDate(epochMs: Long, preference: DateFormatPreference): String =
    Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .let { formatDate(it, preference) }

private fun dateFormatPreferenceLabel(preference: DateFormatPreference): String = when (preference) {
    DateFormatPreference.DEVICE_LOCALE -> "Device locale"
    DateFormatPreference.DD_MMM_YYYY -> "26 Aug 2026"
    DateFormatPreference.DD_MM_YYYY -> "26/08/2026"
    DateFormatPreference.MM_DD_YYYY -> "08/26/2026"
    DateFormatPreference.YYYY_MM_DD -> "2026-08-26"
}

private data class InvestmentPerformance(val absolutePaise: Long, val absolutePercent: Double?, val xirrPercent: Double?)

private fun cashFlowsForXirr(history: List<InvestmentBalanceSnapshotEntity>): List<Pair<Long, Long>> {
    val ordered = history.sortedBy { it.asOfEpochDay }
    val first = ordered.firstOrNull() ?: return emptyList()
    if (first.totalCostPaise <= 0L) return emptyList()
    return buildList {
        add(first.asOfEpochDay to -first.totalCostPaise)
        ordered.drop(1).filter { it.netContributionPaise != 0L }.forEach { snapshot ->
            add(snapshot.asOfEpochDay to -snapshot.netContributionPaise)
        }
        add(ordered.last().asOfEpochDay to ordered.last().currentValuePaise)
    }
}

private fun investmentPerformance(history: List<InvestmentBalanceSnapshotEntity>): InvestmentPerformance? {
    val latest = history.maxByOrNull { it.asOfEpochDay } ?: return null
    val absolutePaise = latest.currentValuePaise - latest.totalCostPaise
    val absolutePercent = latest.totalCostPaise.takeIf { it != 0L }?.let { absolutePaise * 100.0 / it }
    return InvestmentPerformance(absolutePaise, absolutePercent, FinancialCalculations.xirrPercent(cashFlowsForXirr(history)))
}

private fun formatPercent(value: Double?): String = value?.let { String.format(Locale.getDefault(), "%.1f%%", it) } ?: "—"

@Composable
private fun UnlockScreen(loading: Boolean, message: String?, onUnlock: (String) -> Unit, onDismiss: () -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Nirmalam Dhanam", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text("Your finances stay encrypted on this device.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(passphrase, { passphrase = it }, Modifier.fillMaxWidth(), label = { Text("Create or enter your passphrase") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onUnlock(passphrase); passphrase = "" }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text(if (loading) "Unlocking…" else "Unlock") }
        message?.let { Spacer(Modifier.height(12.dp)); AssistChip(onClick = onDismiss, label = { Text(it) }) }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MvpHome(state: MvpFinanceState, onCreateAccount: (String, AccountProductType, AssetClass, String, String, (AccountEntity) -> Unit) -> Unit, onUpdateInvestmentAccount: (String, String, AccountProductType, AssetClass, String) -> Unit, onArchiveInvestmentAccount: (String) -> Unit, onSaveInvestmentBalance: (String, String, String, String, String, String) -> Unit, onUpdateInvestmentBalance: (String, String, String, String, String, String, String) -> Unit, onContributeToInvestment: (String, String, String) -> Unit, onDeleteInvestmentSnapshot: (String) -> Unit, onDeleteTransaction: (String) -> Unit, onUpdateTransaction: (String, String, String, String, String, TransactionDirection) -> Unit, onRecordTransaction: (String, String, String, String, TransactionDirection, String, Long) -> Unit, onNeurodiverseModeChanged: (Boolean) -> Unit, onCurrencyChanged: (String) -> Unit, onDateFormatPreferenceChanged: (DateFormatPreference) -> Unit, onSavedLedgerViewChanged: (LedgerRange, LedgerFilter, String?, String?) -> Unit, onSaveNirmalamAi: (String, String, String, Boolean) -> Unit, onDisableNirmalamAi: () -> Unit, onNirmalamAiInsight: (NirmalamAiInsight) -> Unit, onExportInterchange: (Uri) -> Unit, onExportNdf: (Uri, String) -> Unit, onImportNdf: (Uri, String) -> Unit, onSaveCategory: (String, TransactionDirection, String) -> Unit, onUpdateCategory: (String, String, TransactionDirection, String) -> Unit, onDeleteCategory: (String) -> Unit, onSavePayee: (String, String?) -> Unit, onUpdatePayee: (String, String, String?) -> Unit, onDeletePayee: (String) -> Unit, onRemoveStarterData: () -> Unit, onConfirmPurchase: (TransactionEntity) -> Unit, onDiscardPurchase: (TransactionEntity) -> Unit, onDismissMessage: () -> Unit) {
    var showAccountSetup by remember { mutableStateOf(false) }
    var accountSetupProduct by remember { mutableStateOf(AccountProductType.CASH) }
    var addKhataMenuExpanded by remember { mutableStateOf(false) }
    var showInvestmentCheckIn by remember { mutableStateOf(false) }
    var createdInvestment by remember { mutableStateOf<AccountEntity?>(null) }
    var initialInvestmentCheckInId by remember { mutableStateOf<String?>(null) }
    var showPortfolio by remember { mutableStateOf(false) }
    var showNetWorth by remember { mutableStateOf(false) }
    var showContribution by remember { mutableStateOf(false) }
    var showTransactions by remember { mutableStateOf(false) }
    var showReports by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    if (showReports) { IncomeExpenseReportsScreen(state, onBack = { showReports = false }); return }
    if (showTransactions) { TransactionHistoryScreen(state, onBack = { showTransactions = false }, onReports = { showTransactions = false; showReports = true }, onDelete = onDeleteTransaction, onUpdate = onUpdateTransaction, onRecord = onRecordTransaction, onSavedLedgerViewChanged = onSavedLedgerViewChanged); return }
    if (showSettings) { SettingsScreen(state, onBack = { showSettings = false }, onNeurodiverseModeChanged = onNeurodiverseModeChanged, onCurrencyChanged = onCurrencyChanged, onDateFormatPreferenceChanged = onDateFormatPreferenceChanged, onSaveNirmalamAi = onSaveNirmalamAi, onDisableNirmalamAi = onDisableNirmalamAi, onNirmalamAiInsight = onNirmalamAiInsight, onExportInterchange = onExportInterchange, onExportNdf = onExportNdf, onImportNdf = onImportNdf, onSaveCategory = onSaveCategory, onUpdateCategory = onUpdateCategory, onDeleteCategory = onDeleteCategory, onSavePayee = onSavePayee, onUpdatePayee = onUpdatePayee, onDeletePayee = onDeletePayee, onRemoveStarterData = onRemoveStarterData); return }
    if (showNetWorth) { NetWorthDashboardScreen(state, onBack = { showNetWorth = false }, onOpenPortfolio = { showNetWorth = false; showPortfolio = true }); return }
    if (showPortfolio) {
        PortfolioAndNetWorthScreen(state, onBack = { showPortfolio = false }, onOpenNetWorth = { showPortfolio = false; showNetWorth = true }, onAddInvestment = { accountSetupProduct = AccountProductType.MUTUAL_FUNDS; showAccountSetup = true; showPortfolio = false }, onRecordBalance = { showInvestmentCheckIn = true }, onDeleteSnapshot = onDeleteInvestmentSnapshot, onUpdateSnapshot = onUpdateInvestmentBalance, onUpdateInvestment = onUpdateInvestmentAccount, onArchiveInvestment = onArchiveInvestmentAccount)
        if (showInvestmentCheckIn) InvestmentBalanceCheckInDialog(state.accounts.filter { it.kind == AccountKind.INVESTMENT }, state.dateFormatPreference, onDismiss = { showInvestmentCheckIn = false }, onSave = { accountId, date, cost, value, contribution, note -> onSaveInvestmentBalance(accountId, date, cost, value, contribution, note); showInvestmentCheckIn = false })
        if (showContribution) InvestmentContributionDialog(state.accounts.filter { it.kind == AccountKind.INVESTMENT }, onDismiss = { showContribution = false }, onSave = { id, amount, payee -> onContributeToInvestment(id, amount, payee); showContribution = false })
        return
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Nirmalam Dhanam", style = MaterialTheme.typography.titleLarge)
                        Text("Your private money space", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = true, onClick = {}, icon = { NavigationGlyph("⌂") }, label = { Text("Prarambha") })
                NavigationBarItem(selected = false, onClick = { showTransactions = true }, icon = { NavigationGlyph("≡") }, label = { Text("Vyavahara") })
                NavigationBarItem(selected = false, onClick = { showPortfolio = true }, icon = { NavigationGlyph("↗") }, label = { Text("Nivesha") })
                NavigationBarItem(selected = false, onClick = { showSettings = true }, icon = { NavigationGlyph("•••") }, label = { Text("Vinyasa") })
            }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("AVAILABLE TO USE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(formatMoney(state.cashPaise, state.currencyCode), style = MaterialTheme.typography.displaySmall, color = if (state.cashPaise < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer)
                        HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.16f))
                        Text(if (state.cashPaise < 0) "You are using credit. A small reset today creates room tomorrow." else "After credit liabilities — the amount available for everyday decisions.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ElevatedCard(Modifier.weight(1f), shape = MaterialTheme.shapes.large) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("SAFE TODAY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(formatMoney(state.safeToSpendTodayPaise, state.currencyCode), style = MaterialTheme.typography.headlineSmall); Text("${formatMoney(state.todaySpentPaise, state.currencyCode)} spent", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    ElevatedCard(Modifier.weight(1f), shape = MaterialTheme.shapes.large) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("COOLING TANK", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${state.holdingTank.size}", style = MaterialTheme.typography.headlineSmall); Text(if (state.holdingTank.isEmpty()) "No decisions waiting" else "Purchase decision waiting", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                }
            }
            item { HomeMoneyPulse(state.recentTransactions, state.currencyCode) }
            item { PrarambhaBalanceCharts(state) }
            item {
                ElevatedCard(onClick = { showPortfolio = true }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val investmentAccounts = state.accounts.filter { it.kind == AccountKind.INVESTMENT }
                        val snapshotsByAccount = state.investmentSnapshots.associateBy { it.accountId }
                        val portfolioValue = state.investmentSnapshots.sumOf { it.currentValuePaise }
                        val portfolioCost = state.investmentSnapshots.sumOf { it.totalCostPaise }
                        val gain = portfolioValue - portfolioCost
                        val returnPercent = if (portfolioCost == 0L) 0.0 else gain * 100.0 / portfolioCost
                        val liquidAndReserve = state.accountBalances.filter { it.kind == AccountKind.SAVINGS || it.kind == AccountKind.EMERGENCY }.sumOf { it.balancePaise }
                        val netWorth = state.cashPaise + liquidAndReserve + portfolioValue
                        val latestCheckIn = state.investmentSnapshots.maxOfOrNull { it.asOfEpochDay }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("NIVESHA · PORTFOLIO", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Box {
                                IconButton(onClick = { addKhataMenuExpanded = true }) { Text("+", style = MaterialTheme.typography.headlineSmall) }
                                DropdownMenu(expanded = addKhataMenuExpanded, onDismissRequest = { addKhataMenuExpanded = false }) {
                                    DropdownMenuItem(text = { Text("Cash or bank Khata") }, onClick = { accountSetupProduct = AccountProductType.BANK; addKhataMenuExpanded = false; showAccountSetup = true })
                                    DropdownMenuItem(text = { Text("Credit card or loan") }, onClick = { accountSetupProduct = AccountProductType.CREDIT_CARD; addKhataMenuExpanded = false; showAccountSetup = true })
                                    HorizontalDivider()
                                    DropdownMenuItem(text = { Text("Mutual fund or ETF") }, onClick = { accountSetupProduct = AccountProductType.MUTUAL_FUNDS; addKhataMenuExpanded = false; showAccountSetup = true })
                                    DropdownMenuItem(text = { Text("Direct stocks") }, onClick = { accountSetupProduct = AccountProductType.STOCKS; addKhataMenuExpanded = false; showAccountSetup = true })
                                    DropdownMenuItem(text = { Text("Bullion — gold or silver") }, onClick = { accountSetupProduct = AccountProductType.BULLION; addKhataMenuExpanded = false; showAccountSetup = true })
                                    DropdownMenuItem(text = { Text("Retirement investment") }, onClick = { accountSetupProduct = AccountProductType.PPF; addKhataMenuExpanded = false; showAccountSetup = true })
                                }
                            }
                        }
                        Text(formatMoney(portfolioValue, state.currencyCode), style = MaterialTheme.typography.headlineLarge)
                        Text(latestCheckIn?.let { "Valued ${LocalDate.ofEpochDay(it)}" } ?: "Add a first balance check-in", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PortfolioValueChart(state.investmentHistory, state.currencyCode, compact = true)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { Text("INVESTED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(formatMoney(portfolioCost, state.currencyCode), style = MaterialTheme.typography.titleSmall) }
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) { Text("RETURN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${formatMoney(gain, state.currencyCode, includeSign = true)} · ${"%.1f".format(returnPercent)}%", style = MaterialTheme.typography.titleSmall, color = if (gain < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column { Text("SAMPADA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(formatMoney(netWorth, state.currencyCode), style = MaterialTheme.typography.titleMedium) }
                            Text("Cash, reserves & Nivesha", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (investmentAccounts.isEmpty()) {
                            ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("No Nivesha yet", style = MaterialTheme.typography.titleSmall)
                                    Text("Add an investment holding for periodic cost-and-value check-ins. Daily cash and liabilities remain separate.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TextButton(onClick = { accountSetupProduct = AccountProductType.MUTUAL_FUNDS; showAccountSetup = true }) { Text("Add investment holding") }
                                }
                            }
                        } else investmentAccounts.take(4).forEach { account ->
                            val snapshot = snapshotsByAccount[account.id]
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) { Text(account.name, style = MaterialTheme.typography.bodyMedium); Text(account.productType.name.replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Text(snapshot?.let { formatMoney(it.currentValuePaise, state.currencyCode) } ?: "Check-in due", style = MaterialTheme.typography.bodyMedium, color = if (snapshot == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        val overdueCheckIns = investmentAccounts.count { account -> snapshotsByAccount[account.id]?.let { LocalDate.now().toEpochDay() - it.asOfEpochDay >= 30 } ?: true }
                        if (overdueCheckIns > 0) AssistChip(onClick = { showInvestmentCheckIn = true }, label = { Text("$overdueCheckIns monthly check-in${if (overdueCheckIns == 1) "" else "s"} due") })
                        if (investmentAccounts.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { showInvestmentCheckIn = true }, modifier = Modifier.weight(1f)) { Text("Check in balance") }
                            OutlinedButton(onClick = { showContribution = true }, modifier = Modifier.weight(1f)) { Text("Add contribution") }
                        }
                        Text("Tap this card for Nivesha and Sampada.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.accounts.isEmpty()) item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No Khatas yet", style = MaterialTheme.typography.titleMedium)
                        Text("Start with a daily cash or bank Khata. Add liabilities and investment holdings separately when you need them.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { accountSetupProduct = AccountProductType.CASH; showAccountSetup = true }, modifier = Modifier.fillMaxWidth()) { Text("Set up daily Khata") }
                    }
                }
            }
            if (!state.neurodiverseModeEnabled) item { Text("Intentional purchases", style = MaterialTheme.typography.titleMedium) }
            items(state.holdingTank.size) { index ->
                CoolDownTankCard(state.holdingTank[index], onConfirm = onConfirmPurchase, onDismiss = onDiscardPurchase)
            }
            item { NeurodiverseModeToggle(state.neurodiverseModeEnabled, onNeurodiverseModeChanged) }
            if (state.neurodiverseModeEnabled) item { Text("Focus mode prioritizes your next essential action. Calculations and data remain unchanged.", style = MaterialTheme.typography.bodyMedium) }
            state.message?.let { message -> item { AssistChip(onClick = onDismissMessage, label = { Text(message) }) } }
        }
    }
    if (showAccountSetup) AccountSetupDialog(initialProduct = accountSetupProduct, onDismiss = { showAccountSetup = false }, onSave = { name, type, assetClass, target, openingBalance -> onCreateAccount(name, type, assetClass, target, openingBalance) { account -> showAccountSetup = false; if (account.kind == AccountKind.INVESTMENT) createdInvestment = account } })
    createdInvestment?.let { account ->
        AlertDialog(
            onDismissRequest = { createdInvestment = null },
            title = { Text("Nivesha created") },
            text = { Text("${account.name} is ready. Record its first dated cost and current value now to begin portfolio tracking.") },
            confirmButton = { Button(onClick = { initialInvestmentCheckInId = account.id; createdInvestment = null; showInvestmentCheckIn = true }) { Text("Record first balance") } },
            dismissButton = { TextButton(onClick = { createdInvestment = null }) { Text("Done") } }
        )
    }
    if (showInvestmentCheckIn) InvestmentBalanceCheckInDialog(state.accounts.filter { it.kind == AccountKind.INVESTMENT }, state.dateFormatPreference, initialAccountId = initialInvestmentCheckInId, onDismiss = { showInvestmentCheckIn = false; initialInvestmentCheckInId = null }, onSave = { accountId, date, cost, value, contribution, note -> onSaveInvestmentBalance(accountId, date, cost, value, contribution, note); showInvestmentCheckIn = false; initialInvestmentCheckInId = null })
    if (showContribution) InvestmentContributionDialog(state.accounts.filter { it.kind == AccountKind.INVESTMENT }, onDismiss = { showContribution = false }, onSave = { id, amount, payee -> onContributeToInvestment(id, amount, payee); showContribution = false })
}

private data class DailyMoneyPulse(val date: LocalDate, val incomePaise: Long, val spentPaise: Long)
private data class PortfolioChartPoint(val date: LocalDate, val costPaise: Long, val valuePaise: Long)

private fun portfolioChartPoints(history: List<InvestmentBalanceSnapshotEntity>): List<PortfolioChartPoint> {
    val byAccount = history.groupBy { it.accountId }.mapValues { (_, items) -> items.sortedBy { it.asOfEpochDay } }
    return history.map { it.asOfEpochDay }.distinct().sorted().takeLast(12).map { day ->
        val snapshots = byAccount.values.mapNotNull { items -> items.lastOrNull { it.asOfEpochDay <= day } }
        PortfolioChartPoint(LocalDate.ofEpochDay(day), snapshots.sumOf { it.totalCostPaise }, snapshots.sumOf { it.currentValuePaise })
    }
}

@Composable
private fun PrarambhaBalanceCharts(state: MvpFinanceState) {
    val liquid = state.accountBalances.filter { it.kind == AccountKind.SPENDING }.sumOf { it.balancePaise }
    val reserves = state.accountBalances.filter { it.kind == AccountKind.SAVINGS || it.kind == AccountKind.EMERGENCY }.sumOf { it.balancePaise }
    val investments = state.investmentSnapshots.sumOf { it.currentValuePaise }
    val liabilities = state.accountBalances.filter { it.kind == AccountKind.CREDIT }.sumOf { (-it.balancePaise).coerceAtLeast(0) }
    val netWorthTrend = state.netWorthHistory.sortedBy { it.asOfEpochDay }.takeLast(12).map { it.netWorthPaise }
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("BALANCE PICTURE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            BalanceCompositionChart(liquid, reserves, investments, liabilities, state.currencyCode)
            if (netWorthTrend.size >= 2) {
                Text("Sampada trend", style = MaterialTheme.typography.labelMedium)
                NetWorthSparkline(netWorthTrend)
            } else Text("Your Sampada trend appears after two dated Nivesha check-ins.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BalanceCompositionChart(cash: Long, reserves: Long, investments: Long, liabilities: Long, currencyCode: String) {
    val parts = listOf("Cash" to cash.coerceAtLeast(0L), "Reserves" to reserves.coerceAtLeast(0L), "Nivesha" to investments.coerceAtLeast(0L))
    val assetTotal = parts.sumOf { it.second }
    val totalForChart = assetTotal.coerceAtLeast(1L)
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary)
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        var x = 0f
        parts.forEachIndexed { index, (_, amount) ->
            val width = size.width * amount / totalForChart
            if (width > 0f) drawRect(colors[index], topLeft = androidx.compose.ui.geometry.Offset(x, 0f), size = androidx.compose.ui.geometry.Size(width, size.height))
            x += width
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Assets ${formatMoney(assetTotal, currencyCode)}", style = MaterialTheme.typography.labelSmall)
        Text("Liabilities ${formatMoney(liabilities, currencyCode)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        parts.forEachIndexed { index, (name, amount) -> Text("$name ${formatMoney(amount, currencyCode)}", style = MaterialTheme.typography.labelSmall, color = colors[index]) }
    }
}

@Composable
private fun HomeMoneyPulse(transactions: List<TransactionEntity>, currencyCode: String) {
    val today = LocalDate.now()
    val days = (6L downTo 0L).map { day ->
        val date = today.minusDays(day)
        val entries = transactions.filter { transaction ->
            !transaction.isHoldingTank && Instant.ofEpochMilli(transaction.occurredAtEpochMs).atZone(ZoneId.systemDefault()).toLocalDate() == date
        }
        DailyMoneyPulse(
            date = date,
            incomePaise = entries.filter { it.direction == TransactionDirection.CREDIT }.sumOf { it.amountPaise },
            spentPaise = entries.filter { it.direction == TransactionDirection.DEBIT }.sumOf { it.amountPaise }
        )
    }
    val income = days.sumOf { it.incomePaise }
    val spent = days.sumOf { it.spentPaise }
    val net = income - spent
    val topCategory = transactions.asSequence()
        .filter { !it.isHoldingTank && it.direction == TransactionDirection.DEBIT && it.occurredAtEpochMs >= today.minusDays(6).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        .groupBy { it.category?.ifBlank { "Uncategorised" } ?: "Uncategorised" }
        .maxByOrNull { (_, entries) -> entries.sumOf { it.amountPaise } }

    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column {
                    Text("MONEY PULSE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text("Last 7 days", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(formatMoney(net, currencyCode, includeSign = true), style = MaterialTheme.typography.titleLarge, color = if (net < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PulseMetric("IN", formatMoney(income, currencyCode), MaterialTheme.colorScheme.primary)
                PulseMetric("OUT", formatMoney(spent, currencyCode), MaterialTheme.colorScheme.error)
                PulseMetric("DAILY AVG", formatMoney(spent / 7, currencyCode), MaterialTheme.colorScheme.onSurface)
            }
            DailyCashflowChart(days)
            topCategory?.let { (name, entries) ->
                Text("Most used Varga: $name · ${formatMoney(entries.sumOf { it.amountPaise }, currencyCode)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } ?: Text("Add your first Vyavahara to start a local seven-day pulse.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PulseMetric(label: String, value: String, color: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, color = color)
    }
}

@Composable
private fun DailyCashflowChart(days: List<DailyMoneyPulse>) {
    val positive = MaterialTheme.colorScheme.primary
    val negative = MaterialTheme.colorScheme.error
    val divider = MaterialTheme.colorScheme.outlineVariant
    val maxMagnitude = days.maxOfOrNull { maxOf(it.incomePaise, it.spentPaise) }?.coerceAtLeast(1L) ?: 1L
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(80.dp)) {
            val baseline = size.height / 2f
            drawLine(divider, start = androidx.compose.ui.geometry.Offset(0f, baseline), end = androidx.compose.ui.geometry.Offset(size.width, baseline), strokeWidth = 1f)
            val step = size.width / days.size
            val barWidth = step * 0.28f
            days.forEachIndexed { index, day ->
                val center = step * index + step / 2f
                val incomeHeight = day.incomePaise.toFloat() / maxMagnitude * (size.height * 0.42f)
                val spendHeight = day.spentPaise.toFloat() / maxMagnitude * (size.height * 0.42f)
                if (incomeHeight > 0f) drawRect(positive, topLeft = androidx.compose.ui.geometry.Offset(center - barWidth - 2f, baseline - incomeHeight), size = androidx.compose.ui.geometry.Size(barWidth, incomeHeight))
                if (spendHeight > 0f) drawRect(negative, topLeft = androidx.compose.ui.geometry.Offset(center + 2f, baseline), size = androidx.compose.ui.geometry.Size(barWidth, spendHeight))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            days.forEach { day -> Text(day.date.dayOfWeek.name.take(1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun PortfolioValueChart(history: List<InvestmentBalanceSnapshotEntity>, currencyCode: String, compact: Boolean = false) {
    val points = portfolioChartPoints(history)
    if (points.size < 2) {
        Text("Record two dated balances to see cost and value trend.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val valueColor = MaterialTheme.colorScheme.primary
    val costColor = MaterialTheme.colorScheme.tertiary
    val allValues = points.flatMap { listOf(it.costPaise, it.valuePaise) }
    val min = allValues.minOrNull() ?: 0L
    val max = allValues.maxOrNull() ?: min + 1L
    val spread = (max - min).coerceAtLeast(1L).toFloat()
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("PORTFOLIO TREND", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Cost ${formatMoney(points.last().costPaise, currencyCode)} · Value ${formatMoney(points.last().valuePaise, currencyCode)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().height(if (compact) 76.dp else 130.dp)) {
            fun line(values: List<Long>, color: Color) {
                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = size.width * index / (values.size - 1)
                    val y = size.height - ((value - min) / spread * size.height)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = if (compact) 4f else 5f, cap = StrokeCap.Round))
            }
            line(points.map { it.costPaise }, costColor)
            line(points.map { it.valuePaise }, valueColor)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(points.first().date.month.name.take(3), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(points.last().date.month.name.take(3), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("— Cost", style = MaterialTheme.typography.labelSmall, color = costColor)
            Text("— Value", style = MaterialTheme.typography.labelSmall, color = valueColor)
        }
    }
}

@Composable
private fun NavigationGlyph(glyph: String) {
    Text(glyph, style = MaterialTheme.typography.titleMedium)
}

private val StandardBackIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ArrowBack",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(20f, 11f)
            horizontalLineTo(7.83f)
            lineTo(13.42f, 5.41f)
            lineTo(12f, 4f)
            lineTo(4f, 12f)
            lineTo(12f, 20f)
            lineTo(13.42f, 18.59f)
            lineTo(7.83f, 13f)
            horizontalLineTo(20f)
            close()
        }
    }.build()
}

private enum class NdfFileAction { EXPORT, IMPORT }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SettingsScreen(state: MvpFinanceState, onBack: () -> Unit, onNeurodiverseModeChanged: (Boolean) -> Unit, onCurrencyChanged: (String) -> Unit, onDateFormatPreferenceChanged: (DateFormatPreference) -> Unit, onSaveNirmalamAi: (String, String, String, Boolean) -> Unit, onDisableNirmalamAi: () -> Unit, onNirmalamAiInsight: (NirmalamAiInsight) -> Unit, onExportInterchange: (Uri) -> Unit, onExportNdf: (Uri, String) -> Unit, onImportNdf: (Uri, String) -> Unit, onSaveCategory: (String, TransactionDirection, String) -> Unit, onUpdateCategory: (String, String, TransactionDirection, String) -> Unit, onDeleteCategory: (String) -> Unit, onSavePayee: (String, String?) -> Unit, onUpdatePayee: (String, String, String?) -> Unit, onDeletePayee: (String) -> Unit, onRemoveStarterData: () -> Unit) {
    var showVargaManagement by remember { mutableStateOf(false) }
    var showVyaktiManagement by remember { mutableStateOf(false) }
    var showUserGuide by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showNirmalamAi by remember { mutableStateOf(false) }
    var showRemoveStarterDataConfirmation by remember { mutableStateOf(false) }
    var showJsonExportConfirmation by remember { mutableStateOf(false) }
    var currencyExpanded by remember { mutableStateOf(false) }
    var dateFormatExpanded by remember { mutableStateOf(false) }
    var ndfAction by remember { mutableStateOf<NdfFileAction?>(null) }
    var ndfPassphrase by remember { mutableStateOf("") }
    var pendingNdfPassphrase by remember { mutableStateOf("") }
    var importReplacementConfirmed by remember { mutableStateOf(false) }
    var passphraseVisible by remember { mutableStateOf(false) }
    val createInterchangeFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(onExportInterchange) }
    val createNdfFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.nirmalam-dhanam.backup+zip")) { uri ->
        uri?.let { onExportNdf(it, pendingNdfPassphrase) }
        pendingNdfPassphrase = ""
    }
    val openNdfFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onImportNdf(it, pendingNdfPassphrase) }
        pendingNdfPassphrase = ""
    }
    if (showUserGuide) { UserGuideScreen(onBack = { showUserGuide = false }); return }
    if (showPrivacy) { PrivacyAndPermissionsScreen(onBack = { showPrivacy = false }); return }
    if (showAbout) { AboutScreen(onBack = { showAbout = false }); return }
    if (showNirmalamAi) { NirmalamAiScreen(state, onBack = { showNirmalamAi = false }, onSave = onSaveNirmalamAi, onDisable = onDisableNirmalamAi, onInsight = onNirmalamAiInsight); return }
    if (showVargaManagement) { VargaManagementScreen(state.categories, onBack = { showVargaManagement = false }, onSave = onSaveCategory, onUpdate = onUpdateCategory, onDelete = onDeleteCategory); return }
    if (showVyaktiManagement) { VyaktiManagementScreen(state.payees, state.categories, onBack = { showVyaktiManagement = false }, onSave = onSavePayee, onUpdate = onUpdatePayee, onDelete = onDeletePayee); return }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, topBar = { TopAppBar(title = { Text("Vinyasa") }, navigationIcon = { IconButton(onClick = onBack) { Icon(StandardBackIcon, contentDescription = "Back") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("Experience", style = MaterialTheme.typography.titleMedium) }
            item { NeurodiverseModeToggle(state.neurodiverseModeEnabled, onNeurodiverseModeChanged) }
            item {
                ExposedDropdownMenuBox(expanded = currencyExpanded, onExpandedChange = { currencyExpanded = !currencyExpanded }) {
                    OutlinedTextField(state.currencyCode, {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text("Currency") }, supportingText = { Text("More currencies will be added in a future update.") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(currencyExpanded) })
                    ExposedDropdownMenu(expanded = currencyExpanded, onDismissRequest = { currencyExpanded = false }) {
                        DropdownMenuItem(text = { Text("Indian Rupee (INR) · ₹") }, onClick = { onCurrencyChanged("INR"); currencyExpanded = false })
                    }
                }
            }
            item {
                ExposedDropdownMenuBox(expanded = dateFormatExpanded, onExpandedChange = { dateFormatExpanded = !dateFormatExpanded }) {
                    OutlinedTextField(
                        value = dateFormatPreferenceLabel(state.dateFormatPreference),
                        onValueChange = {},
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        readOnly = true,
                        label = { Text("Date format") },
                        supportingText = { Text("Default is your mobile or tablet locale. Example: ${formatDate(LocalDate.of(2026, 8, 26), state.dateFormatPreference)}") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(dateFormatExpanded) }
                    )
                    ExposedDropdownMenu(expanded = dateFormatExpanded, onDismissRequest = { dateFormatExpanded = false }) {
                        DateFormatPreference.entries.forEach { preference ->
                            DropdownMenuItem(
                                text = { Text("${dateFormatPreferenceLabel(preference)} · ${formatDate(LocalDate.of(2026, 8, 26), preference)}") },
                                onClick = {
                                    onDateFormatPreferenceChanged(preference)
                                    dateFormatExpanded = false
                                }
                            )
                        }
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column { Text("User guide", style = MaterialTheme.typography.titleMedium); Text("Concepts, features, and FAQ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick = { showUserGuide = true }) { Text("Open") }
                    }
                }
            }
            item { ManagementLinkCard("About", "App version, support, privacy, and acknowledgements", onClick = { showAbout = true }) }
            item { ManagementLinkCard("Varga", "${state.categories.size} categories · create, search, edit, and organise", onClick = { showVargaManagement = true }) }
            item { ManagementLinkCard("Vyakti", "${state.payees.size} saved people, shops, and institutions", onClick = { showVyaktiManagement = true }) }
            item { ManagementLinkCard("Nirmalam AI", if (state.nirmalamAiReady) "BYOL enabled · preset private insights" else "Optional BYOL insights · disabled", onClick = { showNirmalamAi = true }) }
            item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Starter data", style = MaterialTheme.typography.titleMedium)
                        Text("Remove demo Khatas, demo Vyavahara, seeded Varga, and suggested Vyakti. Your entries stay untouched and the starter data will not return.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { showRemoveStarterDataConfirmation = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove starter data") }
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Data & interoperability", style = MaterialTheme.typography.titleMedium)
                        Text("Export a read-only JSON report for local Python, Java, R, or spreadsheet analysis. This file is plaintext; use encrypted .ndf for backup and restore.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { showJsonExportConfirmation = true }, modifier = Modifier.fillMaxWidth()) { Text("Export JSON interchange") }
                        HorizontalDivider()
                        Text("Encrypted .ndf backup", style = MaterialTheme.typography.titleSmall)
                        Text("Use the same database passphrase to export or restore. Import replaces the current local database only after the selected backup passes encryption and integrity checks.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Backup ritual", style = MaterialTheme.typography.labelLarge)
                                Text("1. Confirm the passphrase\n2. Pick a safe location\n3. Keep the .ndf file and passphrase together in your records\n4. If restore says the passphrase is wrong, your current device data stays untouched", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { ndfAction = NdfFileAction.EXPORT }, modifier = Modifier.weight(1f)) { Text("Export .ndf") }
                            OutlinedButton(onClick = { ndfAction = NdfFileAction.IMPORT }, modifier = Modifier.weight(1f)) { Text("Import .ndf") }
                        }
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Privacy & permissions", style = MaterialTheme.typography.titleMedium); Text("Local encryption, data sharing, and backup guidance", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick = { showPrivacy = true }) { Text("Review") }
                    }
                }
            }
            item { ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("Your vocabulary", style = MaterialTheme.typography.titleMedium); Text("Prarambha: home and daily actions\nVyavahara: money activity\nKhata: money place\nNivesha: investments\nSampada: overall wealth\nVinyasa: preferences and data controls", style = MaterialTheme.typography.bodySmall) } } }
        }
    }
    if (showRemoveStarterDataConfirmation) {
        AlertDialog(
            onDismissRequest = { showRemoveStarterDataConfirmation = false },
            title = { Text("Remove starter data?") },
            text = { Text("This removes only data supplied with the app: demo Khatas and their demo records, seeded Varga, and suggested Vyakti. Your own Khatas, Vyavahara, Varga, and Vyakti remain. You can add anything back manually.") },
            confirmButton = { Button(onClick = { onRemoveStarterData(); showRemoveStarterDataConfirmation = false }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { showRemoveStarterDataConfirmation = false }) { Text("Cancel") } }
        )
    }
    if (showJsonExportConfirmation) {
        AlertDialog(
            onDismissRequest = { showJsonExportConfirmation = false },
            title = { Text("Export plaintext JSON?") },
            text = { Text("This file contains readable financial data. Save it only to a trusted location and share it only with tools you trust. Use encrypted .ndf for backup and restore.") },
            confirmButton = {
                Button(onClick = {
                    showJsonExportConfirmation = false
                    createInterchangeFile.launch("nirmalam-dhanam-interchange.json")
                }) { Text("Choose location") }
            },
            dismissButton = { TextButton(onClick = { showJsonExportConfirmation = false }) { Text("Cancel") } }
        )
    }
    ndfAction?.let { action ->
        AlertDialog(
            onDismissRequest = { ndfAction = null; ndfPassphrase = ""; importReplacementConfirmed = false; passphraseVisible = false },
            title = { Text(if (action == NdfFileAction.EXPORT) "Export encrypted backup" else "Import encrypted backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (action == NdfFileAction.EXPORT) "Confirm the current database passphrase. The backup remains encrypted and is safe to move only to locations you trust." else "Enter the passphrase used for the selected .ndf backup. If the passphrase is wrong or the file is invalid, your current device data remains unchanged.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        ndfPassphrase,
                        { ndfPassphrase = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("Database passphrase") },
                        visualTransformation = if (passphraseVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        trailingIcon = { TextButton(onClick = { passphraseVisible = !passphraseVisible }) { Text(if (passphraseVisible) "Hide" else "Show") } },
                        supportingText = { Text(if (action == NdfFileAction.EXPORT) "A mistyped passphrase will stop the export before any backup is created." else "Use the passphrase from the backup's original device or export session.") },
                        singleLine = true
                    )
                    if (action == NdfFileAction.IMPORT) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = importReplacementConfirmed, onCheckedChange = { importReplacementConfirmed = it })
                            Text("I understand that a successful restore replaces this device's current local finance database.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    pendingNdfPassphrase = ndfPassphrase
                    ndfPassphrase = ""
                    importReplacementConfirmed = false
                    passphraseVisible = false
                    ndfAction = null
                    if (action == NdfFileAction.EXPORT) createNdfFile.launch("nirmalam-dhanam-backup.ndf") else openNdfFile.launch(arrayOf("application/vnd.nirmalam-dhanam.backup+zip", "application/zip"))
                }, enabled = pendingNdfPassphrase.isEmpty() && ndfPassphrase.length >= 8 && (action == NdfFileAction.EXPORT || importReplacementConfirmed)) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { ndfAction = null; ndfPassphrase = ""; importReplacementConfirmed = false; passphraseVisible = false }) { Text("Cancel") } }
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun NirmalamAiScreen(state: MvpFinanceState, onBack: () -> Unit, onSave: (String, String, String, Boolean) -> Unit, onDisable: () -> Unit, onInsight: (NirmalamAiInsight) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val saved = remember { NirmalamAiPreferences(context).settings() }
    var endpoint by remember { mutableStateOf(saved.endpoint) }
    var model by remember { mutableStateOf(saved.model) }
    var apiKey by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TopAppBar(title = { Column { Text("Nirmalam AI"); Text("Preset private insights", style = MaterialTheme.typography.labelMedium) } }, navigationIcon = { IconButton(onClick = onBack) { Icon(StandardBackIcon, contentDescription = "Back") } }) }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Not a chat assistant", style = MaterialTheme.typography.titleMedium)
                        Text("Nirmalam AI offers only the four fixed reflections below. It does not read free-form questions or make trades, tax, credit, or investment recommendations.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            if (!state.nirmalamAiReady) {
                item { Text("Bring your own LLM", style = MaterialTheme.typography.titleMedium) }
                item { Text("Use an OpenAI-compatible HTTPS endpoint. Your API key is encrypted with Android Keystore and is never written to the finance database or export files.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("Provider base URL") }, supportingText = { Text("Example: https://api.openai.com/v1") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true) }
                item { OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true) }
                item { OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text("API key") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true) }
                item {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = consent, onCheckedChange = { consent = it })
                        Text("I understand that pressing a preset sends only the displayed aggregate summary to my chosen provider. No raw Vyavahara, Vyakti, descriptions, or account IDs are sent.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { Button(onClick = { onSave(endpoint, model, apiKey, true) }, enabled = consent && endpoint.startsWith("https://") && model.isNotBlank() && apiKey.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Enable Nirmalam AI") } }
            } else {
                item { Text("Choose an insight", style = MaterialTheme.typography.titleMedium) }
                item { Text("Every request uses an aggregate INR summary for the current month and current portfolio. Data is sent only when you press a button.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(NirmalamAiInsight.entries.size) { index ->
                    val insight = NirmalamAiInsight.entries[index]
                    ElevatedCard(onClick = { if (!state.nirmalamAiLoading) onInsight(insight) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(insight.title, style = MaterialTheme.typography.titleMedium); Text(insight.prompt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (state.nirmalamAiLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Preparing your preset insight…", style = MaterialTheme.typography.bodySmall) }
                state.nirmalamAiResponse?.let { response -> item { ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Nirmalam AI reflection", style = MaterialTheme.typography.titleMedium); Text(response, style = MaterialTheme.typography.bodyMedium); Text("Review this as a reflection, not financial advice.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer) } } } }
                item { OutlinedButton(onClick = onDisable, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Disable Nirmalam AI") } }
            }
        }
    }
}

@Composable
private fun ManagementLinkCard(title: String, subtitle: String, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun VargaManagementScreen(categories: List<CategoryEntity>, onBack: () -> Unit, onSave: (String, TransactionDirection, String) -> Unit, onUpdate: (String, String, TransactionDirection, String) -> Unit, onDelete: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    val shown = categories.filter { it.name.contains(query.trim(), ignoreCase = true) }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, topBar = { TopAppBar(title = { Column { Text("Varga"); Text("Categories", style = MaterialTheme.typography.labelMedium) } }, navigationIcon = { IconButton(onClick = onBack) { Icon(StandardBackIcon, contentDescription = "Back") } }, actions = { TextButton(onClick = { showNew = true }) { Text("Add") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search Varga") }, placeholder = { Text("Name or purpose") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true) }
            item { Text("${shown.size} of ${categories.size} Varga", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(shown.size) { index ->
                val category = shown[index]
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editingId == category.id) InlineCategoryEditor(category, onCancel = { editingId = null }, onSave = { name, direction, icon -> onUpdate(category.id, name, direction, icon); editingId = null })
                    else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            IconifiedCategoryLabel(category.name, category.iconKey)
                            Row { TextButton(onClick = { editingId = category.id }) { Text("Modify") }; if (!category.isSystem) TextButton(onClick = { onDelete(category.id) }) { Text("Remove") } }
                        }
                    }
                } }
            }
        }
    }
    if (showNew) CategoryEditorDialog(onDismiss = { showNew = false }, onSave = { name, direction, icon -> onSave(name, direction, icon); showNew = false })
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun VyaktiManagementScreen(payees: List<PayeeEntity>, categories: List<CategoryEntity>, onBack: () -> Unit, onSave: (String, String?) -> Unit, onUpdate: (String, String, String?) -> Unit, onDelete: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    val shown = payees.filter { payee -> listOfNotNull(payee.name, payee.defaultCategory).any { it.contains(query.trim(), ignoreCase = true) } }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, topBar = { TopAppBar(title = { Column { Text("Vyakti"); Text("Payees", style = MaterialTheme.typography.labelMedium) } }, navigationIcon = { IconButton(onClick = onBack) { Icon(StandardBackIcon, contentDescription = "Back") } }, actions = { TextButton(onClick = { showNew = true }) { Text("Add") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search Vyakti") }, placeholder = { Text("Person, shop, or institution") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true) }
            item { Text("${shown.size} of ${payees.size} saved Vyakti", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(shown.size) { index ->
                val payee = shown[index]
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editingId == payee.id) InlinePayeeEditor(payee, categories, onCancel = { editingId = null }, onSave = { name, category -> onUpdate(payee.id, name, category); editingId = null })
                    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column { Text(payee.name, style = MaterialTheme.typography.titleSmall); Text(payee.defaultCategory?.let { "Default Varga · $it" } ?: "No default Varga", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Row { TextButton(onClick = { editingId = payee.id }) { Text("Modify") }; TextButton(onClick = { onDelete(payee.id) }) { Text("Remove") } }
                    }
                } }
            }
        }
    }
    if (showNew) PayeeEditorDialog(categories, onDismiss = { showNew = false }, onSave = { name, category -> onSave(name, category); showNew = false })
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun UserGuideScreen(onBack: () -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, topBar = { TopAppBar(title = { Text("User guide") }, navigationIcon = { IconButton(onClick = onBack) { Icon(StandardBackIcon, contentDescription = "Back") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("Nirmalam Dhanam", style = MaterialTheme.typography.headlineSmall) }
            item { Text("A calm, private daily money practice. Records stay encrypted on this device; optional BYOL AI requests are explained below.", style = MaterialTheme.typography.bodyLarge) }
            item { UserGuideSection("Concepts", listOf(
                "Prarambha — your daily starting point for available cash and quick entry.",
                "Vyavahara — a money event. Aaya is inflow; Vyaya is outflow.",
                "Khata — a money place, such as cash, bank, card, loan, or investment holding.",
                "Varga — a reusable category that explains the purpose of a Vyavahara.",
                "Vyakti — a person, shop, employer, or institution involved in a Vyavahara.",
                "Nivesha and Sampada — investments and your overall wealth view."
            )) }
            item { UserGuideSection("Features", listOf(
                "Create Khatas for cash, banks, liabilities, and investment products.",
                "Record Aaya or Vyaya with a transaction date, Varga, Vyakti, and private description.",
                "Manage Varga entries with label-backed glyphs and manage saved Vyakti defaults.",
                "Review Vyavahara by Khata, Varga, timeframe, inflow/outflow, or search.",
                "Use Money Pulse and Spend Map for local-only summaries.",
                "Record dated Nivesha cost-and-value check-ins for Sampada and allocation context.",
                "Use the cooling tank for larger wants purchases and neurodiverse mode for a calmer presentation."
            )) }
            item { UserGuideSection("Nirmalam AI", listOf(
                "Nirmalam AI is optional and disabled by default. It is a set of fixed financial reflections, not an open chat assistant.",
                "Set it up in Vinyasa with your own OpenAI-compatible HTTPS provider, model, and API key. The key is protected by Android Keystore and is not put in exports or backups.",
                "Choose only from Spending focus, Cash plan, Portfolio review, or Monthly recap. You cannot submit arbitrary questions.",
                "A request is sent only when you press one of these buttons. It contains a minimised aggregate summary: available cash, monthly income/expense, top Varga totals, and portfolio totals.",
                "Raw Vyavahara, Vyakti, descriptions, and account identifiers are excluded. Your selected provider processes the summary under its own privacy terms.",
                "AI reflections are for awareness, not investment, tax, credit, legal, or trading advice. You can disable Nirmalam AI at any time in Vinyasa."
            )) }
            item { UserGuideSection("FAQ", listOf(
                "Is my data uploaded? The database remains local and encrypted. If you enable Nirmalam AI, only its minimised aggregate summary is sent to your chosen provider when you tap a preset insight.",
                "Khata or Varga? Khata holds money; Varga explains why money moved.",
                "Why a cooling tank? Wants above the threshold wait 48 hours before confirmation.",
                "Can I remove a Vyakti? Yes. Removing it only affects the reusable list; old records stay intact.",
                "Can I recover a forgotten passphrase? No. Keep it safe; encryption is intentional.",
                "Where are backups? Use Vinyasa to export or import an encrypted .ndf file. Keep the backup passphrase safe; a wrong passphrase never replaces your local data.",
                "Why does Nirmalam AI show a dash or limited insight? It needs enough aggregate, dated records for the selected reflection; it will not invent missing financial facts."
            )) }
        }
    }
}

@Composable
private fun UserGuideSection(title: String, items: List<String>) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            items.forEach { item -> Text("•  $item", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun CategoryEditorDialog(onDismiss: () -> Unit, onSave: (String, TransactionDirection, String) -> Unit) {
    var name by remember { mutableStateOf("") }; var icon by remember { mutableStateOf("other") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New Varga") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Varga name") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
        Text("Use a Varga for either money in or money out.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Choose a glyph", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(categoryGlyphKeys.size) { index -> val key = categoryGlyphKeys[index]; FilterChip(icon == key, { icon = key }, label = { CategoryGlyph(key, 22.dp) }) } }
    } }, confirmButton = { Button(onClick = { onSave(name, TransactionDirection.DEBIT, icon) }, enabled = name.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun PayeeEditorDialog(categories: List<CategoryEntity>, onDismiss: () -> Unit, onSave: (String, String?) -> Unit) {
    var name by remember { mutableStateOf("") }; var defaultCategory by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New Vyakti") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
        OutlinedTextField(defaultCategory, { defaultCategory = it }, label = { Text("Default Varga (optional)") }, placeholder = { Text(categories.firstOrNull()?.name ?: "Food") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
    } }, confirmButton = { Button(onClick = { onSave(name, defaultCategory.ifBlank { null }) }, enabled = name.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun InlineCategoryEditor(category: CategoryEntity, onCancel: () -> Unit, onSave: (String, TransactionDirection, String) -> Unit) {
    var name by remember(category.id) { mutableStateOf(category.name) }
    var icon by remember(category.id) { mutableStateOf(category.iconKey ?: "other") }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("Modify Varga", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Varga name") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
    Text("Available for both Aaya and Vyaya.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(categoryGlyphKeys.size) { index -> val key = categoryGlyphKeys[index]; FilterChip(icon == key, { icon = key }, label = { CategoryGlyph(key, 22.dp) }) } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onCancel) { Text("Cancel") }; Button(onClick = { onSave(name, category.transactionDirection, icon) }, enabled = name.isNotBlank()) { Text("Save") } }
}

@Composable
private fun InlinePayeeEditor(payee: PayeeEntity, categories: List<CategoryEntity>, onCancel: () -> Unit, onSave: (String, String?) -> Unit) {
    var name by remember(payee.id) { mutableStateOf(payee.name) }
    var defaultCategory by remember(payee.id) { mutableStateOf(payee.defaultCategory.orEmpty()) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("Modify Vyakti", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
    OutlinedTextField(defaultCategory, { defaultCategory = it }, Modifier.fillMaxWidth(), label = { Text("Default Varga") }, placeholder = { Text(categories.firstOrNull()?.name ?: "Optional") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onCancel) { Text("Cancel") }; Button(onClick = { onSave(name, defaultCategory.ifBlank { null }) }, enabled = name.isNotBlank()) { Text("Save") } }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PrivacyAndPermissionsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, topBar = { TopAppBar(title = { Text("Privacy & permissions") }, navigationIcon = { IconButton(onClick = onBack) { Icon(StandardBackIcon, contentDescription = "Back") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("Your data stays yours", style = MaterialTheme.typography.headlineSmall) }
            item { Text("Nirmalam Dhanam is local-first. It has no account sign-in, advertising SDK, analytics SDK, or cloud sync in this release.", style = MaterialTheme.typography.bodyMedium) }
            item { PrivacySection("Encrypted local storage", "Financial records are stored in a SQLCipher-encrypted on-device database unlocked with your passphrase. App backups are disabled by default.") }
            item { PrivacySection("Device feedback", "Vibration is used only for optional haptic confirmation around cooling-tank actions.") }
            item { PrivacySection("Backups", "Encrypted .ndf backup files should be stored only in locations you trust. Treat a backup and its passphrase as sensitive financial information.") }
            item { PrivacySection("Data sharing", "The app does not upload, sell, or share raw financial records. If you explicitly enable Nirmalam AI and press a preset insight, it sends a minimised aggregate summary to the provider you chose. JSON exports and encrypted .ndf backups are created only after you select a destination through Android's system file picker.") }
            item { OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, PrivacyPolicyUrl.toUri())) }, modifier = Modifier.fillMaxWidth()) { Text("Open privacy policy") } }
        }
    }
}

@Composable
private fun PrivacySection(title: String, body: String) {
    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AboutScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val versionLabel = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    val packageName = context.packageName
    val contactIntent = remember {
        Intent(Intent.ACTION_SENDTO, "mailto:$SupportEmail".toUri()).apply {
            putExtra(Intent.EXTRA_SUBJECT, "Nirmalam Dhanam support")
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TopAppBar(title = { Text("About") }, navigationIcon = { IconButton(onClick = onBack) { Icon(StandardBackIcon, contentDescription = "Back") } }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 20.dp),
            contentPadding = PaddingValues(vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(88.dp)
                        ) {
                            Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                                Text("ND", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Nirmalam Dhanam", style = MaterialTheme.typography.headlineSmall)
                            Text("A clear, private money practice", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Version $versionLabel", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("What this app is", style = MaterialTheme.typography.titleMedium)
                        Text("Nirmalam Dhanam is a local-first personal finance app for cashflow, investments, and net worth. It is designed to keep records on-device, reduce clutter, and support calmer decisions.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("App details", style = MaterialTheme.typography.titleMedium)
                        AboutDetailRow("Version", versionLabel)
                        AboutDetailRow("Package", packageName)
                        AboutDetailRow("Database", "SQLCipher + Room")
                        AboutDetailRow("Storage", "Local encrypted records")
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Support & trust", style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, PrivacyPolicyUrl.toUri())) }, modifier = Modifier.fillMaxWidth()) { Text("Open privacy policy") }
                        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, SupportWebsiteUrl.toUri())) }, modifier = Modifier.fillMaxWidth()) { Text("Open website") }
                        OutlinedButton(onClick = { context.startActivity(contactIntent) }, modifier = Modifier.fillMaxWidth()) { Text("Email support") }
                        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, GithubRepositoryUrl.toUri())) }, modifier = Modifier.fillMaxWidth()) { Text("Open GitHub repository") }
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Acknowledgements", style = MaterialTheme.typography.titleMedium)
                        Text("Built with Kotlin, Jetpack Compose Material 3, Coroutines, Room, and SQLCipher for encrypted local storage.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Nirmalam AI is optional, bring-your-own-provider, and available only through fixed insight buttons.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TransactionHistoryScreen(state: MvpFinanceState, onBack: () -> Unit, onReports: () -> Unit, onDelete: (String) -> Unit, onUpdate: (String, String, String, String, String, TransactionDirection) -> Unit, onRecord: (String, String, String, String, TransactionDirection, String, Long) -> Unit, onSavedLedgerViewChanged: (LedgerRange, LedgerFilter, String?, String?) -> Unit) {
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var showNewVyavahara by remember { mutableStateOf(false) }
    var filter by remember(state.savedLedgerFilter) { mutableStateOf(ledgerFilterFromConfig(state.savedLedgerFilter)) }
    var range by remember(state.savedLedgerRange) { mutableStateOf(ledgerRangeFromConfig(state.savedLedgerRange)) }
    var selectedAccountId by remember(state.savedLedgerAccountId) { mutableStateOf(state.savedLedgerAccountId) }
    var selectedCategoryName by remember(state.savedLedgerCategoryName) { mutableStateOf(state.savedLedgerCategoryName) }
    var transactionToDelete by remember { mutableStateOf<TransactionEntity?>(null) }
    var editingTransactionId by remember { mutableStateOf<String?>(null) }
    val defaultViewLabel = "${range.label} · ${selectedAccountId?.let { id -> state.accounts.firstOrNull { it.id == id }?.name } ?: "All Khatas"} · ${selectedCategoryName ?: "All Varga"} · ${filter.label}"
    val accountNames = state.accounts.associate { it.id to it.name }
    val selectedAccount = state.accounts.firstOrNull { it.id == selectedAccountId }
    val queryMatches = state.recentTransactions.filter { transaction ->
        query.isBlank() || listOfNotNull(transaction.payee, transaction.category, transaction.description, accountNames[transaction.accountId]).any { it.contains(query.trim(), ignoreCase = true) }
    }
    val accountScoped = queryMatches.filter { transaction -> selectedAccountId == null || transaction.accountId == selectedAccountId }
    val categoryScoped = accountScoped.filter { transaction -> selectedCategoryName == null || transaction.category == selectedCategoryName }
    val periodScoped = categoryScoped.filter { transaction -> range.includes(transaction.occurredAtEpochMs) }
    val shown = periodScoped.filter { transaction ->
        when (filter) {
            LedgerFilter.ALL -> true
            LedgerFilter.SPENT -> transaction.direction == TransactionDirection.DEBIT
            LedgerFilter.INCOME -> transaction.direction == TransactionDirection.CREDIT
        }
    }
    val income = periodScoped.filter { it.direction == TransactionDirection.CREDIT }.sumOf { it.amountPaise }
    val spent = periodScoped.filter { it.direction == TransactionDirection.DEBIT }.sumOf { it.amountPaise }
    val net = income - spent
    val spendingByCategory = periodScoped.filter { it.direction == TransactionDirection.DEBIT }
        .groupBy { it.category?.ifBlank { null } ?: "Uncategorised" }
        .mapValues { (_, entries) -> entries.sumOf { it.amountPaise } }
        .toList()
        .sortedByDescending { it.second }
    val recentAmounts = state.recentTransactions
        .asSequence()
        .filterNot { it.isHoldingTank }
        .map { it.amountPaise }
        .distinct()
        .take(6)
        .toList()
    val mostUsedPayee = periodScoped.filter { it.direction == TransactionDirection.DEBIT && !it.payee.isNullOrBlank() }
        .groupingBy { it.payee!! }
        .eachCount()
        .maxByOrNull { it.value }
    val grouped = shown.groupBy { Instant.ofEpochMilli(it.occurredAtEpochMs).atZone(ZoneId.systemDefault()).toLocalDate() }
        .toSortedMap(compareByDescending { it })
    LaunchedEffect(range, filter, selectedAccountId, selectedCategoryName) {
        onSavedLedgerViewChanged(range, filter, selectedAccountId, selectedCategoryName)
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    if (searchExpanded) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Search Vyavahara") },
                            placeholder = { Text("Payee, category, Khata, or note") },
                            singleLine = true
                        )
                    } else {
                        Column { Text("Vyavahara"); Text("Money activity", style = MaterialTheme.typography.labelMedium) }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(StandardBackIcon, contentDescription = "Back") } },
                actions = {
                    if (searchExpanded) {
                        TextButton(onClick = { query = ""; searchExpanded = false }) { Text("Close") }
                    } else {
                        IconButton(onClick = onReports, modifier = Modifier.semantics { contentDescription = "Income and expense reports" }) { Text("▥", style = MaterialTheme.typography.headlineSmall) }
                        IconButton(onClick = { showFilters = true }, modifier = Modifier.semantics { contentDescription = "Filter Vyavahara" }) { Text("☷", style = MaterialTheme.typography.headlineSmall) }
                        IconButton(onClick = { searchExpanded = true }, modifier = Modifier.semantics { contentDescription = "Search Vyavahara" }) {
                            Text("⌕", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewVyavahara = true }, modifier = Modifier.navigationBarsPadding(), containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                Text("+", style = MaterialTheme.typography.headlineMedium)
            }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("MONEY PULSE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            LedgerMetric("In", formatMoney(income, state.currencyCode, includeSign = true), MaterialTheme.colorScheme.primary)
                            LedgerMetric("Out", formatMoney(-spent, state.currencyCode), MaterialTheme.colorScheme.error)
                            LedgerMetric("Net", formatMoney(net, state.currencyCode, includeSign = true), if (net < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                        Text("${range.description} · ${periodScoped.size} entries${selectedAccount?.let { " in ${it.name}" } ?: ""}. Use this as a gentle check-in, not a judgement.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { showFilters = true }, label = { Text(defaultViewLabel) })
                    if (query.isNotBlank() || filter != LedgerFilter.ALL || range != ledgerRangeFromConfig(state.savedLedgerRange) || selectedAccountId != state.savedLedgerAccountId || selectedCategoryName != state.savedLedgerCategoryName) {
                        AssistChip(onClick = {
                            query = ""
                            filter = ledgerFilterFromConfig(state.savedLedgerFilter)
                            range = ledgerRangeFromConfig(state.savedLedgerRange)
                            selectedAccountId = state.savedLedgerAccountId
                            selectedCategoryName = state.savedLedgerCategoryName
                        }, label = { Text("Reset") })
                    }
                }
            }
            if (query.isBlank()) item {
                Text("This default view is saved automatically for your next visit.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (spendingByCategory.isNotEmpty()) item {
                SpendingMap(
                    categories = spendingByCategory,
                    totalSpent = spent,
                    mostUsedPayee = mostUsedPayee?.key,
                    mostUsedPayeeCount = mostUsedPayee?.value ?: 0,
                    currencyCode = state.currencyCode
                )
            }
            selectedAccount?.let { account ->
                val balance = state.accountBalances.firstOrNull { it.accountId == account.id }?.balancePaise ?: account.openingBalancePaise
                item {
                    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) { Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("Selected Khata", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); Text(account.name, style = MaterialTheme.typography.titleSmall); Text(account.productType.name.replace('_', ' '), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text(formatMoney(balance, state.currencyCode), style = MaterialTheme.typography.titleMedium) } }
                }
            }
            item { Text("Vyavahara", style = MaterialTheme.typography.titleMedium) }
            if (grouped.isEmpty()) item { EmptyLedgerState(query.isNotBlank() || filter != LedgerFilter.ALL) }
            grouped.forEach { (date, transactions) ->
                item { Text(ledgerDayLabel(date, state.dateFormatPreference), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                items(transactions.size) { index ->
                    val transaction = transactions[index]
                    val signed = if (transaction.direction == TransactionDirection.CREDIT) transaction.amountPaise else -transaction.amountPaise
                    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(transaction.payee ?: "Unlabelled Vyavahara", style = MaterialTheme.typography.titleSmall)
                                Text(accountNames[transaction.accountId] ?: "Khata", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(formatMoney(signed, state.currencyCode, includeSign = true), style = MaterialTheme.typography.titleMedium, color = if (signed < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            transaction.category?.let { AssistChip(onClick = {}, label = { IconifiedCategoryLabel(it, compact = true) }) }
                            transaction.envelopeType?.let { Text(it.name.lowercase().replaceFirstChar { char -> char.titlecase() }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        transaction.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (editingTransactionId == transaction.id) {
                            InlineVyavaharaEditor(
                                transaction = transaction,
                                payees = state.payees,
                                categories = state.categories,
                                onCancel = { editingTransactionId = null },
                                onSave = { amount, payee, category, description, direction -> onUpdate(transaction.id, amount, payee, category, description, direction); editingTransactionId = null }
                            )
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { editingTransactionId = transaction.id }) { Text("Modify") }
                                TextButton(onClick = { transactionToDelete = transaction }) { Text("Delete") }
                            }
                        }
                    } }
                }
            }
        }
    }
    if (showFilters) LedgerFilterSheet(
        state = state, selectedAccountId = selectedAccountId, selectedCategoryName = selectedCategoryName, range = range, filter = filter,
        onAccountSelected = { selectedAccountId = it }, onCategorySelected = { selectedCategoryName = it }, onRangeSelected = { range = it }, onFilterSelected = { filter = it }, onDismiss = { showFilters = false }
    )
    if (showNewVyavahara) NewVyavaharaDialog(state.categories, state.payees, state.accounts, state.dateFormatPreference, recentAmounts, state.recentTransactions, selectedAccountId, selectedCategoryName, onDismiss = { showNewVyavahara = false }, onSave = { amount, payee, category, description, direction, accountId, occurredAtEpochMs -> onRecord(amount, payee, category, description, direction, accountId, occurredAtEpochMs); showNewVyavahara = false })
    transactionToDelete?.let { transaction -> AlertDialog(onDismissRequest = { transactionToDelete = null }, title = { Text("Delete Vyavahara?") }, text = { Text("This removes the entry from your encrypted money activity and updates balances.") }, confirmButton = { Button(onClick = { onDelete(transaction.id); transactionToDelete = null }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { transactionToDelete = null }) { Text("Cancel") } }) }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun IncomeExpenseReportsScreen(state: MvpFinanceState, onBack: () -> Unit) {
    var range by remember { mutableStateOf(LedgerRange.MONTH) }
    val entries = state.recentTransactions.filter { range.includes(it.occurredAtEpochMs) && !it.isHoldingTank }
    val previousEntries = state.recentTransactions.filter { range.previousWindowIncludes(it.occurredAtEpochMs) && !it.isHoldingTank }
    val income = entries.filter { it.direction == TransactionDirection.CREDIT }.sumOf { it.amountPaise }
    val expense = entries.filter { it.direction == TransactionDirection.DEBIT }.sumOf { it.amountPaise }
    val net = income - expense
    val previousNet = previousEntries.filter { it.direction == TransactionDirection.CREDIT }.sumOf { it.amountPaise } -
        previousEntries.filter { it.direction == TransactionDirection.DEBIT }.sumOf { it.amountPaise }
    val categoryTotals = entries.filter { it.direction == TransactionDirection.DEBIT }
        .groupBy { it.category?.ifBlank { null } ?: "Uncategorised" }
        .mapValues { (_, values) -> values.sumOf { it.amountPaise } }
        .entries.sortedByDescending { it.value }
    val payeeTotals = entries.filter { it.direction == TransactionDirection.DEBIT && !it.payee.isNullOrBlank() }
        .groupBy { it.payee!! }
        .mapValues { (_, values) -> values.sumOf { it.amountPaise } }
        .entries.sortedByDescending { it.value }
    val accountTotals = entries.groupBy { it.accountId }
        .mapValues { (_, values) ->
            values.sumOf { if (it.direction == TransactionDirection.CREDIT) it.amountPaise else -it.amountPaise }
        }
        .entries.sortedByDescending { kotlin.math.abs(it.value) }
    val categoryIcons = state.categories.associate { it.name to it.iconKey }
    val accountNames = state.accounts.associate { it.id to it.name }
    val monthly = entries.groupBy { java.time.YearMonth.from(Instant.ofEpochMilli(it.occurredAtEpochMs).atZone(ZoneId.systemDefault())) }
        .toSortedMap(compareByDescending { it }).entries.take(6)
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TopAppBar(title = { Column { Text("Aaya & Vyaya reports"); Text("Income and expense insights", style = MaterialTheme.typography.labelMedium) } }, navigationIcon = { IconButton(onClick = onBack) { Icon(StandardBackIcon, contentDescription = "Back") } }) }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LedgerRange.entries.size) { index -> val option = LedgerRange.entries[index]; FilterChip(selected = range == option, onClick = { range = option }, label = { Text(option.label) }) }
                }
            }
            if (entries.isEmpty()) item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("No report data for this period", style = MaterialTheme.typography.titleSmall)
                        Text("Record an Aaya or Vyaya in a daily Khata to see cashflow, monthly trends, and Varga insights here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("CASHFLOW SUMMARY", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { Text("AAYA", style = MaterialTheme.typography.labelSmall); Text(formatMoney(income, state.currencyCode, includeSign = true), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) { Text("NET", style = MaterialTheme.typography.labelSmall); Text(formatMoney(net, state.currencyCode, includeSign = true), style = MaterialTheme.typography.titleMedium, color = if (net < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) { Text("VYAYA", style = MaterialTheme.typography.labelSmall); Text(formatMoney(-expense, state.currencyCode), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error) }
                        }
                        Text("${entries.size} confirmed entries · Holding-tank purchases excluded.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Period comparison", style = MaterialTheme.typography.titleMedium)
                            Text("Current net ${formatMoney(net, state.currencyCode, includeSign = true)}", style = MaterialTheme.typography.bodyMedium)
                        }
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Previous", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatMoney(previousNet, state.currencyCode, includeSign = true), style = MaterialTheme.typography.titleSmall, color = if (previousNet < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            item { Text("Vyaya by Varga", style = MaterialTheme.typography.titleMedium) }
            if (categoryTotals.isEmpty()) item { ElevatedCard(Modifier.fillMaxWidth()) { Text("Record a Vyaya to see its Varga breakdown.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) } }
            items(categoryTotals.take(8).size) { index ->
                val (category, amount) = categoryTotals[index]
                val share = if (expense == 0L) 0f else (amount.toDouble() / expense).toFloat()
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { IconifiedCategoryLabel(category, categoryIcons[category], compact = true); Text(formatMoney(amount, state.currencyCode), style = MaterialTheme.typography.titleSmall) }
                    LinearProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth())
                    Text("${"%.1f".format(share * 100)}% of Vyaya", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
            item { Text("Top Vyakti", style = MaterialTheme.typography.titleMedium) }
            if (payeeTotals.isEmpty()) item { ElevatedCard(Modifier.fillMaxWidth()) { Text("Add a Vyakti to your Vyaya and the most-used people, shops, or institutions will appear here.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) } }
            items(payeeTotals.take(5).size) { index ->
                val payeeEntry = payeeTotals[index]
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(payeeEntry.key, style = MaterialTheme.typography.titleSmall)
                            Text("${entries.count { it.payee == payeeEntry.key }} entries", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(formatMoney(payeeEntry.value, state.currencyCode), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            item { Text("Cashflow by Khata", style = MaterialTheme.typography.titleMedium) }
            if (accountTotals.isEmpty()) item { ElevatedCard(Modifier.fillMaxWidth()) { Text("Your Khata-level flow appears after your first confirmed entry.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) } }
            items(accountTotals.take(6).size) { index ->
                val accountEntry = accountTotals[index]
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(accountNames[accountEntry.key] ?: "Khata", style = MaterialTheme.typography.titleSmall)
                            Text("Net movement for ${range.label.lowercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(formatMoney(accountEntry.value, state.currencyCode, includeSign = true), style = MaterialTheme.typography.titleSmall, color = if (accountEntry.value < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item { Text("Monthly cashflow", style = MaterialTheme.typography.titleMedium) }
            if (monthly.isEmpty()) item { ElevatedCard(Modifier.fillMaxWidth()) { Text("Your month-by-month cashflow appears after your first entry.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) } }
            items(monthly.size) { index ->
                val (month, monthEntries) = monthly[index]
                val monthIncome = monthEntries.filter { it.direction == TransactionDirection.CREDIT }.sumOf { it.amountPaise }
                val monthExpense = monthEntries.filter { it.direction == TransactionDirection.DEBIT && !it.isHoldingTank }.sumOf { it.amountPaise }
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(month.toString(), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Aaya ${formatMoney(monthIncome, state.currencyCode)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary); Text("Vyaya ${formatMoney(monthExpense, state.currencyCode)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Text("Net ${formatMoney(monthIncome - monthExpense, state.currencyCode, includeSign = true)}", style = MaterialTheme.typography.bodyMedium, color = if (monthIncome < monthExpense) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                } }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LedgerFilterSheet(state: MvpFinanceState, selectedAccountId: String?, selectedCategoryName: String?, range: LedgerRange, filter: LedgerFilter, onAccountSelected: (String?) -> Unit, onCategorySelected: (String?) -> Unit, onRangeSelected: (LedgerRange) -> Unit, onFilterSelected: (LedgerFilter) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.padding(horizontal = 20.dp), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("Filter Vyavahara", style = MaterialTheme.typography.titleLarge) }
            item { Text("Khata", style = MaterialTheme.typography.labelLarge) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = selectedAccountId == null, onClick = { onAccountSelected(null) }, label = { Text("All Khatas") }) }
                    items(state.accounts.size) { index -> val account = state.accounts[index]; FilterChip(selected = selectedAccountId == account.id, onClick = { onAccountSelected(account.id) }, label = { Text(account.name) }) }
                }
            }
            item { Text("Varga", style = MaterialTheme.typography.labelLarge) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = selectedCategoryName == null, onClick = { onCategorySelected(null) }, label = { Text("All Varga") }) }
                    items(state.categories.size) { index -> val category = state.categories[index]; FilterChip(selected = selectedCategoryName == category.name, onClick = { onCategorySelected(category.name) }, label = { IconifiedCategoryLabel(category.name, category.iconKey, compact = true) }) }
                }
            }
            item { Text("Period", style = MaterialTheme.typography.labelLarge) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(LedgerRange.entries.size) { index -> val option = LedgerRange.entries[index]; FilterChip(selected = range == option, onClick = { onRangeSelected(option) }, label = { Text(option.label) }) } }
            }
            item { Text("Direction", style = MaterialTheme.typography.labelLarge) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { LedgerFilter.entries.forEach { option -> FilterChip(selected = filter == option, onClick = { onFilterSelected(option) }, label = { Text(option.label) }) } } }
            item { Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Show Vyavahara") } }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun InlineVyavaharaEditor(transaction: TransactionEntity, payees: List<PayeeEntity>, categories: List<CategoryEntity>, onCancel: () -> Unit, onSave: (String, String, String, String, TransactionDirection) -> Unit) {
    var amount by remember(transaction.id) { mutableStateOf((transaction.amountPaise / 100.0).toString()) }
    var payee by remember(transaction.id) { mutableStateOf(transaction.payee.orEmpty()) }
    var category by remember(transaction.id) { mutableStateOf(transaction.category.orEmpty()) }
    var description by remember(transaction.id) { mutableStateOf(transaction.description.orEmpty()) }
    var direction by remember(transaction.id) { mutableStateOf(transaction.direction) }
    var payeeExpanded by remember(transaction.id) { mutableStateOf(false) }
    var categoryExpanded by remember(transaction.id) { mutableStateOf(false) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("Modify Vyavahara", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = direction == TransactionDirection.DEBIT, onClick = { direction = TransactionDirection.DEBIT }, label = { Text("Vyaya") })
        FilterChip(selected = direction == TransactionDirection.CREDIT, onClick = { direction = TransactionDirection.CREDIT }, label = { Text("Aaya") })
    }
    OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text("Amount in ₹") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
    ExposedDropdownMenuBox(expanded = payeeExpanded, onExpandedChange = { payeeExpanded = !payeeExpanded }) {
        OutlinedTextField(
            value = payee,
            onValueChange = { payee = it; payeeExpanded = true },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            label = { Text("Vyakti") },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(payeeExpanded) }
        )
        ExposedDropdownMenu(expanded = payeeExpanded, onDismissRequest = { payeeExpanded = false }) {
            payees.filter { it.name.contains(payee, ignoreCase = true) }.forEach { savedPayee ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(savedPayee.name)
                            savedPayee.defaultCategory?.let { Text("Default Varga: $it", style = MaterialTheme.typography.labelSmall) }
                        }
                    },
                    onClick = {
                        payee = savedPayee.name
                        savedPayee.defaultCategory?.let { category = it }
                        payeeExpanded = false
                    }
                )
            }
        }
    }
    ExposedDropdownMenuBox(expanded = categoryExpanded, onExpandedChange = { categoryExpanded = !categoryExpanded }) {
        OutlinedTextField(
            value = category,
            onValueChange = { category = it; categoryExpanded = true },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            label = { Text("Varga") },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryExpanded) }
        )
        ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
            categories.filter { it.name.contains(category, ignoreCase = true) }.forEach { savedCategory ->
                DropdownMenuItem(text = { IconifiedCategoryLabel(savedCategory.name, savedCategory.iconKey) }, onClick = { category = savedCategory.name; categoryExpanded = false })
            }
        }
    }
    OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Description") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), minLines = 2)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        Button(onClick = { onSave(amount, payee, category, description, direction) }) { Text("Save") }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun NewVyavaharaDialog(categories: List<CategoryEntity>, payees: List<PayeeEntity>, accounts: List<AccountEntity>, dateFormatPreference: DateFormatPreference, recentAmounts: List<Long>, recentTransactions: List<TransactionEntity>, preferredAccountId: String?, preferredCategoryName: String?, onDismiss: () -> Unit, onSave: (String, String, String, String, TransactionDirection, String, Long) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var payee by remember { mutableStateOf("") }
    var category by remember(preferredCategoryName, categories) { mutableStateOf(preferredCategoryName ?: categories.firstOrNull()?.name ?: "Other") }
    var description by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf(TransactionDirection.DEBIT) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var payeeExpanded by remember { mutableStateOf(false) }
    var accountExpanded by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
    val liquidAccounts = accounts.filter { it.kind == AccountKind.SPENDING || it.kind == AccountKind.CREDIT }
    var accountId by remember(liquidAccounts, preferredAccountId) { mutableStateOf(preferredAccountId?.takeIf { id -> liquidAccounts.any { it.id == id } } ?: liquidAccounts.firstOrNull()?.id.orEmpty()) }
    val selectedAccount = liquidAccounts.firstOrNull { it.id == accountId }
    val options = categories
    val recentPayees = recentTransactions.mapNotNull { it.payee?.takeIf(String::isNotBlank) }.distinct().take(4)
    val recentCategories = recentTransactions.mapNotNull { it.category?.takeIf(String::isNotBlank) }.distinct().take(4)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Vyavahara") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = direction == TransactionDirection.DEBIT, onClick = { direction = TransactionDirection.DEBIT }, label = { Text("Vyaya") })
                    FilterChip(selected = direction == TransactionDirection.CREDIT, onClick = { direction = TransactionDirection.CREDIT }, label = { Text("Aaya") })
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Amount in ₹") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                if (recentAmounts.isNotEmpty()) {
                    Text("Recent amounts", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recentAmounts.size) { index ->
                            val paise = recentAmounts[index]
                            AssistChip(onClick = { amount = BigDecimal(paise).divide(BigDecimal(100)).stripTrailingZeros().toPlainString() }, label = { Text(formatMoney(paise, "INR")) })
                        }
                    }
                }
                OutlinedTextField(
                    value = datePickerState.selectedDateMillis?.let { formatDate(it, dateFormatPreference) }.orEmpty(),
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    label = { Text("Transaction date") },
                    trailingIcon = { TextButton(onClick = { showDatePicker = true }) { Text("Pick") } },
                    singleLine = true
                )
                ExposedDropdownMenuBox(expanded = accountExpanded, onExpandedChange = { accountExpanded = !accountExpanded }) {
                    OutlinedTextField(selectedAccount?.name.orEmpty(), {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text("Khata") }, placeholder = { Text("Choose a cash or credit Khata") }, singleLine = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(accountExpanded) })
                    ExposedDropdownMenu(expanded = accountExpanded, onDismissRequest = { accountExpanded = false }) {
                        liquidAccounts.forEach { account -> DropdownMenuItem(text = { Text(account.name) }, onClick = { accountId = account.id; accountExpanded = false }) }
                    }
                }
                ExposedDropdownMenuBox(expanded = categoryExpanded, onExpandedChange = { categoryExpanded = !categoryExpanded }) {
                    OutlinedTextField(category, { category = it; categoryExpanded = true }, Modifier.menuAnchor().fillMaxWidth(), label = { Text("Varga") }, singleLine = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryExpanded) })
                    ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) { options.filter { it.name.contains(category, ignoreCase = true) }.forEach { option -> DropdownMenuItem(text = { IconifiedCategoryLabel(option.name, option.iconKey) }, onClick = { category = option.name; categoryExpanded = false }) } }
                }
                if (recentCategories.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recentCategories.size) { index ->
                            val recentCategory = recentCategories[index]
                            val iconKey = categories.firstOrNull { it.name == recentCategory }?.iconKey
                            FilterChip(selected = category == recentCategory, onClick = { category = recentCategory }, label = { IconifiedCategoryLabel(recentCategory, iconKey, compact = true) })
                        }
                    }
                }
                ExposedDropdownMenuBox(expanded = payeeExpanded, onExpandedChange = { payeeExpanded = !payeeExpanded }) {
                    OutlinedTextField(payee, { payee = it; payeeExpanded = true }, Modifier.menuAnchor().fillMaxWidth(), label = { Text("Vyakti (optional)") }, singleLine = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(payeeExpanded) })
                    ExposedDropdownMenu(expanded = payeeExpanded, onDismissRequest = { payeeExpanded = false }) {
                        payees.filter { it.name.contains(payee, ignoreCase = true) }.forEach { savedPayee ->
                            DropdownMenuItem(text = { Column { Text(savedPayee.name); savedPayee.defaultCategory?.let { Text("Default Varga: $it", style = MaterialTheme.typography.labelSmall) } } }, onClick = { payee = savedPayee.name; savedPayee.defaultCategory?.let { category = it }; payeeExpanded = false })
                        }
                    }
                }
                if (recentPayees.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recentPayees.size) { index ->
                            val recentPayee = recentPayees[index]
                            AssistChip(onClick = { payee = recentPayee }, label = { Text(recentPayee) })
                        }
                    }
                }
                OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Description (optional)") }, minLines = 2)
            }
        },
        confirmButton = { Button(onClick = { onSave(amount, payee, category, description, direction, accountId, datePickerState.selectedDateMillis ?: System.currentTimeMillis()) }, enabled = amount.isNotBlank() && accountId.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = { TextButton(onClick = { showDatePicker = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = datePickerState) }
    }
}

enum class LedgerFilter(val label: String) { ALL("All"), SPENT("Spent"), INCOME("Income") }

enum class LedgerRange(val label: String, val description: String) {
    WEEK("7 days", "Last 7 days"),
    MONTH("This month", "This month"),
    QUARTER("3 months", "Last 3 months"),
    HALF_YEAR("6 months", "Last 6 months"),
    YEAR("This year", "This year"),
    RECENT("All", "All recorded activity");

    fun includes(epochMs: Long): Boolean {
        val date = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now()
        return when (this) {
            WEEK -> !date.isBefore(today.minusDays(6))
            MONTH -> date.year == today.year && date.month == today.month
            QUARTER -> !date.isBefore(today.minusMonths(3).plusDays(1))
            HALF_YEAR -> !date.isBefore(today.minusMonths(6).plusDays(1))
            YEAR -> date.year == today.year
            RECENT -> true
        }
    }
}

private fun LedgerRange.previousWindowIncludes(epochMs: Long): Boolean {
    val date = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (this) {
        LedgerRange.WEEK -> date in today.minusDays(13)..today.minusDays(7)
        LedgerRange.MONTH -> {
            val previous = today.minusMonths(1)
            date.year == previous.year && date.month == previous.month
        }
        LedgerRange.QUARTER -> date in today.minusMonths(6).plusDays(1)..today.minusMonths(3)
        LedgerRange.HALF_YEAR -> date in today.minusMonths(12).plusDays(1)..today.minusMonths(6)
        LedgerRange.YEAR -> date.year == today.year - 1
        LedgerRange.RECENT -> false
    }
}

@Composable
private fun LedgerMetric(label: String, value: String, color: Color) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

/** Category glyphs are deliberately label-backed, so their meaning never depends on colour alone. */
@Composable
private fun IconifiedCategoryLabel(category: String, iconKey: String? = null, compact: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        CategoryGlyph(iconKey ?: category, if (compact) 22.dp else 28.dp)
        Text(category, style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyLarge)
    }
}

private val categoryGlyphKeys = listOf("food", "transport", "bills", "health", "shopping", "education", "salary", "freelance", "investment", "gift", "other")

@Composable
private fun CategoryGlyph(category: String, size: androidx.compose.ui.unit.Dp = 24.dp) {
    val glyph = when (category.lowercase()) {
        "food" -> "●"
        "transport" -> "→"
        "bills" -> "▤"
        "health" -> "+"
        "shopping" -> "◇"
        "education" -> "▣"
        "salary" -> "↑"
        "freelance" -> "✦"
        "investment", "investment contribution" -> "↗"
        "gift" -> "♡"
        else -> "•"
    }
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text(glyph, style = if (size <= 22.dp) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall)
        }
    }
}

/** A local-only insight derived from the entries already visible in the encrypted ledger. */
@Composable
private fun SpendingMap(categories: List<Pair<String, Long>>, totalSpent: Long, mostUsedPayee: String?, mostUsedPayeeCount: Int, currencyCode: String) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("SPEND MAP", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("Where your money went", style = MaterialTheme.typography.titleMedium)
            categories.take(3).forEach { (category, amount) ->
                val share = if (totalSpent == 0L) 0f else (amount.toDouble() / totalSpent).toFloat()
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        IconifiedCategoryLabel(category, compact = true)
                        Text(formatMoney(amount, currencyCode), style = MaterialTheme.typography.labelLarge)
                    }
                    LinearProgressIndicator(
                        progress = { share },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
            if (mostUsedPayee != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Most visited: $mostUsedPayee · $mostUsedPayeeCount ${if (mostUsedPayeeCount == 1) "entry" else "entries"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyLedgerState(isFiltered: Boolean) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (isFiltered) "Nothing matches this view" else "Your Vyavahara is ready", style = MaterialTheme.typography.titleMedium)
            Text(if (isFiltered) "Try a different search or filter." else "Record your first Aaya or Vyaya from Prarambha to begin your money story.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun ledgerDayLabel(date: LocalDate, preference: DateFormatPreference): String = when (date) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> formatDate(date, preference)
}

private fun trendDelta(values: List<Long>): Long? =
    if (values.size >= 2) values.last() - values[values.lastIndex - 1] else null

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun NetWorthDashboardScreen(state: MvpFinanceState, onBack: () -> Unit, onOpenPortfolio: () -> Unit) {
    val latestByInvestment = state.investmentHistory.groupBy { it.accountId }.mapValues { (_, values) -> values.maxBy { it.asOfEpochDay } }
    val spending = state.accountBalances.filter { it.kind == AccountKind.SPENDING }.sumOf { it.balancePaise }
    val reserves = state.accountBalances.filter { it.kind == AccountKind.SAVINGS || it.kind == AccountKind.EMERGENCY }.sumOf { it.balancePaise }
    val liabilities = state.accountBalances.filter { it.kind == AccountKind.CREDIT }.sumOf { (-it.balancePaise).coerceAtLeast(0) }
    val investments = latestByInvestment.values.sumOf { it.currentValuePaise }
    val netWorth = spending + reserves + investments - liabilities
    val assetTotal = spending + reserves + investments
    val accountById = state.accounts.associateBy { it.id }
    val allocationByClass = latestByInvestment.entries.groupBy { (id, _) -> accountById[id]?.assetClass ?: AssetClass.OTHER }
        .mapValues { (_, entries) -> entries.sumOf { it.value.currentValuePaise } }
    val trend = state.netWorthHistory.sortedBy { it.asOfEpochDay }.map { it.netWorthPaise }.let { history -> if (history.lastOrNull() == netWorth) history else history + netWorth }
    val latestTrendDelta = trendDelta(trend)
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TopAppBar(title = { Column { Text("Sampada"); Text("Your net worth dashboard", style = MaterialTheme.typography.labelMedium) } }, navigationIcon = { IconButton(onClick = onBack) { Icon(StandardBackIcon, contentDescription = "Back") } }, actions = { TextButton(onClick = onOpenPortfolio) { Text("Nivesha") } }) }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("CURRENT NET WORTH", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(formatMoney(netWorth, state.currencyCode), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Assets ${formatMoney(assetTotal, state.currencyCode)} · Liabilities ${formatMoney(liabilities, state.currencyCode)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Nivesha cost & value", style = MaterialTheme.typography.titleMedium)
                        Text("Your dated check-ins show invested cost against current value.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PortfolioValueChart(state.investmentHistory, state.currencyCode)
                    }
                }
            }
            latestTrendDelta?.let { delta ->
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Latest Sampada move", style = MaterialTheme.typography.titleMedium)
                                Text("Compared with the previous dated snapshot", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(formatMoney(delta, state.currencyCode, includeSign = true), style = MaterialTheme.typography.titleMedium, color = if (delta < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ElevatedCard(Modifier.weight(1f)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("LIQUID", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(formatMoney(spending, state.currencyCode), style = MaterialTheme.typography.titleLarge); Text("Cash & bank", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    ElevatedCard(Modifier.weight(1f)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("RESERVES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(formatMoney(reserves, state.currencyCode), style = MaterialTheme.typography.titleLarge); Text("Savings & emergency", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Nivesha & liabilities", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Investment assets"); Text(formatMoney(investments, state.currencyCode), style = MaterialTheme.typography.titleSmall) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Credit & loans"); Text(formatMoney(-liabilities, state.currencyCode), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = onOpenPortfolio, modifier = Modifier.fillMaxWidth()) { Text("Manage Nivesha") }
                } }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sampada trend", style = MaterialTheme.typography.titleMedium)
                    if (trend.size >= 2) NetWorthSparkline(trend) else Text("Your trend grows as you record monthly Nivesha balances.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
            item { Text("Nivesha by asset class", style = MaterialTheme.typography.titleMedium) }
            if (allocationByClass.isEmpty()) item { ElevatedCard(Modifier.fillMaxWidth()) { Text("Add an investment asset and its first check-in to see allocation here.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) } }
            items(allocationByClass.entries.sortedByDescending { it.value }.size) { index ->
                val (assetClass, amount) = allocationByClass.entries.sortedByDescending { it.value }[index]
                val share = if (investments == 0L) 0f else (amount.toDouble() / investments).toFloat()
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(assetClass.name.replace('_', ' '), style = MaterialTheme.typography.titleSmall); Text(formatMoney(amount, state.currencyCode), style = MaterialTheme.typography.titleSmall) }
                    LinearProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth())
                    Text("${"%.1f".format(share * 100)}% of Nivesha", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
            item { Text("How Sampada is calculated", style = MaterialTheme.typography.titleMedium) }
            item { Text("Liquid cash + reserves + latest Nivesha values − credit and loan liabilities. Dated Nivesha check-ins provide the historical trend; everyday cashflow updates the current total instantly.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PortfolioAndNetWorthScreen(state: MvpFinanceState, onBack: () -> Unit, onOpenNetWorth: () -> Unit, onAddInvestment: () -> Unit, onRecordBalance: () -> Unit, onDeleteSnapshot: (String) -> Unit, onUpdateSnapshot: (String, String, String, String, String, String, String) -> Unit, onUpdateInvestment: (String, String, AccountProductType, AssetClass, String) -> Unit, onArchiveInvestment: (String) -> Unit) {
    var editingSnapshotId by remember { mutableStateOf<String?>(null) }
    var editingInvestmentId by remember { mutableStateOf<String?>(null) }
    var investmentToArchive by remember { mutableStateOf<AccountEntity?>(null) }
    val latestByAsset = state.investmentHistory.groupBy { it.accountId }.mapValues { (_, snapshots) -> snapshots.maxBy { it.asOfEpochDay } }
    val historyByInvestment = state.investmentHistory.groupBy { it.accountId }
    val portfolioValue = latestByAsset.values.sumOf { it.currentValuePaise }
    val portfolioCost = latestByAsset.values.sumOf { it.totalCostPaise }
    val portfolioAbsolutePaise = portfolioValue - portfolioCost
    val portfolioAbsolutePercent = portfolioCost.takeIf { it != 0L }?.let { portfolioAbsolutePaise * 100.0 / it }
    val portfolioXirr = FinancialCalculations.xirrPercent(historyByInvestment.values.flatMap(::cashFlowsForXirr))
    val portfolioTrendDelta = trendDelta(portfolioChartPoints(state.investmentHistory).map { it.valuePaise })
    val netWorth = state.netWorthHistory.maxByOrNull { it.asOfEpochDay }?.netWorthPaise ?: run {
        val reserves = state.accountBalances.filter { it.kind == AccountKind.SAVINGS || it.kind == AccountKind.EMERGENCY }.sumOf { it.balancePaise }
        state.cashPaise + reserves + portfolioValue
    }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, topBar = { TopAppBar(title = { Column { Text("Nivesha & Sampada"); Text("Investments and overall wealth", style = MaterialTheme.typography.labelMedium) } }, navigationIcon = { IconButton(onClick = onBack) { Icon(StandardBackIcon, contentDescription = "Back") } }, actions = { TextButton(onClick = onOpenNetWorth) { Text("Sampada") }; TextButton(onClick = onAddInvestment) { Text("Add") }; TextButton(onClick = onRecordBalance) { Text("Check-in") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("SAMPADA · NET WORTH", style = MaterialTheme.typography.labelLarge)
                        Text(formatMoney(netWorth, state.currencyCode), style = MaterialTheme.typography.displaySmall)
                        HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { Text("NIVESHA VALUE", style = MaterialTheme.typography.labelSmall); Text(formatMoney(portfolioValue, state.currencyCode), style = MaterialTheme.typography.titleMedium) }
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) { Text("TOTAL RETURN", style = MaterialTheme.typography.labelSmall); Text("${formatMoney(portfolioAbsolutePaise, state.currencyCode, includeSign = true)} · ABS ${formatPercent(portfolioAbsolutePercent)}", style = MaterialTheme.typography.titleSmall, color = if (portfolioAbsolutePaise < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer); Text("XIRR ${formatPercent(portfolioXirr)}", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sampada trend", style = MaterialTheme.typography.titleMedium)
                    if (state.netWorthHistory.size >= 2) NetWorthSparkline(state.netWorthHistory.sortedBy { it.asOfEpochDay }.map { it.netWorthPaise }) else Text("Your trend appears after two dated investment check-ins.", style = MaterialTheme.typography.bodySmall)
                } }
            }
            portfolioTrendDelta?.let { delta ->
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Latest Nivesha move", style = MaterialTheme.typography.titleMedium)
                                Text("Change from the previous portfolio check-in", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(formatMoney(delta, state.currencyCode, includeSign = true), style = MaterialTheme.typography.titleMedium, color = if (delta < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            item { Text("Nivesha allocation", style = MaterialTheme.typography.titleMedium) }
            items(latestByAsset.size) { index ->
                val (accountId, snapshot) = latestByAsset.entries.sortedByDescending { it.value.currentValuePaise }[index]
                val account = state.accounts.firstOrNull { it.id == accountId }
                val allocation = if (portfolioValue == 0L) 0.0 else snapshot.currentValuePaise * 100.0 / portfolioValue
                val performance = investmentPerformance(historyByInvestment[accountId].orEmpty())
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text(account?.name ?: "Investment", style = MaterialTheme.typography.titleSmall); Text(account?.productType?.name?.replace('_', ' ') ?: "Asset", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); account?.benchmarkIndexName?.let { Text("Benchmark · $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }; Text("Cost ${formatMoney(snapshot.totalCostPaise, state.currencyCode)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End) { Text(formatMoney(snapshot.currentValuePaise, state.currencyCode), style = MaterialTheme.typography.titleMedium); Text("${"%.1f".format(allocation)}% allocation · ABS ${formatPercent(performance?.absolutePercent)}", style = MaterialTheme.typography.bodySmall, color = if ((performance?.absolutePaise ?: 0L) < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary); Text("XIRR ${formatPercent(performance?.xirrPercent)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    account?.let { investment ->
                        if (editingInvestmentId == investment.id) InlineInvestmentEditor(investment, onCancel = { editingInvestmentId = null }, onSave = { name, product, assetClass, target -> onUpdateInvestment(investment.id, name, product, assetClass, target); editingInvestmentId = null })
                        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { editingInvestmentId = investment.id }) { Text("Modify") }; TextButton(onClick = { investmentToArchive = investment }) { Text("Archive") } }
                    }
                } }
            }
            val unvaluedInvestments = state.accounts.filter { it.kind == AccountKind.INVESTMENT && it.id !in latestByAsset }
            if (unvaluedInvestments.isNotEmpty()) item { Text("Awaiting first check-in", style = MaterialTheme.typography.titleMedium) }
            items(unvaluedInvestments.size) { index ->
                val investment = unvaluedInvestments[index]
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(investment.name, style = MaterialTheme.typography.titleSmall)
                    Text("${investment.productType.name.replace('_', ' ')} · no valuation recorded yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (editingInvestmentId == investment.id) InlineInvestmentEditor(investment, onCancel = { editingInvestmentId = null }, onSave = { name, product, assetClass, target -> onUpdateInvestment(investment.id, name, product, assetClass, target); editingInvestmentId = null })
                    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { editingInvestmentId = investment.id }) { Text("Modify") }; TextButton(onClick = { investmentToArchive = investment }) { Text("Archive") } }
                } }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Nivesha allocation vs target", style = MaterialTheme.typography.titleMedium)
                    AssetClass.entries.filter { assetClass -> state.accounts.any { it.assetClass == assetClass && it.targetAllocationBps > 0 } }.forEach { assetClass ->
                        val actualValue = latestByAsset.entries.filter { (accountId, _) -> state.accounts.firstOrNull { it.id == accountId }?.assetClass == assetClass }.sumOf { it.value.currentValuePaise }
                        val actual = if (portfolioValue == 0L) 0.0 else actualValue * 100.0 / portfolioValue
                        val target = state.accounts.filter { it.assetClass == assetClass }.sumOf { it.targetAllocationBps } / 100.0
                        val difference = actual - target
                        Text("${assetClass.name}: %.1f%% / %.1f%% target · %s %.1f%%".format(actual, target, if (difference >= 0) "over" else "under", kotlin.math.abs(difference)), style = MaterialTheme.typography.bodySmall)
                    }
                } }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Nivesha history", style = MaterialTheme.typography.titleMedium)
                    Text("Dated cost and value check-ins", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val history = state.investmentHistory.sortedWith(compareByDescending<InvestmentBalanceSnapshotEntity> { it.asOfEpochDay }.thenByDescending { it.createdAtEpochMs })
            items(history.size) { index ->
                val snapshot = history[index]
                val account = state.accounts.firstOrNull { it.id == snapshot.accountId }
                val performance = investmentPerformance(historyByInvestment[snapshot.accountId].orEmpty().filter { it.asOfEpochDay <= snapshot.asOfEpochDay })
                val gain = performance?.absolutePaise ?: snapshot.currentValuePaise - snapshot.totalCostPaise
                val gainColor = if (gain < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(account?.name ?: "Investment", style = MaterialTheme.typography.titleSmall)
                            Text(account?.productType?.name?.replace('_', ' ') ?: "Nivesha holding", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            account?.benchmarkIndexName?.let { benchmark -> Text("Benchmark · $benchmark", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                        }
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            Text("AS ON", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(formatDate(LocalDate.ofEpochDay(snapshot.asOfEpochDay), state.dateFormatPreference), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Box(Modifier.weight(1f)) { InvestmentHistoryMetric("COST", formatMoney(snapshot.totalCostPaise, state.currencyCode)) }
                        Box(Modifier.weight(1f)) { InvestmentHistoryMetric("VALUE", formatMoney(snapshot.currentValuePaise, state.currencyCode), alignment = androidx.compose.ui.Alignment.CenterHorizontally) }
                        Box(Modifier.weight(1f)) { InvestmentHistoryMetric("GAIN / LOSS", formatMoney(gain, state.currencyCode, includeSign = true), gainColor, androidx.compose.ui.Alignment.End) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("ABS ${formatPercent(performance?.absolutePercent)}", style = MaterialTheme.typography.labelMedium, color = gainColor)
                        Text("XIRR ${formatPercent(performance?.xirrPercent)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AssistChip(
                        onClick = {},
                        label = { Text("Net contribution ${formatMoney(snapshot.netContributionPaise, state.currencyCode, includeSign = true)}") }
                    )
                    snapshot.note?.takeIf { it.isNotBlank() }?.let { note ->
                        Text(note, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (editingSnapshotId == snapshot.id) {
                        InlineInvestmentSnapshotEditor(
                            snapshot = snapshot,
                            dateFormatPreference = state.dateFormatPreference,
                            onCancel = { editingSnapshotId = null },
                            onSave = { date, cost, value, contribution, note ->
                                onUpdateSnapshot(snapshot.id, snapshot.accountId, date, cost, value, contribution, note)
                                editingSnapshotId = null
                            }
                        )
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { editingSnapshotId = snapshot.id }) { Text("Modify") }
                            TextButton(onClick = { onDeleteSnapshot(snapshot.id) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                        }
                    }
                } }
            }
        }
    }
    investmentToArchive?.let { account -> AlertDialog(onDismissRequest = { investmentToArchive = null }, title = { Text("Archive Nivesha?") }, text = { Text("${account.name} will be hidden from active portfolio tracking. Its dated history remains safely in this encrypted database.") }, confirmButton = { Button(onClick = { onArchiveInvestment(account.id); investmentToArchive = null }) { Text("Archive") } }, dismissButton = { TextButton(onClick = { investmentToArchive = null }) { Text("Cancel") } }) }
}

@Composable
private fun InvestmentHistoryMetric(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface, alignment: androidx.compose.ui.Alignment.Horizontal = androidx.compose.ui.Alignment.Start) {
    Column(horizontalAlignment = alignment) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, color = color, maxLines = 1)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun InlineInvestmentEditor(account: AccountEntity, onCancel: () -> Unit, onSave: (String, AccountProductType, AssetClass, String) -> Unit) {
    var name by remember(account.id) { mutableStateOf(account.name) }
    var product by remember(account.id) { mutableStateOf(account.productType) }
    var assetClass by remember(account.id) { mutableStateOf(account.assetClass) }
    var target by remember(account.id) { mutableStateOf((account.targetAllocationBps / 100.0).toString()) }
    var productExpanded by remember(account.id) { mutableStateOf(false) }
    var classExpanded by remember(account.id) { mutableStateOf(false) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("Modify Nivesha", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nivesha name") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
    ExposedDropdownMenuBox(productExpanded, { productExpanded = !productExpanded }) {
        OutlinedTextField(product.name.replace('_', ' '), {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text("Product type") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(productExpanded) })
        ExposedDropdownMenu(productExpanded, { productExpanded = false }) { investmentProductTypes.forEach { type -> DropdownMenuItem(text = { Text(type.name.replace('_', ' ')) }, onClick = { product = type; assetClass = suggestedAssetClass(type); productExpanded = false }) } }
    }
    ExposedDropdownMenuBox(classExpanded, { classExpanded = !classExpanded }) {
        OutlinedTextField(assetClass.name, {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text("Asset class") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(classExpanded) })
        ExposedDropdownMenu(classExpanded, { classExpanded = false }) { AssetClass.entries.forEach { type -> DropdownMenuItem(text = { Text(type.name) }, onClick = { assetClass = type; classExpanded = false }) } }
    }
    OutlinedTextField(target, { target = it }, Modifier.fillMaxWidth(), label = { Text("Target allocation (%)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onCancel) { Text("Cancel") }; Button(onClick = { onSave(name, product, assetClass, target) }, enabled = name.isNotBlank()) { Text("Save") } }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun InlineInvestmentSnapshotEditor(snapshot: InvestmentBalanceSnapshotEntity, dateFormatPreference: DateFormatPreference, onCancel: () -> Unit, onSave: (String, String, String, String, String) -> Unit) {
    var date by remember(snapshot.id) { mutableStateOf(LocalDate.ofEpochDay(snapshot.asOfEpochDay)) }
    var cost by remember(snapshot.id) { mutableStateOf((snapshot.totalCostPaise / 100.0).toString()) }
    var value by remember(snapshot.id) { mutableStateOf((snapshot.currentValuePaise / 100.0).toString()) }
    var contribution by remember(snapshot.id) { mutableStateOf((snapshot.netContributionPaise / 100.0).toString()) }
    var note by remember(snapshot.id) { mutableStateOf(snapshot.note.orEmpty()) }
    var showDatePicker by remember(snapshot.id) { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = LocalDate.ofEpochDay(snapshot.asOfEpochDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("Modify check-in", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    OutlinedTextField(value = formatDate(date, dateFormatPreference), onValueChange = {}, modifier = Modifier.fillMaxWidth(), readOnly = true, label = { Text("As-on date") }, trailingIcon = { TextButton(onClick = { showDatePicker = true }) { Text("Pick") } }, singleLine = true)
    OutlinedTextField(value = cost, onValueChange = { cost = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Total cost in ₹") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
    OutlinedTextField(value = value, onValueChange = { value = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Current value in ₹") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
    OutlinedTextField(value = contribution, onValueChange = { contribution = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Net contribution in ₹") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
    OutlinedTextField(value = note, onValueChange = { note = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Statement / note") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        Button(onClick = { onSave(date.toString(), cost, value, contribution, note) }) { Text("Save") }
    }
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = { TextButton(onClick = { datePickerState.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; showDatePicker = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = datePickerState) }
    }
}

@Composable
private fun NetWorthSparkline(values: List<Long>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    Canvas(Modifier.fillMaxWidth().height(88.dp)) {
        val min = values.minOrNull() ?: 0L
        val max = values.maxOrNull() ?: min + 1L
        val spread = (max - min).coerceAtLeast(1L).toFloat()
        val path = Path()
        val fill = Path()
        values.forEachIndexed { index, value ->
            val x = if (values.size == 1) 0f else size.width * index / (values.size - 1)
            val y = size.height - ((value - min) / spread * size.height)
            if (index == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, size.height)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(size.width, size.height)
        fill.close()
        drawPath(fill, fillColor)
        drawPath(path, lineColor, style = Stroke(width = 5f, cap = StrokeCap.Round))
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AccountSetupDialog(initialProduct: AccountProductType = AccountProductType.CASH, onDismiss: () -> Unit, onSave: (String, AccountProductType, AssetClass, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var openingBalance by remember { mutableStateOf("") }
    var product by remember(initialProduct) { mutableStateOf(initialProduct) }
    var assetClass by remember(initialProduct) { mutableStateOf(suggestedAssetClass(initialProduct)) }
    var expanded by remember { mutableStateOf(false) }
    var assetClassExpanded by remember { mutableStateOf(false) }
    var targetPercent by remember { mutableStateOf("0") }
    val isInvestment = product in investmentProductTypes
    val purpose = when {
        isInvestment -> "Investment holding: record dated cost and value check-ins in Nivesha. It does not become daily Aaya or Vyaya."
        product == AccountProductType.CREDIT_CARD || product == AccountProductType.LOAN -> "Liability Khata: record what you owe. Its balance reduces true available cash."
        else -> "Daily Khata: use this cash or bank place for Aaya, Vyaya, and your everyday balance."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set up Khata") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Set up a daily Khata, a liability, or an investment holding.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Khata name") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
                ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
                    OutlinedTextField(product.name.replace('_', ' '), {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text("Khata type") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
                    ExposedDropdownMenu(expanded, { expanded = false }) {
                        Text("DAILY KHATAS", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall)
                        listOf(AccountProductType.CASH, AccountProductType.BANK).forEach { type -> DropdownMenuItem(text = { Text(type.name.replace('_', ' ')) }, onClick = { product = type; assetClass = suggestedAssetClass(type); expanded = false }) }
                        HorizontalDivider()
                        Text("LIABILITIES", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall)
                        listOf(AccountProductType.CREDIT_CARD, AccountProductType.LOAN).forEach { type -> DropdownMenuItem(text = { Text(type.name.replace('_', ' ')) }, onClick = { product = type; assetClass = suggestedAssetClass(type); expanded = false }) }
                        HorizontalDivider()
                        Text("INVESTMENT HOLDINGS", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall)
                        investmentProductTypes.forEach { type -> DropdownMenuItem(text = { Text(type.name.replace('_', ' ')) }, onClick = { product = type; assetClass = suggestedAssetClass(type); expanded = false }) }
                    }
                }
                ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(purpose, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isInvestment) {
                    ExposedDropdownMenuBox(assetClassExpanded, { assetClassExpanded = !assetClassExpanded }) {
                        OutlinedTextField(assetClass.name, {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text("Asset class") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(assetClassExpanded) })
                        ExposedDropdownMenu(assetClassExpanded, { assetClassExpanded = false }) {
                            AssetClass.entries.forEach { type -> DropdownMenuItem(text = { Text(type.name) }, onClick = { assetClass = type; assetClassExpanded = false }) }
                        }
                    }
                    OutlinedTextField(targetPercent, { targetPercent = it }, Modifier.fillMaxWidth(), label = { Text("Target allocation (%)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                }
                OutlinedTextField(openingBalance, { openingBalance = it }, Modifier.fillMaxWidth(), label = { Text(if (isInvestment) "Starting cost / value in ₹" else if (product == AccountProductType.CREDIT_CARD || product == AccountProductType.LOAN) "Current amount owed in ₹" else "Opening balance in ₹") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            }
        },
        confirmButton = { Button(onClick = { onSave(name, product, assetClass, targetPercent, openingBalance) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun InvestmentBalanceCheckInDialog(accounts: List<AccountEntity>, dateFormatPreference: DateFormatPreference, initialAccountId: String? = null, onDismiss: () -> Unit, onSave: (String, String, String, String, String, String) -> Unit) {
    var account by remember(accounts, initialAccountId) { mutableStateOf(accounts.firstOrNull { it.id == initialAccountId } ?: accounts.first()) }
    var accountExpanded by remember { mutableStateOf(false) }
    var asOfDate by remember { mutableStateOf(LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    var cost by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var contribution by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Investment balance check-in") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Enter the statement or market balance as on a date. This is a Nivesha valuation, not a Vyaya.", style = MaterialTheme.typography.bodySmall)
                ExposedDropdownMenuBox(accountExpanded, { accountExpanded = !accountExpanded }) {
                OutlinedTextField(account.name, {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text("Nivesha") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(accountExpanded) })
                    ExposedDropdownMenu(accountExpanded, { accountExpanded = false }) {
                        accounts.forEach { item -> DropdownMenuItem(text = { Text("${item.productType.name.replace('_', ' ')} · ${item.name}") }, onClick = { account = item; accountExpanded = false }) }
                    }
                }
                OutlinedTextField(
                    value = formatDate(asOfDate, dateFormatPreference),
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    label = { Text("As-on date") },
                    trailingIcon = { TextButton(onClick = { showDatePicker = true }) { Text("Pick") } },
                    singleLine = true
                )
                OutlinedTextField(cost, { cost = it }, Modifier.fillMaxWidth(), label = { Text("Total cost / invested amount in ₹") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text("Current value in ₹") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                OutlinedTextField(contribution, { contribution = it }, Modifier.fillMaxWidth(), label = { Text("Net contribution since prior check-in in ₹") }, placeholder = { Text("0 if none") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Statement / note (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
            }
        },
        confirmButton = { Button(onClick = { onSave(account.id, asOfDate.toString(), cost, value, contribution, note) }, enabled = cost.isNotBlank() && value.isNotBlank()) { Text("Save balance") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { selected ->
                        asOfDate = Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = datePickerState) }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun InvestmentContributionDialog(accounts: List<AccountEntity>, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var account by remember(accounts) { mutableStateOf(accounts.first()) }
    var expanded by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var payee by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Contribute to investment") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This records Vyaya from your cash/bank Khata and increases the selected Nivesha cost and value today.", style = MaterialTheme.typography.bodySmall)
            ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
                OutlinedTextField(account.name, {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text("Nivesha") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
                ExposedDropdownMenu(expanded, { expanded = false }) { accounts.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { account = item; expanded = false }) } }
            }
            OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text("Contribution in ₹") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            OutlinedTextField(payee, { payee = it }, Modifier.fillMaxWidth(), label = { Text("Provider / payee (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
        } },
        confirmButton = { Button(onClick = { onSave(account.id, amount, payee) }, enabled = amount.isNotBlank()) { Text("Record contribution") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
