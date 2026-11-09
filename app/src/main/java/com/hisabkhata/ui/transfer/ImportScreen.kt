package com.hisabkhata.ui.transfer
import com.hisabkhata.util.latinStringResource

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hisabkhata.R
import com.hisabkhata.data.local.entity.PersonType
import com.hisabkhata.domain.MoneyFormatter
import com.hisabkhata.domain.interchange.ImportIssue
import com.hisabkhata.domain.interchange.ImportKind
import com.hisabkhata.domain.interchange.ImportLine
import com.hisabkhata.util.Dates

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onBack: () -> Unit, viewModel: ImportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingKind by rememberSaveable { mutableStateOf<ImportKind?>(null) }
    var pendingSample by rememberSaveable { mutableStateOf<SampleFile?>(null) }
    val cashCsvName = stringResource(R.string.sample_filename_cash_csv)
    val cashExcelName = stringResource(R.string.sample_filename_cash_excel)
    val loanCsvName = stringResource(R.string.sample_filename_loan_csv)
    val loanExcelName = stringResource(R.string.sample_filename_loan_excel)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val kind = pendingKind; pendingKind = null
        if (uri != null && kind != null) viewModel.load(uri, kind)
    }
    val csvSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val sample = pendingSample; pendingSample = null
        if (uri != null && sample != null) viewModel.saveSample(uri, sample)
    }
    val xlsxSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        val sample = pendingSample; pendingSample = null
        if (uri != null && sample != null) viewModel.saveSample(uri, sample)
    }
    fun back() { if (state.preview != null) viewModel.closePreview() else onBack() }
    BackHandler(enabled = state.preview != null && !state.busy) { back() }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(if (state.preview == null) R.string.import_title else R.string.import_preview)) },
            navigationIcon = { IconButton(onClick = { back() }, enabled = !state.busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }) },
        bottomBar = {
            if (state.preview != null) Surface(shadowElevation = 4.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::closePreview, enabled = !state.busy, modifier = Modifier.weight(0.8f).heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(onClick = viewModel::importRows, enabled = !state.busy && state.preview!!.lines.any { it.record != null },
                        modifier = Modifier.weight(1.6f).heightIn(min = 52.dp)) {
                        Text(stringResource(if (state.busy) R.string.importing else R.string.import_valid_button))
                    }
                }
            }
        }
    ) { padding ->
        val preview = state.preview
        if (preview == null) {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.import_help), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { pendingKind = ImportKind.CASH; picker.launch(arrayOf("text/*", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream", "application/zip")) },
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.import_cash)) }
                Button(onClick = { pendingKind = ImportKind.LOAN; picker.launch(arrayOf("text/*", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream", "application/zip")) },
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.import_loans)) }
                Text(stringResource(R.string.sample_files), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                SampleButton(R.string.sample_cash_csv, state.busy) { pendingSample = SampleFile.CASH_CSV; csvSave.launch(cashCsvName) }
                SampleButton(R.string.sample_cash_excel, state.busy) { pendingSample = SampleFile.CASH_EXCEL; xlsxSave.launch(cashExcelName) }
                SampleButton(R.string.sample_loan_csv, state.busy) { pendingSample = SampleFile.LOAN_CSV; csvSave.launch(loanCsvName) }
                SampleButton(R.string.sample_loan_excel, state.busy) { pendingSample = SampleFile.LOAN_EXCEL; xlsxSave.launch(loanExcelName) }
                if (state.busy) { CircularProgressIndicator(); Text(stringResource(R.string.import_loading)) }
                if (state.sampleSaved) Text(stringResource(R.string.sample_saved), color = MaterialTheme.colorScheme.primary)
                state.problem?.let { Text(importProblem(it), color = MaterialTheme.colorScheme.error) }
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "controls") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val valid = preview.lines.count { it.record != null }; val errors = preview.lines.size - valid
                    Text(latinStringResource(R.string.import_counts, valid, errors, preview.lines.count { it.duplicate }), style = MaterialTheme.typography.titleSmall)
                    TransferSwitch(R.string.import_skip_duplicates, R.string.import_skip_help, state.skipDuplicates, !state.busy, viewModel::setSkip)
                    if (preview.kind == ImportKind.LOAN) TransferSwitch(R.string.import_create_cash, R.string.import_create_cash_help, state.createCash, !state.busy, viewModel::setCreateCash)
                    state.problem?.let { Text(importProblem(it), color = MaterialTheme.colorScheme.error) }
                    if (preview.lines.isEmpty()) Text(stringResource(R.string.import_no_rows), modifier = Modifier.padding(vertical = 16.dp))
                }
            }
            items(preview.lines, key = { it.sourceRow }) { line -> PreviewRow(line, preview.kind) }
            item(key = "bottom") { Spacer(Modifier.height(16.dp)) }
        }
    }
    state.result?.let { result ->
        AlertDialog(onDismissRequest = viewModel::dismissResult, title = { Text(stringResource(R.string.import_result_title)) },
            text = { Text(latinStringResource(R.string.import_result_message, result.imported, result.duplicatesSkipped, result.errorRows, result.peopleCreated)) },
            confirmButton = { TextButton(onClick = viewModel::dismissResult) { Text(stringResource(R.string.ok)) } })
    }
}

@Composable
private fun SampleButton(label: Int, busy: Boolean, action: () -> Unit) {
    OutlinedButton(onClick = action, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(label)) }
}

@Composable
internal fun TransferSwitch(title: Int, subtitle: Int, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).toggleable(value = checked, onValueChange = onChecked, enabled = enabled, role = Role.Switch),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun PreviewRow(line: ImportLine, kind: ImportKind) {
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(latinStringResource(R.string.import_row_number, line.sourceRow), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(when { line.record == null -> R.string.import_error_row; line.duplicate -> R.string.import_duplicate_row; else -> R.string.import_valid_row }),
                    color = if (line.record == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium)
            }
            val r = line.record
            val content = if (r == null) line.cells.joinToString("  |  ") else if (kind == ImportKind.CASH)
                stringResource(R.string.import_cash_preview_row, Dates.display(r.date), MoneyFormatter.format(r.first), MoneyFormatter.format(r.second), r.description)
            else stringResource(R.string.import_loan_preview_row, r.personName,
                stringResource(if (r.personType == PersonType.BORROWED) R.string.person_type_borrowed else R.string.person_type_lent),
                Dates.display(r.date), MoneyFormatter.format(r.first), MoneyFormatter.format(r.second), r.description)
            Text(content, style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis)
            line.issues.forEach { issue -> Text(stringResource(when (issue) {
                ImportIssue.MISSING_PERSON -> R.string.import_person_required
                ImportIssue.INVALID_PERSON_TYPE -> R.string.import_person_type_error
                ImportIssue.INVALID_DATE -> R.string.import_date_error
                ImportIssue.INVALID_AMOUNT -> R.string.import_amount_error
                ImportIssue.AMOUNT_REQUIRED -> R.string.import_amount_required
            }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun importProblem(problem: ImportProblem): String = stringResource(when (problem) {
    ImportProblem.INVALID_FILE -> R.string.import_invalid_file
    ImportProblem.MISSING_COLUMNS -> R.string.import_missing_columns
    ImportProblem.FAILED -> R.string.import_failed
    ImportProblem.SAMPLE_FAILED -> R.string.export_failed
})
