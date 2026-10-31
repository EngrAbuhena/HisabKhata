package com.hisabkhata.ui.loan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisabkhata.data.local.entity.PersonEntity
import com.hisabkhata.data.local.entity.PersonType
import com.hisabkhata.data.repository.LoanRepository
import com.hisabkhata.domain.LoanBalanceCalculator
import com.hisabkhata.domain.LoanMovement
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigInteger
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class PersonWithDue(val person: PersonEntity, val due: BigInteger)

data class PeopleUiState(
    val isLoading: Boolean = true,
    val loadError: Boolean = false,
    val rows: List<PersonWithDue> = emptyList(),
    val totalCount: Int = 0,
    val totalToReceive: BigInteger = BigInteger.ZERO,
    val totalToPay: BigInteger = BigInteger.ZERO
)

private data class PeopleSnapshot(
    val rows: List<PersonWithDue>,
    val receive: BigInteger,
    val pay: BigInteger
)

@HiltViewModel
class PeopleViewModel @Inject constructor(repository: LoanRepository) : ViewModel() {
    private val calculator = LoanBalanceCalculator()
    private val query = MutableStateFlow("")
    val currentQuery: String get() = query.value

    private val snapshot = combine(repository.observePeople(), repository.observeAllEntries()) { people, entries ->
        val groups = entries.groupBy { it.personId }
        val rows = people.map { person ->
            val due = calculator.calculate(groups[person.id].orEmpty().map {
                LoanMovement(it.id, it.date, it.createdAt, it.loan, it.paid)
            }).currentDue
            PersonWithDue(person, due)
        }
        PeopleSnapshot(
            rows = rows,
            receive = rows.filter { it.person.type == PersonType.LENT }.fold(BigInteger.ZERO) { total, row -> total.add(row.due) },
            pay = rows.filter { it.person.type == PersonType.BORROWED }.fold(BigInteger.ZERO) { total, row -> total.add(row.due) }
        )
    }.flowOn(Dispatchers.Default)

    val uiState = combine(snapshot, query) { all, search ->
        val needle = search.trim()
        PeopleUiState(
            isLoading = false,
            rows = all.rows.filter { needle.isEmpty() || it.person.name.contains(needle, ignoreCase = true) },
            totalCount = all.rows.size,
            totalToReceive = all.receive,
            totalToPay = all.pay
        )
    }.flowOn(Dispatchers.Default)
        .catch { emit(PeopleUiState(isLoading = false, loadError = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeopleUiState())

    fun setQuery(value: String) { query.value = value }
}
