package com.hisabkhata.data.repository

import android.content.Context
import com.hisabkhata.R
import com.hisabkhata.data.local.HisabDatabase
import com.hisabkhata.data.local.entity.PersonEntity
import com.hisabkhata.data.local.entity.PersonType
import com.hisabkhata.domain.LoanCashSynchronizer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LoanRepository @Inject constructor(
    private val database: HisabDatabase,
    @ApplicationContext context: Context
) {
    private val people = database.personDao()
    private val loans = database.loanEntryDao()
    private val sync = LoanCashSynchronizer(
        store = RoomLoanSyncStore(database),
        cashDescription = { name, description -> context.getString(R.string.loan_cash_description, name, description) }
    )

    fun observePeople() = people.observeAll()
    fun observeAllEntries() = loans.observeAll()
    fun observePerson(personId: Long) = people.observeById(personId)
    fun observeEntries(personId: Long) = loans.observeForPerson(personId)
    suspend fun findPerson(id: Long) = people.findById(id)
    suspend fun findEntry(id: Long) = loans.findById(id)

    suspend fun addPerson(name: String, phone: String, note: String, type: PersonType): Long {
        require(name.isNotBlank())
        return people.insert(PersonEntity(name = name.trim(), phone = phone.trim(), note = note.trim(), type = type))
    }

    suspend fun updatePerson(person: PersonEntity) = sync.updatePerson(person)
    suspend fun deletePerson(personId: Long) = sync.deletePerson(personId)
    suspend fun saveEntry(personId: Long, entryId: Long?, date: Long, loan: Long, paid: Long,
        description: String, recordInCash: Boolean): Long =
        sync.saveEntry(personId, entryId, date, loan, paid, description, recordInCash)
    suspend fun deleteEntry(personId: Long, entryId: Long) = sync.deleteEntry(personId, entryId)
}
