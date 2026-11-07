package com.hisabkhata.data.drive

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.hisabkhata.data.settings.AppPreferences
import com.hisabkhata.domain.backup.AutomaticBackupStatus
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomaticDriveBackupIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val isolated = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("auto_backup_instrumentation_test", mode)
    }
    private lateinit var preferences: AppPreferences
    private lateinit var scheduler: AutomaticDriveBackupScheduler
    private val manager get() = WorkManager.getInstance(context)
    private val tag = "hisab-automatic-drive-backup"

    @Before fun setup() {
        isolated.getSharedPreferences("ignored", Context.MODE_PRIVATE).edit().clear().commit()
        preferences = AppPreferences(isolated)
        scheduler = AutomaticDriveBackupScheduler(context, preferences)
        manager.cancelAllWorkByTag(tag).result.get(30, TimeUnit.SECONDS)
    }
    @After fun cleanup() {
        preferences.setAutomaticDriveEnabled(false)
        manager.cancelAllWorkByTag(tag).result.get(30, TimeUnit.SECONDS)
        isolated.getSharedPreferences("ignored", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun activeWork() = manager.getWorkInfosByTag(tag).get(30, TimeUnit.SECONDS)
        .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }

    @Test fun optInPersistsAndSchedulingIsUniqueAndCancellable() {
        assertNull(scheduler.scheduleNext()) // off by default; no background job
        preferences.setDriveAccount("one@example.invalid")
        preferences.setAutomaticDriveEnabled(true)
        assertEquals("one@example.invalid", AppPreferences(isolated).activeAccount())
        scheduler.scheduleNext()!!.result.get(30, TimeUnit.SECONDS)
        scheduler.scheduleNext()!!.result.get(30, TimeUnit.SECONDS)
        val job = activeWork().single()
        assertEquals(NetworkType.CONNECTED, job.constraints.requiredNetworkType)
        assertTrue(job.initialDelayMillis in 0..(25L * 60 * 60 * 1000))
        // A system clock/time-zone change replaces, rather than duplicates, the job.
        scheduler.rescheduleAfterClockChange()!!.result.get(30, TimeUnit.SECONDS)
        assertEquals(1, activeWork().size)
        preferences.setAutomaticDriveEnabled(false)
        manager.cancelAllWorkByTag(tag).result.get(30, TimeUnit.SECONDS)
        assertTrue(activeWork().isEmpty())
        assertNull(AppPreferences(isolated).activeAccount())
    }

    @Test fun disabledWorkerRunsAsNoOpUsingRealWorkManager() {
        val appPreferences = EntryPointAccessors.fromApplication(context, AutomaticDriveWorkerEntryPoint::class.java).preferences()
        assertNull("Test requires the real app's opt-in to be off", appPreferences.activeAccount())
        val request = OneTimeWorkRequestBuilder<AutomaticDriveBackupWorker>().build()
        manager.enqueue(request).result.get(30, TimeUnit.SECONDS)
        val deadline = SystemClock.elapsedRealtime() + 120_000
        var state: WorkInfo.State? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            state = manager.getWorkInfoById(request.id).get(30, TimeUnit.SECONDS)?.state
            if (state == WorkInfo.State.SUCCEEDED || state == WorkInfo.State.FAILED || state == WorkInfo.State.CANCELLED) break
            SystemClock.sleep(250)
        }
        assertEquals("The persisted worker should instantiate and skip safely", WorkInfo.State.SUCCEEDED, state)
    }

    @Test fun accountChangeAndSignOutClearOptInAndAutomaticStatus() {
        preferences.setDriveAccount("one@example.invalid")
        preferences.setAutomaticDriveEnabled(true)
        preferences.recordSuccess("one@example.invalid", 123L, "2026-11-07")
        assertEquals(AutomaticBackupStatus.SUCCESS, preferences.automaticDriveStatus.value)
        assertEquals(123L, preferences.lastAutomaticDriveBackup.value)
        preferences.setDriveAccount("two@example.invalid")
        assertFalse(preferences.automaticDriveEnabled.value)
        assertNull(preferences.activeAccount())
        assertNull(preferences.lastSuccessfulDay())
        assertEquals(0L, preferences.lastAutomaticDriveBackup.value)
        assertEquals(AutomaticBackupStatus.NONE, AppPreferences(isolated).automaticDriveStatus.value)
        preferences.setAutomaticDriveEnabled(true)
        preferences.setDriveAccount(null)
        assertNull(preferences.activeAccount())
        assertFalse(AppPreferences(isolated).automaticDriveEnabled.value)
    }
}
