package com.nirmalamgroup.nirmalamdhanam.domain.usecase

import com.nirmalamgroup.nirmalamdhanam.data.local.AccountKind
import com.nirmalamgroup.nirmalamdhanam.data.local.AccountProductType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountRolePolicyTest {
    @Test
    fun `cash and bank accounts preserve spending savings and emergency roles`() {
        for (product in listOf(AccountProductType.CASH, AccountProductType.BANK)) {
            for (role in AccountRolePolicy.cashAccountKinds) {
                assertEquals(role, AccountRolePolicy.resolve(product, role))
            }
        }
    }

    @Test
    fun `invalid cash role falls back to spending`() {
        assertEquals(
            AccountKind.SPENDING,
            AccountRolePolicy.resolve(AccountProductType.BANK, AccountKind.INVESTMENT)
        )
        assertEquals(
            AccountKind.SPENDING,
            AccountRolePolicy.resolve(AccountProductType.CASH, AccountKind.CREDIT)
        )
    }

    @Test
    fun `liability products always resolve to credit`() {
        assertEquals(AccountKind.CREDIT, AccountRolePolicy.resolve(AccountProductType.CREDIT_CARD, AccountKind.SAVINGS))
        assertEquals(AccountKind.CREDIT, AccountRolePolicy.resolve(AccountProductType.LOAN, AccountKind.EMERGENCY))
    }

    @Test
    fun `investment products always resolve to investment`() {
        val investmentProducts = AccountProductType.entries.filterNot {
            it == AccountProductType.CASH ||
                it == AccountProductType.BANK ||
                it == AccountProductType.CREDIT_CARD ||
                it == AccountProductType.LOAN
        }
        investmentProducts.forEach { product ->
            assertEquals(AccountKind.INVESTMENT, AccountRolePolicy.resolve(product, AccountKind.SPENDING))
        }
    }

    @Test
    fun `only cash and bank expose selectable roles`() {
        assertTrue(AccountRolePolicy.supportsUserSelectedCashRole(AccountProductType.CASH))
        assertTrue(AccountRolePolicy.supportsUserSelectedCashRole(AccountProductType.BANK))
        assertFalse(AccountRolePolicy.supportsUserSelectedCashRole(AccountProductType.LOAN))
        assertFalse(AccountRolePolicy.supportsUserSelectedCashRole(AccountProductType.MUTUAL_FUNDS))
    }

    @Test
    fun savings_and_emergency_are_transaction_eligible() {
        assertTrue(AccountRolePolicy.supportsTransactions(AccountKind.SPENDING))
        assertTrue(AccountRolePolicy.supportsTransactions(AccountKind.SAVINGS))
        assertTrue(AccountRolePolicy.supportsTransactions(AccountKind.EMERGENCY))
        assertTrue(AccountRolePolicy.supportsTransactions(AccountKind.CREDIT))
        assertFalse(AccountRolePolicy.supportsTransactions(AccountKind.INVESTMENT))
    }

}
