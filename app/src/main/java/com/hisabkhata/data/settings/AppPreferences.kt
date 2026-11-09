package com.hisabkhata.data.settings

import android.content.Context
import com.hisabkhata.domain.backup.AutomaticBackupState
import com.hisabkhata.domain.backup.AutomaticBackupStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Singleton
class AppPreferences @Inject constructor(@ApplicationContext context: Context) : AutomaticBackupState {
    private val prefs = context.getSharedPreferences(AppLocale.PREFERENCES, Context.MODE_PRIVATE)
    private val mutableLanguage = MutableStateFlow(AppLocale.selected(context))
    val language = mutableLanguage.asStateFlow()
    private val mutableTheme = MutableStateFlow(runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM") ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM))
    val theme = mutableTheme.asStateFlow()
    private val mutableLocalBackup = MutableStateFlow(prefs.getLong("last_local_backup", 0L))
    val lastLocalBackup = mutableLocalBackup.asStateFlow()
    private val mutableDriveAccount = MutableStateFlow(prefs.getString("drive_account", null))
    val driveAccount = mutableDriveAccount.asStateFlow()
    private val mutableDriveBackup = MutableStateFlow(mutableDriveAccount.value?.let { prefs.getLong(driveKey(it), 0L) } ?: 0L)
    val lastDriveBackup = mutableDriveBackup.asStateFlow()
    private val mutableAutoEnabled = MutableStateFlow(prefs.getBoolean("automatic_drive_enabled", false))
    val automaticDriveEnabled = mutableAutoEnabled.asStateFlow()
    private val mutableAutoStatus = MutableStateFlow(runCatching {
        AutomaticBackupStatus.valueOf(prefs.getString("automatic_drive_status", "NONE") ?: "NONE")
    }.getOrDefault(AutomaticBackupStatus.NONE))
    val automaticDriveStatus = mutableAutoStatus.asStateFlow()
    private val mutableLastAutomatic = MutableStateFlow(prefs.getLong("last_automatic_drive_backup", 0L))
    val lastAutomaticDriveBackup = mutableLastAutomatic.asStateFlow()

    fun setTheme(mode: ThemeMode) { prefs.edit().putString("theme", mode.name).apply(); mutableTheme.value = mode }
    fun setLanguage(language: AppLanguage) {
        // apply() updates in-memory preferences synchronously before the Activity is recreated.
        prefs.edit().putString(AppLocale.LANGUAGE_KEY, language.name).apply()
        mutableLanguage.value = language
    }
    fun markLocalBackup(time: Long) { prefs.edit().putLong("last_local_backup", time).apply(); mutableLocalBackup.value = time }
    fun setDriveAccount(name: String?) {
        val changed = mutableDriveAccount.value != name
        val editor = prefs.edit().putString("drive_account", name)
        if (changed) {
            // A grant for one account is never treated as consent for another.
            editor.putBoolean("automatic_drive_enabled", false)
                .putString("automatic_drive_status", AutomaticBackupStatus.NONE.name)
                .remove("last_automatic_drive_backup").remove("last_automatic_drive_day")
            mutableAutoEnabled.value = false
            mutableAutoStatus.value = AutomaticBackupStatus.NONE
            mutableLastAutomatic.value = 0L
        }
        editor.apply(); mutableDriveAccount.value = name
        mutableDriveBackup.value = name?.let { prefs.getLong(driveKey(it), 0L) } ?: 0L
    }
    fun setAutomaticDriveEnabled(enabled: Boolean) {
        val allowed = enabled && mutableDriveAccount.value != null
        prefs.edit().putBoolean("automatic_drive_enabled", allowed)
            .putString("automatic_drive_status", AutomaticBackupStatus.NONE.name).apply()
        mutableAutoEnabled.value = allowed
        mutableAutoStatus.value = AutomaticBackupStatus.NONE
    }
    override fun activeAccount(): String? = mutableDriveAccount.value.takeIf { mutableAutoEnabled.value }
    override fun lastSuccessfulDay(): String? = prefs.getString("last_automatic_drive_day", null)
    override fun recordStatus(status: AutomaticBackupStatus) {
        prefs.edit().putString("automatic_drive_status", status.name).apply()
        mutableAutoStatus.value = status
    }
    override fun recordSuccess(account: String, time: Long, localDay: String) {
        if (activeAccount() != account) return
        prefs.edit().putLong("last_automatic_drive_backup", time)
            .putString("last_automatic_drive_day", localDay)
            .putString("automatic_drive_status", AutomaticBackupStatus.SUCCESS.name).apply()
        mutableLastAutomatic.value = time
        mutableAutoStatus.value = AutomaticBackupStatus.SUCCESS
        markDriveBackup(account, time)
    }
    fun markDriveBackup(account: String, time: Long) {
        prefs.edit().putLong(driveKey(account), time).apply()
        if (mutableDriveAccount.value == account) mutableDriveBackup.value = time
    }
    private fun driveKey(name: String): String = "last_drive_" + MessageDigest.getInstance("SHA-256")
        .digest(name.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
