package com.ledgerx.domain

import org.junit.Assert.*
import org.junit.Test

class LedgerTest {
    private var counter = 0
    private fun m(kind: Kind, amount: Long, account: Account = Account.CASH, person: String? = null, category: Category? = null, destination: Account? = null) = Movement((counter++).toString(), kind, amount, account, destination, category, person)
    @Test fun `given and taken loans affect opposite balances`() {
        val entries = listOf(m(Kind.INCOME, 100000), m(Kind.LEND, 30000, person = "Rahim"), m(Kind.BORROW, 20000, Account.BKASH, "Karim"))
        val s = Ledger.summarize(entries)
        assertEquals(70000L, s.accounts[Account.CASH])
        assertEquals(20000L, s.accounts[Account.BKASH])
        assertEquals(30000L, s.people["Rahim"])
        assertEquals(-20000L, s.people["Karim"])
        assertEquals(100000L, s.netWorth)
    }
    @Test fun `repayments reduce debt and conserve net worth`() {
        val entries = listOf(m(Kind.INCOME, 100000), m(Kind.LEND, 30000, person = "Rahim"), m(Kind.BORROW, 20000, person = "Karim"))
        val receive = m(Kind.RECEIVE_REPAYMENT, 10000, person = "Rahim")
        Ledger.validate(receive, entries)
        val pay = m(Kind.PAY_REPAYMENT, 5000, person = "Karim")
        Ledger.validate(pay, entries + receive)
        val s = Ledger.summarize(entries + receive + pay)
        assertEquals(20000L, s.people["Rahim"])
        assertEquals(-15000L, s.people["Karim"])
        assertEquals(100000L, s.netWorth)
    }
    @Test fun `transfer preserves total and expenses use only selected category`() {
        val entries = listOf(m(Kind.INCOME, 100000), m(Kind.TRANSFER, 40000, destination = Account.BANK), m(Kind.EXPENSE, 12550, Account.BANK, category = Category.EAT))
        val s = Ledger.summarize(entries)
        assertEquals(60000L, s.accounts[Account.CASH])
        assertEquals(27450L, s.accounts[Account.BANK])
        assertEquals(87450L, s.available)
        assertEquals(12550L, s.expenses[Category.EAT])
        assertEquals(0L, s.expenses[Category.PAYMENT])
        assertTrue(s.people.isEmpty())
    }
    @Test fun `decimal amounts are exact`() {
        assertEquals(101L, Money.parse("1.01"))
        assertEquals(123456L, Money.parse("1234.56"))
        assertEquals("৳1,234.56", Money.format(123456))
        listOf("0", "-1", "1.001", "NaN", "1e5", "", "1000000001", "99999999999999999999999").forEach { input -> assertThrows(IllegalArgumentException::class.java) { Money.parse(input) } }
    }
    @Test fun `invalid transfers and overdrafts rejected`() {
        val existing = listOf(m(Kind.INCOME, 1000))
        assertThrows(IllegalArgumentException::class.java) { Ledger.validate(m(Kind.TRANSFER, 500, destination = Account.CASH), existing) }
        assertThrows(IllegalArgumentException::class.java) { Ledger.validate(m(Kind.EXPENSE, 1001, category = Category.OTHER), existing) }
        assertThrows(IllegalArgumentException::class.java) { Ledger.validate(m(Kind.LEND, 10, person = " "), existing) }
    }
    @Test fun `over repayments rejected in both directions`() {
        val existing = listOf(m(Kind.INCOME, 10000), m(Kind.LEND, 3000, person = "Rahim"), m(Kind.BORROW, 2000, person = "Karim"))
        assertThrows(IllegalArgumentException::class.java) { Ledger.validate(m(Kind.RECEIVE_REPAYMENT, 3001, person = "Rahim"), existing) }
        assertThrows(IllegalArgumentException::class.java) { Ledger.validate(m(Kind.PAY_REPAYMENT, 2001, person = "Karim"), existing) }
        assertThrows(IllegalArgumentException::class.java) { Ledger.validate(m(Kind.RECEIVE_REPAYMENT, 1, person = "Unknown"), existing) }
    }
    @Test fun `opposing loans for same person net correctly`() {
        val s = Ledger.summarize(listOf(m(Kind.INCOME, 10000), m(Kind.LEND, 3000, person = "Rahim"), m(Kind.BORROW, 5000, person = "Rahim")))
        assertEquals(-2000L, s.people["Rahim"])
        assertEquals(0L, s.owedToMe)
        assertEquals(2000L, s.iOwe)
    }
}
