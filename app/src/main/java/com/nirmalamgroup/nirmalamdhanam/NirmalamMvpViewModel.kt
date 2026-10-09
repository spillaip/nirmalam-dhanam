package com.nirmalamgroup.nirmalamdhanam

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.net.toUri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import com.nirmalamgroup.nirmalamdhanam.data.ai.NirmalamAiClient
import com.nirmalamgroup.nirmalamdhanam.data.ai.NirmalamAiInsight
import com.nirmalamgroup.nirmalamdhanam.data.ai.NirmalamAiPreferences
import com.nirmalamgroup.nirmalamdhanam.data.ai.prepareNirmalamAiContext
import com.nirmalamgroup.nirmalamdhanam.data.local.*
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.AccountRolePolicy
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.CoolDownTankInterceptorUseCase
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.MoneyFormatter
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.FinancialCalculations
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.InvestmentPerformanceMetric
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.InvestmentDeltaEngine
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.FinancialTimelineBuilder
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.ExplainableInsightEngine
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.FinancialHealthCalculator
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.GoalProgressCalculator
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.GoalAllocationPolicy
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.FinancialTimeMachine
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.LocalFinancialCopilot
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.LocalDayClock
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.CopilotResult
import com.nirmalamgroup.nirmalamdhanam.ui.components.CoolDownTankCard
import com.nirmalamgroup.nirmalamdhanam.ui.components.NeurodiverseModeToggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

