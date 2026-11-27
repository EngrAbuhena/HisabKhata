package com.hisabkhata.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hisabkhata.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    onBack: () -> Unit,
    viewModel: NoteEditorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    DisposableEffect(Unit) {
        onDispose { viewModel.saveNow() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val titleRes = when {
                        !state.isMine -> R.string.notepad_view_note
                        state.noteId == null -> R.string.notepad_new_note
                        else -> R.string.notepad_edit_note
                    }
                    Text(stringResource(titleRes))
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.saveNow()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (state.isMine) {
                        IconButton(onClick = { viewModel.setPinned(!state.pinned) }) {
                            Icon(
                                imageVector = if (state.pinned) Icons.Filled.Star else Icons.Outlined.Star,
                                contentDescription = stringResource(
                                    if (state.pinned) R.string.notepad_unpin else R.string.notepad_pin
                                )
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!state.isMine) {
                val author = state.authorDisplayName?.let {
                    stringResource(R.string.notepad_author_by, it)
                } ?: stringResource(R.string.notepad_author_anonymous)
                Text(
                    text = "${stringResource(R.string.notepad_read_only_hint)} • $author",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (state.saved) {
                Text(
                    text = stringResource(R.string.notepad_saved_status),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::onTitleChange,
                label = { Text(stringResource(R.string.notepad_title_label)) },
                singleLine = true,
                readOnly = !state.isMine,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.body,
                onValueChange = viewModel::onBodyChange,
                label = { Text(stringResource(R.string.notepad_body_label)) },
                readOnly = !state.isMine,
                minLines = 8,
                modifier = Modifier.fillMaxWidth()
            )

            if (state.isMine) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilterChip(
                        selected = !state.isPublic,
                        onClick = { viewModel.setPublic(false) },
                        label = { Text(stringResource(R.string.notepad_visibility_private)) }
                    )
                    FilterChip(
                        selected = state.isPublic,
                        onClick = { viewModel.setPublic(true) },
                        label = { Text(stringResource(R.string.notepad_visibility_public)) }
                    )
                }

                if (state.isPublic) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = stringResource(R.string.notepad_show_author),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.notepad_show_author_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.showAuthor,
                            onCheckedChange = viewModel::setShowAuthor
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
