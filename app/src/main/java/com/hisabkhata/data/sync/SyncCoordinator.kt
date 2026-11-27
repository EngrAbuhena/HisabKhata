package com.hisabkhata.data.sync

import com.hisabkhata.data.cloud.CloudPull
import com.hisabkhata.data.cloud.CloudSession
import com.hisabkhata.domain.sync.SyncEngine
import com.hisabkhata.domain.sync.SyncPass
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the UI shows about backup state. "Action needed" is never silent. */
enum class SyncStatus { IDLE, SYNCING, SYNCED, WAITING, ACTION_NEEDED }

data class SyncUiState(
    val status: SyncStatus = SyncStatus.IDLE,
    val pending: Int = 0,
    val conflicts: Int = 0,
    val detail: String? = null
)

/**
 * Runs sync passes for the signed-in account only, one at a time.
 *
 * A pass never changes the account it was started for: if the session changes
 * mid-flight the result is discarded instead of being reported against the new user.
 */
@Singleton
class SyncCoordinator @Inject constructor(
    private val engine: SyncEngine,
    private val store: RoomSyncStore,
    private val pull: CloudPull,
    private val session: CloudSession
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = mutableState.asStateFlow()

    suspend fun refreshCounts() {
        val account = session.currentUserId() ?: return
        mutableState.value = mutableState.value.copy(
            pending = store.countPending(account),
            conflicts = store.countConflicts(account)
        )
    }

    /**
     * One full refresh: send this account's queued work, then download the account's
     * current rows. Upload first so a local edit is never replaced by an older cloud
     * copy, and so a freshly migrated device publishes its data before pulling.
     */
    suspend fun pushNow(): SyncPass? = mutex.withLock {
        val account = session.currentUserId() ?: return@withLock null
        mutableState.value = mutableState.value.copy(status = SyncStatus.SYNCING)
        val pass = try {
            val sent = engine.push(account)
            // A pull failure must not lose the upload result; it only means the
            // device keeps working from its local cache until the next refresh.
            runCatching { pull.pull() }
            sent
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // Offline or server problem: the queue stays intact for the next attempt.
            mutableState.value = SyncUiState(
                status = if (store.countPending(account) > 0) SyncStatus.WAITING else SyncStatus.IDLE,
                pending = store.countPending(account),
                conflicts = store.countConflicts(account),
                detail = error.message?.take(160)
            )
            return@withLock null
        }
        if (session.currentUserId() != account) return@withLock null
        val conflicts = store.countConflicts(account)
        mutableState.value = SyncUiState(
            status = when {
                conflicts > 0 -> SyncStatus.ACTION_NEEDED
                pass.remaining > 0 -> SyncStatus.WAITING
                else -> SyncStatus.SYNCED
            },
            pending = pass.remaining,
            conflicts = conflicts,
            detail = pass.lastError
        )
        pass
    }

    fun clear() { mutableState.value = SyncUiState() }
}
