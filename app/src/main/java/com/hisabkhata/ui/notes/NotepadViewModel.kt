package com.hisabkhata.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisabkhata.data.local.entity.NoteEntity
import com.hisabkhata.data.repository.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class NotepadTab { MINE, PUBLIC }

data class NotepadUiState(
    val tab: NotepadTab = NotepadTab.MINE,
    val query: String = "",
    val notes: List<NoteEntity> = emptyList()
)

@HiltViewModel
class NotepadViewModel @Inject constructor(
    private val repo: NoteRepository
) : ViewModel() {
    private val tab = MutableStateFlow(NotepadTab.MINE)
    private val query = MutableStateFlow("")

    val uiState: StateFlow<NotepadUiState> = combine(
        tab,
        query,
        repo.observeActiveNotes()
    ) { currentTab, search, allNotes ->
        val scoped = when (currentTab) {
            NotepadTab.MINE -> allNotes.filter { it.isMine }
            NotepadTab.PUBLIC -> allNotes.filter { it.isPublic }
        }
        val trimmed = search.trim()
        val filtered = if (trimmed.isEmpty()) {
            scoped
        } else {
            scoped.filter {
                it.title.contains(trimmed, ignoreCase = true) ||
                    it.body.contains(trimmed, ignoreCase = true)
            }
        }
        NotepadUiState(tab = currentTab, query = search, notes = filtered)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotepadUiState())

    fun selectTab(next: NotepadTab) { tab.value = next }
    fun setQuery(next: String) { query.value = next }

    fun togglePinned(note: NoteEntity) {
        viewModelScope.launch { repo.togglePinned(note) }
    }

    fun deleteNote(note: NoteEntity, onDeleted: (Long) -> Unit) {
        viewModelScope.launch {
            repo.softDelete(note)
            onDeleted(note.id)
        }
    }

    fun restoreNote(noteId: Long) {
        viewModelScope.launch { repo.restore(noteId) }
    }
}
