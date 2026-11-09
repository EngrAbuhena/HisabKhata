package com.hisabkhata

import android.app.Application
import android.content.res.Configuration
import com.hisabkhata.data.drive.AutomaticDriveBackupScheduler
import com.hisabkhata.data.settings.AppLocale
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HisabKhataApplication : Application() {
    @Inject lateinit var backupScheduler: AutomaticDriveBackupScheduler

    override fun onCreate() {
        super.onCreate()
        AppLocale.useLatinNumberFormatting()
        // Restores a missing future job after an upgrade or normal app restart.
        backupScheduler.scheduleNext()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The system may reset the process format category on a language change.
        AppLocale.useLatinNumberFormatting()
    }
}
