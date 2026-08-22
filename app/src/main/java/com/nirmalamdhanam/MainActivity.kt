package com.nirmalamgroup.nirmalamdhanam

import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.Canvas
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import com.nirmalamgroup.nirmalamdhanam.data.local.*
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.CoolDownTankInterceptorUseCase
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.LaborHourConversionUseCase
import com.nirmalamgroup.nirmalamdhanam.ui.components.CoolDownTankCard
import com.nirmalamgroup.nirmalamdhanam.ui.components.NeurodiverseModeToggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.Locale

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
    val neurodiverseModeEnabled: Boolean = false,
    val safeToSpendTodayPaise: Long = 50_000,
    val todaySpentPaise: Long = 0,
    val holdingTank: List<TransactionEntity> = emptyList(),
    val accountBalances: List<AccountBalance> = emptyList(),
    val investmentSnapshots: List<InvestmentBalanceSnapshotEntity> = emptyList(),
    val investmentHistory: List<InvestmentBalanceSnapshotEntity> = emptyList(),
    val netWorthHistory: List<NetWorthSnapshotEntity> = emptyList(),
    val recentTransactions: List<TransactionEntity> = emptyList(),
    val message: String? = null
)

private data class DayDetails(val envelopes: List<EnvelopeEntity>, val spent: Long, val holding: List<TransactionEntity>, val recent: List<TransactionEntity>)

/**
 * Public because Android's default [ViewModelProvider] factory creates it through reflection.
 * Keeping this type private prevents the launcher activity from being created at runtime.
 */
class NirmalamMvpViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.applicationContext
    private val _state = MutableStateFlow(MvpFinanceState())
    internal val state: StateFlow<MvpFinanceState> = _state.asStateFlow()
    private var database: NirmalamDatabase? = null
    private var observation: Job? = null
    private val coolDown = CoolDownTankInterceptorUseCase()
    private val laborHours = LaborHourConversionUseCase()

    fun unlock(passphrase: String) {
        if (passphrase.length < 8) { _state.update { it.copy(message = "Use at least 8 characters for your passphrase.") }; return }
        _state.update { it.copy(isLoading = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val opened = NirmalamDatabase.create(app, passphrase.toCharArray())
                opened.withTransaction {
                    if (opened.configDao().observe().first() == null) {
                        opened.configDao().save(ConfigEntity(hourlyRatePaise = 10_000, impulseCoolDownThresholdPaise = 50_000))
                    }
                    if (opened.envelopeDao().observeActive().first().none { it.type == EnvelopeType.WANTS }) {
                        opened.envelopeDao().upsert(EnvelopeEntity("daily-wants", "Daily spending", EnvelopeType.WANTS, dailyLimitPaise = 50_000))
                    }
                    listOf("Food", "Transport", "Bills", "Health", "Shopping", "Education", "Other").forEachIndexed { index, name ->
                        opened.categoryDao().upsert(CategoryEntity("expense-$index", name, TransactionDirection.DEBIT, isSystem = true))
                    }
                    listOf("Salary", "Freelance", "Investment income", "Gift", "Other").forEachIndexed { index, name ->
                        opened.categoryDao().upsert(CategoryEntity("income-$index", name, TransactionDirection.CREDIT, isSystem = true))
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
                    val accountDetails = combine(opened.accountDao().observeActive(), opened.accountDao().observeBalances()) { accounts, balances -> accounts to balances }
                    val portfolioDetails = combine(opened.investmentBalanceSnapshotDao().observeLatestForAll(), opened.investmentBalanceSnapshotDao().observeAll(), opened.netWorthSnapshotDao().observeAll()) { latest, history, netWorth -> Triple(latest, history, netWorth) }
                    combine(opened.accountDao().observeCashPosition(), accountDetails, opened.configDao().observe(), dayDetails, portfolioDetails) { cash, accountDetailsValue, config, day, portfolio ->
                        val dailyLimit = day.envelopes.firstOrNull { it.type == EnvelopeType.WANTS }?.dailyLimitPaise ?: 50_000
                        MvpFinanceState(true, false, cash.trueAvailableCashPaise, accountDetailsValue.first, config?.neurodiverseModeEnabled ?: false, (dailyLimit - day.spent).coerceAtLeast(0), day.spent, day.holding, accountBalances = accountDetailsValue.second, investmentSnapshots = portfolio.first, investmentHistory = portfolio.second, netWorthHistory = portfolio.third, recentTransactions = day.recent)
                    }.catch { error -> emit(MvpFinanceState(message = "Could not read the encrypted database: ${error.message}")) }
                        .collect { _state.value = it }
                }
            } catch (error: Throwable) {
                _state.update { it.copy(isLoading = false, message = "Unable to unlock this database. Check your passphrase.") }
            }
        }
    }

    fun createCashAccount() = viewModelScope.launch(Dispatchers.IO) {
        val opened = database ?: return@launch
        opened.accountDao().upsert(AccountEntity(UUID.randomUUID().toString(), "Daily Cash", AccountKind.SPENDING))
    }

    fun createAccount(name: String, productType: AccountProductType, assetClass: AssetClass, targetPercentText: String, openingBalanceText: String) = viewModelScope.launch(Dispatchers.IO) {
        val balance = runCatching { BigDecimal(openingBalanceText.trim().ifBlank { "0" }).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
        val targetBps = runCatching { BigDecimal(targetPercentText.trim().ifBlank { "0" }).movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact() }.getOrNull()
        if (balance == null || balance < 0 || targetBps == null || targetBps !in 0..10_000 || name.isBlank()) { _state.update { it.copy(message = "Enter a name, valid opening balance, and target between 0% and 100%.") }; return@launch }
        val kind = when (productType) {
            AccountProductType.CASH, AccountProductType.BANK -> AccountKind.SPENDING
            AccountProductType.CREDIT_CARD, AccountProductType.LOAN -> AccountKind.CREDIT
            AccountProductType.PPF, AccountProductType.EPF, AccountProductType.NPS, AccountProductType.SUPERANNUATION, AccountProductType.MUTUAL_FUNDS, AccountProductType.EQUITY -> AccountKind.INVESTMENT
        }
        val storedBalance = if (productType == AccountProductType.CREDIT_CARD || productType == AccountProductType.LOAN) -balance else balance
        database?.accountDao()?.upsert(AccountEntity(UUID.randomUUID().toString(), name.trim(), kind, productType, assetClass, targetBps, storedBalance))
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
            val snapshot = InvestmentBalanceSnapshotEntity(UUID.randomUUID().toString(), accountId, today, (previous?.totalCostPaise ?: 0) + amount, (previous?.currentValuePaise ?: 0) + amount, (if (sameDay) previous?.netContributionPaise ?: 0 else 0) + amount, "Contribution")
            opened.investmentBalanceSnapshotDao().upsert(snapshot)
            val portfolioValue = opened.investmentBalanceSnapshotDao().getAll().groupBy { it.accountId }.values.sumOf { items -> items.maxBy { it.asOfEpochDay }.currentValuePaise }
            val reserves = state.value.accountBalances.filter { it.kind == AccountKind.SAVINGS || it.kind == AccountKind.EMERGENCY }.sumOf { it.balancePaise }
            opened.netWorthSnapshotDao().upsert(NetWorthSnapshotEntity(UUID.randomUUID().toString(), today, state.value.cashPaise - amount + reserves + portfolioValue, portfolioValue))
        }
    }

    fun recordTransaction(amountText: String, payee: String, category: String, description: String, direction: TransactionDirection) {
        val paise = runCatching { BigDecimal(amountText.trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
        val account = state.value.accounts.firstOrNull { it.kind == AccountKind.SPENDING }
        if (paise == null || paise <= 0 || account == null) { _state.update { it.copy(message = "Create a cash account and enter a valid amount.") }; return }
        viewModelScope.launch(Dispatchers.IO) {
            val opened = database ?: return@launch
            val config = opened.configDao().observe().first() ?: return@launch
            val resolvedPayee = payee.ifBlank { if (direction == TransactionDirection.CREDIT) "Unlabelled income" else "Unlabelled expense" }
            val envelope = when {
                direction == TransactionDirection.CREDIT -> null
                category == "Shopping" -> EnvelopeType.WANTS
                else -> EnvelopeType.NEEDS
            }
            val transaction = coolDown(
                TransactionEntity(
                    id = UUID.randomUUID().toString(), accountId = account.id, amountPaise = paise,
                    direction = direction, merchant = resolvedPayee, category = category.ifBlank { null },
                    payee = resolvedPayee, description = description.ifBlank { null }, envelopeType = envelope
                ),
                config.impulseCoolDownThresholdPaise
            )
            opened.withTransaction {
                opened.transactionDao().upsert(transaction)
                opened.categoryDao().upsert(CategoryEntity("user-${direction.name.lowercase()}-${category.lowercase().replace(Regex("[^a-z0-9]+"), "-")}", category, direction))
                opened.payeeDao().upsert(PayeeEntity("payee-${resolvedPayee.lowercase().replace(Regex("[^a-z0-9]+"), "-")}", resolvedPayee, category))
            }
            if (transaction.isHoldingTank) _state.update { it.copy(message = "This purchase is in the 48-hour cooling tank.") }
        }
    }

    fun setNeurodiverseMode(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) { database?.configDao()?.setNeurodiverseMode(enabled) }
    fun confirmPurchase(transaction: TransactionEntity) = viewModelScope.launch(Dispatchers.IO) { database?.transactionDao()?.confirmHoldingTank(transaction.id) }
    fun discardPurchase(transaction: TransactionEntity) = viewModelScope.launch(Dispatchers.IO) { database?.transactionDao()?.discardHoldingTank(transaction.id) }
    fun deleteInvestmentSnapshot(snapshotId: String) = viewModelScope.launch(Dispatchers.IO) { database?.investmentBalanceSnapshotDao()?.delete(snapshotId) }
    fun deleteTransaction(transactionId: String) = viewModelScope.launch(Dispatchers.IO) { database?.transactionDao()?.delete(transactionId) }
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
            if (state.isUnlocked) MvpHome(state, viewModel::createCashAccount, viewModel::createAccount, viewModel::saveInvestmentBalance, viewModel::contributeToInvestment, viewModel::deleteInvestmentSnapshot, viewModel::deleteTransaction, viewModel::recordTransaction, viewModel::setNeurodiverseMode, viewModel::confirmPurchase, viewModel::discardPurchase, viewModel::clearMessage)
            else UnlockScreen(state.isLoading, state.message, viewModel::unlock, viewModel::clearMessage)
        }
    }
}

