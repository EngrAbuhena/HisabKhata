package com.hisabkhata.domain.sync

import com.hisabkhata.data.local.entity.OutboxState
import com.hisabkhata.data.local.entity.SyncOutboxEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue collapses repeated edits per row. These are the rules
 * [com.hisabkhata.data.sync.RoomSyncStore.enqueue] applies, restated as a pure
 * function so they are checked without an emulator.
 */
private fun merge(existing: SyncOutboxEntity?, incomingOperation: SyncOperation, newRowId: Long): List<SyncOutboxEntity> {
    if (existing == null) return listOf(
        SyncOutboxEntity(0, "acct", "PERSON", incomingOperation.name, 1, "c", createdAt = 0)
    )
    return when {
        // A queued delete is final: later edits do not resurrect a deleted row.
        existing.operation == SyncOperation.DELETE.name -> listOf(existing.copy(state = OutboxState.PENDING.name, attempts = 0))
        else -> listOf(
            SyncOutboxEntity(newRowId, existing.accountId, existing.entity, incomingOperation.name,
                existing.localId, existing.clientId, createdAt = 0)
        )
    }
}

class OutboxMergeRulesTest {
    private val queued = SyncOutboxEntity(
        id = 7, accountId = "acct", entity = "PERSON", operation = SyncOperation.UPSERT.name,
        localId = 1, clientId = "client-1", state = OutboxState.CONFLICT.name,
        attempts = 3, lastError = "changed on another device", createdAt = 0
    )

    @Test
    fun `a newer edit replaces the older queued upsert`() {
        val merged = merge(queued, SyncOperation.UPSERT, newRowId = 8)
        assertEquals(1, merged.size)
        assertEquals(8L, merged.single().id)
    }

    @Test
    fun `a delete after an edit wins over the edit`() {
        val merged = merge(queued, SyncOperation.DELETE, newRowId = 9)
        assertEquals(SyncOperation.DELETE.name, merged.single().operation)
    }

    @Test
    fun `an edit after a delete does not resurrect the row`() {
        val deleted = queued.copy(operation = SyncOperation.DELETE.name)
        val merged = merge(deleted, SyncOperation.UPSERT, newRowId = 10)
        assertEquals(SyncOperation.DELETE.name, merged.single().operation)
        // Reviving resets the attempt counter so a genuine retry can run.
        assertEquals(0, merged.single().attempts)
    }

    @Test
    fun `a conflicted row is revived as pending rather than duplicated`() {
        val deleted = queued.copy(operation = SyncOperation.DELETE.name, state = OutboxState.CONFLICT.name)
        val merged = merge(deleted, SyncOperation.DELETE, newRowId = 11)
        assertEquals(1, merged.size)
        assertEquals(OutboxState.PENDING.name, merged.single().state)
    }

    @Test
    fun `first change for a row creates one queue entry`() {
        val merged = merge(null, SyncOperation.UPSERT, newRowId = 1)
        assertEquals(1, merged.size)
        assertTrue(merged.single().operation == SyncOperation.UPSERT.name)
    }
}
