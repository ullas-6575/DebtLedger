package com.ledgerx.domain

import java.math.BigDecimal
import java.util.Locale

enum class Account(val label: String) { CASH("Cash"), BKASH("bKash"), BANK("Bank") }
enum class Category(val label: String) { EAT("Eat"), PAYMENT("Payment"), OTHER("Other") }
enum class Kind(val label: String) {
    INCOME("Income"), EXPENSE("Expense"), TRANSFER("Transfer"),
    LEND("Loan given"), BORROW("Loan taken"), RECEIVE_REPAYMENT("Repayment received"), PAY_REPAYMENT("Repayment paid")
}
data class Movement(
    val id: String,
    val kind: Kind,
    val amount: Long,
    val account: Account,
    val destination: Account? = null,
    val category: Category? = null,
    val person: String? = null,
    val note: String = "",
    val occurredAt: Long = System.currentTimeMillis()
)

data class LedgerSummary(
    val accounts: Map<Account, Long>,
    val people: Map<String, Long>,
    val expenses: Map<Category, Long>
) {
    val available get() = accounts.values.fold(0L, Math::addExact)
    val owedToMe get() = people.values.filter { it > 0 }.fold(0L, Math::addExact)
    val iOwe get() = people.values.filter { it < 0 }.fold(0L) { total, value -> Math.addExact(total, Math.negateExact(value)) }
    val netWorth get() = Math.subtractExact(Math.addExact(available, owedToMe), iOwe)
}

object Money {
    // BDT stored in integer poisha; decimals are never calculated with floating point.
    fun parse(input: String): Long {
        val value = input.trim()
        require(Regex("[0-9]+(\\.[0-9]{1,2})?").matches(value)) { "Enter an amount with up to two decimal places." }
        val minor = try { BigDecimal(value).movePointRight(2).longValueExact() } catch (_: ArithmeticException) { throw IllegalArgumentException("Amount is too large.") }
        require(minor > 0) { "Amount must be greater than zero." }
        require(minor <= 100_000_000_000L) { "Maximum entry is ৳1,000,000,000." }
        return minor
    }
    fun format(minor: Long): String = "৳" + String.format(Locale.US, "%,.2f", BigDecimal.valueOf(minor, 2))
}

object Ledger {
    fun summarize(movements: List<Movement>): LedgerSummary {
        val accounts = Account.entries.associateWith { 0L }.toMutableMap()
        val people = linkedMapOf<String, Long>()
        val expenses = Category.entries.associateWith { 0L }.toMutableMap()
        movements.forEach { m ->
            val incoming = m.kind in setOf(Kind.INCOME, Kind.BORROW, Kind.RECEIVE_REPAYMENT)
            accounts[m.account] = Math.addExact(accounts.getValue(m.account), if (incoming) m.amount else -m.amount)
            if (m.kind == Kind.TRANSFER) m.destination?.let { accounts[it] = Math.addExact(accounts.getValue(it), m.amount) }
            if (m.kind == Kind.EXPENSE) m.category?.let { expenses[it] = Math.addExact(expenses.getValue(it), m.amount) }
            m.person?.let { person ->
                val delta = when (m.kind) {
                    Kind.LEND, Kind.PAY_REPAYMENT -> m.amount
                    Kind.BORROW, Kind.RECEIVE_REPAYMENT -> -m.amount
                    else -> 0L
                }
                people[person] = Math.addExact(people[person] ?: 0L, delta)
            }
        }
        return LedgerSummary(accounts, people, expenses)
    }
    fun validate(m: Movement, existing: List<Movement>) {
        require(m.amount in 1..100_000_000_000L) { "Enter a valid positive amount." }
        require(m.note.length <= 300) { "Note must be at most 300 characters." }
        if (m.kind == Kind.TRANSFER) require(m.destination != null && m.destination != m.account) { "Choose a different destination account." }
        else require(m.destination == null) { "Only transfers can have a destination account." }
        if (m.kind == Kind.EXPENSE) require(m.category != null) { "Choose an expense category." }
        else require(m.category == null) { "Only expenses can have an expense category." }
        val loan = m.kind in setOf(Kind.LEND, Kind.BORROW, Kind.RECEIVE_REPAYMENT, Kind.PAY_REPAYMENT)
        if (loan) require(!m.person.isNullOrBlank() && m.person.length <= 80) { "Enter a person's name (up to 80 characters)." }
        else require(m.person == null) { "Only loans and repayments can name a person." }
        val s = summarize(existing)
        if (m.kind !in setOf(Kind.INCOME, Kind.BORROW, Kind.RECEIVE_REPAYMENT))
            require(s.accounts.getValue(m.account) >= m.amount) { "Insufficient ${m.account.label} balance. Add income first." }
        val balance = s.people[m.person] ?: 0L
        if (m.kind == Kind.RECEIVE_REPAYMENT) require(balance > 0 && m.amount <= balance) { "Repayment cannot exceed what this person owes you." }
        if (m.kind == Kind.PAY_REPAYMENT) require(balance < 0 && m.amount <= -balance) { "Repayment cannot exceed what you owe this person." }
        summarize(existing + m) // Detect aggregate overflow before saving.
    }
}
