package com.hisabkhata.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hisabkhata.R
import com.hisabkhata.util.Dates
import java.util.Locale

/** The picker exchanges UTC-midnight milliseconds; the rest of the app uses epoch days. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDatePickerDialog(initialDate: Long, onDismiss: () -> Unit, onDateSelected: (Long) -> Unit) {
    val currentConfiguration = LocalConfiguration.current
    // Only the calendar's month/weekday names are forced to English for Step 1.
    val englishConfiguration = remember(currentConfiguration) {
        Configuration(currentConfiguration).apply { setLocale(Locale.US) }
    }
    CompositionLocalProvider(LocalConfiguration provides englishConfiguration) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = Dates.toPickerMillis(initialDate),
            yearRange = Dates.FIRST_PICKER_YEAR..Dates.LAST_PICKER_YEAR
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = { pickerState.selectedDateMillis?.let { onDateSelected(Dates.fromPickerMillis(it)) } },
                    enabled = pickerState.selectedDateMillis != null
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
        ) {
            DatePicker(
                state = pickerState,
                showModeToggle = false,
                title = {
                    Text(stringResource(R.string.choose_date), modifier = Modifier.padding(start = 24.dp, top = 16.dp),
                        style = MaterialTheme.typography.labelLarge)
                },
                headline = {
                    val selected = pickerState.selectedDateMillis?.let { Dates.fromPickerMillis(it) } ?: initialDate
                    Text(stringResource(R.string.selected_date, Dates.display(selected)),
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.headlineSmall)
                }
            )
        }
    }
}
