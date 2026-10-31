package com.hisabkhata.domain

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class CashBalanceCalculatorTest {
    private val calculator = CashBalanceCalculator()
    private fun taka(value: Long) = value * 100
    private fun movement(id: Long, date: Long, createdAt: Long = id, deposit: Long = 0, cost: Long = 0) =
        CashMovement(id, date, createdAt, taka(deposit), taka(cost))
    private fun amount(value: Long) = BigInteger.valueOf(taka(value))

    @Test fun addEntryUpdatesBalancesAndSummary() {
        val initial = listOf(movement(1, 26, deposit = 50_000, cost = 20_000))
        val result = calculator.calculate(initial + movement(2, 28, cost = 10_000))
        assertEquals(listOf(amount(30_000), amount(20_000)), result.rows.map { it.balance })
        assertEquals(amount(20_000), result.currentBalance)
        assertEquals(amount(50_000), result.totalDeposit)
        assertEquals(amount(30_000), result.totalCost)
    }

    @Test fun editEntryRecalculatesEveryFollowingTotal() {
        val entries = listOf(
            movement(1, 26, deposit = 50_000, cost = 20_000),
            movement(2, 28, cost = 10_000),
            movement(3, 30, deposit = 20_000)
        )
        val edited = entries.map { if (it.id == 2L) it.copy(cost = taka(5_000)) else it }
        val result = calculator.calculate(edited)
        assertEquals(listOf(amount(30_000), amount(25_000), amount(45_000)), result.rows.map { it.balance })
        assertEquals(amount(25_000), result.totalCost)
    }

    @Test fun deleteEntryRecalculatesFollowingRows() {
        val entries = listOf(
            movement(1, 26, deposit = 50_000, cost = 20_000),
            movement(2, 28, cost = 10_000),
            movement(3, 30, deposit = 20_000)
        )
        val result = calculator.calculate(entries.filterNot { it.id == 2L })
        assertEquals(listOf(1L, 3L), result.rows.map { it.id })
        assertEquals(listOf(amount(30_000), amount(50_000)), result.rows.map { it.balance })
        assertEquals(amount(20_000), result.totalCost)
    }

    @Test fun backDatedEntryIsInsertedChronologically() {
        val entries = listOf(movement(1, 26, deposit = 50_000), movement(2, 30, cost = 10_000))
        val result = calculator.calculate(entries + movement(3, 20, deposit = 5_000))
        assertEquals(listOf(3L, 1L, 2L), result.rows.map { it.id })
        assertEquals(listOf(amount(5_000), amount(55_000), amount(45_000)), result.rows.map { it.balance })
    }

    @Test fun depositAndCostOnTheSameRowAreBothApplied() {
        val result = calculator.calculate(listOf(movement(1, 26, deposit = 50_000, cost = 20_000)))
        assertEquals(amount(30_000), result.rows.single().balance)
        assertEquals(amount(50_000), result.totalDeposit)
        assertEquals(amount(20_000), result.totalCost)
    }

    @Test fun negativeBalancesArePreservedAndCanRecover() {
        val result = calculator.calculate(listOf(movement(1, 1, cost = 15_000), movement(2, 2, deposit = 20_000)))
        assertEquals(listOf(amount(-15_000), amount(5_000)), result.rows.map { it.balance })
        assertEquals(amount(5_000), result.currentBalance)
    }

    @Test fun sameDayUsesCreatedAtThenIdAsStableTieBreaker() {
        val result = calculator.calculate(listOf(
            movement(3, 10, createdAt = 50, deposit = 3),
            movement(2, 10, createdAt = 40, deposit = 2),
            movement(1, 10, createdAt = 50, deposit = 1)
        ))
        assertEquals(listOf(2L, 1L, 3L), result.rows.map { it.id })
        assertEquals(listOf(amount(2), amount(3), amount(6)), result.rows.map { it.balance })
    }

    @Test fun sumsCannotOverflowEvenThoughEachStoredAmountIsLong() {
        val result = calculator.calculate(listOf(
            CashMovement(1, 1, 1, Long.MAX_VALUE, 0),
            CashMovement(2, 2, 2, Long.MAX_VALUE, 0)
        ))
        val expected = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(2))
        assertEquals(expected, result.totalDeposit)
        assertEquals(expected, result.currentBalance)
    }

    @Test fun notebookExampleProducesThirtyThenTwentyThenFortyThousand() {
        val result = calculator.calculate(listOf(
            movement(1, 26, deposit = 50_000, cost = 20_000),
            movement(2, 28, cost = 10_000),
            movement(3, 30, deposit = 20_000)
        ))
        assertEquals(listOf(amount(30_000), amount(20_000), amount(40_000)), result.rows.map { it.balance })
        assertEquals(amount(70_000), result.totalDeposit)
        assertEquals(amount(30_000), result.totalCost)
    }

    @Test fun emptyRegisterHasZeroSummary() {
        val result = calculator.calculate(emptyList())
        assertEquals(emptyList<BalancedMovement>(), result.rows)
        assertEquals(BigInteger.ZERO, result.currentBalance)
        assertEquals(BigInteger.ZERO, result.totalDeposit)
        assertEquals(BigInteger.ZERO, result.totalCost)
    }
}
