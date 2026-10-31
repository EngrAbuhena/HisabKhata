package com.hisabkhata.data.repository

import com.hisabkhata.data.local.dao.CashEntryDao
import com.hisabkhata.data.local.entity.CashEntryEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class CashRepository @Inject constructor(private val dao: CashEntryDao) {
    fun observeEntries(): Flow<List<CashEntryEntity>> = dao.observeAll()
    suspend fun findEntry(id: Long): CashEntryEntity? = dao.findById(id)

    suspend fun addEntry(date: Long, deposit: Long, cost: Long, description: String): Long =
        dao.insert(
            CashEntryEntity(
                date = date,
                deposit = deposit,
                cost = cost,
                description = description,
                createdAt = System.currentTimeMillis(),
                linkedLoanEntryId = null
            )
        )

    suspend fun updateEntry(id: Long, date: Long, deposit: Long, cost: Long, description: String): Int =
        dao.updateUnlinked(id, date, deposit, cost, description)

    suspend fun deleteEntry(id: Long): Int = dao.deleteUnlinked(id)
}
