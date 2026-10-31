package com.hisabkhata.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.hisabkhata.data.local.entity.PersonEntity
import com.hisabkhata.data.local.entity.PersonType
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonDao {
    // Snapshot/restore operations are called inside a single Room transaction.
    @Query("SELECT * FROM people ORDER BY name COLLATE NOCASE ASC, id ASC")
    suspend fun findAll(): List<PersonEntity>

    @Query("DELETE FROM people")
    suspend fun deleteAll(): Int

    @Query("SELECT * FROM people ORDER BY name COLLATE NOCASE ASC, id ASC")
    fun observeAll(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM people WHERE type = :type ORDER BY name COLLATE NOCASE ASC, id ASC")
    fun observeByType(type: PersonType): Flow<List<PersonEntity>>

    @Query("SELECT * FROM people WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<PersonEntity?>

    @Query("SELECT * FROM people WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): PersonEntity?

    @Query("DELETE FROM people WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Insert suspend fun insert(person: PersonEntity): Long
    @Update suspend fun update(person: PersonEntity): Int
    @Delete suspend fun delete(person: PersonEntity): Int
}
