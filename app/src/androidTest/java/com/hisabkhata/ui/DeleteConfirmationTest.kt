package com.hisabkhata.ui

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisabkhata.data.drive.DriveBackupFile
import com.hisabkhata.data.drive.DriveBackupService
import com.hisabkhata.data.local.HisabDatabase
import com.hisabkhata.data.local.entity.PersonType
import com.hisabkhata.data.repository.CashRepository
import com.hisabkhata.data.repository.LoanRepository
import com.hisabkhata.data.settings.AppPreferences
import com.hisabkhata.data.transfer.DataTransferRepository
import com.hisabkhata.domain.backup.BackupCodec
import com.hisabkhata.ui.backup.BackupDeleteTarget
import com.hisabkhata.ui.backup.BackupNotice
import com.hisabkhata.ui.backup.BackupViewModel
import com.hisabkhata.ui.backup.DriveAction
import com.hisabkhata.ui.cash.CashEditorEvent
import com.hisabkhata.ui.cash.CashEntryEditorViewModel
import com.hisabkhata.ui.loan.LoanEntryEditorEvent
import com.hisabkhata.ui.loan.LoanEntryEditorViewModel
import com.hisabkhata.ui.loan.PersonLedgerEvent
import com.hisabkhata.ui.loan.PersonLedgerViewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Calls the same guarded ViewModels as the UI, backed by a real in-memory Room database. */
@RunWith(AndroidJUnit4::class)
class DeleteConfirmationTest {
    private lateinit var context: Context
    private lateinit var db: HisabDatabase
    private lateinit var loans: LoanRepository
    private lateinit var cash: CashRepository

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
        loans = LoanRepository(db, context)
        cash = CashRepository(db.cashEntryDao())
    }
    @After fun tearDown() { db.close() }

    @Test fun cashCannotBeDeletedWithoutRequestAndCancelPreservesIt() = runBlocking {
        val id = cash.addEntry(20_000, 25_000, 0, "Saved cash")
        val vm = CashEntryEditorViewModel(SavedStateHandle(mapOf("entryId" to id)), cash)
        withTimeout(30_000) { vm.state.first { !it.isLoading && it.originalEntry != null } }
        vm.confirmDelete()
        assertFalse(vm.state.value.isSaving)
        vm.requestDelete()
        assertTrue(vm.state.value.pendingDelete)
        vm.cancelDelete()
        vm.confirmDelete()
        assertNotNull(db.cashEntryDao().findById(id))
        vm.requestDelete()
        vm.confirmDelete()
        vm.confirmDelete() // second tap cannot queue another deletion
        assertEquals(CashEditorEvent.Deleted, withTimeout(30_000) { vm.events.first() })
        assertNull(db.cashEntryDao().findById(id))
    }

    @Test fun loanCancelPreservesBothRecordsAndConfirmedDeleteRemovesBoth() = runBlocking {
        val person = loans.addPerson("Example Person A", "", "", PersonType.LENT)
        val entry = loans.saveEntry(person, null, 20_000, 5_000, 0, "Saved loan", true)
        val cashId = db.loanEntryDao().findById(entry)!!.linkedCashEntryId!!
        val vm = LoanEntryEditorViewModel(SavedStateHandle(mapOf("personId" to person, "entryId" to entry)), loans)
        withTimeout(30_000) { vm.state.first { !it.isLoading && it.originalEntry != null } }
        vm.setRecordInCash(false) // an unsaved change must not suppress the persisted-link warning
        assertNotNull(vm.state.value.originalEntry!!.linkedCashEntryId)
        vm.confirmDelete()
        vm.requestDelete()
        vm.cancelDelete()
        vm.confirmDelete()
        assertNotNull(db.loanEntryDao().findById(entry))
        assertNotNull(db.cashEntryDao().findById(cashId))
        vm.requestDelete()
        vm.confirmDelete()
        vm.confirmDelete()
        assertEquals(LoanEntryEditorEvent.Deleted, withTimeout(30_000) { vm.events.first() })
        assertNull(db.loanEntryDao().findById(entry))
        assertNull(db.cashEntryDao().findById(cashId))
    }

    @Test fun personCancelThenConfirmDeletesOnlyThatPersonsLoansAndLinkedCash() = runBlocking {
        val person = loans.addPerson("Example Person A", "", "", PersonType.LENT)
        val other = loans.addPerson("Other", "", "", PersonType.LENT)
        val first = loans.saveEntry(person, null, 10, 1_000, 0, "First", true)
        val second = loans.saveEntry(person, null, 11, 2_000, 0, "Second", true)
        val otherLoan = loans.saveEntry(other, null, 12, 3_000, 0, "Other", true)
        val firstCash = db.loanEntryDao().findById(first)!!.linkedCashEntryId!!
        val secondCash = db.loanEntryDao().findById(second)!!.linkedCashEntryId!!
        val otherCash = db.loanEntryDao().findById(otherLoan)!!.linkedCashEntryId!!
        val vm = PersonLedgerViewModel(SavedStateHandle(mapOf("personId" to person)), loans)
        val collector = launch { vm.uiState.collect() }
        try {
            withTimeout(30_000) { vm.uiState.first { it.person != null && it.rows.size == 2 } }
            vm.confirmDelete()
            vm.requestDelete()
            withTimeout(30_000) { vm.uiState.first { it.pendingDelete } }
            vm.cancelDelete()
            vm.confirmDelete()
            assertNotNull(db.personDao().findById(person))
            assertNotNull(db.cashEntryDao().findById(firstCash))
            vm.requestDelete()
            vm.confirmDelete()
            vm.confirmDelete()
            assertEquals(PersonLedgerEvent.Deleted, withTimeout(30_000) { vm.events.first() })
            assertNull(db.personDao().findById(person))
            assertNull(db.loanEntryDao().findById(first))
            assertNull(db.loanEntryDao().findById(second))
            assertNull(db.cashEntryDao().findById(firstCash))
            assertNull(db.cashEntryDao().findById(secondCash))
            assertNotNull(db.personDao().findById(other))
            assertNotNull(db.loanEntryDao().findById(otherLoan))
            assertNotNull(db.cashEntryDao().findById(otherCash))
        } finally { collector.cancel() }
    }

    @Test fun replaceRequiresConfirmationAndLocalBackupCancelDoesNotDeleteFile() = runBlocking {
        val transfer = DataTransferRepository(db, context)
        loans.addPerson("In backup", "", "", PersonType.LENT)
        val file = File(context.cacheDir, "guarded-restore-test.json")
        file.writeText(BackupCodec.encode(transfer.snapshot()))
        try {
            loans.addPerson("Added later", "", "", PersonType.LENT)
            val isolatedPrefsContext = object : ContextWrapper(context) {
                override fun getSharedPreferences(name: String, mode: Int) =
                    super.getSharedPreferences("delete_confirmation_test", mode)
            }
            val vm = BackupViewModel(transfer, DriveBackupService(context), AppPreferences(isolatedPrefsContext), context)
            vm.loadLocal(Uri.fromFile(file))
            withTimeout(30_000) { vm.state.first { it.candidate != null } }
            vm.confirmDelete()
            vm.requestReplace()
            assertTrue(vm.state.value.deleteTarget is BackupDeleteTarget.ReplaceDatabase)
            vm.cancelDelete()
            vm.confirmDelete()
            assertEquals(2, db.personDao().findAll().size)
            vm.requestReplace()
            vm.confirmDelete()
            vm.confirmDelete()
            withTimeout(30_000) { vm.state.first { it.notice == BackupNotice.RESTORED } }
            assertEquals(listOf("In backup"), db.personDao().findAll().map { it.name })

            vm.requestLocalDelete(Uri.fromFile(file))
            withTimeout(30_000) { vm.state.first { it.deleteTarget is BackupDeleteTarget.Local } }
            vm.cancelDelete()
            vm.confirmDelete()
            assertTrue(file.exists())

            vm.connected("test@example.invalid")
            vm.runDrive(DriveAction.Delete("unconfirmed"), "not-an-access-token")
            assertFalse(vm.state.value.busy) // no network request for a direct delete
            vm.requestDriveDelete(DriveBackupFile("not-listed", "backup.json", 0, 1))
            assertNull(vm.state.value.deleteTarget)
        } finally { file.delete() }
    }
}
