package com.hisabkhata.data.drive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hisabkhata.MainActivity
import com.hisabkhata.R
import com.hisabkhata.data.settings.AppLocale
import com.hisabkhata.data.settings.AppPreferences
import com.hisabkhata.domain.backup.AutomaticBackupResult
import com.hisabkhata.domain.backup.DailyBackupSchedule
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val AUTO_WORK_TAG = "hisab-automatic-drive-backup"
private const val WORK_NAME_PREFIX = "hisab-automatic-drive-"
private const val SCHEDULED_DAY = "scheduled-local-day"
private const val NOTIFICATION_CHANNEL = "automatic_drive_backup"
private const val NOTIFICATION_ID = 1701

/** One scheduled job per local calendar date; each run re-anchors the next date. */
@Singleton
class AutomaticDriveBackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences
) {
    fun scheduleNext(): Operation? {
        if (preferences.activeAccount() == null) return null
        val now = Instant.now()
        val next = DailyBackupSchedule.next(now, ZoneId.systemDefault())
        val delay = Duration.between(now, next.toInstant()).toMillis().coerceAtLeast(0L)
        val work = OneTimeWorkRequestBuilder<AutomaticDriveBackupWorker>()
            .setInputData(workDataOf(SCHEDULED_DAY to next.toLocalDate().toString()))
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .addTag(AUTO_WORK_TAG)
            .build()
        return WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_PREFIX + next.toLocalDate().toString(), ExistingWorkPolicy.KEEP, work
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelAllWorkByTag(AUTO_WORK_TAG)
        dismissAlert()
    }

    fun dismissAlert() = clearAttentionNotification(context)

    fun rescheduleAfterClockChange(): Operation? {
        if (preferences.activeAccount() == null) return null
        // Both calls are ordered on WorkManager's internal serial executor.
        WorkManager.getInstance(context).cancelAllWorkByTag(AUTO_WORK_TAG)
        return scheduleNext()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutomaticDriveWorkerEntryPoint {
    fun preferences(): AppPreferences
    fun scheduler(): AutomaticDriveBackupScheduler
    fun coordinator(): AutomaticDriveBackupCoordinator
}

/** WorkManager persists this job across normal process death and device restarts. */
class AutomaticDriveBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, AutomaticDriveWorkerEntryPoint::class.java)
        if (entry.preferences().activeAccount() == null) return Result.success()
        // Schedule the next local day independently of this run's result/retries.
        entry.scheduler().scheduleNext()
        val scheduledDay = inputData.getString(SCHEDULED_DAY) ?: java.time.LocalDate.now().toString()
        return when (entry.coordinator().run(scheduledDay)) {
            AutomaticBackupResult.SUCCESS -> { clearAttentionNotification(applicationContext); Result.success() }
            AutomaticBackupResult.SKIPPED -> Result.success()
            AutomaticBackupResult.RETRY -> {
                // Avoid building an unbounded backlog of old retry jobs. The next
                // daily job remains scheduled even after this run gives up.
                if (runAttemptCount >= 4) {
                    showAttentionNotification(applicationContext, R.string.auto_backup_retry_notification)
                    Result.success()
                } else Result.retry()
            }
            AutomaticBackupResult.NEEDS_AUTHORIZATION -> {
                showAttentionNotification(applicationContext, R.string.auto_backup_reauthorize_notification)
                Result.success()
            }
            AutomaticBackupResult.FAILED -> {
                showAttentionNotification(applicationContext, R.string.auto_backup_failed_notification)
                Result.success()
            }
        }
    }
}

/** Re-anchors pending work after a device time or time-zone change. */
class BackupClockChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIME_CHANGED && intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        val pending = goAsync()
        try {
            val operation = EntryPointAccessors.fromApplication(context.applicationContext, AutomaticDriveWorkerEntryPoint::class.java)
                .scheduler().rescheduleAfterClockChange()
            if (operation == null) pending.finish()
            else operation.result.addListener({ pending.finish() }, Executor { it.run() })
        } catch (_: Exception) { pending.finish() }
    }
}

private fun clearAttentionNotification(context: Context) {
    NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
}

private fun showAttentionNotification(context: Context, message: Int) {
    val localized = AppLocale.context(context)
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return
    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED) return
    if (Build.VERSION.SDK_INT >= 26) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL, localized.getString(R.string.auto_backup_notification_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }
    val open = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
        .setSmallIcon(R.drawable.ic_link)
        .setContentTitle(localized.getString(R.string.auto_backup_attention_title))
        .setContentText(localized.getString(message))
        .setStyle(NotificationCompat.BigTextStyle().bigText(localized.getString(message)))
        .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .build()
    // Permission can be revoked between the check and notify; status remains visible in the app.
    try { manager.notify(NOTIFICATION_ID, notification) } catch (_: SecurityException) { }
}
