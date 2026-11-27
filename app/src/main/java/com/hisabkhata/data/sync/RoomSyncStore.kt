package com.hisabkhata.data.sync

import androidx.room.withTransaction
import com.hisabkhata.data.local.HisabDatabase
import com.hisabkhata.data.local.entity.OutboxState
import com.hisabkhata.data.local.entity.SyncIdEntity
import com.hisabkhata.data.local.entity.SyncOutboxEntity
import com.hisabkhata.domain.sync.SyncEntity
import com.hisabkhata.domain.sync.SyncEntry
import com.hisabkhata.domain.sync.SyncOperation
import com.hisabkhata.domain.sync.SyncStore
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed upload queue.
 *
 * The important guarantee is [track]: the ledger write and its queue row commit in
 * one Room transaction, so a saved row is never left without an outgoing change and
 * a queued change never points at a row that was rolled back.
 */
@Singleton
class RoomSyncStore @Inject constructor(private val db: HisabDatabase) : SyncStore {
    private val dao = db.syncDao()

    /**
     * Runs [write] and records the resulting row in the same transaction.
     * [write] returns the affected local row ids (empty when nothing changed).
     */
    suspend fun <T> track(
        accountId: String?,
        entity: SyncEntity,
        operation: SyncOperation,
        write: suspend () -> Pair<T, List<Long>>
    ): T = db.withTransaction {
        val (result, ids) = write()
        // A signed-out local write still saves locally; it is queued on sign-in by
        // the migration pass rather than being sent without an owner.
        if (accountId != null) ids.forEach { id -> enqueue(accountId, entity, operation, id) }
        result
    }

    /**
     * Removes a queue row whose local change matched nothing (for example an edit
     * refused by the SQL guard). Without this the app would keep retrying a change
     * that never happened.
     */
    suspend fun dropQueued(entity: SyncEntity, localId: Long) { dao.dropFor(entity.name, localId) }

    suspend fun enqueue(accountId: String, entity: SyncEntity, operation: SyncOperation, localId: Long) {
        val key = entity.name
        val clientId = clientIdFor(key, localId)
        val existing = dao.existing(key, localId)
        if (existing != null) {
            // Repeated edits collapse into one queued row; a delete always wins.
            if (existing.operation != SyncOperation.DELETE.name) {
                dao.dropFor(key, localId)
                dao.enqueue(row(accountId, entity, operation, localId, clientId))
            } else {
                dao.revive(existing.id)
            }
            return
        }
        dao.enqueue(row(accountId, entity, operation, localId, clientId))
    }

    private suspend fun clientIdFor(entity: String, localId: Long): String {
        dao.findId(entity, localId)?.let { return it.clientId }
        // Generated once per row and kept forever, so a retried upsert is idempotent.
        val generated = UUID.randomUUID().toString()
        dao.insertId(SyncIdEntity(entity = entity, localId = localId, clientId = generated))
        return dao.findId(entity, localId)?.clientId ?: generated
    }

    private fun row(accountId: String, entity: SyncEntity, operation: SyncOperation, localId: Long, clientId: String) =
        SyncOutboxEntity(
            accountId = accountId,
            entity = entity.name,
            operation = operation.name,
            localId = localId,
            clientId = clientId,
            state = OutboxState.PENDING.name,
            createdAt = System.currentTimeMillis()
        )

    override suspend fun pending(accountId: String, limit: Int): List<SyncEntry> =
        dao.pending(accountId, limit).mapNotNull { row ->
            val entity = SyncEntity.entries.firstOrNull { it.name == row.entity } ?: return@mapNotNull null
            val operation = SyncOperation.entries.firstOrNull { it.name == row.operation } ?: return@mapNotNull null
            SyncEntry(
                outboxId = row.id,
                accountId = row.accountId,
                entity = entity,
                operation = operation,
                localId = row.localId,
                clientId = row.clientId,
                attempts = row.attempts
            )
        }

    override suspend fun cloudId(entity: SyncEntity, localId: Long): String? = dao.cloudId(entity.name, localId)

    override suspend fun clientId(entity: SyncEntity, localId: Long): String? =
        dao.findId(entity.name, localId)?.clientId

    override suspend fun knownUpdatedAt(entity: SyncEntity, localId: Long): String? =
        dao.knownUpdatedAt(entity.name, localId)

    override suspend fun recordUploaded(entity: SyncEntity, localId: Long, cloudId: String, updatedAt: String?) {
        val changed = dao.setCloudId(entity.name, localId, cloudId, updatedAt)
        if (changed == 0) {
            // The mapping row can be missing only if the local row predates v1.3.0;
            // re-create it rather than lose the server id.
            val clientId = dao.findId(entity.name, localId)?.clientId ?: UUID.randomUUID().toString()
            dao.insertId(
                SyncIdEntity(
                    entity = entity.name,
                    localId = localId,
                    clientId = clientId,
                    cloudId = cloudId,
                    knownUpdatedAt = updatedAt
                )
            )
        }
    }

    override suspend fun remove(outboxId: Long) { dao.remove(outboxId) }

    override suspend fun recordFailure(outboxId: Long, reason: String) { dao.recordFailure(outboxId, reason) }

    override suspend fun markConflict(outboxId: Long, reason: String) { dao.markConflict(outboxId, reason) }

    override suspend fun countPending(accountId: String): Int = dao.countPending(accountId)

    suspend fun countConflicts(accountId: String): Int = dao.countConflicts(accountId)

    /** Sign-out cleanup: this account's queue only. */
    suspend fun forgetAccount(accountId: String) = dao.deleteForAccount(accountId)
}
