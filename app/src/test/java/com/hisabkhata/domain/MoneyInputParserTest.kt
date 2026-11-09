package com.hisabkhata.domain

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyInputParserTest {
    @Test fun bengaliDigitsAreAcceptedAndNormalizedWithoutRounding() {
        assertEquals("12,345.67", MoneyInputParser.normalizeDigits("১২,৩৪৫.৬৭"))
        assertEquals(MoneyInputResult.Amount(1_234_567L), MoneyInputParser.parse("১২,৩৪৫.৬৭"))
        assertEquals(MoneyInputResult.Amount(50L), MoneyInputParser.parse(".৫০"))
        assertEquals(MoneyInputResult.TooManyDecimals, MoneyInputParser.parse("১.২৩৪"))
        assertEquals(MoneyInputResult.Invalid, MoneyInputParser.parse("১,২.৩"))
    }

    @Test fun parsesWholeAndFractionalTakaExactly() {
        assertEquals(MoneyInputResult.Amount(5_000_000), MoneyInputParser.parse("50,000"))
        assertEquals(MoneyInputResult.Amount(5_000_025), MoneyInputParser.parse("50,000.25"))
        assertEquals(MoneyInputResult.Amount(50), MoneyInputParser.parse(".5"))
        assertEquals(MoneyInputResult.Amount(105), MoneyInputParser.parse("1.05"))
        assertEquals(MoneyInputResult.Amount(100), MoneyInputParser.parse("1."))
        assertEquals(MoneyInputResult.Empty, MoneyInputParser.parse("  "))
    }

    @Test fun rejectsBadGroupingNegativesAndExtraPrecision() {
        assertEquals(MoneyInputResult.Invalid, MoneyInputParser.parse("12,34"))
        assertEquals(MoneyInputResult.Invalid, MoneyInputParser.parse("-10"))
        assertEquals(MoneyInputResult.Invalid, MoneyInputParser.parse("1.2.3"))
        assertEquals(MoneyInputResult.TooManyDecimals, MoneyInputParser.parse("1.234"))
    }

    @Test fun acceptsLongMaximumPaisaAndRejectsOverflow() {
        assertEquals(MoneyInputResult.Amount(Long.MAX_VALUE), MoneyInputParser.parse("92,233,720,368,547,758.07"))
        assertEquals(MoneyInputResult.TooLarge, MoneyInputParser.parse("92,233,720,368,547,758.08"))
        assertEquals(MoneyInputResult.TooLarge, MoneyInputParser.parse("1000000000000000000"))
    }

    @Test fun formatsWholeFractionalAndNegativeAmounts() {
        assertEquals("50,000", MoneyFormatter.format(5_000_000))
        assertEquals("50,000.05", MoneyFormatter.format(5_000_005))
        assertEquals("-10,000.50", MoneyFormatter.format(-1_000_050))
        assertEquals("184,467,440,737,095,516.14", MoneyFormatter.format(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(2))))
    }
}