private fun formatInr(paise: Long, includeSign: Boolean = false): String {
    val sign = if (includeSign && paise > 0) "+" else ""
    val amount = java.text.NumberFormat.getNumberInstance(Locale("en", "IN")).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(paise / 100.0)
    return "$sign₹$amount"
}

@Composable
private fun UnlockScreen(loading: Boolean, message: String?, onUnlock: (String) -> Unit, onDismiss: () -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Nirmalam Dhanam", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text("Your finances stay encrypted on this device.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(passphrase, { passphrase = it }, Modifier.fillMaxWidth(), label = { Text("Create or enter your passphrase") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onUnlock(passphrase); passphrase = "" }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text(if (loading) "Unlocking…" else "Unlock") }
        message?.let { Spacer(Modifier.height(12.dp)); AssistChip(onClick = onDismiss, label = { Text(it) }) }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MvpHome(state: MvpFinanceState, onCreateCashAccount: () -> Unit, onCreateAccount: (String, AccountProductType, AssetClass, String, String) -> Unit, onSaveInvestmentBalance: (String, String, String, String, String, String) -> Unit, onContributeToInvestment: (String, String, String) -> Unit, onDeleteInvestmentSnapshot: (String) -> Unit, onDeleteTransaction: (String) -> Unit, onRecordTransaction: (String, String, String, String, TransactionDirection) -> Unit, onNeurodiverseModeChanged: (Boolean) -> Unit, onConfirmPurchase: (TransactionEntity) -> Unit, onDiscardPurchase: (TransactionEntity) -> Unit, onDismissMessage: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var payee by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Food") }
    var description by remember { mutableStateOf("") }
    var categoryExpanded by remember { mutableStateOf(false) }
    var showAccountSetup by remember { mutableStateOf(false) }
    var showInvestmentCheckIn by remember { mutableStateOf(false) }
    var showPortfolio by remember { mutableStateOf(false) }
    var showContribution by remember { mutableStateOf(false) }
    var showTransactions by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    if (showTransactions) { TransactionHistoryScreen(state, onBack = { showTransactions = false }, onDelete = onDeleteTransaction); return }
    if (showSettings) { SettingsScreen(state, onBack = { showSettings = false }, onNeurodiverseModeChanged = onNeurodiverseModeChanged); return }
    if (showPortfolio) {
        PortfolioAndNetWorthScreen(state, onBack = { showPortfolio = false }, onRecordBalance = { showInvestmentCheckIn = true }, onRecordContribution = { showContribution = true }, onDeleteSnapshot = onDeleteInvestmentSnapshot)
        if (showInvestmentCheckIn) InvestmentBalanceCheckInDialog(state.accounts.filter { it.kind == AccountKind.INVESTMENT }, onDismiss = { showInvestmentCheckIn = false }, onSave = { accountId, date, cost, value, contribution, note -> onSaveInvestmentBalance(accountId, date, cost, value, contribution, note); showInvestmentCheckIn = false })
        if (showContribution) InvestmentContributionDialog(state.accounts.filter { it.kind == AccountKind.INVESTMENT }, onDismiss = { showContribution = false }, onSave = { id, amount, payee -> onContributeToInvestment(id, amount, payee); showContribution = false })
        return
    }
    var direction by remember { mutableStateOf(TransactionDirection.DEBIT) }
    val isExpense = direction == TransactionDirection.DEBIT
    Scaffold(
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
                NavigationBarItem(selected = true, onClick = {}, icon = { NavigationGlyph("⌂") }, label = { Text("Home") })
                NavigationBarItem(selected = false, onClick = { showTransactions = true }, icon = { NavigationGlyph("≡") }, label = { Text("Ledger") })
                NavigationBarItem(selected = false, onClick = { showPortfolio = true }, icon = { NavigationGlyph("↗") }, label = { Text("Portfolio") })
                NavigationBarItem(selected = false, onClick = { showSettings = true }, icon = { NavigationGlyph("•••") }, label = { Text("More") })
            }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("AVAILABLE TO USE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(formatInr(state.cashPaise), style = MaterialTheme.typography.displaySmall, color = if (state.cashPaise < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer)
                        HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.16f))
                        Text(if (state.cashPaise < 0) "You are using credit. A small reset today creates room tomorrow." else "After credit liabilities — the amount available for everyday decisions.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ElevatedCard(Modifier.weight(1f), shape = MaterialTheme.shapes.large) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("SAFE TODAY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(formatInr(state.safeToSpendTodayPaise), style = MaterialTheme.typography.headlineSmall); Text("${formatInr(state.todaySpentPaise)} spent", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    ElevatedCard(Modifier.weight(1f), shape = MaterialTheme.shapes.large) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("COOLING TANK", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${state.holdingTank.size}", style = MaterialTheme.typography.headlineSmall); Text(if (state.holdingTank.isEmpty()) "No decisions waiting" else "Purchase decision waiting", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Accounts & portfolio", style = MaterialTheme.typography.titleMedium)
                        Text("Cash and bank accounts power your daily ledger. Investments use dated cost-and-value check-ins for a calmer, accurate portfolio history.", style = MaterialTheme.typography.bodySmall)
                        val investmentAccounts = state.accounts.filter { it.kind == AccountKind.INVESTMENT }
                        val snapshotsByAccount = state.investmentSnapshots.associateBy { it.accountId }
                        val portfolioValue = state.investmentSnapshots.sumOf { it.currentValuePaise }
                        val portfolioCost = state.investmentSnapshots.sumOf { it.totalCostPaise }
                        val gain = portfolioValue - portfolioCost
                        val liquidAndReserve = state.accountBalances.filter { it.kind == AccountKind.SAVINGS || it.kind == AccountKind.EMERGENCY }.sumOf { it.balancePaise }
                        val netWorth = state.cashPaise + liquidAndReserve + portfolioValue
                        Text("Portfolio ${formatInr(portfolioValue)}", style = MaterialTheme.typography.titleLarge)
                        Text("Cost ${formatInr(portfolioCost)} · Gain ${formatInr(gain, includeSign = true)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Net worth ${formatInr(netWorth)}", style = MaterialTheme.typography.titleMedium)
                        investmentAccounts.take(4).forEach { account ->
                            val snapshot = snapshotsByAccount[account.id]
                            Text("${account.productType.name.replace('_', ' ')} · ${account.name} · ${snapshot?.let { formatInr(it.currentValuePaise) } ?: "Needs a check-in"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val overdueCheckIns = investmentAccounts.count { account -> snapshotsByAccount[account.id]?.let { LocalDate.now().toEpochDay() - it.asOfEpochDay >= 30 } ?: true }
                        if (overdueCheckIns > 0) AssistChip(onClick = { showInvestmentCheckIn = true }, label = { Text("$overdueCheckIns monthly check-in${if (overdueCheckIns == 1) "" else "s"} due") })
                        if (investmentAccounts.isNotEmpty()) Button(onClick = { showInvestmentCheckIn = true }, modifier = Modifier.fillMaxWidth()) { Text("Record monthly balance") }
                        if (investmentAccounts.isNotEmpty()) OutlinedButton(onClick = { showContribution = true }, modifier = Modifier.fillMaxWidth()) { Text("Contribute from cash / bank") }
                        TextButton(onClick = { showPortfolio = true }, modifier = Modifier.fillMaxWidth()) { Text("Open portfolio & net worth") }
                        OutlinedButton(onClick = { showAccountSetup = true }, modifier = Modifier.fillMaxWidth()) { Text("Set up account or investment") }
                    }
                }
            }
            item {
                if (state.accounts.isEmpty()) Button(onClick = onCreateCashAccount, modifier = Modifier.fillMaxWidth()) { Text("Set up my daily cash account") }
                else ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Add a transaction", style = MaterialTheme.typography.titleLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = isExpense, onClick = { direction = TransactionDirection.DEBIT; category = "Food" }, label = { Text("Expense") })
                            FilterChip(selected = !isExpense, onClick = { direction = TransactionDirection.CREDIT; category = "Salary" }, label = { Text("Income") })
                        }
                        OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text("Amount in ₹") }, singleLine = true)
                        ExposedDropdownMenuBox(expanded = categoryExpanded, onExpandedChange = { categoryExpanded = !categoryExpanded }) {
                            OutlinedTextField(category, {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text("Category") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryExpanded) })
                            ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                                (if (isExpense) listOf("Food", "Transport", "Bills", "Health", "Shopping", "Other") else listOf("Salary", "Freelance", "Investment", "Gift", "Other")).forEach { option ->
                                    DropdownMenuItem(text = { Text(option) }, onClick = { category = option; categoryExpanded = false })
                                }
                            }
                        }
                        OutlinedTextField(payee, { payee = it }, Modifier.fillMaxWidth(), label = { Text(if (isExpense) "Payee / merchant" else "Income source") }, singleLine = true)
                        OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Description (optional)") }, minLines = 2)
                        Button(onClick = { onRecordTransaction(amount, payee, category, description, direction); amount = ""; payee = ""; description = "" }, modifier = Modifier.fillMaxWidth()) { Text(if (isExpense) "Add expense" else "Add income") }
                        if (isExpense) Text("Expenses above ₹500 enter a 48-hour cooling tank.", style = MaterialTheme.typography.bodySmall)
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
    if (showAccountSetup) AccountSetupDialog(onDismiss = { showAccountSetup = false }, onSave = { name, type, assetClass, target, openingBalance -> onCreateAccount(name, type, assetClass, target, openingBalance); showAccountSetup = false })
    if (showInvestmentCheckIn) InvestmentBalanceCheckInDialog(state.accounts.filter { it.kind == AccountKind.INVESTMENT }, onDismiss = { showInvestmentCheckIn = false }, onSave = { accountId, date, cost, value, contribution, note -> onSaveInvestmentBalance(accountId, date, cost, value, contribution, note); showInvestmentCheckIn = false })
    if (showContribution) InvestmentContributionDialog(state.accounts.filter { it.kind == AccountKind.INVESTMENT }, onDismiss = { showContribution = false }, onSave = { id, amount, payee -> onContributeToInvestment(id, amount, payee); showContribution = false })
}

