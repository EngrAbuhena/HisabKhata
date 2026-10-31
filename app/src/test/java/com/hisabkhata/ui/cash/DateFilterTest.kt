package com.hisabkhata.ui.cash

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DateFilterTest {
    private fun day(year: Int, month: Int, date: Int) = LocalDate.of(year, month, date).toEpochDay()

    @Test fun monthFilterCoversExactlyTheChosenCalendarMonth() {
        val filter = DateFilter.Month(2026, 2)
        assertFalse(filter.includes(day(2026, 1, 31)))
        assertTrue(filter.includes(day(2026, 2, 1)))
        assertTrue(filter.includes(day(2026, 2, 28)))
        assertFalse(filter.includes(day(2026, 3, 1)))
    }

    @Test fun rangeIncludesBothEndDates() {
        val filter = DateFilter.Range(day(2026, 1, 26), day(2026, 1, 30))
        assertFalse(filter.includes(day(2026, 1, 25)))
        assertTrue(filter.includes(day(2026, 1, 26)))
        assertTrue(filter.includes(day(2026, 1, 30)))
        assertFalse(filter.includes(day(2026, 1, 31)))
    }
}
