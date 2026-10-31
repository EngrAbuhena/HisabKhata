package com.hisabkhata.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.hisabkhata.data.local.entity.LoanEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LoanEntryDao {
    // Snapshot/restore operations are called inside a single Room transaction.
    @Query("SELECT * FROM loan_entries ORDER BY date ASC, createdAt ASC, id ASC")
    suspend fun findAll(): List<LoanEntryEntity>

    @Query("DELETE FROM loan_entries")
    suspend fun deleteAll(): Int

    @Query("SELECT * FROM loan_entries ORDER BY date ASC, createdAt ASC, id ASC")
    fun observeAll(): Flow<List<LoanEntryEntity>>

    @Query("SELECT * FROM loan_entries WHERE personId = :personId ORDER BY date ASC, createdAt ASC, id ASC")
    suspend fun findAllForPerson(personId: Long): List<LoanEntryEntity>

    @Query("SELECT * FROM loan_entries WHERE personId = :personId ORDER BY date ASC, createdAt ASC, id ASC")
    fun observeForPerson(personId: Long): Flow<List<LoanEntryEntity>>

    @Query("SELECT * FROM loan_entries WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): LoanEntryEntity?

    @Query("DELETE FROM loan_entries WHERE id = :id AND personId = :personId")
    suspend fun deleteForPerson(id: Long, personId: Long): Int

    @Insert suspend fun insert(entry: LoanEntryEntity): Long
    @Update suspend fun update(entry: LoanEntryEntity): Int
    @Delete suspend fun delete(entry: LoanEntryEntity): Int

    @Query("UPDATE loan_entries SET linkedCashEntryId = :cashEntryId WHERE id = :loanEntryId")
    suspend fun setCashLink(loanEntryId: Long, cashEntryId: Long?): Int
}
