package com.hisabkhata.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Singleton
class AppPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("hisab_settings", Context.MODE_PRIVATE)
    private val mutableTheme = MutableStateFlow(runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM") ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM))
    val theme = mutableTheme.asStateFlow()
    private val mutableLocalBackup = MutableStateFlow(prefs.getLong("last_local_backup", 0L))
    val lastLocalBackup = mutableLocalBackup.asStateFlow()
    private val mutableDriveAccount = MutableStateFlow(prefs.getString("drive_account", null))
    val driveAccount = mutableDriveAccount.asStateFlow()
    private val mutableDriveBackup = MutableStateFlow(mutableDriveAccount.value?.let { prefs.getLong(driveKey(it), 0L) } ?: 0L)
    val lastDriveBackup = mutableDriveBackup.asStateFlow()

    fun setTheme(mode: ThemeMode) { prefs.edit().putString("theme", mode.name).apply(); mutableTheme.value = mode }
    fun markLocalBackup(time: Long) { prefs.edit().putLong("last_local_backup", time).apply(); mutableLocalBackup.value = time }
    fun setDriveAccount(name: String?) {
        prefs.edit().putString("drive_account", name).apply(); mutableDriveAccount.value = name
        mutableDriveBackup.value = name?.let { prefs.getLong(driveKey(it), 0L) } ?: 0L
    }
    fun markDriveBackup(account: String, time: Long) {
        prefs.edit().putLong(driveKey(account), time).apply()
        if (mutableDriveAccount.value == account) mutableDriveBackup.value = time
    }
    private fun driveKey(name: String): String = "last_drive_" + MessageDigest.getInstance("SHA-256")
        .digest(name.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
