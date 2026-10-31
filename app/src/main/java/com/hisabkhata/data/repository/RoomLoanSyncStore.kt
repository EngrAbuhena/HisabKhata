package com.hisabkhata.data.repository

import androidx.room.withTransaction
import com.hisabkhata.data.local.HisabDatabase
import com.hisabkhata.data.local.entity.CashEntryEntity
import com.hisabkhata.data.local.entity.LoanEntryEntity
import com.hisabkhata.data.local.entity.PersonEntity
import com.hisabkhata.domain.LoanSyncAccess
import com.hisabkhata.domain.LoanSyncStore

/** Room adapter for the testable coordinator; a mutation can never commit halfway. */
class RoomLoanSyncStore(private val db: HisabDatabase) : LoanSyncStore {
    private val access = object : LoanSyncAccess {
        override suspend fun findPerson(id: Long) = db.personDao().findById(id)
        override suspend fun findLoan(id: Long) = db.loanEntryDao().findById(id)
        override suspend fun findCash(id: Long) = db.cashEntryDao().findById(id)
        override suspend fun loansForPerson(personId: Long) = db.loanEntryDao().findAllForPerson(personId)
        override suspend fun insertLoan(entry: LoanEntryEntity) = db.loanEntryDao().insert(entry)
        override suspend fun updateLoan(entry: LoanEntryEntity) = db.loanEntryDao().update(entry)
        override suspend fun deleteLoan(id: Long, personId: Long) = db.loanEntryDao().deleteForPerson(id, personId)
        override suspend fun insertCash(entry: CashEntryEntity) = db.cashEntryDao().insert(entry)
        override suspend fun updateLinkedCash(entry: CashEntryEntity, loanEntryId: Long) =
            db.cashEntryDao().updateLinked(entry.id, loanEntryId, entry.date, entry.deposit, entry.cost, entry.description)
        override suspend fun deleteCashForLoan(loanEntryId: Long) = db.cashEntryDao().deleteLinkedToLoan(loanEntryId)
        override suspend fun deleteCashForPerson(personId: Long) = db.cashEntryDao().deleteLinkedToPerson(personId)
        override suspend fun updatePerson(person: PersonEntity) = db.personDao().update(person)
        override suspend fun deletePerson(id: Long) = db.personDao().deleteById(id)
    }

    override suspend fun <T> inTransaction(block: suspend LoanSyncAccess.() -> T): T =
        db.withTransaction { block(access) }
}
