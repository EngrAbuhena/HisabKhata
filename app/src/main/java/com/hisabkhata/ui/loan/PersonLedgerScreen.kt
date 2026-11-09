package com.hisabkhata.ui.loan
import com.hisabkhata.util.latinPluralResource

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hisabkhata.R
import com.hisabkhata.data.local.entity.PersonEntity
import com.hisabkhata.data.local.entity.PersonType
import com.hisabkhata.domain.MoneyFormatter
import com.hisabkhata.ui.components.ConfirmDeleteDialog
import com.hisabkhata.ui.components.FitLedgerText
import com.hisabkhata.ui.components.MobileLedgerHeader
import com.hisabkhata.ui.components.MobileLedgerRow
import com.hisabkhata.util.Dates
import java.math.BigInteger

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PersonLedgerScreen(
    onBack: () -> Unit,
    onDeleted: () -> Unit = onBack,
    onEditPerson: (Long) -> Unit,
    onAddEntry: (Long) -> Unit,
    onEditEntry: (Long, Long) -> Unit,
    onExportPerson: (Long) -> Unit,
    viewModel: PersonLedgerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val person = state.person
    val addLabel = stringResource(R.string.add_entry)
    LaunchedEffect(viewModel) { viewModel.events.collect { if (it == PersonLedgerEvent.Deleted) onDeleted() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (person == null) stringResource(R.string.nav_loan_ledger)
                        else stringResource(R.string.person_ledger_title, person.name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = { IconButton(onClick = onBack, enabled = !state.isDeleting) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                } },
                actions = {
                    if (person != null) {
                        IconButton(onClick = { onExportPerson(person.id) }, enabled = !state.isDeleting) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.export_person_ledger))
                        }
                        IconButton(onClick = { onEditPerson(person.id) }, enabled = !state.isDeleting) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit_person))
                        }
                        IconButton(onClick = viewModel::requestDelete, enabled = !state.isDeleting) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_person))
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (person != null && !state.isDeleting) ExtendedFloatingActionButton(
                onClick = { onAddEntry(person.id) }, modifier = Modifier.semantics { contentDescription = addLabel },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) }, text = { Text(addLabel) }
            )
        }
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            person == null -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(if (state.problem == PersonLedgerProblem.NOT_FOUND) R.string.person_not_found else R.string.loan_load_error),
                    textAlign = TextAlign.Center)
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("loan_ledger")) {
                item(key = "summary") {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        LoanSummary(person, state)
                        if (person.type == PersonType.LENT) {
                            Spacer(Modifier.height(10.dp))
                            ReminderButton(person, state.currentDue)
                        }
                        if (state.problem == PersonLedgerProblem.DELETE_FAILED) {
                            Text(stringResource(R.string.person_delete_error), color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (state.rows.isEmpty()) {
                    item(key = "empty") { LoanEmptyState() }
                } else {
                    stickyHeader(key = "header") {
                        MobileLedgerHeader(listOf(stringResource(R.string.column_date), stringResource(R.string.column_loan),
                            stringResource(R.string.column_paid), stringResource(R.string.column_due)), Modifier.testTag("loan_ledger_header"))
                    }
                    itemsIndexed(state.rows, key = { _, row -> row.entry.id }) { index, row ->
                        MobileLedgerRow(
                            date = Dates.display(row.entry.date),
                            first = if (row.entry.loan == 0L) stringResource(R.string.empty_amount) else MoneyFormatter.format(row.entry.loan),
                            second = if (row.entry.paid == 0L) stringResource(R.string.empty_amount) else MoneyFormatter.format(row.entry.paid),
                            balance = MoneyFormatter.format(row.due), description = row.entry.description,
                            linked = row.entry.linkedCashEntryId != null, balanceColor = dueColor(row.due),
                            alternating = index % 2 == 1, linkDescription = stringResource(R.string.loan_cash_link_description),
                            modifier = Modifier.testTag("loan_row_${row.entry.id}"), onClick = { onEditEntry(person.id, row.entry.id) }
                        )
                    }
                }
                item(key = "bottom-space") { Spacer(Modifier.height(88.dp)) }
            }
        }
    }
    if (state.pendingDelete && person != null) {
        val warning = latinPluralResource(R.plurals.delete_person_entries_warning, state.rows.size, state.rows.size)
        ConfirmDeleteDialog(title = stringResource(R.string.delete_person_title),
            message = stringResource(R.string.delete_person_details, person.name, warning),
            isBusy = state.isDeleting, onCancel = viewModel::cancelDelete, onConfirm = viewModel::confirmDelete)
    }
}

@Composable
private fun LoanSummary(person: PersonEntity, state: PersonLedgerUiState) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            // The app bar may ellipsize on a narrow device; keep the full name readable here.
            Text(stringResource(R.string.person_ledger_title, person.name), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(personTypeLabel(person.type), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            val dueText = if (state.currentDue.signum() < 0) loanTaka(state.currentDue.abs()) else loanTaka(state.currentDue)
            val status = when {
                state.currentDue.signum() < 0 && person.type == PersonType.LENT -> stringResource(R.string.they_overpaid, dueText)
                state.currentDue.signum() < 0 -> stringResource(R.string.you_overpaid, dueText)
                person.type == PersonType.LENT -> stringResource(R.string.they_owe_you, dueText)
                else -> stringResource(R.string.you_owe, dueText)
            }
            FitLedgerText(status, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge,
                bold = true, color = dueColor(state.currentDue))
            if (state.currentDue.signum() == 0) Text(stringResource(R.string.settled),
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LoanSummaryAmount(stringResource(R.string.total_loan), state.totalLoan, Modifier.weight(1f))
                LoanSummaryAmount(stringResource(R.string.total_paid), state.totalPaid, Modifier.weight(1f))
                LoanSummaryAmount(stringResource(R.string.current_due), state.currentDue, Modifier.weight(1f), highlightDue = true)
            }
            Text(stringResource(if (person.type == PersonType.LENT) R.string.meaning_lent else R.string.meaning_borrowed),
                modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (person.phone.isNotBlank()) Text(stringResource(R.string.person_phone_value, person.phone),
                modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (person.note.isNotBlank()) Text(stringResource(R.string.person_note_value, person.note),
                modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun LoanSummaryAmount(label: String, value: BigInteger, modifier: Modifier = Modifier, highlightDue: Boolean = false) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        FitLedgerText(loanTaka(value), modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall,
            bold = true, color = if (highlightDue) dueColor(value) else MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun ReminderButton(person: PersonEntity, due: BigInteger) {
    val context = LocalContext.current
    val message = stringResource(R.string.reminder_message, person.name, loanTaka(due))
    val chooserTitle = stringResource(R.string.reminder_share_title)
    val unavailable = stringResource(R.string.share_not_available)
    OutlinedButton(onClick = {
        try {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
            context.startActivity(Intent.createChooser(send, chooserTitle))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, unavailable, Toast.LENGTH_LONG).show()
        }
    }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Icon(Icons.Filled.Share, contentDescription = null)
        Text(stringResource(R.string.send_reminder), modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun LoanEmptyState() {
    Column(Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.loan_empty_title), style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        Text(stringResource(R.string.loan_empty_message), style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

