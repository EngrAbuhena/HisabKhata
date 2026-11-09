package com.hisabkhata.ui.cash
import com.hisabkhata.util.latinStringResource
import com.hisabkhata.util.latinPluralResource

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hisabkhata.R
import com.hisabkhata.domain.MoneyFormatter
import com.hisabkhata.ui.components.CompactResultCount
import com.hisabkhata.ui.components.CompactSearchField
import com.hisabkhata.ui.components.FitLedgerText
import com.hisabkhata.ui.components.MobileLedgerHeader
import com.hisabkhata.ui.components.MobileLedgerRow
import com.hisabkhata.util.Dates
import java.math.BigInteger

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CashBookScreen(
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenLinkedPerson: (Long) -> Unit,
    onExport: () -> Unit,
    viewModel: CashBookViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val addEntryLabel = stringResource(R.string.add_entry)
    // The text field updates synchronously, independently of background filter calculations.
    var searchInput by rememberSaveable { mutableStateOf(viewModel.currentQuery) }
    LaunchedEffect(viewModel) { viewModel.setQuery(searchInput) }
    var showMonth by rememberSaveable { mutableStateOf(false) }
    var showRange by rememberSaveable { mutableStateOf(false) }
    var showLinkedInfo by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.linkEvents.collect { event ->
            when (event) {
                is LinkedCashEvent.OpenPerson -> onOpenLinkedPerson(event.personId)
                LinkedCashEvent.Missing -> showLinkedInfo = true
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // In short landscape windows an extended FAB would cover the search field.
        // Keep the same add action reachable in the app bar instead.
        val shortWindow = maxHeight < 360.dp
        Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_cash_book)) },
            actions = {
                if (shortWindow && !state.loadError) IconButton(onClick = onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = addEntryLabel)
                }
                IconButton(onClick = onExport) { Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.export_title)) }
            }) },
        floatingActionButton = {
            if (!state.loadError && !shortWindow) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    modifier = Modifier.semantics { contentDescription = addEntryLabel },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(addEntryLabel) }
                )
            }
        }
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadError -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.load_error), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            else -> BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                // Keep the summary fixed, while leaving at least a header and row in very short
                // landscape windows. At those heights the same three metrics use one compact line.
                val tightHeight = maxHeight < (124f + 68f * LocalDensity.current.fontScale).dp
                Column(Modifier.fillMaxSize()) {
                    CashSummary(state, compact = tightHeight, modifier = Modifier.padding(horizontal = 12.dp).testTag("cash_summary"))
                    Spacer(Modifier.height(if (tightHeight) 4.dp else 8.dp))
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("cash_ledger")) {
                        item(key = "controls") {
                            CashSearchFilters(
                                query = searchInput,
                                onQueryChange = { searchInput = it; viewModel.setQuery(it) },
                                filter = state.filter,
                                shown = state.rows.size,
                                total = state.totalCount,
                                onAll = { viewModel.setFilter(DateFilter.All) },
                                onMonth = { showMonth = true },
                                onRange = { showRange = true },
                                onClearDate = { viewModel.setFilter(DateFilter.All) }
                            )
                        }
                        when {
                            state.totalCount == 0 -> item(key = "empty") {
                                BookEmptyState(
                                    title = stringResource(R.string.empty_book_title),
                                    message = stringResource(R.string.empty_book_message),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp)
                                )
                            }
                            state.rows.isEmpty() -> item(key = "empty") {
                                BookEmptyState(
                                    title = stringResource(R.string.no_results_title),
                                    message = stringResource(R.string.no_results_message),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                                    action = { TextButton(onClick = { searchInput = ""; viewModel.clearFilters() }) { Text(stringResource(R.string.clear_filters)) } }
                                )
                            }
                            else -> {
                                stickyHeader(key = "header") {
                                    MobileLedgerHeader(
                                        listOf(stringResource(R.string.column_date), stringResource(R.string.column_deposit_short),
                                            stringResource(R.string.column_cost), stringResource(R.string.column_total)),
                                        Modifier.padding(horizontal = 12.dp).testTag("cash_ledger_header")
                                    )
                                }
                                itemsIndexed(state.rows, key = { _, row -> row.entry.id }) { index, row ->
                                    MobileLedgerRow(
                                        date = Dates.display(row.entry.date),
                                        first = if (row.entry.deposit == 0L) stringResource(R.string.empty_amount) else MoneyFormatter.format(row.entry.deposit),
                                        second = if (row.entry.cost == 0L) stringResource(R.string.empty_amount) else MoneyFormatter.format(row.entry.cost),
                                        balance = MoneyFormatter.format(row.balance), description = row.entry.description,
                                        linked = row.entry.linkedLoanEntryId != null,
                                        balanceColor = if (row.balance.signum() < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                        alternating = index % 2 == 1, linkDescription = stringResource(R.string.link_icon_description),
                                        modifier = Modifier.padding(horizontal = 12.dp).testTag("cash_row_${row.entry.id}"),
                                        onClick = { row.entry.linkedLoanEntryId?.let(viewModel::openLinkedLoan) ?: onEdit(row.entry.id) }
                                    )
                                }
                            }
                        }
                        // Keep the final row / empty-state action clear of the floating action button.
                        item(key = "bottom-space") { Spacer(Modifier.height(88.dp)) }
                    }
                }
            }
        }
        }
    }

    if (showMonth) MonthFilterDialog(
        initial = state.filter as? DateFilter.Month,
        onDismiss = { showMonth = false },
        onApply = { viewModel.setFilter(it); showMonth = false }
    )
    if (showRange) DateRangeFilterDialog(
        initial = state.filter as? DateFilter.Range,
        onDismiss = { showRange = false },
        onApply = { viewModel.setFilter(it); showRange = false }
    )
    if (showLinkedInfo) AlertDialog(
        onDismissRequest = { showLinkedInfo = false },
        title = { Text(stringResource(R.string.linked_entry_title)) },
        text = { Text(stringResource(R.string.linked_loan_missing)) },
        confirmButton = { TextButton(onClick = { showLinkedInfo = false }) { Text(stringResource(R.string.ok)) } }
    )
}

