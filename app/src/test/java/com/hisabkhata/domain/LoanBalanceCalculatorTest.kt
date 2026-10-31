package com.hisabkhata.domain

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class LoanBalanceCalculatorTest {
    private val calculator = LoanBalanceCalculator()
    private fun taka(value: Long) = value * 100
    private fun amount(value: Long) = BigInteger.valueOf(taka(value))
    private fun row(id: Long, date: Long, loan: Long = 0, paid: Long = 0, createdAt: Long = id) =
        LoanMovement(id, date, createdAt, taka(loan), taka(paid))

    @Test fun loanAndPaymentOnOneRowMatchTheNotebookExample() {
        val result = calculator.calculate(listOf(row(1, 20, loan = 50_000, paid = 30_000)))
        assertEquals(amount(20_000), result.rows.single().due)
        assertEquals(amount(50_000), result.totalLoan)
        assertEquals(amount(30_000), result.totalPaid)
    }

    @Test fun addEditAndDeleteRecalculateLaterDues() {
        val entries = listOf(row(1, 20, loan = 50_000), row(2, 25, paid = 10_000), row(3, 30, loan = 5_000))
        assertEquals(listOf(amount(50_000), amount(40_000), amount(45_000)), calculator.calculate(entries).rows.map { it.due })
        val edited = entries.map { if (it.id == 2L) it.copy(paid = taka(20_000)) else it }
        assertEquals(listOf(amount(50_000), amount(30_000), amount(35_000)), calculator.calculate(edited).rows.map { it.due })
        assertEquals(listOf(amount(50_000), amount(55_000)), calculator.calculate(entries.filterNot { it.id == 2L }).rows.map { it.due })
    }

    @Test fun backDatedAndSameDayEntriesUseDateCreationTimeAndId() {
        val result = calculator.calculate(listOf(
            row(3, 25, loan = 3, createdAt = 50),
            row(2, 25, paid = 2, createdAt = 40),
            row(1, 25, loan = 1, createdAt = 50),
            row(4, 10, loan = 10, createdAt = 99)
        ))
        assertEquals(listOf(4L, 2L, 1L, 3L), result.rows.map { it.id })
        assertEquals(listOf(amount(10), amount(8), amount(9), amount(12)), result.rows.map { it.due })
    }

    @Test fun settledAndOverpaidValuesStayExact() {
        val settled = calculator.calculate(listOf(row(1, 1, loan = 100), row(2, 2, paid = 100)))
        assertEquals(BigInteger.ZERO, settled.currentDue)
        val overpaid = calculator.calculate(listOf(row(1, 1, loan = 100, paid = 125)))
        assertEquals(amount(-25), overpaid.currentDue)
    }

    @Test fun totalsDoNotOverflowLong() {
        val result = calculator.calculate(listOf(
            LoanMovement(1, 1, 1, Long.MAX_VALUE, 0), LoanMovement(2, 2, 2, Long.MAX_VALUE, 0)
        ))
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(2)), result.currentDue)
    }
}
