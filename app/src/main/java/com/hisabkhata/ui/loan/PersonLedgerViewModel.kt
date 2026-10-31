package com.hisabkhata.ui.loan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisabkhata.data.local.entity.LoanEntryEntity
import com.hisabkhata.data.local.entity.PersonEntity
import com.hisabkhata.data.repository.LoanRepository
import com.hisabkhata.domain.LoanBalanceCalculator
import com.hisabkhata.domain.LoanMovement
import com.hisabkhata.domain.LoanRecordMissingException
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigInteger
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoanLedgerRow(val entry: LoanEntryEntity, val due: BigInteger)
enum class PersonLedgerProblem { LOAD_FAILED, NOT_FOUND, DELETE_FAILED }

data class PersonLedgerUiState(
    val isLoading: Boolean = true,
    val person: PersonEntity? = null,
    val rows: List<LoanLedgerRow> = emptyList(),
    val totalLoan: BigInteger = BigInteger.ZERO,
    val totalPaid: BigInteger = BigInteger.ZERO,
    val currentDue: BigInteger = BigInteger.ZERO,
    val isDeleting: Boolean = false,
    val pendingDelete: Boolean = false,
    val problem: PersonLedgerProblem? = null
)

private data class PersonDeleteState(val isDeleting: Boolean = false, val pendingDelete: Boolean = false, val problem: PersonLedgerProblem? = null)
sealed interface PersonLedgerEvent { data object Deleted : PersonLedgerEvent }

@HiltViewModel
class PersonLedgerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: LoanRepository
) : ViewModel() {
    private val personId: Long = savedStateHandle["personId"] ?: 0L
    private val operation = MutableStateFlow(PersonDeleteState())
    private val eventChannel = Channel<PersonLedgerEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private val calculator = LoanBalanceCalculator()

    val uiState = combine(repository.observePerson(personId), repository.observeEntries(personId), operation) { person, entries, op ->
        if (person == null) {
            PersonLedgerUiState(isLoading = false, isDeleting = op.isDeleting, problem = PersonLedgerProblem.NOT_FOUND)
        } else {
            val calculation = calculator.calculate(entries.map { LoanMovement(it.id, it.date, it.createdAt, it.loan, it.paid) })
            val byId = entries.associateBy { it.id }
            PersonLedgerUiState(
                isLoading = false,
                person = person,
                rows = calculation.rows.map { LoanLedgerRow(byId.getValue(it.id), it.due) },
                totalLoan = calculation.totalLoan,
                totalPaid = calculation.totalPaid,
                currentDue = calculation.currentDue,
                isDeleting = op.isDeleting,
                pendingDelete = op.pendingDelete,
                problem = op.problem
            )
        }
    }.flowOn(Dispatchers.Default)
        .catch { emit(PersonLedgerUiState(isLoading = false, problem = PersonLedgerProblem.LOAD_FAILED)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonLedgerUiState())

    fun requestDelete() {
        if (!operation.value.isDeleting && uiState.value.person != null) {
            operation.update { it.copy(pendingDelete = true, problem = null) }
        }
    }

    fun cancelDelete() {
        if (!operation.value.isDeleting) operation.update { it.copy(pendingDelete = false) }
    }

    fun confirmDelete() {
        val current = operation.value
        if (!current.pendingDelete || current.isDeleting) return
        operation.update { it.copy(isDeleting = true, problem = null) }
        viewModelScope.launch {
            try {
                repository.deletePerson(personId)
                eventChannel.send(PersonLedgerEvent.Deleted)
            } catch (e: CancellationException) {
                throw e
            } catch (_: LoanRecordMissingException) {
                operation.update { it.copy(isDeleting = false, pendingDelete = false, problem = PersonLedgerProblem.NOT_FOUND) }
            } catch (_: Exception) {
                operation.update { it.copy(isDeleting = false, pendingDelete = false, problem = PersonLedgerProblem.DELETE_FAILED) }
            }
        }
    }
}
