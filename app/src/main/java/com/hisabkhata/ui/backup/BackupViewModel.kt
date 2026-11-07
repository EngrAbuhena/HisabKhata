package com.hisabkhata.ui.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisabkhata.R
import com.hisabkhata.data.drive.AutomaticDriveBackupScheduler
import com.hisabkhata.data.drive.DriveBackupFile
import com.hisabkhata.data.drive.DriveBackupService
import com.hisabkhata.data.drive.DriveUnauthorizedException
import com.hisabkhata.data.settings.AppPreferences
import com.hisabkhata.data.transfer.DataTransferRepository
import com.hisabkhata.data.transfer.FileTransfer
import com.hisabkhata.data.transfer.RestoreMode
import com.hisabkhata.domain.backup.AutomaticBackupStatus
import com.hisabkhata.domain.backup.BackupCodec
import com.hisabkhata.domain.backup.DatabaseBackup
import com.hisabkhata.domain.backup.InvalidBackupException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BackupProblem { INVALID, BACKUP_FAILED, RESTORE_FAILED, DRIVE_FAILED, SIGN_IN_FAILED, REVOKE_FAILED, LOCAL_DELETE_FAILED }
enum class BackupNotice { LOCAL_SAVED, RESTORED, DRIVE_UPLOADED, DRIVE_DELETED }
data class BackupUiState(
    val busy: Boolean = false,
    val accountName: String? = null,
    val backups: List<DriveBackupFile> = emptyList(),
    val driveLoaded: Boolean = false,
    val candidate: DatabaseBackup? = null,
    val deleteTarget: BackupDeleteTarget? = null,
    val deleteConfirmed: Boolean = false,
    val problem: BackupProblem? = null,
    val notice: BackupNotice? = null
)
sealed interface BackupDeleteTarget {
    data class ReplaceDatabase(val people: Int, val loans: Int, val cash: Int) : BackupDeleteTarget
    data class Drive(val file: DriveBackupFile) : BackupDeleteTarget
    data class Local(val uri: Uri, val name: String) : BackupDeleteTarget
}
sealed interface BackupEvent {
    data class ClearExpiredToken(val token: String) : BackupEvent
    data object BackupDeleted : BackupEvent
}
sealed interface DriveAction {
    data object Connect : DriveAction
    data object Refresh : DriveAction
    data object Upload : DriveAction
    data class Download(val id: String) : DriveAction
    data class Delete(val id: String) : DriveAction
}
private sealed interface DriveOutcome {
    data class Listed(val files: List<DriveBackupFile>) : DriveOutcome
    data class Uploaded(val time: Long) : DriveOutcome
    data class Downloaded(val backup: DatabaseBackup) : DriveOutcome
    data class Deleted(val id: String) : DriveOutcome
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val repository: DataTransferRepository,
    private val drive: DriveBackupService,
    private val preferences: AppPreferences,
    private val automaticScheduler: AutomaticDriveBackupScheduler,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val mutable = MutableStateFlow(BackupUiState(accountName = preferences.driveAccount.value))
    val state = mutable.asStateFlow()
    val lastLocalBackup = preferences.lastLocalBackup
    val lastDriveBackup = preferences.lastDriveBackup
    val automaticDriveEnabled = preferences.automaticDriveEnabled
    val automaticDriveStatus = preferences.automaticDriveStatus
    val lastAutomaticDriveBackup = preferences.lastAutomaticDriveBackup
    private val eventChannel = Channel<BackupEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    fun connected(account: String) {
        if (preferences.driveAccount.value != account) automaticScheduler.cancel()
        preferences.setDriveAccount(account)
        mutable.update { it.copy(accountName = account, backups = emptyList(), driveLoaded = false, problem = null, notice = null) }
    }
    fun setAutomaticDriveBackup(enabled: Boolean) {
        preferences.setAutomaticDriveEnabled(enabled)
        if (preferences.activeAccount() == null) automaticScheduler.cancel() else automaticScheduler.scheduleNext()
    }
    private fun authorizationSucceeded() {
        if (preferences.automaticDriveStatus.value == AutomaticBackupStatus.NEEDS_AUTHORIZATION) {
            preferences.recordStatus(AutomaticBackupStatus.NONE)
            automaticScheduler.dismissAlert()
            automaticScheduler.scheduleNext()
        }
    }
    fun signInFailed() { mutable.update { it.copy(deleteTarget = if (it.deleteConfirmed) null else it.deleteTarget,
        deleteConfirmed = false, problem = BackupProblem.SIGN_IN_FAILED) } }
    fun signedOut(revoked: Boolean) {
        preferences.setAutomaticDriveEnabled(false)
        automaticScheduler.cancel()
        preferences.setDriveAccount(null)
        mutable.value = BackupUiState(problem = if (revoked) null else BackupProblem.REVOKE_FAILED)
    }
    fun saveLocal(uri: Uri?) {
        if (uri == null || mutable.value.busy || mutable.value.deleteTarget != null) return
        mutable.update { it.copy(busy = true, problem = null, notice = null) }
        viewModelScope.launch {
            try {
                val snapshot = repository.snapshot()
                withContext(Dispatchers.IO) { FileTransfer.write(context, uri, BackupCodec.encode(snapshot).toByteArray(Charsets.UTF_8)) }
                preferences.markLocalBackup(snapshot.createdAt)
                mutable.update { it.copy(busy = false, notice = BackupNotice.LOCAL_SAVED) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(busy = false, problem = BackupProblem.BACKUP_FAILED) } }
        }
    }
    fun loadLocal(uri: Uri?) {
        if (uri == null || mutable.value.busy || mutable.value.deleteTarget != null) return
        mutable.update { it.copy(busy = true, problem = null, notice = null) }
        viewModelScope.launch {
            try {
                val backup = withContext(Dispatchers.IO) { BackupCodec.decode(FileTransfer.read(context, uri)) }
                mutable.update { it.copy(busy = false, candidate = backup) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(busy = false, problem = BackupProblem.INVALID) } }
        }
    }
    fun cancelRestore() {
        if (!mutable.value.busy && mutable.value.deleteTarget == null) mutable.update { it.copy(candidate = null) }
    }

