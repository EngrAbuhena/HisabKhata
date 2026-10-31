package com.hisabkhata.ui.loan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisabkhata.data.local.entity.LoanEntryEntity
import com.hisabkhata.data.local.entity.PersonEntity
import com.hisabkhata.data.repository.LoanRepository
import com.hisabkhata.domain.LoanLinkIntegrityException
import com.hisabkhata.domain.LoanRecordMissingException
import com.hisabkhata.domain.MoneyFormatter
import com.hisabkhata.domain.MoneyInputParser
import com.hisabkhata.domain.MoneyInputResult
import com.hisabkhata.util.Dates
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LoanAmountError { INVALID, TOO_MANY_DECIMALS, TOO_LARGE }
enum class LoanEditorProblem { NOT_FOUND, LOAD_FAILED, SAVE_FAILED, DELETE_FAILED, LINK_FAILED }

data class LoanEntryEditorState(
    val isEdit: Boolean = false,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val pendingDelete: Boolean = false,
    val originalEntry: LoanEntryEntity? = null,
    val person: PersonEntity? = null,
    val date: Long = Dates.today(),
    val loan: String = "",
    val paid: String = "",
    val description: String = "",
    val recordInCash: Boolean = true,
    val loanError: LoanAmountError? = null,
    val paidError: LoanAmountError? = null,
    val amountRequired: Boolean = false,
    val problem: LoanEditorProblem? = null
) {
    val isBlocked: Boolean get() = problem == LoanEditorProblem.NOT_FOUND || problem == LoanEditorProblem.LOAD_FAILED
}
sealed interface LoanEntryEditorEvent {
    data object Finished : LoanEntryEditorEvent
    data object Deleted : LoanEntryEditorEvent
}

@HiltViewModel
class LoanEntryEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: LoanRepository
) : ViewModel() {
    private val personId: Long = savedStateHandle["personId"] ?: 0L
    private val entryId: Long? = savedStateHandle["entryId"]
    private val mutableState = MutableStateFlow(LoanEntryEditorState(isEdit = entryId != null))
    val state = mutableState.asStateFlow()
    private val eventChannel = Channel<LoanEntryEditorEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            try {
                val person = repository.findPerson(personId)
                val entry = entryId?.let { repository.findEntry(it) }
                mutableState.update {
                    when {
                        person == null || (entryId != null && (entry == null || entry.personId != personId)) ->
                            it.copy(isLoading = false, problem = LoanEditorProblem.NOT_FOUND)
                        entry == null -> it.copy(isLoading = false, person = person)
                        else -> it.copy(
                            isLoading = false, person = person, originalEntry = entry, date = entry.date,
                            loan = if (entry.loan == 0L) "" else MoneyFormatter.format(entry.loan),
                            paid = if (entry.paid == 0L) "" else MoneyFormatter.format(entry.paid),
                            description = entry.description, recordInCash = entry.linkedCashEntryId != null
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(isLoading = false, problem = LoanEditorProblem.LOAD_FAILED) }
            }
        }
    }

    fun setDate(value: Long) { mutableState.update { it.copy(date = value, problem = null) } }
    fun setLoan(value: String) {
        if (value.length <= 100) mutableState.update { it.copy(loan = value, loanError = null, amountRequired = false, problem = null) }
    }
    fun setPaid(value: String) {
        if (value.length <= 100) mutableState.update { it.copy(paid = value, paidError = null, amountRequired = false, problem = null) }
    }
    fun setDescription(value: String) { mutableState.update { it.copy(description = value, problem = null) } }
    fun setRecordInCash(value: Boolean) { mutableState.update { it.copy(recordInCash = value, problem = null) } }

    fun save() {
        val snapshot = mutableState.value
        if (snapshot.isLoading || snapshot.isSaving || snapshot.isBlocked || snapshot.pendingDelete) return
        val loanResult = MoneyInputParser.parse(snapshot.loan)
        val paidResult = MoneyInputParser.parse(snapshot.paid)
        val loanError = loanResult.errorOrNull()
        val paidError = paidResult.errorOrNull()
        val loan = (loanResult as? MoneyInputResult.Amount)?.paisa ?: 0L
        val paid = (paidResult as? MoneyInputResult.Amount)?.paisa ?: 0L
        val required = loanError == null && paidError == null && loan == 0L && paid == 0L
        if (loanError != null || paidError != null || required) {
            mutableState.update { it.copy(loanError = loanError, paidError = paidError, amountRequired = required, problem = null) }
            return
        }
        mutableState.update { it.copy(isSaving = true, problem = null) }
        viewModelScope.launch {
            try {
                repository.saveEntry(personId, entryId, snapshot.date, loan, paid, snapshot.description, snapshot.recordInCash)
                eventChannel.send(LoanEntryEditorEvent.Finished)
            } catch (e: CancellationException) {
                throw e
            } catch (_: LoanRecordMissingException) {
                mutableState.update { it.copy(isSaving = false, problem = LoanEditorProblem.NOT_FOUND) }
            } catch (_: LoanLinkIntegrityException) {
                mutableState.update { it.copy(isSaving = false, problem = LoanEditorProblem.LINK_FAILED) }
            } catch (_: Exception) {
                mutableState.update { it.copy(isSaving = false, problem = LoanEditorProblem.SAVE_FAILED) }
            }
        }
    }

    fun requestDelete() {
        val current = mutableState.value
        if (entryId != null && current.originalEntry != null && !current.isLoading && !current.isSaving && !current.isBlocked) {
            mutableState.update { it.copy(pendingDelete = true, problem = null) }
        }
    }

    fun cancelDelete() {
        if (!mutableState.value.isSaving) mutableState.update { it.copy(pendingDelete = false) }
    }

    fun confirmDelete() {
        val id = entryId ?: return
        val current = mutableState.value
        if (!current.pendingDelete || current.isLoading || current.isSaving || current.isBlocked) return
        mutableState.update { it.copy(isSaving = true, problem = null) }
        viewModelScope.launch {
            try {
                repository.deleteEntry(personId, id)
                eventChannel.send(LoanEntryEditorEvent.Deleted)
            } catch (e: CancellationException) {
                throw e
            } catch (_: LoanRecordMissingException) {
                mutableState.update { it.copy(isSaving = false, pendingDelete = false, problem = LoanEditorProblem.NOT_FOUND) }
            } catch (_: Exception) {
                mutableState.update { it.copy(isSaving = false, pendingDelete = false, problem = LoanEditorProblem.DELETE_FAILED) }
            }
        }
    }

    private fun MoneyInputResult.errorOrNull(): LoanAmountError? = when (this) {
        MoneyInputResult.Invalid -> LoanAmountError.INVALID
        MoneyInputResult.TooManyDecimals -> LoanAmountError.TOO_MANY_DECIMALS
        MoneyInputResult.TooLarge -> LoanAmountError.TOO_LARGE
        else -> null
    }
}
