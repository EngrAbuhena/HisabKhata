package com.hisabkhata.domain

import java.math.BigInteger

/** A framework-free snapshot. Each stored amount is a non-negative Long in paisa. */
data class CashMovement(
    val id: Long,
    val date: Long,
    val createdAt: Long,
    val deposit: Long,
    val cost: Long
)

data class BalancedMovement(val id: Long, val balance: BigInteger)

data class CashCalculation(
    val rows: List<BalancedMovement>,
    val currentBalance: BigInteger,
    val totalDeposit: BigInteger,
    val totalCost: BigInteger
)

/**
 * Always calculated on the entire register before search or date filters are applied.
 * BigInteger is used only for derived sums, so many valid Long rows can never overflow.
 * The database itself stores all monetary columns as Long paisa, never floating point.
 */
class CashBalanceCalculator {
    fun calculate(movements: List<CashMovement>): CashCalculation {
        var balance = BigInteger.ZERO
        var deposits = BigInteger.ZERO
        var costs = BigInteger.ZERO
        val rows = movements
            .sortedWith(compareBy<CashMovement> { it.date }.thenBy { it.createdAt }.thenBy { it.id })
            .map { movement ->
                require(movement.deposit >= 0 && movement.cost >= 0) { "Amounts must not be negative" }
                val deposit = BigInteger.valueOf(movement.deposit)
                val cost = BigInteger.valueOf(movement.cost)
                deposits = deposits.add(deposit)
                costs = costs.add(cost)
                balance = balance.add(deposit).subtract(cost)
                BalancedMovement(movement.id, balance)
            }
        return CashCalculation(rows, balance, deposits, costs)
    }
}
