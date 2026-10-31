package com.hisabkhata.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisabkhata.data.local.HisabDatabase
import com.hisabkhata.data.local.entity.CashEntryEntity
import com.hisabkhata.data.local.entity.LoanEntryEntity
import com.hisabkhata.data.local.entity.PersonEntity
import com.hisabkhata.data.local.entity.PersonType
import com.hisabkhata.data.transfer.DataTransferRepository
import com.hisabkhata.data.transfer.ExportData
import com.hisabkhata.data.transfer.PdfLedgerWriter
import com.hisabkhata.data.transfer.RestoreMode
import com.hisabkhata.domain.backup.BackupCodec
import com.hisabkhata.domain.backup.DatabaseBackup
import com.hisabkhata.domain.interchange.CsvTableCodec
import com.hisabkhata.domain.interchange.ImportKind
import com.hisabkhata.domain.interchange.ImportParser
import com.hisabkhata.domain.interchange.XlsxTableCodec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferIntegrationTest {
    private lateinit var db: HisabDatabase
    private lateinit var repo: DataTransferRepository
    private lateinit var context: Context
    private val example = DatabaseBackup(
        123, listOf(PersonEntity(7, "Example Person A", "", "", PersonType.LENT)),
        listOf(LoanEntryEntity(11, 7, 20_000, 5_000_000, 3_000_000, "Example transfer", 100, 31)),
        listOf(CashEntryEntity(31, 20_000, 3_000_000, 5_000_000, "Example Person A - Example transfer", 100, 11))
    )

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
        repo = DataTransferRepository(db, context)
    }
    @After fun tearDown() { db.close() }

    @Test fun assetsContainRealCsvAndExcelSamples() {
        val cashCsv = context.assets.open("samples/cash_sample.csv").use { it.readBytes() }
        val cashXlsx = context.assets.open("samples/cash_sample.xlsx").use { it.readBytes() }
        val loanCsv = context.assets.open("samples/loan_sample.csv").use { it.readBytes() }
        val loanXlsx = context.assets.open("samples/loan_sample.xlsx").use { it.readBytes() }
        val csv = ImportParser.parse(ImportKind.CASH, CsvTableCodec.read(cashCsv))
        val xlsx = ImportParser.parse(ImportKind.CASH, XlsxTableCodec.read(cashXlsx, context.cacheDir))
        assertEquals(3, csv.lines.size); assertEquals(csv.lines.map { it.record }, xlsx.lines.map { it.record })
        val loan1 = ImportParser.parse(ImportKind.LOAN, CsvTableCodec.read(loanCsv)).lines.single().record!!
        val loan2 = ImportParser.parse(ImportKind.LOAN, XlsxTableCodec.read(loanXlsx, context.cacheDir)).lines.single().record!!
        assertEquals(loan1, loan2); assertEquals("Example transfer", loan2.description)
    }

    @Test fun replaceAndMergeKeepBothLinksAndAllocateNewIds() = runBlocking {
        repo.restore(BackupCodec.decode(BackupCodec.encode(example)), RestoreMode.REPLACE)
        val initial = repo.snapshot()
        assertEquals(example.people, initial.people); assertEquals(example.loans, initial.loans); assertEquals(example.cash, initial.cash)
        repo.restore(example, RestoreMode.MERGE)
        val merged = repo.snapshot()
        assertEquals(2, merged.people.size); assertEquals(2, merged.loans.size); assertEquals(2, merged.cash.size)
        assertEquals(2, merged.loans.map { it.id }.toSet().size)
        merged.loans.forEach { loan ->
            val cash = merged.cash.single { it.id == loan.linkedCashEntryId }
            assertEquals(loan.id, cash.linkedLoanEntryId)
            assertTrue(merged.people.any { it.id == loan.personId })
        }
    }

    @Test fun replaceFailureAfterDeletesRollsBackEntireDatabase() = runBlocking {
        db.cashEntryDao().insert(CashEntryEntity(id = 2, date = 20_001, deposit = 100, createdAt = 1))
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_cash_restore BEFORE INSERT ON cash_entries
            WHEN NEW.id = 31 BEGIN SELECT RAISE(ABORT, 'simulated restore failure'); END
        """.trimIndent())
        try { repo.restore(example, RestoreMode.REPLACE); fail("Expected SQLite failure") }
        catch (_: SQLiteConstraintException) {
            val snapshot = repo.snapshot()
            assertEquals(listOf(2L), snapshot.cash.map { it.id })
            assertTrue(snapshot.people.isEmpty()); assertTrue(snapshot.loans.isEmpty())
        }
    }

    @Test fun actualExcelWriterAndPdfExportWorkOnAndroid24() = runBlocking {
        repo.restore(example, RestoreMode.REPLACE)
        val data = ExportData(context, repo.snapshot())
        val cashTable = data.cash(null, null)
        val excel = data.excel(listOf("Cash Book" to cashTable))
        val imported = ImportParser.parse(ImportKind.CASH, XlsxTableCodec.read(excel, context.cacheDir))
        val row = imported.lines.single().record!!
        assertEquals(3_000_000L, row.first); assertEquals(5_000_000L, row.second)
        assertEquals("Example Person A - Example transfer", row.description)
        val pdf = PdfLedgerWriter.write(cashTable)
        assertTrue(pdf.size > 1000); assertEquals("%PDF", String(pdf.copyOfRange(0, 4), Charsets.US_ASCII))
        val person = data.singlePerson(7)
        val csv = data.csv(person, includeTitle = true).toString(Charsets.UTF_8)
        assertTrue(csv.contains("NAME - Example Person A")); assertTrue(csv.contains("Example transfer"))
        val combined = data.combinedLoansCsv()
        val loan = ImportParser.parse(ImportKind.LOAN, CsvTableCodec.read(combined)).lines.single().record!!
        assertEquals("Example Person A", loan.personName); assertEquals(PersonType.LENT, loan.personType)
    }

    @Test fun importSkipsDuplicatesAndOptionalCashDefaultsToNoLink() = runBlocking {
        val bytes = context.assets.open("samples/loan_sample.csv").use { it.readBytes() }
        val p = ImportParser.parse(ImportKind.LOAN, CsvTableCodec.read(bytes))
        assertEquals(1, repo.importValid(p, skipDuplicates = true, createCash = false).imported)
        assertEquals(0, repo.snapshot().cash.size)
        val repeated = repo.importValid(p, skipDuplicates = true, createCash = true)
        assertEquals(0, repeated.imported); assertEquals(1, repeated.duplicatesSkipped)
        val forced = repo.importValid(p, skipDuplicates = false, createCash = true)
        assertEquals(1, forced.imported)
        val snapshot = repo.snapshot()
        assertEquals(1, snapshot.people.size); assertEquals(2, snapshot.loans.size); assertEquals(1, snapshot.cash.size)
        val loan = snapshot.loans.single { it.linkedCashEntryId != null }
        assertEquals(loan.id, snapshot.cash.single().linkedLoanEntryId)
    }
}
