package com.hisabkhata.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object Dates {
    private val displayFormatter = DateTimeFormatter.ofPattern("dd.MM.yy", Locale.US)
    const val FIRST_PICKER_YEAR = 1
    const val LAST_PICKER_YEAR = 9999

    fun today(): Long = LocalDate.now().toEpochDay()
    fun display(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(displayFormatter)

    // Material's date picker uses midnight UTC. Epoch-day storage avoids local-zone shifts.
    fun toPickerMillis(epochDay: Long): Long =
        LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun fromPickerMillis(millis: Long): Long =
        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
}
