package com.nirmalamgroup.nirmalamdhanam.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for the historical bug where analytical screens consumed
 * observeRecent(limit = 100) and silently ignored older transactions.
 *
 * The recent feed is intentionally capped for lightweight UI affordances. The
 * authoritative ledger queries used by reports/insights/AI must remain complete.
 */
@RunWith(AndroidJUnit4::class)
class FullLedgerReportingDaoTest {
    private lateinit var db: NirmalamDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NirmalamDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun recentFeedMayBeCappedButAuthoritativeLedgerReturnsEveryTransaction() = runBlocking {
        val base = 1_700_000_000_000L
        val transactions = (1..150).map { index ->
            TransactionEntity(
                id = "txn-$index",
                accountId = "cash",
                amountPaise = index.toLong() * 100,
                direction = if (index % 5 == 0) TransactionDirection.CREDIT else TransactionDirection.DEBIT,
                category = if (index % 2 == 0) "Food" else "Bills",
                occurredAtEpochMs = base + index
            )
        }
        db.transactionDao().upsertAll(transactions)

        val recent = db.transactionDao().observeRecent(limit = 100).first()
        val fullLedger = db.transactionDao().observeAll().first()
        val rangedLedger = db.transactionDao().getBetween(base, base + 151)

        assertEquals(100, recent.size)
        assertEquals(150, fullLedger.size)
        assertEquals(150, rangedLedger.size)
        assertEquals(transactions.sumOf { it.amountPaise }, fullLedger.sumOf { it.amountPaise })
    }

    @Test
    fun transactionsOlderThanRecentLimitRemainAvailableToAnalytics() = runBlocking {
        val base = 1_700_000_000_000L
        db.transactionDao().upsertAll(
            (1..125).map { index ->
                TransactionEntity(
                    id = "expense-$index",
                    accountId = "cash",
                    amountPaise = 1_000,
                    direction = TransactionDirection.DEBIT,
                    category = "Groceries",
                    occurredAtEpochMs = base + index
                )
            }
        )

        val recentTotal = db.transactionDao().observeRecent(limit = 100).first().sumOf { it.amountPaise }
        val authoritativeTotal = db.transactionDao().observeAll().first().sumOf { it.amountPaise }

        assertEquals(100_000L, recentTotal)
        assertEquals(125_000L, authoritativeTotal)
    }
}
