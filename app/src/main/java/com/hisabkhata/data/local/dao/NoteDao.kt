package com.hisabkhata.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.hisabkhata.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query(
        """
        SELECT * FROM notes
        WHERE deletedAtMs IS NULL
        ORDER BY isMine DESC, pinned DESC, updatedAtMs DESC, id DESC
        """
    )
    fun observeActiveNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): NoteEntity?

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity): Int

    @Query("UPDATE notes SET deletedAtMs = :deletedAtMs, updatedAtMs = :deletedAtMs WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAtMs: Long): Int

    @Query("UPDATE notes SET deletedAtMs = NULL, updatedAtMs = :restoredAtMs WHERE id = :id")
    suspend fun restore(id: Long, restoredAtMs: Long): Int

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun hardDelete(id: Long): Int
}
