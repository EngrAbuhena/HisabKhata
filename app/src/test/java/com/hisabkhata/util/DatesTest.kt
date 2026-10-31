package com.hisabkhata.util

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class DatesTest {
    @Test fun displayAndPickerUtcRoundTrip() {
        val day = LocalDate.of(2026, 1, 26).toEpochDay()
        assertEquals("26.01.26", Dates.display(day))
        assertEquals(day, Dates.fromPickerMillis(Dates.toPickerMillis(day)))
        val beforeEpoch = LocalDate.of(1960, 2, 29).toEpochDay()
        assertEquals(beforeEpoch, Dates.fromPickerMillis(Dates.toPickerMillis(beforeEpoch)))
    }
}
