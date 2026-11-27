package com.hisabkhata.data.repository

import com.hisabkhata.data.local.dao.CashEntryDao
import com.hisabkhata.data.local.entity.CashEntryEntity
import com.hisabkhata.data.sync.RoomSyncStore
import com.hisabkhata.domain.sync.SyncEntity
import com.hisabkhata.domain.sync.SyncOperation
import com.hisabkhata.data.cloud.CloudSession
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class CashRepository @Inject constructor(
    private val dao: CashEntryDao,
    private val syncStore: RoomSyncStore,
    private val session: CloudSession
) {
    fun observeEntries(): Flow<List<CashEntryEntity>> = dao.observeAll()
    suspend fun findEntry(id: Long): CashEntryEntity? = dao.findById(id)

    /** The row and its queued upload commit together; see [RoomSyncStore.track]. */
    suspend fun addEntry(date: Long, deposit: Long, cost: Long, description: String): Long =
        syncStore.track(session.currentUserId(), SyncEntity.CASH, SyncOperation.UPSERT) {
            val id = dao.insert(
                CashEntryEntity(
                    date = date,
                    deposit = deposit,
                    cost = cost,
                    description = description,
                    createdAt = System.currentTimeMillis(),
                    linkedLoanEntryId = null
                )
            )
            id to listOf(id)
        }

    suspend fun updateEntry(id: Long, date: Long, deposit: Long, cost: Long, description: String): Int {
        val changed = syncStore.track(session.currentUserId(), SyncEntity.CASH, SyncOperation.UPSERT) {
            dao.updateUnlinked(id, date, deposit, cost, description) to listOf(id)
        }
        // A 0-row update (for example a linked row) must not stay queued.
        if (changed == 0) syncStore.dropQueued(SyncEntity.CASH, id)
        return changed
    }

    suspend fun deleteEntry(id: Long): Int {
        val changed = syncStore.track(session.currentUserId(), SyncEntity.CASH, SyncOperation.DELETE) {
            dao.deleteUnlinked(id) to listOf(id)
        }
        if (changed == 0) syncStore.dropQueued(SyncEntity.CASH, id)
        return changed
    }
}
