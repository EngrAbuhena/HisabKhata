package com.hisabkhata.ui.cash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hisabkhata.R
import com.hisabkhata.ui.components.AppDatePickerDialog
import com.hisabkhata.util.Dates
import java.time.LocalDate

@Composable
fun MonthFilterDialog(initial: DateFilter.Month?, onDismiss: () -> Unit, onApply: (DateFilter.Month) -> Unit) {
    val today = LocalDate.now()
    var year by rememberSaveable { mutableIntStateOf(initial?.year ?: today.year) }
    var month by rememberSaveable { mutableIntStateOf(initial?.month ?: today.monthValue) }
    val months = stringArrayResource(R.array.short_month_names)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_month)) },
        text = {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { year-- }, enabled = year > Dates.FIRST_PICKER_YEAR) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_year))
                    }
                    Text(year.toString(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    IconButton(onClick = { year++ }, enabled = year < Dates.LAST_PICKER_YEAR) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_year))
                    }
                }
                for (row in 0..3) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (column in 0..2) {
                            val monthNumber = row * 3 + column + 1
                            FilterChip(
                                selected = month == monthNumber,
                                onClick = { month = monthNumber },
                                label = { Text(months[monthNumber - 1], maxLines = 1) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(DateFilter.Month(year, month)) }) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

private enum class RangeEnd { FROM, THROUGH }

@Composable
fun DateRangeFilterDialog(initial: DateFilter.Range?, onDismiss: () -> Unit, onApply: (DateFilter.Range) -> Unit) {
    val today = LocalDate.now()
    var from by rememberSaveable { mutableLongStateOf(initial?.from ?: today.withDayOfMonth(1).toEpochDay()) }
    var through by rememberSaveable { mutableLongStateOf(initial?.through ?: today.toEpochDay()) }
    var picking by rememberSaveable { mutableStateOf<RangeEnd?>(null) }
    val valid = from <= through
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_date_range)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picking = RangeEnd.FROM }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.DateRange, contentDescription = null)
                    Text(stringResource(R.string.range_date_value, stringResource(R.string.range_from), Dates.display(from)),
                        modifier = Modifier.padding(start = 10.dp))
                }
                OutlinedButton(onClick = { picking = RangeEnd.THROUGH }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.DateRange, contentDescription = null)
                    Text(stringResource(R.string.range_date_value, stringResource(R.string.range_to), Dates.display(through)),
                        modifier = Modifier.padding(start = 10.dp))
                }
                if (!valid) Text(stringResource(R.string.range_order_error), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(DateFilter.Range(from, through)) }, enabled = valid) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
    picking?.let { selectedEnd ->
        AppDatePickerDialog(
            initialDate = if (selectedEnd == RangeEnd.FROM) from else through,
            onDismiss = { picking = null },
            onDateSelected = {
                if (selectedEnd == RangeEnd.FROM) from = it else through = it
                picking = null
            }
        )
    }
}
