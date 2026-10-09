package com.nirmalamgroup.nirmalamdhanam.domain.usecase

import com.nirmalamgroup.nirmalamdhanam.data.local.AccountKind
import com.nirmalamgroup.nirmalamdhanam.data.local.AccountProductType

/**
 * Single source of truth for mapping a Khata product to its accounting role.
 *
 * Cash and bank accounts may be explicitly classified by the user as day-to-day
 * spending, savings, or emergency reserves. Liabilities and investments have a
 * fixed role regardless of any stale UI value passed by a caller.
 */
object AccountRolePolicy {
    val cashAccountKinds: List<AccountKind> = listOf(
        AccountKind.SPENDING,
        AccountKind.SAVINGS,
        AccountKind.EMERGENCY
    )

    /** Accounts that can legitimately carry ordinary income/expense ledger transactions. */
    val transactionAccountKinds: Set<AccountKind> = setOf(
        AccountKind.SPENDING,
        AccountKind.SAVINGS,
        AccountKind.EMERGENCY,
        AccountKind.CREDIT
    )

    /** Positive-balance cash sources that may fund an investment contribution. */
    val contributionSourceKinds: Set<AccountKind> = setOf(
        AccountKind.SPENDING,
        AccountKind.SAVINGS,
        AccountKind.EMERGENCY
    )

    fun supportsTransactions(kind: AccountKind): Boolean = kind in transactionAccountKinds

    fun supportsUserSelectedCashRole(productType: AccountProductType): Boolean =
        productType == AccountProductType.CASH || productType == AccountProductType.BANK

    fun resolve(productType: AccountProductType, requestedCashKind: AccountKind): AccountKind = when (productType) {
        AccountProductType.CASH,
        AccountProductType.BANK -> requestedCashKind.takeIf { it in cashAccountKinds } ?: AccountKind.SPENDING

        AccountProductType.CREDIT_CARD,
        AccountProductType.LOAN -> AccountKind.CREDIT

        AccountProductType.PPF,
        AccountProductType.EPF,
        AccountProductType.NPS,
        AccountProductType.SUPERANNUATION,
        AccountProductType.MUTUAL_FUNDS,
        AccountProductType.EQUITY,
        AccountProductType.STOCKS,
        AccountProductType.BULLION,
        AccountProductType.REAL_ESTATE -> AccountKind.INVESTMENT
    }
}
