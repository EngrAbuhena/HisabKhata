package com.hisabkhata.domain

import java.math.BigInteger

/** Framework-free running-due inputs. Stored amounts are non-negative Long paisa. */
data class LoanMovement(
    val id: Long,
    val date: Long,
    val createdAt: Long,
    val loan: Long,
    val paid: Long
)

data class BalancedLoanMovement(val id: Long, val due: BigInteger)

data class LoanCalculation(
    val rows: List<BalancedLoanMovement>,
    val totalLoan: BigInteger,
    val totalPaid: BigInteger,
    val currentDue: BigInteger
)

/** Derived totals use exact integers and are recomputed over the complete person ledger. */
class LoanBalanceCalculator {
    fun calculate(movements: List<LoanMovement>): LoanCalculation {
        var due = BigInteger.ZERO
        var totalLoan = BigInteger.ZERO
        var totalPaid = BigInteger.ZERO
        val rows = movements
            .sortedWith(compareBy<LoanMovement> { it.date }.thenBy { it.createdAt }.thenBy { it.id })
            .map { entry ->
                require(entry.loan >= 0 && entry.paid >= 0) { "Loan and paid amounts cannot be negative" }
                val loan = BigInteger.valueOf(entry.loan)
                val paid = BigInteger.valueOf(entry.paid)
                totalLoan = totalLoan.add(loan)
                totalPaid = totalPaid.add(paid)
                due = due.add(loan).subtract(paid)
                BalancedLoanMovement(entry.id, due)
            }
        return LoanCalculation(rows, totalLoan, totalPaid, due)
    }
}
