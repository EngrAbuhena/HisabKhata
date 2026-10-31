package com.hisabkhata.ui.cash

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisabkhata.data.local.entity.CashEntryEntity
import com.hisabkhata.data.repository.CashRepository
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

enum class AmountError { INVALID, TOO_MANY_DECIMALS, TOO_LARGE }
enum class EditorProblem { NOT_FOUND, LINKED, LOAD_FAILED, SAVE_FAILED, DELETE_FAILED }

data class CashEditorState(
    val isEdit: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val pendingDelete: Boolean = false,
    val originalEntry: CashEntryEntity? = null,
    val date: Long = Dates.today(),
    val deposit: String = "",
    val cost: String = "",
    val description: String = "",
    val depositError: AmountError? = null,
    val costError: AmountError? = null,
    val amountRequired: Boolean = false,
    val problem: EditorProblem? = null
) {
    val isBlocked: Boolean get() = problem == EditorProblem.NOT_FOUND || problem == EditorProblem.LINKED || problem == EditorProblem.LOAD_FAILED
}

sealed interface CashEditorEvent {
    data object Finished : CashEditorEvent
    data object Deleted : CashEditorEvent
}

@HiltViewModel
class CashEntryEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: CashRepository
) : ViewModel() {
    private val entryId: Long? = savedStateHandle["entryId"]
    private val mutableState = MutableStateFlow(CashEditorState(isEdit = entryId != null, isLoading = entryId != null))
    val state = mutableState.asStateFlow()
    private val eventChannel = Channel<CashEditorEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    init {
        if (entryId != null) {
            viewModelScope.launch {
                try {
                    val entry = repository.findEntry(entryId)
                    mutableState.update { current ->
                        when {
                            entry == null -> current.copy(isLoading = false, problem = EditorProblem.NOT_FOUND)
                            entry.linkedLoanEntryId != null -> current.copy(isLoading = false, problem = EditorProblem.LINKED)
                            else -> current.copy(
                                isLoading = false,
                                originalEntry = entry,
                                date = entry.date,
                                deposit = if (entry.deposit == 0L) "" else MoneyFormatter.format(entry.deposit),
                                cost = if (entry.cost == 0L) "" else MoneyFormatter.format(entry.cost),
                                description = entry.description
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    mutableState.update { it.copy(isLoading = false, problem = EditorProblem.LOAD_FAILED) }
                }
            }
        }
    }

    fun setDate(value: Long) { mutableState.update { it.copy(date = value, problem = null) } }
    fun setDeposit(value: String) {
        if (value.length <= 100) mutableState.update { it.copy(deposit = value, depositError = null, amountRequired = false, problem = null) }
    }
    fun setCost(value: String) {
        if (value.length <= 100) mutableState.update { it.copy(cost = value, costError = null, amountRequired = false, problem = null) }
    }
    fun setDescription(value: String) { mutableState.update { it.copy(description = value, problem = null) } }

    fun save() {
        val snapshot = mutableState.value
        if (snapshot.isLoading || snapshot.isSaving || snapshot.isBlocked || snapshot.pendingDelete) return
        val depositResult = MoneyInputParser.parse(snapshot.deposit)
        val costResult = MoneyInputParser.parse(snapshot.cost)
        val depositError = depositResult.errorOrNull()
        val costError = costResult.errorOrNull()
        val deposit = (depositResult as? MoneyInputResult.Amount)?.paisa ?: 0L
        val cost = (costResult as? MoneyInputResult.Amount)?.paisa ?: 0L
        val amountRequired = depositError == null && costError == null && deposit == 0L && cost == 0L
        if (depositError != null || costError != null || amountRequired) {
            mutableState.update { it.copy(depositError = depositError, costError = costError, amountRequired = amountRequired, problem = null) }
            return
        }
        mutableState.update { it.copy(isSaving = true, problem = null) }
        viewModelScope.launch {
            try {
                if (entryId == null) {
                    repository.addEntry(snapshot.date, deposit, cost, snapshot.description.trim())
                    eventChannel.send(CashEditorEvent.Finished)
                } else {
                    val changed = repository.updateEntry(entryId, snapshot.date, deposit, cost, snapshot.description.trim())
                    if (changed == 1) eventChannel.send(CashEditorEvent.Finished)
                    else {
                        val reason = blockedReason(entryId)
                        mutableState.update { it.copy(isSaving = false, problem = reason) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(isSaving = false, problem = EditorProblem.SAVE_FAILED) }
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
                if (repository.deleteEntry(id) == 1) eventChannel.send(CashEditorEvent.Deleted)
                else {
                    val reason = blockedReason(id)
                    mutableState.update { it.copy(isSaving = false, pendingDelete = false, problem = reason) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(isSaving = false, pendingDelete = false, problem = EditorProblem.DELETE_FAILED) }
            }
        }
    }

    private suspend fun blockedReason(id: Long): EditorProblem =
        if (repository.findEntry(id)?.linkedLoanEntryId != null) EditorProblem.LINKED else EditorProblem.NOT_FOUND

    private fun MoneyInputResult.errorOrNull(): AmountError? = when (this) {
        MoneyInputResult.Invalid -> AmountError.INVALID
        MoneyInputResult.TooManyDecimals -> AmountError.TOO_MANY_DECIMALS
        MoneyInputResult.TooLarge -> AmountError.TOO_LARGE
        else -> null
    }
}