@Composable
private fun NavigationGlyph(glyph: String) {
    Text(glyph, style = MaterialTheme.typography.titleMedium)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SettingsScreen(state: MvpFinanceState, onBack: () -> Unit, onNeurodiverseModeChanged: (Boolean) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("Experience", style = MaterialTheme.typography.titleMedium) }
            item { NeurodiverseModeToggle(state.neurodiverseModeEnabled, onNeurodiverseModeChanged) }
            item { ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("Privacy & backups", style = MaterialTheme.typography.titleMedium); Text("Your data stays in an encrypted local database. Encrypted .ndf import/export is available in the data layer and will be surfaced here in the next Settings update.", style = MaterialTheme.typography.bodySmall) } } }
            item { ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("How the app is organized", style = MaterialTheme.typography.titleMedium); Text("Home: daily cash and actions\nTransactions: ledger and accounts\nPortfolio: valuations, allocation, and net worth\nSettings: preferences and data controls", style = MaterialTheme.typography.bodySmall) } } }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TransactionHistoryScreen(state: MvpFinanceState, onBack: () -> Unit, onDelete: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(LedgerFilter.ALL) }
    var selectedAccountId by remember { mutableStateOf<String?>(null) }
    var accountMenuExpanded by remember { mutableStateOf(false) }
    var transactionToDelete by remember { mutableStateOf<TransactionEntity?>(null) }
    val accountNames = state.accounts.associate { it.id to it.name }
    val selectedAccount = state.accounts.firstOrNull { it.id == selectedAccountId }
    val queryMatches = state.recentTransactions.filter { transaction ->
        query.isBlank() || listOfNotNull(transaction.payee, transaction.category, transaction.description, accountNames[transaction.accountId]).any { it.contains(query.trim(), ignoreCase = true) }
    }
    val accountScoped = queryMatches.filter { transaction -> selectedAccountId == null || transaction.accountId == selectedAccountId }
    val shown = accountScoped.filter { transaction ->
        when (filter) {
            LedgerFilter.ALL -> true
            LedgerFilter.SPENT -> transaction.direction == TransactionDirection.DEBIT
            LedgerFilter.INCOME -> transaction.direction == TransactionDirection.CREDIT
        }
    }
    val income = accountScoped.filter { it.direction == TransactionDirection.CREDIT }.sumOf { it.amountPaise }
    val spent = accountScoped.filter { it.direction == TransactionDirection.DEBIT }.sumOf { it.amountPaise }
    val net = income - spent
    val spendingByCategory = accountScoped.filter { it.direction == TransactionDirection.DEBIT }
        .groupBy { it.category?.ifBlank { null } ?: "Uncategorised" }
        .mapValues { (_, entries) -> entries.sumOf { it.amountPaise } }
        .toList()
        .sortedByDescending { it.second }
    val mostUsedPayee = accountScoped.filter { it.direction == TransactionDirection.DEBIT && !it.payee.isNullOrBlank() }
        .groupingBy { it.payee!! }
        .eachCount()
        .maxByOrNull { it.value }
    val grouped = shown.groupBy { LocalDate.ofInstant(Instant.ofEpochMilli(it.occurredAtEpochMs), ZoneId.systemDefault()) }
        .toSortedMap(compareByDescending { it })
    Scaffold(topBar = { TopAppBar(title = { Text("Transactions & accounts") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("MONEY PULSE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            LedgerMetric("In", formatInr(income, includeSign = true), MaterialTheme.colorScheme.primary)
                            LedgerMetric("Out", formatInr(-spent), MaterialTheme.colorScheme.error)
                            LedgerMetric("Net", formatInr(net, includeSign = true), if (net < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                        Text("Based on ${accountScoped.size} recent entries${selectedAccount?.let { " in ${it.name}" } ?: ""}. Use this as a gentle check-in, not a judgement.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search your ledger") }, placeholder = { Text("Payee, category, account, or note") }, singleLine = true) }
            item {
                ExposedDropdownMenuBox(expanded = accountMenuExpanded, onExpandedChange = { accountMenuExpanded = !accountMenuExpanded }) {
                    OutlinedTextField(
                        value = selectedAccount?.name ?: "All accounts",
                        onValueChange = {},
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        readOnly = true,
                        label = { Text("Account scope") },
                        supportingText = { Text("Filters your money pulse, spend map, and entries") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(accountMenuExpanded) }
                    )
                    ExposedDropdownMenu(expanded = accountMenuExpanded, onDismissRequest = { accountMenuExpanded = false }) {
                        DropdownMenuItem(text = { Text("All accounts") }, onClick = { selectedAccountId = null; accountMenuExpanded = false })
                        state.accounts.forEach { account ->
                            DropdownMenuItem(
                                text = { Column { Text(account.name); Text(account.productType.name.replace('_', ' '), style = MaterialTheme.typography.labelSmall) } },
                                onClick = { selectedAccountId = account.id; accountMenuExpanded = false }
                            )
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LedgerFilter.entries.forEach { option ->
                        FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option.label) })
                    }
                }
            }
            if (spendingByCategory.isNotEmpty()) item {
                SpendingMap(
                    categories = spendingByCategory,
                    totalSpent = spent,
                    mostUsedPayee = mostUsedPayee?.key,
                    mostUsedPayeeCount = mostUsedPayee?.value ?: 0
                )
            }
            selectedAccount?.let { account ->
                val balance = state.accountBalances.firstOrNull { it.accountId == account.id }?.balancePaise ?: account.openingBalancePaise
                item {
                    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) { Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("Selected account", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); Text(account.name, style = MaterialTheme.typography.titleSmall); Text(account.productType.name.replace('_', ' '), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text(formatInr(balance), style = MaterialTheme.typography.titleMedium) } }
                }
            }
            item { Text("Ledger", style = MaterialTheme.typography.titleMedium) }
            if (grouped.isEmpty()) item { EmptyLedgerState(query.isNotBlank() || filter != LedgerFilter.ALL) }
            grouped.forEach { (date, transactions) ->
                item { Text(ledgerDayLabel(date), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                items(transactions.size) { index ->
                    val transaction = transactions[index]
                    val signed = if (transaction.direction == TransactionDirection.CREDIT) transaction.amountPaise else -transaction.amountPaise
                    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(transaction.payee ?: "Unlabelled transaction", style = MaterialTheme.typography.titleSmall)
                                Text(accountNames[transaction.accountId] ?: "Account", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(formatInr(signed, includeSign = true), style = MaterialTheme.typography.titleMedium, color = if (signed < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            transaction.category?.let { AssistChip(onClick = {}, label = { Text(it) }) }
                            transaction.envelopeType?.let { Text(it.name.lowercase().replaceFirstChar { char -> char.titlecase() }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        transaction.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { transactionToDelete = transaction }) { Text("Delete") } }
                    } }
                }
            }
        }
    }
    transactionToDelete?.let { transaction -> AlertDialog(onDismissRequest = { transactionToDelete = null }, title = { Text("Delete transaction?") }, text = { Text("This removes the entry from your encrypted ledger and updates balances.") }, confirmButton = { Button(onClick = { onDelete(transaction.id); transactionToDelete = null }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { transactionToDelete = null }) { Text("Cancel") } }) }
}

private enum class LedgerFilter(val label: String) { ALL("All"), SPENT("Spent"), INCOME("Income") }

@Composable
private fun LedgerMetric(label: String, value: String, color: Color) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

/** A local-only insight derived from the entries already visible in the encrypted ledger. */
@Composable
private fun SpendingMap(categories: List<Pair<String, Long>>, totalSpent: Long, mostUsedPayee: String?, mostUsedPayeeCount: Int) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("SPEND MAP", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("Where your money went", style = MaterialTheme.typography.titleMedium)
            categories.take(3).forEach { (category, amount) ->
                val share = if (totalSpent == 0L) 0f else (amount.toDouble() / totalSpent).toFloat()
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(category, style = MaterialTheme.typography.bodyMedium)
                        Text(formatInr(amount), style = MaterialTheme.typography.labelLarge)
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
            Text(if (isFiltered) "Nothing matches this view" else "Your ledger is ready", style = MaterialTheme.typography.titleMedium)
            Text(if (isFiltered) "Try a different search or filter." else "Record your first income or expense from Home to start your money story.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun ledgerDayLabel(date: LocalDate): String = when (date) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> date.toString()
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PortfolioAndNetWorthScreen(state: MvpFinanceState, onBack: () -> Unit, onRecordBalance: () -> Unit, onRecordContribution: () -> Unit, onDeleteSnapshot: (String) -> Unit) {
    val latestByAsset = state.investmentHistory.groupBy { it.accountId }.mapValues { (_, snapshots) -> snapshots.maxBy { it.asOfEpochDay } }
    val portfolioValue = latestByAsset.values.sumOf { it.currentValuePaise }
    val portfolioCost = latestByAsset.values.sumOf { it.totalCostPaise }
    val netWorth = state.netWorthHistory.maxByOrNull { it.asOfEpochDay }?.netWorthPaise ?: run {
        val reserves = state.accountBalances.filter { it.kind == AccountKind.SAVINGS || it.kind == AccountKind.EMERGENCY }.sumOf { it.balancePaise }
        state.cashPaise + reserves + portfolioValue
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Portfolio & net worth") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }, actions = { TextButton(onClick = onRecordContribution) { Text("Contribute") }; TextButton(onClick = onRecordBalance) { Text("Check-in") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("CURRENT NET WORTH", style = MaterialTheme.typography.labelMedium)
                        Text("₹ %.2f".format(netWorth / 100.0), style = MaterialTheme.typography.displaySmall)
                        Text("Portfolio ₹ %.2f · Cost ₹ %.2f · Gain ₹ %.2f".format(portfolioValue / 100.0, portfolioCost / 100.0, (portfolioValue - portfolioCost) / 100.0), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Net-worth trend", style = MaterialTheme.typography.titleMedium)
                    if (state.netWorthHistory.size >= 2) NetWorthSparkline(state.netWorthHistory.sortedBy { it.asOfEpochDay }.map { it.netWorthPaise }) else Text("Your trend appears after two dated investment check-ins.", style = MaterialTheme.typography.bodySmall)
                } }
            }
            item { Text("Asset allocation", style = MaterialTheme.typography.titleMedium) }
            items(latestByAsset.size) { index ->
                val (accountId, snapshot) = latestByAsset.entries.sortedByDescending { it.value.currentValuePaise }[index]
                val account = state.accounts.firstOrNull { it.id == accountId }
                val allocation = if (portfolioValue == 0L) 0.0 else snapshot.currentValuePaise * 100.0 / portfolioValue
                ElevatedCard(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text(account?.name ?: "Investment", style = MaterialTheme.typography.titleSmall); Text(account?.productType?.name?.replace('_', ' ') ?: "Asset", style = MaterialTheme.typography.bodySmall) }
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End) { Text("₹ %.2f".format(snapshot.currentValuePaise / 100.0), style = MaterialTheme.typography.titleMedium); Text("%.1f%% · gain ₹ %.2f".format(allocation, (snapshot.currentValuePaise - snapshot.totalCostPaise) / 100.0), style = MaterialTheme.typography.bodySmall) }
                } }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Allocation vs target", style = MaterialTheme.typography.titleMedium)
                    AssetClass.entries.filter { assetClass -> state.accounts.any { it.assetClass == assetClass && it.targetAllocationBps > 0 } }.forEach { assetClass ->
                        val actualValue = latestByAsset.entries.filter { (accountId, _) -> state.accounts.firstOrNull { it.id == accountId }?.assetClass == assetClass }.sumOf { it.value.currentValuePaise }
                        val actual = if (portfolioValue == 0L) 0.0 else actualValue * 100.0 / portfolioValue
                        val target = state.accounts.filter { it.assetClass == assetClass }.sumOf { it.targetAllocationBps } / 100.0
                        val difference = actual - target
                        Text("${assetClass.name}: %.1f%% / %.1f%% target · %s %.1f%%".format(actual, target, if (difference >= 0) "over" else "under", kotlin.math.abs(difference)), style = MaterialTheme.typography.bodySmall)
                    }
                } }
            }
            item { Text("Balance history", style = MaterialTheme.typography.titleMedium) }
            items(state.investmentHistory.size) { index ->
                val snapshot = state.investmentHistory[index]
                val account = state.accounts.firstOrNull { it.id == snapshot.accountId }
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    Text("${account?.name ?: "Investment"} · ${LocalDate.ofEpochDay(snapshot.asOfEpochDay)}", style = MaterialTheme.typography.titleSmall)
                    Text("Cost ₹ %.2f · Value ₹ %.2f · Contribution ₹ %.2f".format(snapshot.totalCostPaise / 100.0, snapshot.currentValuePaise / 100.0, snapshot.netContributionPaise / 100.0), style = MaterialTheme.typography.bodySmall)
                    snapshot.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { onDeleteSnapshot(snapshot.id) }) { Text("Delete") } }
                } }
            }
        }
    }
}

