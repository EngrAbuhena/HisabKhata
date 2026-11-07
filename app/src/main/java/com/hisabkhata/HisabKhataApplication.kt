package com.hisabkhata

import android.app.Application
import com.hisabkhata.data.drive.AutomaticDriveBackupScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HisabKhataApplication : Application() {
    @Inject lateinit var backupScheduler: AutomaticDriveBackupScheduler

    override fun onCreate() {
        super.onCreate()
        // Restores a missing future job after an upgrade or normal app restart.
        backupScheduler.scheduleNext()
    }
}
