package com.hisabkhata.ui.notes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisabkhata.data.repository.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NoteEditorUiState(
    val noteId: Long? = null,
    val title: String = "",
    val body: String = "",
    val isPublic: Boolean = false,
    val showAuthor: Boolean = false,
    val pinned: Boolean = false,
    val isMine: Boolean = true,
    val authorDisplayName: String? = null,
    val loaded: Boolean = false,
    val saved: Boolean = false
)

@HiltViewModel
class NoteEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: NoteRepository
) : ViewModel() {
    private val initialId: Long? = savedStateHandle.get<Long>("noteId")?.takeIf { it > 0L }
    private val _state = MutableStateFlow(NoteEditorUiState(noteId = initialId))
    val state: StateFlow<NoteEditorUiState> = _state.asStateFlow()
    private var autoSaveJob: Job? = null

    init {
        viewModelScope.launch {
            if (initialId != null) {
                val note = repo.findById(initialId)
                if (note != null) {
                    _state.update {
                        it.copy(
                            noteId = note.id,
                            title = note.title,
                            body = note.body,
                            isPublic = note.isPublic,
                            showAuthor = note.showAuthor,
                            pinned = note.pinned,
                            isMine = note.isMine,
                            authorDisplayName = note.authorDisplayName,
                            loaded = true
                        )
                    }
                    return@launch
                }
            }
            _state.update { it.copy(loaded = true) }
        }
    }

    fun onTitleChange(value: String) {
        if (!_state.value.isMine) return
        _state.update { it.copy(title = value.take(200), saved = false) }
        scheduleAutoSave()
    }

    fun onBodyChange(value: String) {
        if (!_state.value.isMine) return
        _state.update { it.copy(body = value, saved = false) }
        scheduleAutoSave()
    }

    fun setPinned(pinned: Boolean) {
        if (!_state.value.isMine) return
        _state.update { it.copy(pinned = pinned, saved = false) }
        saveNow()
    }

    fun setPublic(isPublic: Boolean) {
        if (!_state.value.isMine) return
        _state.update {
            it.copy(
                isPublic = isPublic,
                showAuthor = if (isPublic) it.showAuthor else false,
                saved = false
            )
        }
        saveNow()
    }

    fun setShowAuthor(showAuthor: Boolean) {
        if (!_state.value.isMine || !_state.value.isPublic) return
        _state.update { it.copy(showAuthor = showAuthor, saved = false) }
        saveNow()
    }

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(500)
            persistCurrent()
        }
    }

    fun saveNow() {
        autoSaveJob?.cancel()
        viewModelScope.launch { persistCurrent() }
    }

    private suspend fun persistCurrent() {
        val s = _state.value
        if (!s.isMine) return
        if (s.title.trim().isEmpty() && s.body.trim().isEmpty()) return
        val id = repo.saveNote(
            id = s.noteId,
            title = s.title,
            body = s.body,
            isPublic = s.isPublic,
            showAuthor = s.showAuthor,
            pinned = s.pinned
        )
        _state.update { it.copy(noteId = id, saved = true) }
    }
}
