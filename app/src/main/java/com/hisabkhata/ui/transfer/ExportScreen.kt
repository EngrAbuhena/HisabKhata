package com.hisabkhata.ui.transfer

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hisabkhata.R
import com.hisabkhata.data.transfer.ExportFormat
import com.hisabkhata.data.transfer.ExportKind
import com.hisabkhata.data.transfer.ExportRequest
import com.hisabkhata.ui.cash.DateFilter
import com.hisabkhata.ui.cash.DateRangeFilterDialog
import com.hisabkhata.util.Dates

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(onBack: () -> Unit, viewModel: ExportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    var personId by rememberSaveable { mutableStateOf(viewModel.initialPerson) }
    var from by rememberSaveable { mutableStateOf<Long?>(null) }
    var through by rememberSaveable { mutableStateOf<Long?>(null) }
    var rangeDialog by rememberSaveable { mutableStateOf(false) }
    var peopleMenu by rememberSaveable { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<ExportRequest?>(null) }
    val context = LocalContext.current
    val chooserTitle = stringResource(R.string.export_share_title)
    val pdfSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf"), viewModel::saveTo)
    val excelSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"), viewModel::saveTo)
    val csvSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"), viewModel::saveTo)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val prepared = (event as ExportEvent.Ready).prepared
            if (prepared.delivery == ExportDelivery.SAVE) when (prepared.format) {
                ExportFormat.PDF -> pdfSave.launch(prepared.suggestedName)
                ExportFormat.EXCEL -> excelSave.launch(prepared.suggestedName)
                ExportFormat.CSV -> csvSave.launch(prepared.suggestedName)
            } else try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", prepared.file)
                val intent = Intent(Intent.ACTION_SEND).setType(prepared.format.mime).putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = ClipData.newRawUri(prepared.suggestedName, uri)
                context.startActivity(Intent.createChooser(intent, chooserTitle))
            } catch (_: Exception) { viewModel.shareFailed() }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.export_title)) },
        navigationIcon = { IconButton(onClick = onBack, enabled = !state.busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ExportCard(R.string.export_cash_book) {
                Text(stringResource(R.string.export_date_range), style = MaterialTheme.typography.bodySmall)
                Text(if (from != null && through != null) stringResource(R.string.filter_range_summary, Dates.display(from!!), Dates.display(through!!)) else stringResource(R.string.export_all_dates),
                    style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { rangeDialog = true }, enabled = !state.busy) { Text(stringResource(R.string.export_change_range)) }
                    if (from != null) TextButton(onClick = { from = null; through = null }, enabled = !state.busy) { Text(stringResource(R.string.export_clear_range)) }
                }
                FormatRow(enabled = !state.busy, includePdf = true) { format -> chosen = ExportRequest(ExportKind.CASH, format, from = from, through = through) }
            }
            ExportCard(R.string.export_one_person) {
                val selected = people.firstOrNull { it.id == personId } ?: people.firstOrNull()
                OutlinedButton(onClick = { peopleMenu = true }, enabled = !state.busy && people.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(selected?.name ?: stringResource(R.string.export_select_person))
                }
                DropdownMenu(expanded = peopleMenu, onDismissRequest = { peopleMenu = false }) {
                    people.forEach { person -> DropdownMenuItem(text = { Text(person.name) }, onClick = { personId = person.id; peopleMenu = false }) }
                }
                if (people.isEmpty()) Text(stringResource(R.string.export_no_person), style = MaterialTheme.typography.bodySmall)
                FormatRow(enabled = !state.busy && selected != null, includePdf = true) { format -> chosen = ExportRequest(ExportKind.PERSON, format, selected!!.id) }
            }
            ExportCard(R.string.export_all_loans) {
                Text(stringResource(R.string.export_all_loans_help), style = MaterialTheme.typography.bodySmall)
                FormatRow(enabled = !state.busy, includePdf = false) { format -> chosen = ExportRequest(ExportKind.ALL_LOANS, format) }
            }
            if (state.busy) { CircularProgressIndicator(); Text(stringResource(R.string.export_preparing)) }
            if (state.failed) Text(stringResource(R.string.export_failed), color = MaterialTheme.colorScheme.error)
            if (state.saved) Text(stringResource(R.string.export_saved), color = MaterialTheme.colorScheme.primary)
        }
    }
    if (rangeDialog) DateRangeFilterDialog(
        initial = if (from != null && through != null) DateFilter.Range(from!!, through!!) else null,
        onDismiss = { rangeDialog = false }, onApply = { from = it.from; through = it.through; rangeDialog = false }
    )
    chosen?.let { request -> AlertDialog(onDismissRequest = { chosen = null }, title = { Text(stringResource(R.string.export_action_title)) },
        text = { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { chosen = null; viewModel.prepare(request, ExportDelivery.SAVE) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_save)) }
            OutlinedButton(onClick = { chosen = null; viewModel.prepare(request, ExportDelivery.SHARE) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_share)) }
        } }, dismissButton = { TextButton(onClick = { chosen = null }) { Text(stringResource(R.string.cancel)) } }, confirmButton = {}) }
}

@Composable
private fun ExportCard(title: Int, content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun FormatRow(enabled: Boolean, includePdf: Boolean, onSelect: (ExportFormat) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (includePdf) OutlinedButton(onClick = { onSelect(ExportFormat.PDF) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.export_pdf), maxLines = 1) }
        OutlinedButton(onClick = { onSelect(ExportFormat.EXCEL) }, enabled = enabled, modifier = Modifier.weight(1.6f)) { Text(stringResource(R.string.export_excel), maxLines = 1) }
        OutlinedButton(onClick = { onSelect(ExportFormat.CSV) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.export_csv), maxLines = 1) }
    }
}
