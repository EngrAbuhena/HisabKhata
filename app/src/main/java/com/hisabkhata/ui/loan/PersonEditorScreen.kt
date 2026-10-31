package com.hisabkhata.ui.loan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hisabkhata.R
import com.hisabkhata.data.local.entity.PersonType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonEditorScreen(onBack: () -> Unit, viewModel: PersonEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.events.collect { if (it == PersonEditorEvent.Finished) onBack() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isEdit) R.string.edit_person else R.string.add_person)) },
                navigationIcon = { IconButton(onClick = onBack, enabled = !state.isSaving) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                } }
            )
        },
        bottomBar = {
            if (!state.isLoading && !state.isBlocked) Surface(shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth().imePadding()) {
                Box(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Button(onClick = viewModel::save, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(stringResource(when {
                            state.isSaving -> R.string.saving
                            state.isEdit -> R.string.save_changes
                            else -> R.string.save_person
                        }))
                    }
                }
            }
        }
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.isBlocked -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(personEditorProblem(state.problem), textAlign = TextAlign.Center)
            }
            else -> Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.name, onValueChange = viewModel::setName, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.person_name_label)) }, singleLine = true,
                    enabled = !state.isSaving, isError = state.nameError,
                    supportingText = if (state.nameError) { { Text(stringResource(R.string.person_name_required)) } } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = state.phone, onValueChange = viewModel::setPhone, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.person_phone_label)) }, singleLine = true, enabled = !state.isSaving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = state.note, onValueChange = viewModel::setNote, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.person_note_label)) }, enabled = !state.isSaving,
                    minLines = 2, maxLines = 5, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
                Text(stringResource(R.string.person_type_label), style = MaterialTheme.typography.labelLarge)
                PersonType.values().forEach { type ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(selected = state.type == type, enabled = !state.isSaving, role = Role.RadioButton,
                                onClick = { viewModel.setType(type) }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = state.type == type, onClick = null, enabled = !state.isSaving)
                        Text(personTypeLabel(type), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                if (state.isEdit) Text(stringResource(R.string.person_type_change_help), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.problem?.let { Text(personEditorProblem(it), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun personEditorProblem(problem: PersonEditorProblem?): String = stringResource(when (problem) {
    PersonEditorProblem.NOT_FOUND -> R.string.person_not_found
    PersonEditorProblem.LOAD_FAILED -> R.string.person_load_error
    PersonEditorProblem.LINK_FAILED -> R.string.loan_link_error
    PersonEditorProblem.SAVE_FAILED, null -> R.string.operation_failed
})