private data class DayDetails(val envelopes: List<EnvelopeEntity>, val spent: Long, val holding: List<TransactionEntity>, val recent: List<TransactionEntity>, val all: List<TransactionEntity>)
private data class AccountDirectory(val accounts: List<AccountEntity>, val balances: List<AccountBalance>, val categories: List<CategoryEntity>, val payees: List<PayeeEntity>)
private data class PortfolioDirectory(val latest: List<InvestmentBalanceSnapshotEntity>, val history: List<InvestmentBalanceSnapshotEntity>, val netWorth: List<NetWorthSnapshotEntity>, val goals: List<GoalEntity>, val allocations: List<GoalAllocationEntity>)
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
                        FinanceDatabaseSeeder.seedReferenceData(opened)
                        // Synthetic balances/transactions are useful for previews and tests, but a
                        // production finance ledger must start with only the user's own money data.
                        if (BuildConfig.DEBUG) FinanceDatabaseSeeder.seedDemoDataIfEmpty(opened)
                    }
                }
                opened.accountDao().getAll().filter { it.kind == AccountKind.INVESTMENT }.forEach { InvestmentLedgerService.recalculateDeltas(opened, it.id) }
                database = opened
                val sessionPrefs = app.getSharedPreferences("dhanam_session", android.content.Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                val previousOpen = sessionPrefs.getLong("last_opened_at", 0L).takeIf { it > 0L } ?: now - 7L * 24 * 60 * 60 * 1000
                sessionPrefs.edit().putLong("last_opened_at", now).apply()
                observation?.cancel()
                observation = viewModelScope.launch {
                    // "Today" follows the device's current local calendar day, not UTC.
                    // Re-check periodically so midnight and timezone changes take effect without
                    // requiring the encrypted database (or app) to be reopened.
                    val spentToday = flow {
                        while (true) {
                            val nowEpochMs = System.currentTimeMillis()
                            val window = LocalDayClock.window(nowEpochMs)
                            emit(window)
                            delay(LocalDayClock.delayUntilBoundaryRecheck(nowEpochMs, window))
                        }
                    }.distinctUntilChanged()
                        .flatMapLatest { window ->
                            opened.transactionDao().observeSpentBetween(
                                window.startEpochMs,
                                window.endEpochMs,
                            )
                        }
                    val dayDetails = combine(
                        opened.envelopeDao().observeActive(),
                        spentToday,
                        opened.transactionDao().observeHoldingTank(),
                        opened.transactionDao().observeRecent(),
                        opened.transactionDao().observeAll()
                    ) { envelopes, spent, holding, recent, all -> DayDetails(envelopes, spent, holding, recent, all) }
                    val accountDetails = combine(opened.accountDao().observeActive(), opened.accountDao().observeBalances(), opened.categoryDao().observeAll(), opened.payeeDao().observeAll()) { accounts, balances, categories, payees -> AccountDirectory(accounts, balances, categories, payees) }
                    val portfolioDetails = combine(opened.investmentBalanceSnapshotDao().observeLatestForAll(), opened.investmentBalanceSnapshotDao().observeAll(), opened.netWorthSnapshotDao().observeAll(), opened.goalDao().observeActive(), opened.goalAllocationDao().observeAll()) { latest, history, netWorth, goals, allocations -> PortfolioDirectory(latest, history, netWorth, goals, allocations) }
                    combine(opened.accountDao().observeCashPosition(), accountDetails, opened.configDao().observe(), dayDetails, portfolioDetails) { cash, accountDetailsValue, config, day, portfolio ->
                        val dailyLimit = day.envelopes.firstOrNull { it.type == EnvelopeType.WANTS }?.dailyLimitPaise ?: 50_000
                        MvpFinanceState(isUnlocked = true, isLoading = false, cashPaise = cash.trueAvailableCashPaise, accounts = accountDetailsValue.accounts, categories = accountDetailsValue.categories, payees = accountDetailsValue.payees, currencyCode = config?.currencyCode ?: "INR", dateFormatPreference = config?.dateFormatPreference ?: DateFormatPreference.DEVICE_LOCALE, savedLedgerRange = config?.savedLedgerRange ?: "MONTH", savedLedgerFilter = config?.savedLedgerFilter ?: "ALL", savedLedgerAccountId = config?.savedLedgerAccountId, savedLedgerCategoryName = config?.savedLedgerCategoryName, neurodiverseModeEnabled = config?.neurodiverseModeEnabled ?: false, hourlyRatePaise = config?.hourlyRatePaise ?: 10_000, safeToSpendTodayPaise = FinancialCalculations.safeToSpend(dailyLimit, day.spent), todaySpentPaise = day.spent, holdingTank = day.holding, accountBalances = accountDetailsValue.balances, investmentSnapshots = portfolio.latest, investmentHistory = portfolio.history, netWorthHistory = portfolio.netWorth, recentTransactions = day.recent, allTransactions = day.all, goals = portfolio.goals, goalAllocations = portfolio.allocations, changesSinceEpochMs = previousOpen, showInvestmentPerformance = _state.value.showInvestmentPerformance, nirmalamAiReady = NirmalamAiPreferences(app).isReady(), nirmalamAiLoading = _state.value.nirmalamAiLoading, nirmalamAiResponse = _state.value.nirmalamAiResponse, csvImportPlan = _state.value.csvImportPlan, message = _state.value.message)
                    }.catch { error -> emit(MvpFinanceState(message = "Could not read the encrypted database: ${error.message}")) }
                        .collect { _state.value = it }
                }
            } catch (_: Throwable) {
                _state.update { it.copy(isLoading = false, message = "Unable to unlock this database. Check your passphrase.") }
            }
        }
    }

    fun createAccount(name: String, productType: AccountProductType, assetClass: AssetClass, targetPercentText: String, openingBalanceText: String, cashKind: AccountKind, onCreated: (AccountEntity) -> Unit = {}) = viewModelScope.launch(Dispatchers.IO) {
        val balance = runCatching { BigDecimal(openingBalanceText.trim().ifBlank { "0" }).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
        val targetBps = runCatching { BigDecimal(targetPercentText.trim().ifBlank { "0" }).movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact() }.getOrNull()
        if (balance == null || balance < 0 || targetBps == null || targetBps !in 0..10_000 || name.isBlank()) { _state.update { it.copy(message = "Enter a name, valid opening balance, and target between 0% and 100%.") }; return@launch }
        val kind = AccountRolePolicy.resolve(productType, cashKind)
        val storedBalance = if (productType == AccountProductType.CREDIT_CARD || productType == AccountProductType.LOAN) -balance else balance
        val benchmark = suggestedBenchmark(name, productType)
        val account = AccountEntity(UUID.randomUUID().toString(), name.trim(), kind, productType, assetClass, targetBps, storedBalance, benchmarkIndexName = benchmark?.indexName, benchmarkTrackingMethod = benchmark?.method ?: BenchmarkTrackingMethod.NONE)
        val opened = database ?: return@launch
        opened.accountDao().upsert(account)
        viewModelScope.launch { onCreated(account) }
    }
    fun updateAccount(accountId: String, name: String, productType: AccountProductType, assetClass: AssetClass, targetPercentText: String, cashKind: AccountKind) = viewModelScope.launch(Dispatchers.IO) {
        val targetBps = runCatching { BigDecimal(targetPercentText.trim().ifBlank { "0" }).movePointRight(2).intValueExact() }.getOrNull()
        val opened = database ?: return@launch
        val account = state.value.accounts.firstOrNull { it.id == accountId } ?: return@launch
        if (name.isBlank() || targetBps == null || targetBps !in 0..10_000) { _state.update { it.copy(message = "Use a name and a target between 0% and 100%.") }; return@launch }
        val kind = AccountRolePolicy.resolve(productType, cashKind)
        val benchmark = suggestedBenchmark(name, productType)
        opened.accountDao().upsert(account.copy(name = name.trim(), kind = kind, productType = productType, assetClass = assetClass, targetAllocationBps = targetBps, benchmarkIndexName = benchmark?.indexName, benchmarkTrackingMethod = benchmark?.method ?: BenchmarkTrackingMethod.NONE))
    }
    fun archiveInvestmentAccount(accountId: String) = viewModelScope.launch(Dispatchers.IO) { database?.accountDao()?.archive(accountId) }
    fun exportInterchangeReport(destination: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val opened = database ?: return@launch
        when (val result = DhanamPortableFileManager(app, opened).exportTo(destination)) {
            is DhanamFileResult.Exported -> _state.update { it.copy(message = "Portable .dhanam file exported (${result.recordCount} records).") }
            is DhanamFileResult.Failure -> _state.update { it.copy(message = result.message) }
            else -> Unit
        }
    }
    fun importInterchangeReport(source: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val opened = database ?: return@launch
        when (val result = DhanamPortableFileManager(app, opened).importFrom(source)) {
            is DhanamFileResult.Imported -> {
                result.summary.let { summary -> _state.update { it.copy(message = ".dhanam import complete: ${summary.totalRecords} core records merged atomically.") } }
                opened.accountDao().getAll().filter { it.kind == AccountKind.INVESTMENT }.forEach { InvestmentLedgerService.recalculateDeltas(opened, it.id) }
            }
            is DhanamFileResult.Failure -> _state.update { it.copy(message = result.message) }
            else -> Unit
        }
    }
    fun createGoal(name: String, targetText: String, targetDateText: String, accountId: String?) = viewModelScope.launch(Dispatchers.IO) {
        val target = runCatching { BigDecimal(targetText.trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
        val targetDate = targetDateText.trim().takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it).toEpochDay() }.getOrNull() }
        if (name.isBlank() || target == null || target <= 0 || (targetDateText.isNotBlank() && targetDate == null)) {
            _state.update { it.copy(message = "Enter a goal name, positive target amount, and optional YYYY-MM-DD date.") }; return@launch
        }
        val opened = database ?: return@launch
        val goalId = UUID.randomUUID().toString()
        opened.withTransaction {
            opened.goalDao().upsert(GoalEntity(goalId, name.trim(), target, targetDate))
            accountId?.let { opened.goalAllocationDao().upsert(GoalAllocationEntity(UUID.randomUUID().toString(), goalId, it, 10_000)) }
        }
    }
    fun archiveGoal(goalId: String) = viewModelScope.launch(Dispatchers.IO) { database?.goalDao()?.archive(goalId) }

    fun setGoalAllocation(goalId: String, accountId: String, percentText: String) = viewModelScope.launch(Dispatchers.IO) {
        val allocationBps = runCatching {
            BigDecimal(percentText.trim())
                .multiply(BigDecimal(100))
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact()
        }.getOrNull()
        if (allocationBps == null || allocationBps !in 1..10_000) {
            _state.update { it.copy(message = "Goal allocation must be greater than 0% and no more than 100%.") }
            return@launch
        }

        val opened = database ?: return@launch
        val error = opened.withTransaction {
            val goal = opened.goalDao().getById(goalId)
                ?: return@withTransaction "That goal is no longer available."
            if (goal.isArchived) return@withTransaction "Archived goals cannot receive new funding links."

            val account = opened.accountDao().getById(accountId)
                ?: return@withTransaction "That funding source is no longer available."
            if (account.isArchived || account.kind == AccountKind.CREDIT) {
                return@withTransaction "Choose an active non-liability Khata or Nivesha source."
            }

            val allGoals = opened.goalDao().getAll()
            val allAllocations = opened.goalAllocationDao().getAll()
            val maxAvailableBps = GoalAllocationPolicy.maxAvailableBpsForGoal(
                accountId = accountId,
                goalId = goalId,
                goals = allGoals,
                allocations = allAllocations,
            )
            if (allocationBps > maxAvailableBps) {
                val availablePercent = maxAvailableBps / 100.0
                return@withTransaction "That source is already linked to other active goals. At most ${"%.1f".format(availablePercent)}% remains available."
            }

            val existing = allAllocations.firstOrNull { it.goalId == goalId && it.accountId == accountId }
            opened.goalAllocationDao().upsert(
                existing?.copy(allocationBps = allocationBps)
                    ?: GoalAllocationEntity(UUID.randomUUID().toString(), goalId, accountId, allocationBps)
            )
            null
        }
        _state.update { it.copy(message = error ?: "Goal funding link updated.") }
    }

    fun removeGoalAllocation(allocationId: String) = viewModelScope.launch(Dispatchers.IO) {
        database?.goalAllocationDao()?.delete(allocationId)
        _state.update { it.copy(message = "Goal funding link removed.") }
    }


    fun exportTransactionsCsv(destination: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val opened = database ?: return@launch
        val transactions = opened.transactionDao().getAll()
        val accountNames = state.value.accounts.associate { it.id to it.name }
        if (CsvTransactionExporter(app).exportTo(destination, transactions, accountNames)) {
            _state.update { it.copy(message = "CSV transaction report exported (${transactions.size} records).") }
        } else {
            _state.update { it.copy(message = "CSV export failed.") }
        }
    }
    fun importTransactionsCsv(source: Uri, accountId: String) = viewModelScope.launch(Dispatchers.IO) {
        val opened = database ?: return@launch
        _state.update { it.copy(csvImportPlan = null, message = null) }
        when (val preview = CsvTransactionExporter(app).previewImport(source, accountId, opened)) {
            is CsvImportPreviewResult.Ready -> _state.update { it.copy(csvImportPlan = preview.plan) }
            is CsvImportPreviewResult.Failure -> _state.update { it.copy(message = preview.message) }
        }
    }

    fun confirmTransactionsCsvImport(actions: Map<Int, CsvImportAction>) = viewModelScope.launch(Dispatchers.IO) {
        val opened = database ?: return@launch
        val plan = state.value.csvImportPlan ?: run {
            _state.update { it.copy(message = "CSV reconciliation preview is no longer available. Choose the CSV file again.") }
            return@launch
        }
        when (val result = CsvTransactionExporter(app).commitImport(plan, actions, opened)) {
            is CsvImportResult.Success -> _state.update {
                it.copy(
                    csvImportPlan = null,
                    message = "CSV reconciliation complete: ${result.imported} new, ${result.replaced} replaced, ${result.skipped} skipped. " +
                        "Review detected ${result.probableDuplicates} probable duplicate(s), ${result.possibleMatches} possible match(es), ${result.conflicts} conflict(s), and ${result.alreadyReconciled} already-reconciled row(s)."
                )
            }
            is CsvImportResult.Failure -> _state.update { it.copy(message = result.message) }
        }
    }

    fun cancelTransactionsCsvImport() {
        _state.update { it.copy(csvImportPlan = null) }
    }

    fun saveInvestmentBalance(accountId: String, asOfDate: String, costText: String, valueText: String, note: String) = viewModelScope.launch(Dispatchers.IO) {
        val date = runCatching { LocalDate.parse(asOfDate.trim()) }.getOrNull()
        fun rupeesToPaise(text: String) = runCatching { BigDecimal(text.trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
        val cost = rupeesToPaise(costText)
        val value = rupeesToPaise(valueText)
        if (date == null || cost == null || value == null || cost < 0 || value < 0) {
            _state.update { it.copy(message = "Use YYYY-MM-DD and valid non-negative cost and value amounts.") }; return@launch
        }
        val opened = database ?: return@launch
        val epochDay = date.toEpochDay()
        if (opened.investmentBalanceSnapshotDao().getForAccountAndDay(accountId, epochDay) != null) {
            _state.update { it.copy(message = "A balance check-in already exists for this Nivesha on that date. Edit that check-in instead.") }; return@launch
        }
        val snapshot = InvestmentBalanceSnapshotEntity(
            id = UUID.randomUUID().toString(), accountId = accountId, asOfEpochDay = epochDay,
            totalCostPaise = cost, currentValuePaise = value, note = note.trim().ifBlank { null }
        )
        opened.withTransaction {
            opened.investmentBalanceSnapshotDao().upsert(snapshot)
            InvestmentLedgerService.recalculateDeltas(opened, accountId)
            InvestmentLedgerService.refreshNetWorthSnapshot(opened, epochDay, state.value.cashPaise, state.value.accountBalances)
        }
    }

    fun updateInvestmentBalance(snapshotId: String, accountId: String, asOfDate: String, costText: String, valueText: String, note: String) = viewModelScope.launch(Dispatchers.IO) {
        val date = runCatching { LocalDate.parse(asOfDate.trim()) }.getOrNull()
        fun rupeesToPaise(text: String) = runCatching { BigDecimal(text.trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
        val cost = rupeesToPaise(costText)
        val value = rupeesToPaise(valueText)
        if (date == null || cost == null || value == null || cost < 0 || value < 0) {
            _state.update { it.copy(message = "Choose a valid date and enter non-negative cost and value amounts.") }; return@launch
        }
        val opened = database ?: return@launch
        val epochDay = date.toEpochDay()
        val collision = opened.investmentBalanceSnapshotDao().getForAccountAndDay(accountId, epochDay)
        if (collision != null && collision.id != snapshotId) {
            _state.update { it.copy(message = "A balance check-in already exists for this Nivesha on that date.") }; return@launch
        }
        val snapshot = InvestmentBalanceSnapshotEntity(
            id = snapshotId, accountId = accountId, asOfEpochDay = epochDay,
            totalCostPaise = cost, currentValuePaise = value, note = note.trim().ifBlank { null }
        )
        opened.withTransaction {
            opened.investmentBalanceSnapshotDao().upsert(snapshot)
            InvestmentLedgerService.recalculateDeltas(opened, accountId)
            InvestmentLedgerService.refreshNetWorthSnapshot(opened, epochDay, state.value.cashPaise, state.value.accountBalances)
        }
    }

    fun contributeToInvestment(accountId: String, amountText: String, payee: String) = viewModelScope.launch(Dispatchers.IO) {
        val amount = runCatching { BigDecimal(amountText.trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
        val cashAccount = state.value.accounts.firstOrNull { it.kind in AccountRolePolicy.contributionSourceKinds }
        if (amount == null || amount <= 0 || cashAccount == null) { _state.update { it.copy(message = "Create a cash/bank account and enter a valid contribution.") }; return@launch }
        val opened = database ?: return@launch
        val today = LocalDate.now().toEpochDay()
        opened.withTransaction {
            val label = payee.ifBlank { "Investment contribution" }
            opened.transactionDao().upsert(TransactionEntity(UUID.randomUUID().toString(), cashAccount.id, amount, TransactionDirection.DEBIT, merchant = label, category = "Investment contribution", payee = label, envelopeType = EnvelopeType.INVESTMENT))
            val latest = opened.investmentBalanceSnapshotDao().getLatest(accountId)
            val sameDay = latest?.asOfEpochDay == today
            val snapshot = InvestmentBalanceSnapshotEntity(
                id = if (sameDay) latest!!.id else UUID.randomUUID().toString(),
                accountId = accountId,
                asOfEpochDay = today,
                totalCostPaise = (latest?.totalCostPaise ?: 0L) + amount,
                currentValuePaise = (latest?.currentValuePaise ?: 0L) + amount,
                note = "Contribution"
            )
            opened.investmentBalanceSnapshotDao().upsert(snapshot)
            InvestmentLedgerService.recalculateDeltas(opened, accountId)
            InvestmentLedgerService.refreshNetWorthSnapshot(opened, today, state.value.cashPaise, state.value.accountBalances, cashAdjustmentPaise = -amount)
        }
    }

    fun recordTransaction(amountText: String, payee: String, category: String, description: String, direction: TransactionDirection, accountId: String, occurredAtEpochMs: Long) {
        val paise = runCatching { BigDecimal(amountText.trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
        val account = state.value.accounts.firstOrNull { it.id == accountId && AccountRolePolicy.supportsTransactions(it.kind) }
        if (paise == null || paise <= 0 || account == null) { _state.update { it.copy(message = "Choose a transactional Khata and enter a valid amount.") }; return }
        viewModelScope.launch(Dispatchers.IO) {
            val opened = database ?: return@launch
            val config = opened.configDao().observe().first() ?: return@launch
            val resolvedPayee = payee.ifBlank { if (direction == TransactionDirection.CREDIT) "Unlabelled income" else "Unlabelled expense" }
            val resolvedCategory = category.trim().ifBlank { if (direction == TransactionDirection.CREDIT) "Income" else "Uncategorised" }
            val categoryMeta = opened.categoryDao().getByName(resolvedCategory)
            val envelope = when {
                direction == TransactionDirection.CREDIT -> null
                categoryMeta?.priority == CategoryPriority.WANT -> EnvelopeType.WANTS
                else -> EnvelopeType.NEEDS
            }
            val transaction = coolDown(
                TransactionEntity(
                    id = UUID.randomUUID().toString(), accountId = account.id, amountPaise = paise,
                    direction = direction, merchant = resolvedPayee, category = resolvedCategory,
                    payee = resolvedPayee, description = description.ifBlank { null }, envelopeType = envelope,
                    occurredAtEpochMs = occurredAtEpochMs
                ),
                config.impulseCoolDownThresholdPaise,
                categoryMeta?.priority ?: CategoryPriority.NEED
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
        val prepared = prepareNirmalamAiContext(
            insight = insight,
            cashPaise = snapshot.cashPaise,
            transactions = snapshot.allTransactions,
            investmentHistory = snapshot.investmentHistory,
            accounts = snapshot.accounts,
            balances = snapshot.accountBalances,
            categories = snapshot.categories
        )
        if (!prepared.available) {
            _state.update { it.copy(nirmalamAiLoading = false, nirmalamAiResponse = null, message = "${insight.title} is unavailable. ${prepared.unavailableReason}") }
            return@launch
        }
        _state.update { it.copy(nirmalamAiLoading = true, nirmalamAiResponse = null) }
        NirmalamAiClient.request(settings, apiKey, insight, prepared.summary)
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
    fun saveCategory(name: String, direction: TransactionDirection, iconKey: String, priority: CategoryPriority, nature: CategoryNature) = viewModelScope.launch(Dispatchers.IO) {
        val clean = name.trim()
        if (clean.isBlank()) { _state.update { it.copy(message = "Enter a category name.") }; return@launch }
        val dao = database?.categoryDao() ?: return@launch
        val current = dao.getByName(clean)
        dao.upsert(current?.copy(transactionDirection = direction, iconKey = iconKey, priority = priority, nature = nature) ?: CategoryEntity("user-${UUID.randomUUID()}", clean, direction, iconKey = iconKey, priority = priority, nature = nature))
    }
    fun updateCategory(id: String, name: String, direction: TransactionDirection, iconKey: String, priority: CategoryPriority, nature: CategoryNature) = viewModelScope.launch(Dispatchers.IO) {
        val clean = name.trim()
        val opened = database ?: return@launch
        val existing = opened.categoryDao().getById(id) ?: return@launch
        val duplicate = opened.categoryDao().getByName(clean)
        if (clean.isBlank() || (duplicate != null && duplicate.id != id)) { _state.update { it.copy(message = "Choose a unique Varga name.") }; return@launch }
        opened.withTransaction {
            opened.categoryDao().upsert(existing.copy(name = clean, transactionDirection = direction, iconKey = iconKey, priority = priority, nature = nature))
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
    fun confirmPurchase(transaction: TransactionEntity) = viewModelScope.launch(Dispatchers.IO) { database?.transactionDao()?.confirmHoldingTank(transaction.id, System.currentTimeMillis()) }
    fun discardPurchase(transaction: TransactionEntity) = viewModelScope.launch(Dispatchers.IO) { database?.transactionDao()?.discardHoldingTank(transaction.id) }
    fun deleteInvestmentSnapshot(snapshotId: String) = viewModelScope.launch(Dispatchers.IO) {
        val opened = database ?: return@launch
        val snapshot = opened.investmentBalanceSnapshotDao().getById(snapshotId) ?: return@launch
        opened.withTransaction {
            opened.investmentBalanceSnapshotDao().delete(snapshotId)
            InvestmentLedgerService.recalculateDeltas(opened, snapshot.accountId)
            InvestmentLedgerService.refreshNetWorthSnapshot(opened, snapshot.asOfEpochDay, state.value.cashPaise, state.value.accountBalances)
        }
    }
    fun deleteTransaction(transactionId: String) = viewModelScope.launch(Dispatchers.IO) { database?.transactionDao()?.delete(transactionId) }
    fun updateTransaction(transactionId: String, amountText: String, payee: String, category: String, description: String, direction: TransactionDirection) = viewModelScope.launch(Dispatchers.IO) {
        val amount = runCatching { BigDecimal(amountText.trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
        if (amount == null || amount <= 0) { _state.update { it.copy(message = "Enter a valid amount.") }; return@launch }
        val opened = database ?: return@launch
        val dao = opened.transactionDao()
        val existing = dao.getById(transactionId) ?: return@launch
        val config = opened.configDao().observe().first() ?: return@launch
        val resolvedPayee = payee.trim().ifBlank { if (direction == TransactionDirection.CREDIT) "Unlabelled Aaya" else "Unlabelled Vyaya" }
        val resolvedCategory = category.trim().ifBlank { if (direction == TransactionDirection.CREDIT) "Income" else "Uncategorised" }
        opened.withTransaction {
            val categoryMeta = opened.categoryDao().getByName(resolvedCategory)
            val updated = coolDown(
                existing.copy(
                    amountPaise = amount, direction = direction, merchant = resolvedPayee, payee = resolvedPayee,
                    category = resolvedCategory, description = description.trim().ifBlank { null }
                ),
                config.impulseCoolDownThresholdPaise,
                categoryMeta?.priority ?: CategoryPriority.NEED
            )
            dao.upsert(updated)
            if (categoryMeta == null) opened.categoryDao().upsert(CategoryEntity("user-${UUID.randomUUID()}", resolvedCategory, direction))
            if (opened.payeeDao().getByName(resolvedPayee) == null) opened.payeeDao().upsert(PayeeEntity("payee-${UUID.randomUUID()}", resolvedPayee, resolvedCategory))
        }
    }
    fun setShowInvestmentPerformance(show: Boolean) { _state.update { it.copy(showInvestmentPerformance = show) } }
    fun clearMessage() { _state.update { it.copy(message = null) } }
    override fun onCleared() { observation?.cancel(); database?.close() }
}