@Composable
private fun NetWorthSparkline(values: List<Long>) {
    val lineColor = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(88.dp)) {
        val min = values.minOrNull() ?: 0L
        val max = values.maxOrNull() ?: min + 1L
        val spread = (max - min).coerceAtLeast(1L).toFloat()
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = if (values.size == 1) 0f else size.width * index / (values.size - 1)
            val y = size.height - ((value - min) / spread * size.height)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 5f, cap = StrokeCap.Round))
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AccountSetupDialog(onDismiss: () -> Unit, onSave: (String, AccountProductType, AssetClass, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var openingBalance by remember { mutableStateOf("") }
    var product by remember { mutableStateOf(AccountProductType.CASH) }
    var assetClass by remember { mutableStateOf(AssetClass.CASH) }
    var expanded by remember { mutableStateOf(false) }
    var assetClassExpanded by remember { mutableStateOf(false) }
    var targetPercent by remember { mutableStateOf("0") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set up account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Choose cash/bank for income and expenses; choose a retirement or investment product for the portfolio.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Account name") }, singleLine = true)
                ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
                    OutlinedTextField(product.name.replace('_', ' '), {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text("Account type") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
                    ExposedDropdownMenu(expanded, { expanded = false }) {
                        AccountProductType.entries.forEach { type -> DropdownMenuItem(text = { Text(type.name.replace('_', ' ')) }, onClick = { product = type; expanded = false }) }
                    }
                }
                ExposedDropdownMenuBox(assetClassExpanded, { assetClassExpanded = !assetClassExpanded }) {
                    OutlinedTextField(assetClass.name, {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text("Asset class") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(assetClassExpanded) })
                    ExposedDropdownMenu(assetClassExpanded, { assetClassExpanded = false }) {
                        AssetClass.entries.forEach { type -> DropdownMenuItem(text = { Text(type.name) }, onClick = { assetClass = type; assetClassExpanded = false }) }
                    }
                }
                OutlinedTextField(targetPercent, { targetPercent = it }, Modifier.fillMaxWidth(), label = { Text("Target allocation (%)") }, singleLine = true)
                OutlinedTextField(openingBalance, { openingBalance = it }, Modifier.fillMaxWidth(), label = { Text("Opening value in ₹") }, singleLine = true)
            }
        },
        confirmButton = { Button(onClick = { onSave(name, product, assetClass, targetPercent, openingBalance) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun InvestmentBalanceCheckInDialog(accounts: List<AccountEntity>, onDismiss: () -> Unit, onSave: (String, String, String, String, String, String) -> Unit) {
    var account by remember(accounts) { mutableStateOf(accounts.first()) }
    var accountExpanded by remember { mutableStateOf(false) }
    var asOfDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var cost by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var contribution by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Investment balance check-in") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Enter the statement or market balance as on a date. This is an asset valuation, not a spending transaction.", style = MaterialTheme.typography.bodySmall)
                ExposedDropdownMenuBox(accountExpanded, { accountExpanded = !accountExpanded }) {
                    OutlinedTextField(account.name, {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text("Investment") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(accountExpanded) })
                    ExposedDropdownMenu(accountExpanded, { accountExpanded = false }) {
                        accounts.forEach { item -> DropdownMenuItem(text = { Text("${item.productType.name.replace('_', ' ')} · ${item.name}") }, onClick = { account = item; accountExpanded = false }) }
                    }
                }
                OutlinedTextField(asOfDate, { asOfDate = it }, Modifier.fillMaxWidth(), label = { Text("As-on date (YYYY-MM-DD)") }, singleLine = true)
                OutlinedTextField(cost, { cost = it }, Modifier.fillMaxWidth(), label = { Text("Total cost / invested amount in ₹") }, singleLine = true)
                OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text("Current value in ₹") }, singleLine = true)
                OutlinedTextField(contribution, { contribution = it }, Modifier.fillMaxWidth(), label = { Text("Net contribution since prior check-in in ₹") }, placeholder = { Text("0 if none") }, singleLine = true)
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Statement / note (optional)") }, singleLine = true)
            }
        },
        confirmButton = { Button(onClick = { onSave(account.id, asOfDate, cost, value, contribution, note) }, enabled = cost.isNotBlank() && value.isNotBlank()) { Text("Save balance") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
            Text("This debits your cash/bank ledger and increases the selected asset's cost and value today.", style = MaterialTheme.typography.bodySmall)
            ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
                OutlinedTextField(account.name, {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text("Investment") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
                ExposedDropdownMenu(expanded, { expanded = false }) { accounts.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { account = item; expanded = false }) } }
            }
            OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text("Contribution in ₹") }, singleLine = true)
            OutlinedTextField(payee, { payee = it }, Modifier.fillMaxWidth(), label = { Text("Provider / payee (optional)") }, singleLine = true)
        } },
        confirmButton = { Button(onClick = { onSave(account.id, amount, payee) }, enabled = amount.isNotBlank()) { Text("Record contribution") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
