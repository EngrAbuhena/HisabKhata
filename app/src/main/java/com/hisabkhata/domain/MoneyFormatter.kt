package com.hisabkhata.domain

import java.math.BigInteger

/** Fixed western thousands grouping as requested; optional two-digit paisa fraction. */
object MoneyFormatter {
    private val hundred = BigInteger.valueOf(100)

    fun format(paisa: Long): String = format(BigInteger.valueOf(paisa))

    fun format(paisa: BigInteger): String {
        val sign = if (paisa.signum() < 0) "-" else ""
        return sign + formatAbsolute(paisa)
    }

    fun formatAbsolute(paisa: BigInteger): String {
        val parts = paisa.abs().divideAndRemainder(hundred)
        val whole = parts[0].toString().reversed().chunked(3).joinToString(",").reversed()
        return if (parts[1] == BigInteger.ZERO) whole else whole + "." + parts[1].toString().padStart(2, '0')
    }
}
