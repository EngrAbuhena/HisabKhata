package com.hisabkhata.ui.loan
import com.hisabkhata.util.latinPluralResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hisabkhata.R
import com.hisabkhata.ui.components.CompactResultCount
import com.hisabkhata.ui.components.CompactSearchField
import com.hisabkhata.ui.components.FitLedgerText
import java.math.BigInteger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(onAddPerson: () -> Unit, onOpenPerson: (Long) -> Unit, viewModel: PeopleViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(viewModel.currentQuery) }
    LaunchedEffect(viewModel) { viewModel.setQuery(query) }
    val addLabel = stringResource(R.string.add_person)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Preserve the add-person action without covering search in short windows.
        val shortWindow = maxHeight < 360.dp
        Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_loan_ledger)) }, actions = {
            if (shortWindow && !state.loadError) IconButton(onClick = onAddPerson) {
                Icon(Icons.Filled.Add, contentDescription = addLabel)
            }
        }) },
        floatingActionButton = {
            if (!state.loadError && !shortWindow) ExtendedFloatingActionButton(
                onClick = onAddPerson,
                modifier = Modifier.semantics { contentDescription = addLabel },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(addLabel) }
            )
        }
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadError -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.people_load_error), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            else -> BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val tightHeight = maxHeight < (112f + 58f * LocalDensity.current.fontScale).dp
                Column(Modifier.fillMaxSize()) {
                    PeopleSummary(state.totalToReceive, state.totalToPay, compact = tightHeight,
                        modifier = Modifier.padding(horizontal = 12.dp).testTag("people_summary"))
                    Spacer(Modifier.height(if (tightHeight) 4.dp else 8.dp))
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("people_list"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(key = "search") {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("people_controls")) {
                                CompactSearchField(query, { query = it; viewModel.setQuery(it) },
                                    stringResource(R.string.search_people), Modifier.testTag("people_search"))
                                Spacer(Modifier.height(8.dp))
                                val count = if (query.isNotBlank()) {
                                    latinPluralResource(R.plurals.people_shown, state.totalCount, state.rows.size, state.totalCount)
                                } else latinPluralResource(R.plurals.people_count, state.totalCount, state.totalCount)
                                CompactResultCount(count, Modifier.testTag("people_status"))
                            }
                        }
                        when {
                            state.totalCount == 0 -> item(key = "empty") {
                                PeopleEmptyState(stringResource(R.string.people_empty_title), stringResource(R.string.people_empty_message))
                            }
                            state.rows.isEmpty() -> item(key = "no-results") {
                                PeopleEmptyState(stringResource(R.string.people_no_results_title), stringResource(R.string.people_no_results_message)) {
                                    TextButton(onClick = { query = ""; viewModel.setQuery("") }) { Text(stringResource(R.string.clear_search)) }
                                }
                            }
                            else -> items(state.rows, key = { it.person.id }) { row ->
                                PersonCard(row, Modifier.padding(horizontal = 12.dp), onClick = { onOpenPerson(row.person.id) })
                            }
                        }
                        item(key = "bottom-space") { Spacer(Modifier.height(88.dp)) }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun PeopleSummary(receive: BigInteger, pay: BigInteger, compact: Boolean = false, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
        if (compact) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FitLedgerText(stringResource(R.string.summary_metric_inline, stringResource(R.string.total_to_receive), loanTaka(receive)),
                    Modifier.weight(1f), style = MaterialTheme.typography.labelSmall.copy(lineHeight = 12.sp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                FitLedgerText(stringResource(R.string.summary_metric_inline, stringResource(R.string.total_to_pay), loanTaka(pay)),
                    Modifier.weight(1f), style = MaterialTheme.typography.labelSmall.copy(lineHeight = 12.sp), color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        } else {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                FitLedgerText(stringResource(R.string.all_people), Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PeopleSummaryAmount(stringResource(R.string.total_to_receive), receive, Modifier.weight(1f))
                    PeopleSummaryAmount(stringResource(R.string.total_to_pay), pay, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PeopleSummaryAmount(label: String, value: BigInteger, modifier: Modifier = Modifier) {
    Column(modifier) {
        FitLedgerText(label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
        FitLedgerText(loanTaka(value), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium,
            bold = true, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonCard(row: PersonWithDue, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 76.dp),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.person.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(personTypeLabel(row.person.type), modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 12.dp)) {
                Text(stringResource(R.string.column_due), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(loanTaka(row.due), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = dueColor(row.due), textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
private fun PeopleEmptyState(title: String, message: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        action?.invoke()
    }
}