    /** Merge is non-destructive; replacement is only reachable via the shared confirmation. */
    fun restoreMerge() {
        if (mutable.value.deleteTarget == null) startRestore(RestoreMode.MERGE)
    }

    fun requestReplace() {
        val current = mutable.value
        if (!current.busy && current.candidate != null && current.deleteTarget == null) {
            val backup = requireNotNull(current.candidate)
            mutable.update { it.copy(deleteTarget = BackupDeleteTarget.ReplaceDatabase(backup.people.size, backup.loans.size, backup.cash.size), problem = null) }
        }
    }

    fun requestDriveDelete(file: DriveBackupFile) {
        val current = mutable.value
        if (!current.busy && current.candidate == null && current.deleteTarget == null &&
            current.backups.any { it.id == file.id } && current.accountName != null) {
            mutable.update { it.copy(deleteTarget = BackupDeleteTarget.Drive(file), problem = null, notice = null) }
        }
    }

    /** SAF allows selecting a document, but only an independently validated Hisab Khata backup
     * can proceed to the destructive confirmation. No write or delete occurs here. */
    fun requestLocalDelete(uri: Uri?) {
        val current = mutable.value
        if (uri == null || current.busy || current.candidate != null || current.deleteTarget != null) return
        mutable.update { it.copy(busy = true, problem = null, notice = null) }
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) {
                    BackupCodec.decode(FileTransfer.read(context, uri))
                    try {
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        }
                    } catch (_: Exception) { null }
                } ?: context.getString(R.string.local_backup_fallback_name)
                mutable.update { it.copy(busy = false, deleteTarget = BackupDeleteTarget.Local(uri, name)) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(busy = false, problem = BackupProblem.INVALID) } }
        }
    }

    fun cancelDelete() {
        val current = mutable.value
        if (!current.busy && !current.deleteConfirmed) mutable.update { it.copy(deleteTarget = null) }
    }

    /** Returns a Drive action only after confirmation; runDrive checks the same one-shot gate. */
    fun confirmDelete(): DriveAction.Delete? {
        val current = mutable.value
        if (current.busy || current.deleteConfirmed) return null
        when (val target = current.deleteTarget ?: return null) {
            is BackupDeleteTarget.ReplaceDatabase -> startRestore(RestoreMode.REPLACE)
            is BackupDeleteTarget.Local -> {
                mutable.update { it.copy(busy = true, problem = null) }
                viewModelScope.launch {
                    try {
                        val deleted = withContext(Dispatchers.IO) { DocumentsContract.deleteDocument(context.contentResolver, target.uri) }
                        check(deleted)
                        mutable.update { it.copy(busy = false, deleteTarget = null) }
                        eventChannel.send(BackupEvent.BackupDeleted)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { mutable.update { it.copy(busy = false, deleteTarget = null, problem = BackupProblem.LOCAL_DELETE_FAILED) } }
                }
            }
            is BackupDeleteTarget.Drive -> {
                if (current.accountName == null) return null
                mutable.update { it.copy(deleteConfirmed = true, problem = null) }
                return DriveAction.Delete(target.file.id)
            }
        }
        return null
    }

    private fun startRestore(mode: RestoreMode) {
        val current = mutable.value
        val backup = current.candidate ?: return
        if (current.busy || (mode == RestoreMode.REPLACE && current.deleteTarget !is BackupDeleteTarget.ReplaceDatabase)) return
        mutable.update { it.copy(busy = true, candidate = null, problem = null, notice = null) }
        viewModelScope.launch {
            try {
                repository.restore(backup, mode)
                mutable.update { it.copy(busy = false, deleteTarget = null, notice = BackupNotice.RESTORED) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(busy = false, deleteTarget = null, problem = BackupProblem.RESTORE_FAILED) } }
        }
    }

    /** The access token is short-lived and never saved to preferences or logs. */
    fun runDrive(action: DriveAction, token: String) {
        val current = mutable.value
        val account = current.accountName ?: return
        if (current.busy || token.isBlank()) return
        if (action !is DriveAction.Delete && current.deleteTarget != null) return
        if (action is DriveAction.Delete &&
            (!current.deleteConfirmed || (current.deleteTarget as? BackupDeleteTarget.Drive)?.file?.id != action.id)) return
        // Consume confirmation before the asynchronous request, preventing repeat calls.
        mutable.update { it.copy(busy = true, problem = null, notice = if (action == DriveAction.Refresh) it.notice else null) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    when (action) {
                        DriveAction.Connect, DriveAction.Refresh -> DriveOutcome.Listed(drive.list(token))
                        DriveAction.Upload -> {
                            val backup = repository.snapshot(); val json = BackupCodec.encode(backup).toByteArray(Charsets.UTF_8)
                            drive.upload(token, json, backup.createdAt); DriveOutcome.Uploaded(backup.createdAt)
                        }
                        is DriveAction.Download -> DriveOutcome.Downloaded(BackupCodec.decode(drive.download(token, action.id)))
                        is DriveAction.Delete -> { drive.delete(token, action.id); DriveOutcome.Deleted(action.id) }
                    }
                }
                authorizationSucceeded()
                when (result) {
                    is DriveOutcome.Listed -> mutable.update { it.copy(busy = false, backups = result.files, driveLoaded = true) }
                    is DriveOutcome.Uploaded -> {
                        preferences.markDriveBackup(account, result.time)
                        mutable.update { it.copy(busy = false, notice = BackupNotice.DRIVE_UPLOADED) }
                        runDrive(DriveAction.Refresh, token) // Separate request; upload already succeeded.
                    }
                    is DriveOutcome.Downloaded -> mutable.update { it.copy(busy = false, candidate = result.backup) }
                    is DriveOutcome.Deleted -> {
                        mutable.update { it.copy(busy = false, deleteTarget = null, deleteConfirmed = false,
                            backups = it.backups.filterNot { file -> file.id == result.id }, notice = BackupNotice.DRIVE_DELETED) }
                        eventChannel.send(BackupEvent.BackupDeleted)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: DriveUnauthorizedException) {
                if (preferences.activeAccount() == account) preferences.recordStatus(AutomaticBackupStatus.NEEDS_AUTHORIZATION)
                mutable.update { it.copy(busy = false, deleteTarget = if (action is DriveAction.Delete) null else it.deleteTarget,
                    deleteConfirmed = false, problem = BackupProblem.DRIVE_FAILED) }
                eventChannel.send(BackupEvent.ClearExpiredToken(token))
            } catch (_: InvalidBackupException) {
                mutable.update { it.copy(busy = false, problem = BackupProblem.INVALID) }
            } catch (_: Exception) {
                mutable.update { it.copy(busy = false, deleteTarget = if (action is DriveAction.Delete) null else it.deleteTarget,
                    deleteConfirmed = false, problem = BackupProblem.DRIVE_FAILED) }
            }
        }
    }
}
