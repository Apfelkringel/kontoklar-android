package de.kontoklar.app

import java.time.LocalDate
import java.util.UUID

data class ExpensePayment(
    val id: String = UUID.randomUUID().toString(),
    val expenseId: String,
    val amountCents: Long,
    val date: String,
    val source: String = "Manuell",
    val bankTransactionId: String? = null
)

fun expensePaidCents(expense: Expense, payments: List<ExpensePayment>): Long =
    payments.asSequence().filter { it.expenseId == expense.id }.fold(0L) { total, payment -> Math.addExact(total, payment.amountCents) }

fun recordExpensePayment(
    expense: Expense,
    payments: List<ExpensePayment>,
    amountCents: Long,
    date: LocalDate,
    source: String = "Manuell",
    bankTransactionId: String? = null
): ExpensePayment {
    require(source in setOf("Manuell", "Kontoauszug")) { "Die Zahlungsquelle ist ungültig." }
    require((source == "Kontoauszug") == (bankTransactionId != null)) { "Die Kontoauszugs-Zuordnung ist unvollständig." }
    require(amountCents > 0) { "Der Zahlungsbetrag muss positiv sein." }
    val paid = expensePaidCents(expense, payments)
    require(amountCents <= expense.amountCents - paid) { "Der Zahlungsbetrag überschreitet den offenen Ausgabenbetrag." }
    return ExpensePayment(expenseId = expense.id, amountCents = amountCents, date = date.toString(), source = source, bankTransactionId = bankTransactionId)
}

fun recoverMatchedBankExpensePayments(
    expenses: List<Expense>,
    payments: List<ExpensePayment>,
    transactions: List<BankTransaction>
): List<ExpensePayment> {
    val knownTransactionIds = payments.mapNotNullTo(mutableSetOf(), ExpensePayment::bankTransactionId)
    val expensesById = expenses.associateBy(Expense::id)
    val remainingByExpense = expenses.associate { expense -> expense.id to (expense.amountCents - expensePaidCents(expense, payments)) }.toMutableMap()
    return transactions.asSequence()
        .filter { transaction -> transaction.matchedExpenseId != null && transaction.id !in knownTransactionIds && transaction.amountCents < 0 && transaction.amountCents != Long.MIN_VALUE && runCatching { LocalDate.parse(transaction.date) }.isSuccess }
        .mapNotNull { transaction ->
            val expense = expensesById[transaction.matchedExpenseId] ?: return@mapNotNull null
            val amount = -transaction.amountCents
            if (amount != expense.amountCents || remainingByExpense[expense.id] != expense.amountCents) return@mapNotNull null
            remainingByExpense[expense.id] = 0L
            ExpensePayment(expenseId = expense.id, amountCents = amount, date = transaction.date, source = "Kontoauszug", bankTransactionId = transaction.id)
        }.toList()
}
