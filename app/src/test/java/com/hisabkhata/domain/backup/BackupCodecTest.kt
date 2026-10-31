package com.hisabkhata.domain.backup

import com.hisabkhata.data.local.entity.CashEntryEntity
import com.hisabkhata.data.local.entity.LoanEntryEntity
import com.hisabkhata.data.local.entity.PersonEntity
import com.hisabkhata.data.local.entity.PersonType
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private val backup = DatabaseBackup(
        createdAt = 1_700_000_000_000,
        people = listOf(PersonEntity(7, "Example Person A", "sample-phone", "By account", PersonType.LENT)),
        loans = listOf(LoanEntryEntity(11, 7, 20_000, Long.MAX_VALUE, 500, "Example transfer", 100, 31)),
        cash = listOf(CashEntryEntity(31, 20_000, 500, Long.MAX_VALUE, "Example Person A - Example transfer", 100, 11),
            CashEntryEntity(33, 20_001, 1, 0, "Unlinked", 101, null))
    )
    @Test fun roundTripKeepsExactLongValuesAndBothLinks() {
        val encoded = BackupCodec.encode(backup)
        assertTrue(encoded.contains("9223372036854775807"))
        assertEquals(backup, BackupCodec.decode(encoded))
    }
    @Test fun rejectsWrongVersionMissingFieldBadIntegerAndBrokenLinks() {
        val valid = BackupCodec.encode(backup)
        val invalid = listOf(
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"personId\":7", "\"personId\":999"),
            valid.replace("\"linkedLoanEntryId\":11", "\"linkedLoanEntryId\":null"),
            valid.replace("9223372036854775807", "9223372036854775808"),
            valid.replace("\"version\":1", "\"version\":1.0"),
            valid.replace("\"loanEntries\"", "\"unknown\"")
        )
        invalid.forEach { json -> try { BackupCodec.decode(json); fail("Expected invalid backup") } catch (_: InvalidBackupException) {} }
    }
    @Test fun rejectsInvalidUtf8LenientSyntaxAndExtremeNumber() {
        try { BackupCodec.decode(byteArrayOf(0xC3.toByte(), 0x28)); fail("bad UTF-8") } catch (_: InvalidBackupException) { }
        try { BackupCodec.decode("{'format':'hisab-khata-backup'}"); fail("single quotes") } catch (_: InvalidBackupException) { }
        val input = BackupCodec.encode(backup).replace("\"version\":1", "\"version\":1e999999999")
        try { BackupCodec.decode(input); fail("invalid version") } catch (_: InvalidBackupException) { }
    }
    @Test fun rejectsDuplicateIdsBeforeAnyRestore() {
        val repeated = backup.copy(people = backup.people + backup.people)
        try { BackupCodec.encode(repeated); fail() } catch (_: InvalidBackupException) { }
    }
}
