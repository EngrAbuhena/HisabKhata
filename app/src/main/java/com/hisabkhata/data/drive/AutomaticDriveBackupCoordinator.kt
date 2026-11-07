package com.hisabkhata.data.drive

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.hisabkhata.data.settings.AppPreferences
import com.hisabkhata.data.transfer.DataTransferRepository
import com.hisabkhata.domain.backup.AutomaticAuthorization
import com.hisabkhata.domain.backup.AutomaticBackupAuthorizationRequired
import com.hisabkhata.domain.backup.AutomaticBackupExecutor
import com.hisabkhata.domain.backup.AutomaticBackupPermanentFailure
import com.hisabkhata.domain.backup.BackupCodec
import com.hisabkhata.domain.backup.InvalidBackupException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reuses the manual backup snapshot/codec/Drive client; no token is stored. */
@Singleton
class AutomaticDriveBackupCoordinator @Inject constructor(
    preferences: AppPreferences,
    repository: DataTransferRepository,
    drive: DriveBackupService,
    @ApplicationContext context: Context
) {
    private val executor = AutomaticBackupExecutor(
        state = preferences,
        authorize = { account ->
            withContext(Dispatchers.IO) {
                try {
                    val request = AuthorizationRequest.builder()
                        .setAccount(Account(account, "com.google"))
                        .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
                        .build()
                    val result = Tasks.await(Identity.getAuthorizationClient(context).authorize(request), 30, TimeUnit.SECONDS)
                    if (result.hasResolution() || result.accessToken.isNullOrBlank() ||
                        !result.grantedScopes.contains(DRIVE_APPDATA_SCOPE)) AutomaticAuthorization.NeedsUser
                    else AutomaticAuthorization.Authorized(requireNotNull(result.accessToken))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    // Tasks.await wraps API errors in ExecutionException. Consent, service
                    // installation and OAuth configuration require user intervention.
                    val cause = if (e is ExecutionException) e.cause else e
                    when ((cause as? ApiException)?.statusCode) {
                        CommonStatusCodes.SIGN_IN_REQUIRED, CommonStatusCodes.RESOLUTION_REQUIRED,
                        CommonStatusCodes.DEVELOPER_ERROR, ConnectionResult.SERVICE_MISSING,
                        ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED, ConnectionResult.SERVICE_DISABLED ->
                            AutomaticAuthorization.NeedsUser
                        else -> AutomaticAuthorization.Retry
                    }
                }
            }
        },
        upload = { _, token ->
            val snapshot = repository.snapshot()
            val bytes = try { BackupCodec.encode(snapshot).toByteArray(Charsets.UTF_8) }
                catch (_: InvalidBackupException) { throw AutomaticBackupPermanentFailure() }
            try {
                withContext(Dispatchers.IO) { drive.upload(token, bytes, snapshot.createdAt) }
            } catch (_: DriveUnauthorizedException) {
                // The UI will request fresh consent; never write an access token to disk.
                withContext(Dispatchers.IO) {
                    runCatching { Tasks.await(Identity.getAuthorizationClient(context).clearToken(
                        ClearTokenRequest.builder().setToken(token).build()), 5, TimeUnit.SECONDS) }
                }
                throw AutomaticBackupAuthorizationRequired()
            } catch (e: DriveRequestException) {
                // A bad request, disabled API/insufficient permission, or full quota
                // needs attention; server and rate-limit errors still get retries.
                if (e.statusCode in setOf(400, 403, 404, 413)) throw AutomaticBackupPermanentFailure()
                throw e
            }
            snapshot.createdAt
        }
    )

    suspend fun run(scheduledDay: String) = executor.run(scheduledDay)
}
