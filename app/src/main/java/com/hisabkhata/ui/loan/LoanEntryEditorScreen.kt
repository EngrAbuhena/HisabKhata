package com.hisabkhata.ui.loan

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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hisabkhata.R
import com.hisabkhata.data.local.entity.PersonType
import com.hisabkhata.ui.components.AppDatePickerDialog
import com.hisabkhata.ui.components.ConfirmDeleteDialog
import com.hisabkhata.domain.MoneyFormatter
import com.hisabkhata.util.Dates

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoanEntryEditorScreen(onBack: () -> Unit, onDeleted: () -> Unit = onBack, viewModel: LoanEntryEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDate by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel) { viewModel.events.collect {
        when (it) {
            LoanEntryEditorEvent.Finished -> onBack()
            LoanEntryEditorEvent.Deleted -> onDeleted()
        }
    } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isEdit) R.string.edit_entry else R.string.add_entry)) },
                navigationIcon = { IconButton(onClick = onBack, enabled = !state.isSaving) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                } },
                actions = {
                    if (state.isEdit && !state.isLoading && !state.isBlocked) IconButton(onClick = viewModel::requestDelete, enabled = !state.isSaving) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_entry))
                    }
                }
            )
        },
        bottomBar = {
            if (!state.isLoading && !state.isBlocked) Surface(shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth().imePadding()) {
                Box(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Button(onClick = viewModel::save, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(stringResource(when {
                            state.isSaving -> R.string.saving
                            state.isEdit -> R.string.save_changes
                            else -> R.string.save_entry
                        }))
                    }
                }
            }
        }
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.isBlocked -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(loanEditorProblem(state.problem), textAlign = TextAlign.Center)
            }
            else -> Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.date_label), style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { showDate = true }, enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.DateRange, contentDescription = null)
                        Text(Dates.display(state.date), modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                OutlinedTextField(
                    value = state.loan, onValueChange = viewModel::setLoan, modifier = Modifier.fillMaxWidth(), enabled = !state.isSaving,
                    label = { Text(stringResource(R.string.loan_amount_label)) },
                    placeholder = { Text(stringResource(R.string.amount_example)) }, singleLine = true,
                    isError = state.loanError != null || state.amountRequired,
                    supportingText = state.loanError?.let { error -> { Text(loanAmountError(error)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = state.paid, onValueChange = viewModel::setPaid, modifier = Modifier.fillMaxWidth(), enabled = !state.isSaving,
                    label = { Text(stringResource(R.string.paid_amount_label)) },
                    placeholder = { Text(stringResource(R.string.amount_example)) }, singleLine = true,
                    isError = state.paidError != null || state.amountRequired,
                    supportingText = state.paidError?.let { error -> { Text(loanAmountError(error)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)
                )
                Text(stringResource(if (state.amountRequired) R.string.loan_amount_required else R.string.amount_help),
                    color = if (state.amountRequired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = state.description, onValueChange = viewModel::setDescription, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.description_label)) }, enabled = !state.isSaving,
                    minLines = 2, maxLines = 5, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 72.dp)
                        .toggleable(value = state.recordInCash, enabled = !state.isSaving, role = Role.Switch, onValueChange = viewModel::setRecordInCash),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(stringResource(R.string.also_record_cash), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(if (state.person?.type == PersonType.BORROWED) R.string.cash_mapping_borrowed else R.string.cash_mapping_lent),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = state.recordInCash, onCheckedChange = null, enabled = !state.isSaving)
                }
                state.problem?.let { Text(loanEditorProblem(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    if (showDate) AppDatePickerDialog(initialDate = state.date, onDismiss = { showDate = false },
        onDateSelected = { viewModel.setDate(it); showDate = false })
    if (state.pendingDelete) state.originalEntry?.let { entry ->
        val empty = stringResource(R.string.empty_amount)
        val details = stringResource(R.string.delete_loan_details, Dates.display(entry.date),
            if (entry.loan == 0L) empty else MoneyFormatter.format(entry.loan),
            if (entry.paid == 0L) empty else MoneyFormatter.format(entry.paid),
            entry.description.ifBlank { stringResource(R.string.empty_description) })
        val warning = if (entry.linkedCashEntryId != null) stringResource(R.string.delete_combine_message,
            stringResource(R.string.delete_linked_cash_warning), stringResource(R.string.delete_irreversible))
            else stringResource(R.string.delete_irreversible)
        ConfirmDeleteDialog(title = stringResource(R.string.loan_delete_title),
            message = stringResource(R.string.delete_combine_message, details, warning),
            isBusy = state.isSaving, onCancel = viewModel::cancelDelete, onConfirm = viewModel::confirmDelete)
    }
}

@Composable
private fun loanAmountError(error: LoanAmountError): String = stringResource(when (error) {
    LoanAmountError.INVALID -> R.string.amount_invalid
    LoanAmountError.TOO_MANY_DECIMALS -> R.string.amount_precision_error
    LoanAmountError.TOO_LARGE -> R.string.amount_too_large
})

@Composable
private fun loanEditorProblem(problem: LoanEditorProblem?): String = stringResource(when (problem) {
    LoanEditorProblem.NOT_FOUND -> R.string.entry_not_found
    LoanEditorProblem.LOAD_FAILED -> R.string.entry_load_failed
    LoanEditorProblem.DELETE_FAILED -> R.string.loan_delete_failed
    LoanEditorProblem.LINK_FAILED -> R.string.loan_link_error
    LoanEditorProblem.SAVE_FAILED, null -> R.string.operation_failed
})
