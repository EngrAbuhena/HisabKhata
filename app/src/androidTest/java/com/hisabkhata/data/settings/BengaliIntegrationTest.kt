package com.hisabkhata.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisabkhata.R
import com.hisabkhata.data.local.entity.CashEntryEntity
import com.hisabkhata.data.local.entity.LoanEntryEntity
import com.hisabkhata.data.local.entity.PersonEntity
import com.hisabkhata.data.local.entity.PersonType
import com.hisabkhata.data.transfer.ExportData
import com.hisabkhata.data.transfer.PdfLedgerWriter
import com.hisabkhata.domain.backup.BackupCodec
import com.hisabkhata.domain.backup.DatabaseBackup
import com.hisabkhata.domain.interchange.CsvTableCodec
import com.hisabkhata.domain.interchange.ImportKind
import com.hisabkhata.domain.interchange.ImportParser
import com.hisabkhata.domain.interchange.XlsxTableCodec
import com.hisabkhata.util.latinFormat
import com.hisabkhata.util.latinQuantityFormat
import java.io.File
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BengaliIntegrationTest {
    private lateinit var context: Context
    private var oldLanguage: String? = null

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences(AppLocale.PREFERENCES, Context.MODE_PRIVATE)
        oldLanguage = prefs.getString(AppLocale.LANGUAGE_KEY, null)
        prefs.edit().putString(AppLocale.LANGUAGE_KEY, AppLanguage.BENGALI.name).commit()
    }

    @After fun tearDown() {
        val editor = context.getSharedPreferences(AppLocale.PREFERENCES, Context.MODE_PRIVATE).edit()
        if (oldLanguage == null) editor.remove(AppLocale.LANGUAGE_KEY) else editor.putString(AppLocale.LANGUAGE_KEY, oldLanguage)
        editor.commit()
    }

    @Test fun selectedLanguageFormatsAppAndBackgroundResourcesWithLatinDigits() {
        val localized = AppLocale.context(context)
        assertEquals(AppLanguage.BENGALI, AppLocale.selected(context))
        assertEquals("bn", localized.resources.configuration.locales[0].language)
        assertEquals("নগদ খাতা", localized.getString(R.string.nav_cash_book))
        assertEquals("Google Drive ব্যাকআপ", localized.getString(R.string.drive_title))
        val count = latinFormat(localized, R.string.import_counts, 1234, 5, 6)
        assertTrue(count, count.contains("1234"))
        assertFalse(count, count.any { it in '০'..'৯' })
        val plural = latinQuantityFormat(localized, R.plurals.entries_count, 24, 24)
        assertTrue(plural, plural.contains("24"))
        assertFalse(plural, plural.any { it in '০'..'৯' })
        assertTrue(localized.getString(R.string.auto_backup_attention_title).contains("স্বয়ংক্রিয়"))
        // Do not replace user-entered Bengali numerals in names or descriptions.
        assertTrue(latinFormat(localized, R.string.delete_person_message, "রাহিম ০১").contains("রাহিম ০১"))
    }

    @Test fun bengaliCsvExcelPdfAndOldJsonRoundTrip() {
        val day = LocalDate.of(2026, 10, 30).toEpochDay()
        val person = PersonEntity(id = 7, name = "রাহিম", phone = "", note = "", type = PersonType.BORROWED)
        val loan = LoanEntryEntity(id = 11, personId = 7, date = day, loan = 123_450, paid = 5_000,
            description = "চালের বাকি", createdAt = 1L, linkedCashEntryId = null)
        val cash = CashEntryEntity(id = 21, date = day, deposit = 123_450, cost = 5_000,
            description = "চালের হিসাব", createdAt = 2L, linkedLoanEntryId = null)
        val original = DatabaseBackup(3L, listOf(person), listOf(loan), listOf(cash))
        assertEquals(original, BackupCodec.decode(BackupCodec.encode(original)))
        val exporter = ExportData(AppLocale.context(context), original)
        val table = exporter.cash(null, null)
        assertEquals(listOf("তারিখ", "জমা", "খরচ", "মোট", "বিবরণ"), table.headers)
        val csv = ImportParser.parse(ImportKind.CASH, CsvTableCodec.read(exporter.csv(table)))
        assertEquals(123_450L, csv.lines.single().record!!.first)
        assertEquals("চালের হিসাব", csv.lines.single().record!!.description)
        val excel = exporter.excel(listOf("নগদ খাতা" to table))
        val excelRows = ImportParser.parse(ImportKind.CASH, XlsxTableCodec.read(excel, context.cacheDir))
        assertEquals(123_450L, excelRows.lines.single().record!!.first)
        assertEquals("চালের হিসাব", excelRows.lines.single().record!!.description)
        val loans = ImportParser.parse(ImportKind.LOAN, CsvTableCodec.read(exporter.combinedLoansCsv()))
        assertEquals(PersonType.BORROWED, loans.lines.single().record!!.personType)
        assertEquals("রাহিম", loans.lines.single().record!!.personName)
        val pdf = PdfLedgerWriter.write(table)
        assertEquals("%PDF", String(pdf.copyOfRange(0, 4), Charsets.US_ASCII))
        assertTrue(pdf.size > 1000)
        // Retained in test-device cache for a visual check of conjuncts and wrapping.
        val complex = "বাংলা যুক্তাক্ষর: ক্ষুদ্র ব্যবসায়ের হিসাব, ঋণ পরিশোধ ও সম্পূর্ণ বিবরণ। " +
            "স্বাধীনতা, প্রত্যয়, সঞ্চয়, কৃতজ্ঞতা এবং বাংলাদেশ।"
        val visualTable = table.copy(rows = table.rows.map { row ->
            row.dropLast(1) + com.hisabkhata.data.transfer.ExportCell(complex.repeat(4))
        })
        File(context.cacheDir, "bengali-visual-check.pdf").writeBytes(PdfLedgerWriter.write(visualTable))
    }

    @Test fun backgroundBackupNotificationUsesSelectedLanguage() {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        org.junit.Assume.assumeTrue(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            org.junit.Assume.assumeTrue(context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
        val localized = AppLocale.context(context)
        val title = localized.getString(R.string.auto_backup_attention_title)
        val message = localized.getString(R.string.auto_backup_reauthorize_notification)
        // Exercise the real worker notification path without requiring Google or a live account.
        val method = Class.forName("com.hisabkhata.data.drive.AutomaticDriveBackupWorkerKt")
            .getDeclaredMethod("showAttentionNotification", Context::class.java, Int::class.javaPrimitiveType)
        method.isAccessible = true
        try {
            method.invoke(null, context, R.string.auto_backup_reauthorize_notification)
            // NotificationManager enqueues asynchronously; wait for the system to post it.
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            var posted: android.service.notification.StatusBarNotification? = null
            while (posted == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                posted = manager.activeNotifications.firstOrNull {
                    it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() == title
                }
                if (posted == null) android.os.SystemClock.sleep(100)
            }
            assertNotNull("Bengali backup notification was not posted", posted)
            assertEquals(message, posted!!.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
            assertTrue(title.contains("স্বয়ংক্রিয়"))
        } finally {
            manager.activeNotifications.filter {
                it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() == title
            }.forEach { manager.cancel(it.id) }
        }
    }

    @Test fun appProcessNumberFormatUsesLatinWithoutChangingPhoneLanguage() {
        val phoneLanguage = android.content.res.Resources.getSystem().configuration.locales[0]
        val displayLocale = java.util.Locale.getDefault(java.util.Locale.Category.DISPLAY)
        val previous = java.util.Locale.getDefault(java.util.Locale.Category.FORMAT)
        try {
            java.util.Locale.setDefault(java.util.Locale.Category.FORMAT, java.util.Locale.forLanguageTag("bn-BD"))
            AppLocale.useLatinNumberFormatting()
            assertEquals("1234", java.text.NumberFormat.getIntegerInstance().apply { isGroupingUsed = false }.format(1234))
            assertEquals(displayLocale, java.util.Locale.getDefault(java.util.Locale.Category.DISPLAY))
            assertEquals(phoneLanguage, android.content.res.Resources.getSystem().configuration.locales[0])
        } finally {
            java.util.Locale.setDefault(java.util.Locale.Category.FORMAT, previous)
        }
    }

    @Test fun englishAndFollowPhoneUseTheirOwnResources() {
        val prefs = context.getSharedPreferences(AppLocale.PREFERENCES, Context.MODE_PRIVATE)
        prefs.edit().putString(AppLocale.LANGUAGE_KEY, AppLanguage.ENGLISH.name).commit()
        assertEquals("Cash Book", AppLocale.context(context).getString(R.string.nav_cash_book))
        prefs.edit().putString(AppLocale.LANGUAGE_KEY, AppLanguage.SYSTEM.name).commit()
        val phoneLanguage = android.content.res.Resources.getSystem().configuration.locales[0].language
        val expected = if (phoneLanguage == "bn") "নগদ খাতা" else "Cash Book"
        assertEquals(expected, AppLocale.context(context).getString(R.string.nav_cash_book))
    }
}
