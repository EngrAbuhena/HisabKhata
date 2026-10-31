package com.hisabkhata.ui.cash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisabkhata.data.local.entity.CashEntryEntity
import com.hisabkhata.data.repository.CashRepository
import com.hisabkhata.data.repository.LoanRepository
import com.hisabkhata.domain.CashBalanceCalculator
import com.hisabkhata.domain.CashMovement
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigInteger
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface DateFilter {
    data object All : DateFilter
    data class Month(val year: Int, val month: Int) : DateFilter {
        private val first = LocalDate.of(year, month, 1)
        val firstDay: Long = first.toEpochDay()
        val afterLastDay: Long = first.plusMonths(1).toEpochDay()
    }
    data class Range(val from: Long, val through: Long) : DateFilter

    fun includes(epochDay: Long): Boolean = when (this) {
        All -> true
        is Month -> epochDay >= firstDay && epochDay < afterLastDay
        is Range -> epochDay in from..through
    }
}

data class CashBookRow(val entry: CashEntryEntity, val balance: BigInteger)

data class CashBookUiState(
    val isLoading: Boolean = true,
    val loadError: Boolean = false,
    val query: String = "",
    val filter: DateFilter = DateFilter.All,
    val rows: List<CashBookRow> = emptyList(),
    val totalCount: Int = 0,
    val currentBalance: BigInteger = BigInteger.ZERO,
    val totalDeposit: BigInteger = BigInteger.ZERO,
    val totalCost: BigInteger = BigInteger.ZERO
)

private data class RegisterSnapshot(
    val rows: List<CashBookRow>,
    val currentBalance: BigInteger,
    val totalDeposit: BigInteger,
    val totalCost: BigInteger
)

sealed interface LinkedCashEvent {
    data class OpenPerson(val personId: Long) : LinkedCashEvent
    data object Missing : LinkedCashEvent
}

@HiltViewModel
class CashBookViewModel @Inject constructor(
    repository: CashRepository,
    private val loanRepository: LoanRepository
) : ViewModel() {
    private val calculator = CashBalanceCalculator()
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow<DateFilter>(DateFilter.All)
    val currentQuery: String get() = query.value
    private val linkChannel = Channel<LinkedCashEvent>(Channel.BUFFERED)
    val linkEvents = linkChannel.receiveAsFlow()
    private var resolvingLink = false

    fun openLinkedLoan(loanEntryId: Long) {
        if (resolvingLink) return
        resolvingLink = true
        viewModelScope.launch {
            try {
                val entry = loanRepository.findEntry(loanEntryId)
                linkChannel.send(if (entry == null) LinkedCashEvent.Missing else LinkedCashEvent.OpenPerson(entry.personId))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                linkChannel.send(LinkedCashEvent.Missing)
            } finally {
                resolvingLink = false
            }
        }
    }

    // Recalculate balances only when the register changes, not on each typed character.
    private val register = repository.observeEntries().map { entries ->
        val calculation = calculator.calculate(entries.map {
            CashMovement(it.id, it.date, it.createdAt, it.deposit, it.cost)
        })
        val entriesById = entries.associateBy { it.id }
        RegisterSnapshot(
            rows = calculation.rows.map { result -> CashBookRow(entriesById.getValue(result.id), result.balance) },
            currentBalance = calculation.currentBalance,
            totalDeposit = calculation.totalDeposit,
            totalCost = calculation.totalCost
        )
    }.flowOn(Dispatchers.Default)

    val uiState = combine(register, query, filter) { all, search, selectedFilter ->
        val needle = search.trim()
        val visibleRows = all.rows.filter { row ->
            selectedFilter.includes(row.entry.date) &&
                (needle.isEmpty() || row.entry.description.contains(needle, ignoreCase = true))
        }
        CashBookUiState(
            isLoading = false,
            query = search,
            filter = selectedFilter,
            rows = visibleRows,
            totalCount = all.rows.size,
            currentBalance = all.currentBalance,
            totalDeposit = all.totalDeposit,
            totalCost = all.totalCost
        )
    }.flowOn(Dispatchers.Default)
        .catch { emit(CashBookUiState(isLoading = false, loadError = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CashBookUiState())

    fun setQuery(value: String) { query.value = value }
    fun setFilter(value: DateFilter) { filter.value = value }
    fun clearFilters() {
        query.value = ""
        filter.value = DateFilter.All
    }
}
