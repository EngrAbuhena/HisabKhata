package com.hisabkhata.domain.backup

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Wall-clock target. WorkManager may execute after this time, not exactly at it. */
object DailyBackupSchedule {
    fun next(now: Instant, zone: ZoneId): ZonedDateTime {
        val local = now.atZone(zone)
        val today = local.toLocalDate().atTime(23, 45).atZone(zone)
        return if (today.toInstant().isAfter(now)) today
        else local.toLocalDate().plusDays(1).atTime(23, 45).atZone(zone)
    }
}

enum class AutomaticBackupStatus { NONE, SUCCESS, RETRYING, NEEDS_AUTHORIZATION, FAILED }
enum class AutomaticBackupResult { SKIPPED, SUCCESS, RETRY, NEEDS_AUTHORIZATION, FAILED }

sealed interface AutomaticAuthorization {
    data class Authorized(val token: String) : AutomaticAuthorization
    data object NeedsUser : AutomaticAuthorization
    data object Retry : AutomaticAuthorization
}

class AutomaticBackupAuthorizationRequired : Exception()
class AutomaticBackupPermanentFailure : Exception()

/** Small boundary to make background decisions testable without an account or network. */
interface AutomaticBackupState {
    fun activeAccount(): String?
    fun lastSuccessfulDay(): String?
    fun recordStatus(status: AutomaticBackupStatus)
    fun recordSuccess(account: String, time: Long, localDay: String)
}

/** Serializes automatic runs and never persists tokens or retries a revoked grant endlessly. */
class AutomaticBackupExecutor(
    private val state: AutomaticBackupState,
    private val authorize: suspend (String) -> AutomaticAuthorization,
    private val upload: suspend (String, String) -> Long,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault
) {
    private val mutex = Mutex()

    suspend fun run(scheduledDay: String? = null): AutomaticBackupResult = mutex.withLock {
        val account = state.activeAccount() ?: return@withLock AutomaticBackupResult.SKIPPED
        // A retry just after midnight belongs to the original scheduled day; it
        // must not suppress the following evening's independently scheduled backup.
        val day = scheduledDay ?: now().atZone(zone()).toLocalDate().toString()
        if (state.lastSuccessfulDay() == day) return@withLock AutomaticBackupResult.SKIPPED
        try {
            when (val grant = authorize(account)) {
                AutomaticAuthorization.NeedsUser -> {
                    if (state.activeAccount() != account) return@withLock AutomaticBackupResult.SKIPPED
                    state.recordStatus(AutomaticBackupStatus.NEEDS_AUTHORIZATION)
                    AutomaticBackupResult.NEEDS_AUTHORIZATION
                }
                AutomaticAuthorization.Retry -> {
                    if (state.activeAccount() != account) return@withLock AutomaticBackupResult.SKIPPED
                    state.recordStatus(AutomaticBackupStatus.RETRYING)
                    AutomaticBackupResult.RETRY
                }
                is AutomaticAuthorization.Authorized -> {
                    if (grant.token.isBlank()) {
                        if (state.activeAccount() != account) return@withLock AutomaticBackupResult.SKIPPED
                        state.recordStatus(AutomaticBackupStatus.NEEDS_AUTHORIZATION)
                        return@withLock AutomaticBackupResult.NEEDS_AUTHORIZATION
                    }
                    if (state.activeAccount() != account) return@withLock AutomaticBackupResult.SKIPPED
                    val savedAt = upload(account, grant.token)
                    // Sign-out/disable during a request must not update another account's status.
                    if (state.activeAccount() != account) return@withLock AutomaticBackupResult.SKIPPED
                    state.recordSuccess(account, savedAt, day)
                    AutomaticBackupResult.SUCCESS
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: AutomaticBackupAuthorizationRequired) {
            if (state.activeAccount() != account) return@withLock AutomaticBackupResult.SKIPPED
            state.recordStatus(AutomaticBackupStatus.NEEDS_AUTHORIZATION)
            AutomaticBackupResult.NEEDS_AUTHORIZATION
        } catch (_: AutomaticBackupPermanentFailure) {
            if (state.activeAccount() != account) return@withLock AutomaticBackupResult.SKIPPED
            state.recordStatus(AutomaticBackupStatus.FAILED)
            AutomaticBackupResult.FAILED
        } catch (_: Exception) {
            if (state.activeAccount() != account) return@withLock AutomaticBackupResult.SKIPPED
            state.recordStatus(AutomaticBackupStatus.RETRYING)
            AutomaticBackupResult.RETRY
        }
    }
}
