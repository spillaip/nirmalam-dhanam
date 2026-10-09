package com.nirmalamgroup.nirmalamdhanam.domain.usecase

import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** Deterministic duplicate/conflict classification used by imports and programmable clients. */
enum class ReconciliationStatus {
    NEW,
    PROBABLE_DUPLICATE,
    POSSIBLE_MATCH,
    CONFLICT,
    ALREADY_RECONCILED,
}

data class ReconciliationDecision(
    val status: ReconciliationStatus,
    val matchedTransactionId: String? = null,
    val reason: String,
)

object TransactionReconciliationEngine {
    private fun normalise(value: String?): String =
        value.orEmpty()
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun dateOf(transaction: TransactionEntity, zoneId: ZoneId): LocalDate =
        Instant.ofEpochMilli(transaction.occurredAtEpochMs).atZone(zoneId).toLocalDate()

    private fun payeeOf(transaction: TransactionEntity): String =
        normalise(transaction.payee ?: transaction.merchant)

    private fun descriptionOf(transaction: TransactionEntity): String = normalise(transaction.description)

    private fun sameCoreValues(a: TransactionEntity, b: TransactionEntity, zoneId: ZoneId): Boolean =
        a.accountId == b.accountId &&
            a.amountPaise == b.amountPaise &&
            a.direction == b.direction &&
            dateOf(a, zoneId) == dateOf(b, zoneId) &&
            payeeOf(a) == payeeOf(b) &&
            normalise(a.category) == normalise(b.category) &&
            descriptionOf(a) == descriptionOf(b)

