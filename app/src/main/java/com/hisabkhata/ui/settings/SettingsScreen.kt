package com.hisabkhata.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hisabkhata.BuildConfig
import com.hisabkhata.R
import com.hisabkhata.data.cloud.CloudSession
import com.hisabkhata.data.settings.AppLanguage
import com.hisabkhata.data.settings.AppMode
import com.hisabkhata.data.settings.AppPreferences
import com.hisabkhata.data.settings.ThemeMode
import com.hisabkhata.data.sync.SyncCoordinator
import com.hisabkhata.data.sync.SyncStatus
import com.hisabkhata.util.latinStringResource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val session: CloudSession,
    private val sync: SyncCoordinator
) : ViewModel() {
    val theme = preferences.theme
    val language = preferences.language
    val appMode = preferences.appMode
    val account = session.account
    val syncState = sync.state
    fun setTheme(mode: ThemeMode) = preferences.setTheme(mode)
    fun setLanguage(language: AppLanguage) = preferences.setLanguage(language)

    init { viewModelScope.launch { sync.refreshCounts() } }

    fun syncNow() = viewModelScope.launch { sync.pushNow() }

    /** Signing out never discards queued work; the warning states that explicitly. */
    fun signOut() = viewModelScope.launch {
        sync.pushNow()
        session.signOut()
        sync.clear()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onTransfer: () -> Unit, onBackup: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val mode by viewModel.theme.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val account by viewModel.account.collectAsStateWithLifecycle()
            val syncState by viewModel.syncState.collectAsStateWithLifecycle()
            AccountCard(
                email = account?.email,
                syncState = syncState,
                onSyncNow = viewModel::syncNow,
                onSignOut = viewModel::signOut
            )
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
                    listOf(AppLanguage.ENGLISH, AppLanguage.BENGALI, AppLanguage.SYSTEM).forEach { option ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).selectable(
                            selected = language == option, role = Role.RadioButton,
                            onClick = {
                                if (language != option) {
                                    viewModel.setLanguage(option)
                                    context.findActivity()?.recreate()
                                }
                            }
                        ), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = language == option, onClick = null)
                            Text(stringResource(when (option) {
                                AppLanguage.ENGLISH -> R.string.language_english
                                AppLanguage.BENGALI -> R.string.language_bengali
                                AppLanguage.SYSTEM -> R.string.language_system
                            }), modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                    Text(stringResource(R.string.language_system_help), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
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
            val currentYear = remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }
            val currentAppMode by viewModel.appMode.collectAsStateWithLifecycle()
            val modeLabel = if (currentAppMode == com.hisabkhata.data.settings.AppMode.LOCAL_ONLY) "Local" else "Full"
            val langLabel = if (language == AppLanguage.BENGALI) "Bengali" else "English"
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color.Black,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Text(
                    text = "Developed by Abuhena Rony",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color(0xFF2E7D32),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Text(
                    text = "\u00A9 $currentYear Abuhena Rony - $langLabel - $modeLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

/**
 * Shows who is signed in and the cloud state of unsynced work.
 * Sign-out asks for confirmation whenever the queue is not empty, because those
 * edits exist only on this phone until the account signs in again.
 */
@Composable
private fun AccountCard(
    email: String?,
    syncState: com.hisabkhata.data.sync.SyncUiState,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit
) {
    var confirmSignOut by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.settings_account), style = MaterialTheme.typography.titleMedium)
            Text(
                text = email?.let { stringResource(R.string.settings_signed_in_as) + " " + it }
                    ?: stringResource(R.string.settings_account_desc),
                style = MaterialTheme.typography.bodyMedium
            )
            val detail = when (syncState.status) {
                SyncStatus.SYNCING -> stringResource(R.string.settings_sync_now)
                SyncStatus.ACTION_NEEDED -> latinStringResource(R.string.settings_sync_conflicts, syncState.conflicts)
                SyncStatus.WAITING -> stringResource(R.string.settings_sync_offline)
                SyncStatus.SYNCED, SyncStatus.IDLE ->
                    if (syncState.pending > 0) latinStringResource(R.string.settings_sync_pending, syncState.pending)
                    else stringResource(R.string.settings_sync_synced)
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSyncNow) { Text(stringResource(R.string.settings_sync_now)) }
                TextButton(onClick = { if (syncState.pending > 0) confirmSignOut = true else onSignOut() }) {
                    Text(stringResource(R.string.settings_sign_out))
                }
            }
        }
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.settings_sign_out)) },
            text = { Text(stringResource(R.string.settings_sign_out_warning)) },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; onSignOut() }) {
                    Text(stringResource(R.string.settings_sign_out_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) {
                    Text(stringResource(R.string.settings_sign_out_cancel))
                }
            }
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findActivity()
    else -> null
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
