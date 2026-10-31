package com.hisabkhata.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.hisabkhata.data.local.entity.CashEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CashEntryDao {
    // Snapshot/restore operations are called inside a single Room transaction.
    @Query("SELECT * FROM cash_entries ORDER BY date ASC, createdAt ASC, id ASC")
    suspend fun findAll(): List<CashEntryEntity>

    @Query("DELETE FROM cash_entries")
    suspend fun deleteAll(): Int

    @Query("SELECT * FROM cash_entries ORDER BY date ASC, createdAt ASC, id ASC")
    fun observeAll(): Flow<List<CashEntryEntity>>

    @Query("SELECT * FROM cash_entries WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): CashEntryEntity?

    @Insert
    suspend fun insert(entry: CashEntryEntity): Long

    // The guard is in SQL as well as in the UI, so a linked row cannot be changed
    // here even if it becomes linked after the editor was opened.
    @Query("""
        UPDATE cash_entries
        SET date = :date, deposit = :deposit, cost = :cost, description = :description
        WHERE id = :id AND linkedLoanEntryId IS NULL
    """)
    suspend fun updateUnlinked(
        id: Long,
        date: Long,
        deposit: Long,
        cost: Long,
        description: String
    ): Int

    @Query("DELETE FROM cash_entries WHERE id = :id AND linkedLoanEntryId IS NULL")
    suspend fun deleteUnlinked(id: Long): Int

    /** Only the owning loan may update or remove a linked cash entry. */
    @Query("""
        UPDATE cash_entries SET date = :date, deposit = :deposit, cost = :cost, description = :description
        WHERE id = :cashEntryId AND linkedLoanEntryId = :loanEntryId
    """)
    suspend fun updateLinked(
        cashEntryId: Long, loanEntryId: Long, date: Long, deposit: Long, cost: Long, description: String
    ): Int

    @Query("DELETE FROM cash_entries WHERE linkedLoanEntryId = :loanEntryId")
    suspend fun deleteLinkedToLoan(loanEntryId: Long): Int

    @Query("""
        DELETE FROM cash_entries WHERE linkedLoanEntryId IN
        (SELECT id FROM loan_entries WHERE personId = :personId)
    """)
    suspend fun deleteLinkedToPerson(personId: Long): Int

    // Both link IDs are set together by the loan synchronization transaction.
    @Query("UPDATE cash_entries SET linkedLoanEntryId = :loanEntryId WHERE id = :cashEntryId")
    suspend fun setLoanLink(cashEntryId: Long, loanEntryId: Long?): Int
}
