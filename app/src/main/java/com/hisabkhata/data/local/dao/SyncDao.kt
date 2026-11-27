package com.hisabkhata.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hisabkhata.data.local.entity.SyncIdEntity
import com.hisabkhata.data.local.entity.SyncOutboxEntity

@Dao
interface SyncDao {
    // ------------------------------------------------------------ id mapping ---
    @Query("SELECT * FROM sync_ids WHERE entity = :entity AND localId = :localId LIMIT 1")
    suspend fun findId(entity: String, localId: Long): SyncIdEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertId(row: SyncIdEntity): Long

    @Query("UPDATE sync_ids SET cloudId = :cloudId, knownUpdatedAt = :updatedAt WHERE entity = :entity AND localId = :localId")
    suspend fun setCloudId(entity: String, localId: Long, cloudId: String, updatedAt: String?): Int

    @Query("UPDATE sync_ids SET knownUpdatedAt = :updatedAt WHERE entity = :entity AND localId = :localId")
    suspend fun setKnownUpdatedAt(entity: String, localId: Long, updatedAt: String?): Int

    /** Reverse lookups used when applying rows pulled from the cloud. */
    @Query("SELECT * FROM sync_ids WHERE entity = :entity AND clientId = :clientId LIMIT 1")
    suspend fun findByClientId(entity: String, clientId: String): SyncIdEntity?

    @Query("SELECT localId FROM sync_ids WHERE entity = :entity AND cloudId = :cloudId LIMIT 1")
    suspend fun localIdForCloudId(entity: String, cloudId: String): Long?

    @Query("UPDATE sync_ids SET clientId = :clientId, cloudId = :cloudId, knownUpdatedAt = :updatedAt WHERE entity = :entity AND localId = :localId")
    suspend fun bind(entity: String, localId: Long, clientId: String, cloudId: String, updatedAt: String?): Int

    @Query("SELECT cloudId FROM sync_ids WHERE entity = :entity AND localId = :localId")
    suspend fun cloudId(entity: String, localId: Long): String?

    @Query("SELECT knownUpdatedAt FROM sync_ids WHERE entity = :entity AND localId = :localId")
    suspend fun knownUpdatedAt(entity: String, localId: Long): String?

    // ----------------------------------------------------------------- outbox ---
    @Insert
    suspend fun enqueue(row: SyncOutboxEntity): Long

    /**
     * Collapses repeated edits: one queued row per (entity, localId). A newer
     * change replaces the older one so the newest local state is what gets sent.
     */
    @Query("UPDATE sync_outbox SET state = 'PENDING', attempts = 0, lastError = NULL WHERE id = :id")
    suspend fun revive(id: Long): Int

    @Query("DELETE FROM sync_outbox WHERE entity = :entity AND localId = :localId")
    suspend fun dropFor(entity: String, localId: Long): Int

    @Query("SELECT * FROM sync_outbox WHERE entity = :entity AND localId = :localId LIMIT 1")
    suspend fun existing(entity: String, localId: Long): SyncOutboxEntity?

    @Query("""
        SELECT * FROM sync_outbox
        WHERE accountId = :accountId AND state = 'PENDING'
        ORDER BY id ASC LIMIT :limit
    """)
    suspend fun pending(accountId: String, limit: Int): List<SyncOutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE accountId = :accountId AND state = 'PENDING'")
    suspend fun countPending(accountId: String): Int

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE accountId = :accountId AND state = 'CONFLICT'")
    suspend fun countConflicts(accountId: String): Int

    @Query("DELETE FROM sync_outbox WHERE id = :id")
    suspend fun remove(id: Long): Int

    @Query("UPDATE sync_outbox SET attempts = attempts + 1, lastError = :reason WHERE id = :id")
    suspend fun recordFailure(id: Long, reason: String): Int

    @Query("UPDATE sync_outbox SET state = 'CONFLICT', lastError = :reason WHERE id = :id")
    suspend fun markConflict(id: Long, reason: String): Int

    /** Account-scoped purge used by sign-out cleanup; never touches other accounts. */
    @Query("DELETE FROM sync_outbox WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String): Int
}
