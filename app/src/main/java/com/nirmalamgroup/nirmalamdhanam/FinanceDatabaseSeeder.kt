package com.nirmalamgroup.nirmalamdhanam

import com.nirmalamgroup.nirmalamdhanam.data.local.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

internal object FinanceDatabaseSeeder {
    /** Gives a new local database a useful, entirely offline starting story for phone and tablet previews. */
    suspend fun seedDemoDataIfEmpty(opened: NirmalamDatabase) {
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
            InvestmentBalanceSnapshotEntity(id = id, accountId = accountId, asOfEpochDay = today.minusDays(daysAgo).toEpochDay(), totalCostPaise = cost, currentValuePaise = value, netContributionPaise = contribution, note = note)
        listOf(
            snapshot("demo-ppf-old", ppfId, 90, 2_00_000_00, 2_11_000_00, 0, "Quarterly statement"),
            snapshot("demo-ppf-now", ppfId, 0, 2_05_000_00, 2_19_500_00, 5_000_00, "Monthly check-in"),
            snapshot("demo-fund-old", fundId, 90, 3_60_000_00, 3_78_000_00, 0, "Quarterly check-in"),
            snapshot("demo-fund-now", fundId, 0, 3_75_000_00, 4_12_400_00, 15_000_00, "Monthly check-in"),
            snapshot("demo-equity-old", equityId, 90, 1_45_000_00, 1_51_000_00, 0, "Quarterly check-in"),
            snapshot("demo-equity-now", equityId, 0, 1_50_000_00, 1_68_600_00, 5_000_00, "Monthly check-in")
        ).forEach { opened.investmentBalanceSnapshotDao().upsert(it) }
        listOf(ppfId, fundId, equityId).forEach { InvestmentLedgerService.recalculateDeltas(opened, it) }
        opened.netWorthSnapshotDao().upsert(NetWorthSnapshotEntity("demo-net-worth", today.toEpochDay(), 12_11_656_00, 8_00_500_00))
    }
    
    /**
     * A Varga describes the purpose of a movement, not whether money came in or went out.
     * The persisted direction is retained only for migration compatibility with older databases.
     */
    suspend fun seedReferenceData(opened: NirmalamDatabase) {
        val categories = listOf(
            "Food & dining" to Triple("food", CategoryPriority.NEED, CategoryNature.VARIABLE),
            "Groceries" to Triple("food", CategoryPriority.NEED, CategoryNature.VARIABLE),
            "Quick commerce" to Triple("shopping", CategoryPriority.WANT, CategoryNature.VARIABLE),
            "Transport & fuel" to Triple("transport", CategoryPriority.NEED, CategoryNature.VARIABLE),
            "Travel" to Triple("transport", CategoryPriority.WANT, CategoryNature.VARIABLE),
            "Rent & housing" to Triple("bills", CategoryPriority.NEED, CategoryNature.FIXED),
            "Utilities & mobile" to Triple("bills", CategoryPriority.NEED, CategoryNature.FIXED),
            "Internet & subscriptions" to Triple("bills", CategoryPriority.WANT, CategoryNature.FIXED),
            "EMI & insurance" to Triple("bills", CategoryPriority.NEED, CategoryNature.FIXED),
            "Health & pharmacy" to Triple("health", CategoryPriority.NEED, CategoryNature.VARIABLE),
            "Education" to Triple("education", CategoryPriority.NEED, CategoryNature.FIXED),
            "Shopping" to Triple("shopping", CategoryPriority.WANT, CategoryNature.VARIABLE),
            "Home & family" to Triple("gift", CategoryPriority.WANT, CategoryNature.VARIABLE),
            "Entertainment" to Triple("gift", CategoryPriority.WANT, CategoryNature.VARIABLE),
            "Investments & savings" to Triple("investment", CategoryPriority.NEED, CategoryNature.FIXED),
            "Salary & wages" to Triple("salary", CategoryPriority.NEED, CategoryNature.VARIABLE),
            "Freelance & business" to Triple("freelance", CategoryPriority.NEED, CategoryNature.VARIABLE),
            "Interest & dividends" to Triple("investment", CategoryPriority.NEED, CategoryNature.VARIABLE),
            "Gifts & transfers" to Triple("gift", CategoryPriority.WANT, CategoryNature.VARIABLE),
            "Transfers & banking" to Triple("other", CategoryPriority.NEED, CategoryNature.VARIABLE),
            "Refunds & cashback" to Triple("gift", CategoryPriority.NEED, CategoryNature.VARIABLE),
            "Taxes & fees" to Triple("bills", CategoryPriority.NEED, CategoryNature.VARIABLE),
            "Cash withdrawal" to Triple("other", CategoryPriority.NEED, CategoryNature.VARIABLE),
            "Other" to Triple("other", CategoryPriority.WANT, CategoryNature.VARIABLE)
        )
        val incomeCategories = setOf("Salary & wages", "Freelance & business", "Interest & dividends", "Refunds & cashback")
        categories.forEachIndexed { index, pair ->
            val (name, meta) = pair
            if (opened.categoryDao().getByName(name) == null) {
                val direction = if (name in incomeCategories) TransactionDirection.CREDIT else TransactionDirection.DEBIT
                opened.categoryDao().upsert(CategoryEntity("system-varga-$index", name, direction, isSystem = true, iconKey = meta.first, priority = meta.second, nature = meta.third))
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
}