    /** Token overlap is deliberately simple and deterministic; no fuzzy/AI service is involved. */
    private fun textSimilarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        val left = a.split(' ').filter { it.length >= 2 }.toSet()
        val right = b.split(' ').filter { it.length >= 2 }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val intersection = left.intersect(right).size.toDouble()
        val union = left.union(right).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }

    /**
     * Classifies one candidate against already-known transactions.
     *
     * Rules, in priority order:
     * 1. A committed matching source fingerprint -> already reconciled.
     * 2. Reused fingerprint/id with changed financial values -> conflict.
     * 3. Same account/date/amount/direction plus matching merchant/description/category -> probable duplicate.
     * 4. Same account/date/amount/direction with meaningful descriptive overlap -> possible match.
     * 5. Same account/amount/direction and matching merchant or description within two days -> possible match.
     * 6. Otherwise -> new.
     *
     * Amount, local date, account, merchant/payee and description all participate. The same inputs
     * always produce the same result and no network or model call is made.
     */
    fun classify(
        candidate: TransactionEntity,
        existing: List<TransactionEntity>,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): ReconciliationDecision {
        candidate.sourceFingerprint?.takeIf { it.isNotBlank() }?.let { fingerprint ->
            val sourceMatch = existing.firstOrNull { it.sourceFingerprint == fingerprint }
            if (sourceMatch != null) {
                return if (sameCoreValues(candidate, sourceMatch, zoneId)) {
                    ReconciliationDecision(
                        ReconciliationStatus.ALREADY_RECONCILED,
                        sourceMatch.id,
                        "This source record was already reconciled into the ledger.",
                    )
                } else {
                    ReconciliationDecision(
                        ReconciliationStatus.CONFLICT,
                        sourceMatch.id,
                        "The same source fingerprint is already recorded with different financial values.",
                    )
                }
            }
        }

        val idCollision = existing.firstOrNull { it.id == candidate.id }
        if (idCollision != null) {
            return if (sameCoreValues(candidate, idCollision, zoneId)) {
                ReconciliationDecision(
                    ReconciliationStatus.ALREADY_RECONCILED,
                    idCollision.id,
                    "This transaction identifier and its financial values are already in the ledger.",
                )
            } else {
                ReconciliationDecision(
                    ReconciliationStatus.CONFLICT,
                    idCollision.id,
                    "Transaction identifier already exists with different financial values.",
                )
            }
        }

        val candidateDate = dateOf(candidate, zoneId)
        val amountMatches = existing.filter {
            it.accountId == candidate.accountId &&
                it.amountPaise == candidate.amountPaise &&
                it.direction == candidate.direction
        }
        val sameDate = amountMatches.filter { dateOf(it, zoneId) == candidateDate }
        val candidatePayee = payeeOf(candidate)
        val candidateDescription = descriptionOf(candidate)
        val candidateCategory = normalise(candidate.category)

        val exact = sameDate.firstOrNull {
            payeeOf(it) == candidatePayee &&
                normalise(it.category) == candidateCategory &&
                descriptionOf(it) == candidateDescription
        }
        if (exact != null) {
            return ReconciliationDecision(
                ReconciliationStatus.PROBABLE_DUPLICATE,
                exact.id,
                "Same account, date, amount, direction, merchant/payee, category and description.",
            )
        }

        val strongSameDay = sameDate.firstOrNull {
            val payeeExact = candidatePayee.isNotBlank() && payeeOf(it) == candidatePayee
            val descriptionExact = candidateDescription.isNotBlank() && descriptionOf(it) == candidateDescription
            payeeExact || descriptionExact
        }
        if (strongSameDay != null) {
            val matchedBy = if (candidateDescription.isNotBlank() && descriptionOf(strongSameDay) == candidateDescription) {
                "description"
            } else {
                "merchant/payee"
            }
            return ReconciliationDecision(
                ReconciliationStatus.PROBABLE_DUPLICATE,
                strongSameDay.id,
                "Same account, date, amount and direction with matching $matchedBy.",
            )
        }

        val descriptiveSameDay = sameDate
            .map { transaction ->
                val merchantScore = textSimilarity(candidatePayee, payeeOf(transaction))
                val descriptionScore = textSimilarity(candidateDescription, descriptionOf(transaction))
                Triple(transaction, merchantScore, descriptionScore)
            }
            .filter { (_, merchantScore, descriptionScore) -> merchantScore >= 0.50 || descriptionScore >= 0.50 }
            .maxByOrNull { (_, merchantScore, descriptionScore) -> maxOf(merchantScore, descriptionScore) }
        if (descriptiveSameDay != null) {
            return ReconciliationDecision(
                ReconciliationStatus.POSSIBLE_MATCH,
                descriptiveSameDay.first.id,
                "Same account, date, amount and direction with similar merchant or description text.",
            )
        }

        val sameDayAmount = sameDate.firstOrNull()
        if (sameDayAmount != null) {
            return ReconciliationDecision(
                ReconciliationStatus.POSSIBLE_MATCH,
                sameDayAmount.id,
                "Same account, date, amount and direction but descriptive fields differ.",
            )
        }

        val nearbyMatch = amountMatches
            .asSequence()
            .map { transaction -> transaction to abs(dateOf(transaction, zoneId).toEpochDay() - candidateDate.toEpochDay()) }
            .filter { (_, distance) -> distance in 1L..2L }
            .map { (transaction, distance) ->
                val merchantExact = candidatePayee.isNotBlank() && payeeOf(transaction) == candidatePayee
                val descriptionExact = candidateDescription.isNotBlank() && descriptionOf(transaction) == candidateDescription
                Triple(transaction, distance, merchantExact || descriptionExact)
            }
            .filter { (_, _, textMatch) -> textMatch }
            .minByOrNull { (_, distance, _) -> distance }

        if (nearbyMatch != null) {
            val (match, dayDistance) = nearbyMatch
            return ReconciliationDecision(
                ReconciliationStatus.POSSIBLE_MATCH,
                match.id,
                "Same account, amount and direction with matching merchant/description within $dayDistance day${if (dayDistance == 1L) "" else "s"}.",
            )
        }

        return ReconciliationDecision(
            ReconciliationStatus.NEW,
            reason = "No matching transaction or reconciled source record found.",
        )
    }
}
