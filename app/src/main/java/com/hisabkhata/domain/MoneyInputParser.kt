package com.hisabkhata.domain

import java.math.BigInteger

sealed interface MoneyInputResult {
    data object Empty : MoneyInputResult
    data class Amount(val paisa: Long) : MoneyInputResult
    data object Invalid : MoneyInputResult
    data object TooManyDecimals : MoneyInputResult
    data object TooLarge : MoneyInputResult
}

/** Parses text directly into integer paisa, without Float, Double or rounding. */
object MoneyInputParser {
    private val ungrouped = Regex("[0-9]*")
    private val grouped = Regex("[0-9]{1,3}(,[0-9]{3})+")
    // Also accept the 3-2-2 grouping commonly used in Bangladesh (e.g. 1,23,456).
    private val southAsianGrouped = Regex("[0-9]{1,2}(,[0-9]{2})*,[0-9]{3}")
    private val maxLong = BigInteger.valueOf(Long.MAX_VALUE)

    /** Accept Bengali keyboard digits but display and store values using Latin digits. */
    fun normalizeDigits(raw: String): String = buildString(raw.length) {
        raw.forEach { c -> append(if (c in '০'..'৯') ('0' + (c - '০')) else c) }
    }

    fun parse(raw: String): MoneyInputResult {
        val input = normalizeDigits(raw.trim())
        if (input.isEmpty()) return MoneyInputResult.Empty
        if (input.count { it == '.' } > 1) return MoneyInputResult.Invalid
        val pieces = input.split('.', limit = 2)
        val whole = pieces[0]
        val fraction = pieces.getOrElse(1) { "" }
        if ((whole.isEmpty() && fraction.isEmpty()) ||
            !(ungrouped.matches(whole) || grouped.matches(whole) || southAsianGrouped.matches(whole)) ||
            !ungrouped.matches(fraction)
        ) return MoneyInputResult.Invalid
        if (fraction.length > 2) return MoneyInputResult.TooManyDecimals

        val digits = whole.replace(",", "").trimStart('0').ifEmpty { "0" }
        // Avoid constructing an arbitrarily large BigInteger from pasted input.
        if (digits.length > 17) return MoneyInputResult.TooLarge
        val wholePaisa = BigInteger(digits).multiply(BigInteger.valueOf(100))
        val fractionPaisa = fraction.padEnd(2, '0').ifEmpty { "00" }.toInt()
        val paisa = wholePaisa.add(BigInteger.valueOf(fractionPaisa.toLong()))
        if (paisa > maxLong) return MoneyInputResult.TooLarge
        return MoneyInputResult.Amount(paisa.toLong())
    }
}
