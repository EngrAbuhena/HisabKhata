package com.hisabkhata.ui.backup
import com.hisabkhata.util.latinStringResource

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.hisabkhata.R
import com.hisabkhata.data.drive.DRIVE_APPDATA_SCOPE
import com.hisabkhata.domain.backup.AutomaticBackupStatus
import com.hisabkhata.ui.components.ConfirmDeleteDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit, onBackupDeleted: () -> Unit = {}, viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastLocal by viewModel.lastLocalBackup.collectAsStateWithLifecycle()
    val lastDrive by viewModel.lastDriveBackup.collectAsStateWithLifecycle()
    val automaticEnabled by viewModel.automaticDriveEnabled.collectAsStateWithLifecycle()
    val automaticStatus by viewModel.automaticDriveStatus.collectAsStateWithLifecycle()
    val lastAutomatic by viewModel.lastAutomaticDriveBackup.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val backupFileName = stringResource(R.string.backup_filename)
    var authorizing by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<String, DriveAction>?>(null) }
    val client = remember(context) { Identity.getAuthorizationClient(context) }
    val scope = remember { Scope(DRIVE_APPDATA_SCOPE) }
    val saveLocal = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), viewModel::saveLocal)
    val openLocal = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), viewModel::loadLocal)
    val deleteLocal = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), viewModel::requestLocalDelete)
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun finishAuthorization(result: AuthorizationResult, name: String, action: DriveAction) {
        authorizing = false; pending = null
        val token = result.accessToken
        if (token.isNullOrBlank() || !result.grantedScopes.contains(DRIVE_APPDATA_SCOPE)) { viewModel.signInFailed(); return }
        if (action == DriveAction.Connect) viewModel.connected(name)
        viewModel.runDrive(if (action == DriveAction.Connect) DriveAction.Refresh else action, token)
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val saved = pending
        if (result.resultCode != Activity.RESULT_OK || saved == null) { authorizing = false; pending = null; viewModel.signInFailed() }
        else try { finishAuthorization(client.getAuthorizationResultFromIntent(result.data), saved.first, saved.second) }
        catch (_: Exception) { authorizing = false; pending = null; viewModel.signInFailed() }
    }
    fun authorize(name: String, action: DriveAction) {
        if (authorizing || state.busy) return
        authorizing = true
        try {
            val request = AuthorizationRequest.builder().setAccount(Account(name, "com.google")).setRequestedScopes(listOf(scope)).build()
            client.authorize(request).addOnSuccessListener { result ->
                try {
                    if (result.hasResolution()) {
                        pending = name to action
                        consent.launch(IntentSenderRequest.Builder(requireNotNull(result.pendingIntent).intentSender).build())
                    } else finishAuthorization(result, name, action)
                } catch (_: Exception) { authorizing = false; pending = null; viewModel.signInFailed() }
            }.addOnFailureListener { authorizing = false; pending = null; viewModel.signInFailed() }
        } catch (_: Exception) { authorizing = false; pending = null; viewModel.signInFailed() }
    }
    val accountPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        authorizing = false
        if (result.resultCode == Activity.RESULT_OK) {
            val name = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            if (name.isNullOrBlank()) viewModel.signInFailed() else authorize(name, DriveAction.Connect)
        }
    }
    LaunchedEffect(viewModel) { viewModel.events.collect { event ->
        when (event) {
            is BackupEvent.ClearExpiredToken -> client.clearToken(ClearTokenRequest.builder().setToken(event.token).build())
            BackupEvent.BackupDeleted -> onBackupDeleted()
        }
    } }
    // Re-opened screens can refresh a previously connected account without storing access tokens.
    LaunchedEffect(state.accountName) {
        val account = state.accountName
        if (account != null && !state.driveLoaded && !state.busy && !authorizing) authorize(account, DriveAction.Refresh)
    }
    val busy = state.busy || authorizing
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.backup_title)) },
        navigationIcon = { IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "local") {
                BackupCard(R.string.backup_local_title) {
                    Text(stringResource(R.string.backup_local_help), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.backup_last, if (lastLocal == 0L) stringResource(R.string.backup_never) else formatBackupTime(lastLocal)), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { saveLocal.launch(backupFileName) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.backup_save_local)) }
                    OutlinedButton(onClick = { openLocal.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.backup_restore_local)) }
                    OutlinedButton(onClick = { deleteLocal.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.delete_local_backup), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item(key = "drive") {
                BackupCard(R.string.drive_title) {
                    Text(stringResource(R.string.drive_help), style = MaterialTheme.typography.bodyMedium)
                    val account = state.accountName
                    if (account == null) Button(onClick = {
                        try {
                            authorizing = true
                            accountPicker.launch(AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null))
                        } catch (_: Exception) { authorizing = false; viewModel.signInFailed() }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.drive_sign_in)) }
                    else {
                        Text(stringResource(R.string.drive_signed_in, account), style = MaterialTheme.typography.bodySmall)
                        val newest = maxOf(state.backups.maxOfOrNull { it.createdAt } ?: 0L, lastDrive)
                        val last = when { newest > 0 -> formatBackupTime(newest); state.driveLoaded -> stringResource(R.string.backup_never); else -> stringResource(R.string.backup_unknown) }
                        Text(stringResource(R.string.drive_last, last), style = MaterialTheme.typography.bodySmall)
                        val automaticTitle = stringResource(R.string.drive_auto_title)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(automaticTitle, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Switch(modifier = Modifier.semantics { contentDescription = automaticTitle },
                                checked = automaticEnabled, enabled = !busy, onCheckedChange = { enabled ->
                                viewModel.setAutomaticDriveBackup(enabled)
                                if (enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            })
                        }
                        Text(stringResource(R.string.drive_auto_description), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.drive_auto_last, if (lastAutomatic == 0L)
                            stringResource(R.string.backup_never) else formatBackupTime(lastAutomatic)), style = MaterialTheme.typography.bodySmall)
                        if (automaticEnabled) {
                            val status = when (automaticStatus) {
                                AutomaticBackupStatus.RETRYING -> R.string.drive_auto_retrying
                                AutomaticBackupStatus.NEEDS_AUTHORIZATION -> R.string.drive_auto_needs_authorization
                                AutomaticBackupStatus.FAILED -> R.string.drive_auto_failed
                                else -> null
                            }
                            status?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                        Button(onClick = { authorize(account, DriveAction.Upload) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.drive_upload)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { authorize(account, DriveAction.Refresh) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.drive_refresh)) }
                            TextButton(onClick = {
                                try {
                                    authorizing = true
                                    val request = RevokeAccessRequest.builder().setAccount(Account(account, "com.google")).setScopes(listOf(scope)).build()
                                    client.revokeAccess(request).addOnCompleteListener { authorizing = false; viewModel.signedOut(it.isSuccessful) }
                                } catch (_: Exception) { authorizing = false; viewModel.signedOut(false) }
                            }, enabled = !busy) { Text(stringResource(R.string.drive_sign_out)) }
                        }
                        Text(stringResource(R.string.drive_previous), style = MaterialTheme.typography.titleSmall)
                        if (state.driveLoaded && state.backups.isEmpty()) Text(stringResource(R.string.drive_empty), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(state.backups, key = { it.id }) { backup ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(backup.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.drive_backup_info, if (backup.createdAt > 0) formatBackupTime(backup.createdAt) else stringResource(R.string.backup_unknown),
                            Formatter.formatShortFileSize(context, backup.size.coerceAtLeast(0))), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { state.accountName?.let { authorize(it, DriveAction.Download(backup.id)) } }, enabled = !busy) { Text(stringResource(R.string.drive_restore)) }
                            TextButton(onClick = { viewModel.requestDriveDelete(backup) }, enabled = !busy) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            item(key = "status") {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (busy) { CircularProgressIndicator(); Text(stringResource(R.string.backup_processing)) }
                    state.problem?.let { Text(backupProblem(it), color = MaterialTheme.colorScheme.error) }
                    state.notice?.let { Text(backupNotice(it), color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
    if (state.deleteTarget == null) state.candidate?.let { backup ->
        AlertDialog(onDismissRequest = viewModel::cancelRestore, title = { Text(stringResource(R.string.restore_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(latinStringResource(R.string.restore_details, backup.people.size, backup.loans.size, backup.cash.size))
                Text(stringResource(R.string.restore_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = viewModel::requestReplace, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.restore_replace)) }
                OutlinedButton(onClick = viewModel::restoreMerge, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.restore_merge)) }
            } }, dismissButton = { TextButton(onClick = viewModel::cancelRestore) { Text(stringResource(R.string.cancel)) } }, confirmButton = {})
    }
    state.deleteTarget?.let { target ->
        val title = when (target) {
            is BackupDeleteTarget.ReplaceDatabase -> stringResource(R.string.restore_replace_confirmation_title)
            is BackupDeleteTarget.Local -> stringResource(R.string.delete_local_backup_title)
            is BackupDeleteTarget.Drive -> stringResource(R.string.drive_delete_title)
        }
        val message = when (target) {
            is BackupDeleteTarget.ReplaceDatabase -> latinStringResource(R.string.restore_replace_confirmation_message, target.people, target.loans, target.cash)
            is BackupDeleteTarget.Local -> stringResource(R.string.delete_local_backup_message, target.name)
            is BackupDeleteTarget.Drive -> stringResource(R.string.delete_combine_message,
                stringResource(R.string.drive_delete_message, target.file.name), stringResource(R.string.delete_irreversible))
        }
        ConfirmDeleteDialog(title = title, message = message, isBusy = busy || state.deleteConfirmed,
            onCancel = viewModel::cancelDelete, onConfirm = {
                val action = viewModel.confirmDelete()
                if (action != null) {
                    val account = state.accountName
                    if (account != null) authorize(account, action) else viewModel.signInFailed()
                }
            })
    }
}

private fun formatBackupTime(time: Long): String = SimpleDateFormat("dd.MM.yy HH:mm", Locale.US).format(Date(time))

@Composable
private fun BackupCard(title: Int, content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium); content()
        }
    }
}
@Composable
private fun backupProblem(problem: BackupProblem) = stringResource(when (problem) {
    BackupProblem.INVALID -> R.string.backup_invalid
    BackupProblem.BACKUP_FAILED -> R.string.backup_failed
    BackupProblem.RESTORE_FAILED -> R.string.restore_failed
    BackupProblem.DRIVE_FAILED -> R.string.drive_error
    BackupProblem.SIGN_IN_FAILED -> R.string.drive_sign_in_failed
    BackupProblem.REVOKE_FAILED -> R.string.drive_revoke_failed
    BackupProblem.LOCAL_DELETE_FAILED -> R.string.delete_local_backup_error
})
@Composable
private fun backupNotice(notice: BackupNotice) = stringResource(when (notice) {
    BackupNotice.LOCAL_SAVED -> R.string.backup_saved
    BackupNotice.RESTORED -> R.string.restore_complete
    BackupNotice.DRIVE_UPLOADED -> R.string.drive_upload_done
    BackupNotice.DRIVE_DELETED -> R.string.drive_delete_done
})
