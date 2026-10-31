package com.hisabkhata.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hisabkhata.R
import com.hisabkhata.data.settings.AppPreferences
import com.hisabkhata.data.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val preferences: AppPreferences) : ViewModel() {
    val theme = preferences.theme
    fun setTheme(mode: ThemeMode) = preferences.setTheme(mode)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onTransfer: () -> Unit, onBackup: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val mode by viewModel.theme.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleMedium)
                    ThemeMode.values().forEach { option ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).selectable(selected = mode == option, role = Role.RadioButton,
                            onClick = { viewModel.setTheme(option) }), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = mode == option, onClick = null)
                            Text(stringResource(when (option) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark }), modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
            Text(stringResource(R.string.settings_data), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp))
            SettingsAction(R.string.settings_export_import, R.string.settings_export_import_desc, onTransfer)
            SettingsAction(R.string.settings_backup, R.string.settings_backup_desc, onBackup)
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.about_data_warning), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsAction(title: Int, subtitle: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
