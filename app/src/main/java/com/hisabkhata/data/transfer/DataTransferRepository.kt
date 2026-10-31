package com.hisabkhata.data.transfer

import android.content.Context
import androidx.room.withTransaction
import com.hisabkhata.R
import com.hisabkhata.data.local.HisabDatabase
import com.hisabkhata.data.local.entity.CashEntryEntity
import com.hisabkhata.data.local.entity.PersonEntity
import com.hisabkhata.data.repository.RoomLoanSyncStore
import com.hisabkhata.domain.LoanCashSynchronizer
import com.hisabkhata.domain.backup.BackupCodec
import com.hisabkhata.domain.backup.DatabaseBackup
import com.hisabkhata.domain.interchange.DuplicatePlanner
import com.hisabkhata.domain.interchange.ImportKind
import com.hisabkhata.domain.interchange.ImportPreview
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class RestoreMode { REPLACE, MERGE }
data class ImportResult(val imported: Int, val duplicatesSkipped: Int, val errorRows: Int, val peopleCreated: Int)

/** All snapshots, imports and restores use Room transactions. */
@Singleton
class DataTransferRepository @Inject constructor(
    private val db: HisabDatabase,
    @ApplicationContext private val context: Context
) {
    private val sync = LoanCashSynchronizer(RoomLoanSyncStore(db),
        { name, desc -> context.getString(R.string.loan_cash_description, name, desc) })

    suspend fun snapshot(): DatabaseBackup = db.withTransaction {
        DatabaseBackup(System.currentTimeMillis(), db.personDao().findAll(), db.loanEntryDao().findAll(), db.cashEntryDao().findAll())
    }

    suspend fun markDuplicates(preview: ImportPreview): ImportPreview = db.withTransaction {
        DuplicatePlanner.mark(preview, db.personDao().findAll(), db.loanEntryDao().findAll(), db.cashEntryDao().findAll())
    }

    /** Rechecks duplicates in the transaction, so another change cannot race the preview. */
    suspend fun importValid(preview: ImportPreview, skipDuplicates: Boolean, createCash: Boolean): ImportResult = db.withTransaction {
        val people = db.personDao().findAll().toMutableList()
        val checked = DuplicatePlanner.mark(preview, people, db.loanEntryDao().findAll(), db.cashEntryDao().findAll())
        val valid = checked.lines.filter { it.record != null }
        var imported = 0; var createdPeople = 0
        val toImport = valid.filterNot { skipDuplicates && it.duplicate }
        toImport.forEach { line ->
            val r = line.record!!
            if (checked.kind == ImportKind.CASH) {
                db.cashEntryDao().insert(CashEntryEntity(date = r.date, deposit = r.first, cost = r.second,
                    description = r.description, createdAt = System.currentTimeMillis()))
            } else {
                val type = requireNotNull(r.personType)
                val normalized = r.personName.trim().lowercase(Locale.ROOT)
                val existing = people.firstOrNull { it.type == type && it.name.trim().lowercase(Locale.ROOT) == normalized }
                val person = existing ?: PersonEntity(name = r.personName.trim(), type = type).let {
                    val id = db.personDao().insert(it); createdPeople++; it.copy(id = id).also(people::add)
                }
                // Room's nested withTransaction joins this outer transaction.
                sync.saveEntry(person.id, null, r.date, r.first, r.second, r.description, createCash)
            }
            imported++
        }
        ImportResult(imported, if (skipDuplicates) valid.count { it.duplicate } else 0, checked.lines.count { it.record == null }, createdPeople)
    }

    suspend fun restore(backup: DatabaseBackup, mode: RestoreMode) {
        BackupCodec.validate(backup) // Never alter the database for a malformed backup.
        db.withTransaction {
            when (mode) {
                RestoreMode.REPLACE -> {
                    db.cashEntryDao().deleteAll(); db.loanEntryDao().deleteAll(); db.personDao().deleteAll()
                    backup.people.forEach { check(db.personDao().insert(it) == it.id) }
                    backup.loans.forEach { check(db.loanEntryDao().insert(it) == it.id) }
                    backup.cash.forEach { check(db.cashEntryDao().insert(it) == it.id) }
                }
                RestoreMode.MERGE -> {
                    // Append the complete backup and remap every ID rather than overwriting names/IDs.
                    val peopleIds = mutableMapOf<Long, Long>(); val loanIds = mutableMapOf<Long, Long>(); val cashIds = mutableMapOf<Long, Long>()
                    backup.people.forEach { p -> peopleIds[p.id] = db.personDao().insert(p.copy(id = 0)) }
                    backup.loans.forEach { loan ->
                        loanIds[loan.id] = db.loanEntryDao().insert(loan.copy(id = 0, personId = peopleIds.getValue(loan.personId), linkedCashEntryId = null))
                    }
                    backup.cash.forEach { cash ->
                        cashIds[cash.id] = db.cashEntryDao().insert(cash.copy(id = 0, linkedLoanEntryId = cash.linkedLoanEntryId?.let { loanIds.getValue(it) }))
                    }
                    backup.loans.forEach { loan ->
                        loan.linkedCashEntryId?.let { check(db.loanEntryDao().setCashLink(loanIds.getValue(loan.id), cashIds.getValue(it)) == 1) }
                    }
                }
            }
        }
    }
}
