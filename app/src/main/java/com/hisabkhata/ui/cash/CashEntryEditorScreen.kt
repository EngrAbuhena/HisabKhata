package com.hisabkhata.ui.cash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hisabkhata.R
import com.hisabkhata.ui.components.AppDatePickerDialog
import com.hisabkhata.ui.components.ConfirmDeleteDialog
import com.hisabkhata.domain.MoneyFormatter
import com.hisabkhata.util.Dates

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashEntryEditorScreen(onBack: () -> Unit, onDeleted: () -> Unit = onBack, viewModel: CashEntryEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                CashEditorEvent.Finished -> onBack()
                CashEditorEvent.Deleted -> onDeleted()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isEdit) R.string.edit_entry else R.string.add_entry)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.isSaving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (state.isEdit && !state.isLoading && !state.isBlocked) {
                        IconButton(onClick = viewModel::requestDelete, enabled = !state.isSaving) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_entry))
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (!state.isLoading && !state.isBlocked) {
                Surface(shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth().imePadding()) {
                    Box(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Button(onClick = viewModel::save, enabled = !state.isSaving,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text(stringResource(when {
                                state.isSaving -> R.string.saving
                                state.isEdit -> R.string.save_changes
                                else -> R.string.save_entry
                            }))
                        }
                    }
                }
            }
        }
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.isBlocked -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(problemText(state.problem), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
            }
            else -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.date_label), style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { showDatePicker = true }, enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.DateRange, contentDescription = null)
                        Text(Dates.display(state.date), modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                OutlinedTextField(
                    value = state.deposit,
                    onValueChange = viewModel::setDeposit,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isSaving,
                    label = { Text(stringResource(R.string.deposit_label)) },
                    placeholder = { Text(stringResource(R.string.amount_example)) },
                    singleLine = true,
                    isError = state.depositError != null || state.amountRequired,
                    supportingText = state.depositError?.let { error -> { Text(amountErrorText(error)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = state.cost,
                    onValueChange = viewModel::setCost,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isSaving,
                    label = { Text(stringResource(R.string.cost_label)) },
                    placeholder = { Text(stringResource(R.string.amount_example)) },
                    singleLine = true,
                    isError = state.costError != null || state.amountRequired,
                    supportingText = state.costError?.let { error -> { Text(amountErrorText(error)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)
                )
                Text(
                    text = stringResource(if (state.amountRequired) R.string.amount_required else R.string.amount_help),
                    color = if (state.amountRequired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = state.description,
                    onValueChange = viewModel::setDescription,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isSaving,
                    label = { Text(stringResource(R.string.description_label)) },
                    minLines = 3,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
                state.problem?.let {
                    Text(problemText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    if (showDatePicker) AppDatePickerDialog(
        initialDate = state.date,
        onDismiss = { showDatePicker = false },
        onDateSelected = { viewModel.setDate(it); showDatePicker = false }
    )
    if (state.pendingDelete) state.originalEntry?.let { entry ->
        val empty = stringResource(R.string.empty_amount)
        val details = stringResource(R.string.delete_cash_details, Dates.display(entry.date),
            if (entry.deposit == 0L) empty else MoneyFormatter.format(entry.deposit),
            if (entry.cost == 0L) empty else MoneyFormatter.format(entry.cost),
            entry.description.ifBlank { stringResource(R.string.empty_description) })
        ConfirmDeleteDialog(title = stringResource(R.string.delete_confirmation_title),
            message = stringResource(R.string.delete_combine_message, details, stringResource(R.string.delete_irreversible)),
            isBusy = state.isSaving, onCancel = viewModel::cancelDelete, onConfirm = viewModel::confirmDelete)
    }
}

@Composable
private fun amountErrorText(error: AmountError): String = stringResource(when (error) {
    AmountError.INVALID -> R.string.amount_invalid
    AmountError.TOO_MANY_DECIMALS -> R.string.amount_precision_error
    AmountError.TOO_LARGE -> R.string.amount_too_large
})

@Composable
private fun problemText(problem: EditorProblem?): String = stringResource(when (problem) {
    EditorProblem.NOT_FOUND -> R.string.entry_not_found
    EditorProblem.LINKED -> R.string.linked_entry_message
    EditorProblem.LOAD_FAILED -> R.string.entry_load_failed
    EditorProblem.SAVE_FAILED -> R.string.operation_failed
    EditorProblem.DELETE_FAILED -> R.string.delete_failed
    null -> R.string.operation_failed
})
