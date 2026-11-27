package com.hisabkhata.data.repository

import com.hisabkhata.data.cloud.CloudSession
import com.hisabkhata.data.local.dao.NoteDao
import com.hisabkhata.data.local.entity.NoteEntity
import com.hisabkhata.data.sync.RoomSyncStore
import com.hisabkhata.domain.sync.SyncEntity
import com.hisabkhata.domain.sync.SyncOperation
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoteRepository @Inject constructor(
    private val dao: NoteDao,
    private val sync: RoomSyncStore,
    private val session: CloudSession
) {
    fun observeActiveNotes(): Flow<List<NoteEntity>> = dao.observeActiveNotes()

    suspend fun findById(id: Long): NoteEntity? = dao.findById(id)

    suspend fun saveNote(
        id: Long?,
        title: String,
        body: String,
        isPublic: Boolean,
        showAuthor: Boolean,
        pinned: Boolean
    ): Long {
        val trimmedTitle = title.trim().take(200)
        val cleanShowAuthor = isPublic && showAuthor
        val now = System.currentTimeMillis()
        if (id == null || id <= 0L) {
            return sync.track(session.currentUserId(), SyncEntity.NOTE, SyncOperation.UPSERT) {
                val newId = dao.insert(
                    NoteEntity(
                        title = trimmedTitle,
                        body = body,
                        isPublic = isPublic,
                        showAuthor = cleanShowAuthor,
                        pinned = pinned,
                        isMine = true,
                        updatedAtMs = now
                    )
                )
                newId to listOf(newId)
            }
        } else {
            val existing = dao.findById(id) ?: return id
            if (!existing.isMine) return id
            sync.track(session.currentUserId(), SyncEntity.NOTE, SyncOperation.UPSERT) {
                dao.update(
                    existing.copy(
                        title = trimmedTitle,
                        body = body,
                        isPublic = isPublic,
                        showAuthor = cleanShowAuthor,
                        pinned = pinned,
                        updatedAtMs = now
                    )
                )
                Unit to listOf(id)
            }
            return id
        }
    }

    suspend fun togglePinned(note: NoteEntity) {
        if (!note.isMine) return
        sync.track(session.currentUserId(), SyncEntity.NOTE, SyncOperation.UPSERT) {
            dao.update(note.copy(pinned = !note.pinned, updatedAtMs = System.currentTimeMillis()))
            Unit to listOf(note.id)
        }
    }

    suspend fun softDelete(note: NoteEntity) {
        if (!note.isMine) return
        val now = System.currentTimeMillis()
        sync.track(session.currentUserId(), SyncEntity.NOTE, SyncOperation.DELETE) {
            dao.softDelete(note.id, now)
            Unit to listOf(note.id)
        }
    }

    suspend fun restore(noteId: Long) {
        val note = dao.findById(noteId) ?: return
        if (!note.isMine) return
        val now = System.currentTimeMillis()
        sync.track(session.currentUserId(), SyncEntity.NOTE, SyncOperation.UPSERT) {
            dao.restore(noteId, now)
            Unit to listOf(noteId)
        }
    }
}
