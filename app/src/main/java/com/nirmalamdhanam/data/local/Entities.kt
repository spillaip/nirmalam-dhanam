package com.nirmalamdhanam.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "nirmalam_dhanam_config")
data class ConfigEntity(
    @PrimaryKey val id: Int = 1,
    val hourlyRatePaise: Long,
    val impulseCoolDownThresholdPaise: Long,
    /** Simplifies presentation only; it never changes balances, budgets, or transaction rules. */
    val neurodiverseModeEnabled: Boolean = false,
    val currencyCode: String = "INR",
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

enum class AccountKind { SPENDING, CREDIT, SAVINGS, EMERGENCY, INVESTMENT }

@Entity(tableName = "accounts", indices = [Index("kind")])
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: AccountKind,
    val openingBalancePaise: Long = 0,
    val isArchived: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

enum class TransactionDirection { DEBIT, CREDIT }
enum class EnvelopeType { NEEDS, WANTS, SAVINGS, INVESTMENT }

@Entity(tableName = "transactions", indices = [Index("accountId"), Index("occurredAtEpochMs"), Index("envelopeType"), Index("isHoldingTank")])
data class TransactionEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val amountPaise: Long,
    val direction: TransactionDirection,
    val merchant: String? = null,
    val envelopeType: EnvelopeType? = null,
    val occurredAtEpochMs: Long = System.currentTimeMillis(),
    val isHoldingTank: Boolean = false,
    val coolDownExpiryEpochMs: Long? = null,
    val note: String? = null
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

data class CashPosition(val spendingPaise: Long, val creditLiabilityPaise: Long) {
    val trueAvailableCashPaise: Long get() = spendingPaise - creditLiabilityPaise
}
