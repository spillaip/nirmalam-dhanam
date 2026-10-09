package com.nirmalamgroup.nirmalamdhanam.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HoldingTankDaoTest {
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
    fun expiredHoldingTransactionRemainsVisibleUntilConfirmed() = runBlocking {
        val now = System.currentTimeMillis()
        val transaction = TransactionEntity(
            id = "expired-hold",
            accountId = "cash",
            amountPaise = 12_500,
            direction = TransactionDirection.DEBIT,
            merchant = "Test merchant",
            envelopeType = EnvelopeType.WANTS,
            occurredAtEpochMs = now - 3L * 24 * 60 * 60 * 1_000,
            isHoldingTank = true,
            coolDownExpiryEpochMs = now - 60_000
        )

        db.transactionDao().upsert(transaction)

        val beforeConfirm = db.transactionDao().observeHoldingTank().first()
        assertTrue(beforeConfirm.any { it.id == transaction.id })
        assertTrue(db.transactionDao().getById(transaction.id)!!.isHoldingTank)

        assertEquals(1, db.transactionDao().confirmHoldingTank(transaction.id, now))

        val afterConfirm = db.transactionDao().observeHoldingTank().first()
        assertFalse(afterConfirm.any { it.id == transaction.id })
        assertFalse(db.transactionDao().getById(transaction.id)!!.isHoldingTank)
    }


    @Test
    fun futureHoldingTransactionCannotBeConfirmedEarly() = runBlocking {
        val now = System.currentTimeMillis()
        val transaction = TransactionEntity(
            id = "not-ready-hold",
            accountId = "cash",
            amountPaise = 8_000,
            direction = TransactionDirection.DEBIT,
            isHoldingTank = true,
            coolDownExpiryEpochMs = now + 24L * 60 * 60 * 1_000
        )

        db.transactionDao().upsert(transaction)

        assertEquals(0, db.transactionDao().confirmHoldingTank(transaction.id, now))
        assertTrue(db.transactionDao().getById(transaction.id)!!.isHoldingTank)
        assertTrue(db.transactionDao().observeHoldingTank().first().any { it.id == transaction.id })
    }

    @Test
    fun futureHoldingTransactionIsAlsoVisible() = runBlocking {
        val now = System.currentTimeMillis()
        val transaction = TransactionEntity(
            id = "active-hold",
            accountId = "cash",
            amountPaise = 5_000,
            direction = TransactionDirection.DEBIT,
            isHoldingTank = true,
            coolDownExpiryEpochMs = now + 24L * 60 * 60 * 1_000
        )

        db.transactionDao().upsert(transaction)

        assertTrue(db.transactionDao().observeHoldingTank().first().any { it.id == transaction.id })
    }
}
