package com.hisabkhata.ui.loan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisabkhata.data.local.entity.PersonEntity
import com.hisabkhata.data.local.entity.PersonType
import com.hisabkhata.data.repository.LoanRepository
import com.hisabkhata.domain.LoanLinkIntegrityException
import com.hisabkhata.domain.LoanRecordMissingException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PersonEditorProblem { NOT_FOUND, LOAD_FAILED, SAVE_FAILED, LINK_FAILED }
data class PersonEditorState(
    val isEdit: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val name: String = "",
    val phone: String = "",
    val note: String = "",
    val type: PersonType = PersonType.LENT,
    val nameError: Boolean = false,
    val problem: PersonEditorProblem? = null
) {
    val isBlocked: Boolean get() = problem == PersonEditorProblem.NOT_FOUND || problem == PersonEditorProblem.LOAD_FAILED
}
sealed interface PersonEditorEvent { data object Finished : PersonEditorEvent }

@HiltViewModel
class PersonEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: LoanRepository
) : ViewModel() {
    private val personId: Long? = savedStateHandle["personId"]
    private val mutableState = MutableStateFlow(PersonEditorState(isEdit = personId != null, isLoading = personId != null))
    val state = mutableState.asStateFlow()
    private val eventChannel = Channel<PersonEditorEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    init {
        if (personId != null) viewModelScope.launch {
            try {
                val person = repository.findPerson(personId)
                mutableState.update {
                    if (person == null) it.copy(isLoading = false, problem = PersonEditorProblem.NOT_FOUND)
                    else it.copy(isLoading = false, name = person.name, phone = person.phone, note = person.note, type = person.type)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(isLoading = false, problem = PersonEditorProblem.LOAD_FAILED) }
            }
        }
    }

    fun setName(value: String) { mutableState.update { it.copy(name = value, nameError = false, problem = null) } }
    fun setPhone(value: String) { mutableState.update { it.copy(phone = value, problem = null) } }
    fun setNote(value: String) { mutableState.update { it.copy(note = value, problem = null) } }
    fun setType(value: PersonType) { mutableState.update { it.copy(type = value, problem = null) } }

    fun save() {
        val snapshot = mutableState.value
        if (snapshot.isLoading || snapshot.isSaving || snapshot.isBlocked) return
        if (snapshot.name.isBlank()) {
            mutableState.update { it.copy(nameError = true, problem = null) }; return
        }
        mutableState.update { it.copy(isSaving = true, problem = null) }
        viewModelScope.launch {
            try {
                if (personId == null) {
                    repository.addPerson(snapshot.name, snapshot.phone, snapshot.note, snapshot.type)
                } else {
                    repository.updatePerson(PersonEntity(personId, snapshot.name, snapshot.phone, snapshot.note, snapshot.type))
                }
                eventChannel.send(PersonEditorEvent.Finished)
            } catch (e: CancellationException) {
                throw e
            } catch (_: LoanRecordMissingException) {
                mutableState.update { it.copy(isSaving = false, problem = PersonEditorProblem.NOT_FOUND) }
            } catch (_: LoanLinkIntegrityException) {
                mutableState.update { it.copy(isSaving = false, problem = PersonEditorProblem.LINK_FAILED) }
            } catch (_: Exception) {
                mutableState.update { it.copy(isSaving = false, problem = PersonEditorProblem.SAVE_FAILED) }
            }
        }
    }
}
