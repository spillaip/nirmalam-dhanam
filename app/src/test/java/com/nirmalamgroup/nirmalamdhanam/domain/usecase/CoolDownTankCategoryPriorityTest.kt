package com.nirmalamgroup.nirmalamdhanam.domain.usecase

import com.nirmalamgroup.nirmalamdhanam.data.local.CategoryPriority
import com.nirmalamgroup.nirmalamdhanam.data.local.EnvelopeType
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionDirection
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoolDownTankCategoryPriorityTest {
    private val now = 1_700_000_000_000L
    private val interceptor = CoolDownTankInterceptorUseCase { now }
    private val threshold = 5_000L

    private fun debit(category: String, amount: Long, envelope: EnvelopeType? = null) = TransactionEntity(
        id = "tx-$category-$amount",
        accountId = "cash",
        amountPaise = amount,
        direction = TransactionDirection.DEBIT,
        category = category,
        envelopeType = envelope
    )

    @Test
    fun everyWantCategoryAboveThresholdEntersCoolingTank() {
        listOf("Shopping", "Travel", "Entertainment", "Quick commerce", "Gifts & transfers").forEach { category ->
            val result = interceptor(debit(category, 10_000L), threshold, CategoryPriority.WANT)
            assertEquals(EnvelopeType.WANTS, result.envelopeType, category)
            assertTrue(result.isHoldingTank, category)
            assertEquals(now + 48L * 60 * 60 * 1000, result.coolDownExpiryEpochMs, category)
        }
    }

    @Test
    fun categoryPriorityOverridesIncorrectPrepopulatedEnvelope() {
        val want = interceptor(debit("Travel", 10_000L, EnvelopeType.NEEDS), threshold, CategoryPriority.WANT)
        assertEquals(EnvelopeType.WANTS, want.envelopeType)
        assertTrue(want.isHoldingTank)

        val need = interceptor(debit("Groceries", 10_000L, EnvelopeType.WANTS), threshold, CategoryPriority.NEED)
        assertEquals(EnvelopeType.NEEDS, need.envelopeType)
        assertFalse(need.isHoldingTank)
        assertNull(need.coolDownExpiryEpochMs)
    }

    @Test
    fun wantBelowThresholdDoesNotEnterCoolingTank() {
        val result = interceptor(debit("Entertainment", threshold), threshold, CategoryPriority.WANT)
        assertEquals(EnvelopeType.WANTS, result.envelopeType)
        assertFalse(result.isHoldingTank)
        assertNull(result.coolDownExpiryEpochMs)
    }


    @Test
    fun editingHeldWantPreservesOriginalExpiry() {
        val originalExpiry = now + 60_000L
        val existing = debit("Travel", 10_000L, EnvelopeType.WANTS).copy(
            isHoldingTank = true,
            coolDownExpiryEpochMs = originalExpiry
        )
        val result = interceptor(existing, threshold, CategoryPriority.WANT)
        assertTrue(result.isHoldingTank)
        assertEquals(originalExpiry, result.coolDownExpiryEpochMs)
    }

    @Test
    fun creditsNeverEnterCoolingTank() {
        val result = interceptor(
            TransactionEntity(
                id = "income",
                accountId = "cash",
                amountPaise = 100_000L,
                direction = TransactionDirection.CREDIT,
                category = "Bonus",
                envelopeType = EnvelopeType.WANTS
            ),
            threshold,
            CategoryPriority.WANT
        )
        assertNull(result.envelopeType)
        assertFalse(result.isHoldingTank)
        assertNull(result.coolDownExpiryEpochMs)
    }

    @Test
    fun investmentTransfersNeverEnterCoolingTank() {
        val result = interceptor(
            debit("Investment contribution", 100_000L, EnvelopeType.INVESTMENT),
            threshold,
            CategoryPriority.WANT
        )
        assertEquals(EnvelopeType.INVESTMENT, result.envelopeType)
        assertFalse(result.isHoldingTank)
        assertNull(result.coolDownExpiryEpochMs)
    }
}