/** Search and filters are a single scrolling item; the summary and sticky table header are not. */
@Composable
private fun CashSearchFilters(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: DateFilter,
    shown: Int,
    total: Int,
    onAll: () -> Unit,
    onMonth: () -> Unit,
    onRange: () -> Unit,
    onClearDate: () -> Unit
) {
    val active = query.isNotBlank() || filter != DateFilter.All
    val count = if (active) latinPluralResource(R.plurals.entries_shown, total, shown, total)
        else latinPluralResource(R.plurals.entries_count, total, total)
    val monthLabel = if (filter is DateFilter.Month) {
        val months = stringArrayResource(R.array.short_month_names)
        latinStringResource(R.string.filter_month_summary, months[filter.month - 1], filter.year)
    } else stringResource(R.string.filter_month)
    val rangeLabel = if (filter is DateFilter.Range) {
        stringResource(R.string.filter_range_summary, Dates.display(filter.from), Dates.display(filter.through))
    } else stringResource(R.string.filter_date_range)

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("cash_controls")) {
        CompactSearchField(query, onQueryChange, stringResource(R.string.search_description), Modifier.testTag("cash_search"))
        // A 48dp hit area surrounds each 36dp visible chip. The two 2dp spacers make
        // the visual gaps from search to chip and chip to status exactly 8dp.
        Spacer(Modifier.height(2.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val scroll = rememberScrollState()
            val maxLabelWidth = (maxWidth - 72.dp).coerceAtLeast(48.dp)
            Row(
                Modifier.fillMaxWidth().height(48.dp)
                    .horizontalScroll(scroll, enabled = scroll.maxValue > 0)
                    .testTag("cash_filter_chips"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactFilterChip(
                    label = stringResource(R.string.filter_all), selected = filter == DateFilter.All,
                    onClick = onAll, maxLabelWidth = maxLabelWidth, tag = "cash_filter_all"
                )
                CompactFilterChip(
                    label = monthLabel, selected = filter is DateFilter.Month,
                    onClick = onMonth, maxLabelWidth = maxLabelWidth, tag = "cash_filter_month",
                    onClear = if (filter is DateFilter.Month) onClearDate else null,
                    clearDescription = stringResource(R.string.clear_month_filter)
                )
                CompactFilterChip(
                    label = rangeLabel, selected = filter is DateFilter.Range,
                    onClick = onRange, maxLabelWidth = maxLabelWidth, tag = "cash_filter_range",
                    onClear = if (filter is DateFilter.Range) onClearDate else null,
                    clearDescription = stringResource(R.string.clear_date_range_filter)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        CompactResultCount(count, Modifier.testTag("cash_status"))
        Spacer(Modifier.height(8.dp))
    }
}

/** Actual Material filter chip, with a separate 48dp clear target when a date is selected. */
@Composable
private fun CompactFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    maxLabelWidth: Dp,
    tag: String,
    onClear: (() -> Unit)? = null,
    clearDescription: String = ""
) {
    val clearable = onClear != null
    Box(Modifier.height(48.dp), contentAlignment = Alignment.CenterStart) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.align(Alignment.CenterStart).height(36.dp).testTag(tag),
            label = { CompactChipLabel(label, maxLabelWidth) },
            // Reserve space for the separate, full-size clear target over the end of the chip.
            trailingIcon = if (clearable) { { Spacer(Modifier.width(32.dp)) } } else null
        )
        if (clearable) {
            IconButton(onClick = { onClear?.invoke() }, modifier = Modifier.align(Alignment.CenterEnd).size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = clearDescription, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Short labels wrap tightly; an exceptionally long selected range is fitted inside the viewport. */
@Composable
private fun CompactChipLabel(value: String, maxWidth: Dp) {
    val style = MaterialTheme.typography.labelMedium.copy(lineHeight = 14.sp)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val naturalPx = measurer.measure(AnnotatedString(value), style = style,
        constraints = Constraints(maxWidth = Constraints.Infinity), maxLines = 1, softWrap = false).size.width + 4
    val width = with(density) { naturalPx.toDp() }.coerceAtMost(maxWidth)
    FitLedgerText(value, Modifier.width(width), style = style, color = LocalContentColor.current)
}

@Composable
private fun CashSummary(state: CashBookUiState, compact: Boolean = false, modifier: Modifier = Modifier) {
    val summaryColor = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        if (compact) {
            // This short-window layout preserves the fixed summary without hiding the ledger.
            Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                InlineSummaryAmount(stringResource(R.string.current_balance), state.currentBalance, Modifier.weight(1f),
                    if (state.currentBalance.signum() < 0) MaterialTheme.colorScheme.error else summaryColor)
                InlineSummaryAmount(stringResource(R.string.total_deposit), state.totalDeposit, Modifier.weight(1f), summaryColor)
                InlineSummaryAmount(stringResource(R.string.total_cost), state.totalCost, Modifier.weight(1f), summaryColor)
            }
        } else {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        FitLedgerText(stringResource(R.string.current_balance), Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.labelMedium, color = summaryColor)
                        FitLedgerText(stringResource(R.string.all_entries_summary), Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.labelSmall, color = summaryColor)
                    }
                    FitLedgerText(
                        value = taka(state.currentBalance), modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge, bold = true, align = TextAlign.End,
                        color = if (state.currentBalance.signum() < 0) MaterialTheme.colorScheme.error else summaryColor
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryAmount(stringResource(R.string.total_deposit), state.totalDeposit, Modifier.weight(1f))
                    SummaryAmount(stringResource(R.string.total_cost), state.totalCost, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun InlineSummaryAmount(label: String, amount: BigInteger, modifier: Modifier, color: androidx.compose.ui.graphics.Color) {
    FitLedgerText(stringResource(R.string.summary_metric_inline, label, taka(amount)), modifier,
        style = MaterialTheme.typography.labelSmall.copy(lineHeight = 12.sp), color = color)
}

@Composable
private fun SummaryAmount(label: String, amount: BigInteger, modifier: Modifier = Modifier) {
    Column(modifier) {
        FitLedgerText(label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
        FitLedgerText(taka(amount), modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer, bold = true)
    }
}

@Composable
private fun taka(amount: BigInteger): String = stringResource(
    if (amount.signum() < 0) R.string.negative_taka_amount else R.string.taka_amount,
    MoneyFormatter.formatAbsolute(amount)
)

@Composable
private fun BookEmptyState(title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        action?.invoke()
    }
}

@Preview(name = "Cash controls - narrow phone", widthDp = 320, heightDp = 640, showBackground = true)
@Preview(name = "Cash controls - normal phone", widthDp = 400, heightDp = 720, showBackground = true)
@Preview(name = "Cash controls - large font", widthDp = 320, heightDp = 800, fontScale = 2f, showBackground = true)
@Composable
private fun CashControlsPreview() {
    MaterialTheme {
        Column(Modifier.fillMaxWidth()) {
            CashSummary(CashBookUiState(currentBalance = BigInteger.valueOf(2_800_000),
                totalDeposit = BigInteger.valueOf(3_000_000), totalCost = BigInteger.valueOf(200_000)),
                modifier = Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.height(8.dp))
            CashSearchFilters(query = "", onQueryChange = {}, filter = DateFilter.Month(2026, 10),
                shown = 4, total = 12, onAll = {}, onMonth = {}, onRange = {}, onClearDate = {})
            MobileLedgerHeader(listOf(stringResource(R.string.column_date), stringResource(R.string.column_deposit_short),
                stringResource(R.string.column_cost), stringResource(R.string.column_total)), Modifier.padding(horizontal = 12.dp))
        }
    }
}
