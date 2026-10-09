package com.nirmalamgroup.nirmalamdhanam.domain.usecase

import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionDirection
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class TransactionReconciliationEngineTest {
    private val t = TransactionEntity(
        id = "1", accountId = "bank", amountPaise = 12_345,
        direction = TransactionDirection.DEBIT, payee = "Cafe",
        category = "Dining", occurredAtEpochMs = 1_700_000_000_000
    )

    @Test
    fun detectsProbableDuplicate() {
        val decision = TransactionReconciliationEngine.classify(t.copy(id = "2"), listOf(t))
        assertEquals(ReconciliationStatus.PROBABLE_DUPLICATE, decision.status)
    }

    @Test
    fun flagsSameAmountDateWithDifferentDescriptionAsPossibleMatch() {
        val decision = TransactionReconciliationEngine.classify(t.copy(id = "2", payee = "Other"), listOf(t))
        assertEquals(ReconciliationStatus.POSSIBLE_MATCH, decision.status)
    }
}
