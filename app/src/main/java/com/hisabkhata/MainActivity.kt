package com.hisabkhata

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hisabkhata.data.settings.AppLocale
import com.hisabkhata.data.settings.AppPreferences
import com.hisabkhata.data.settings.ThemeMode
import javax.inject.Inject
import com.hisabkhata.data.cloud.CloudSession
import com.hisabkhata.ui.auth.SignInScreen
import com.hisabkhata.ui.navigation.HisabKhataRoot
import com.hisabkhata.ui.theme.HisabKhataTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var session: CloudSession

    override fun attachBaseContext(newBase: Context) {
        AppLocale.useLatinNumberFormatting()
        super.attachBaseContext(AppLocale.context(newBase))
    }

    override fun onResume() {
        super.onResume()
        AppLocale.useLatinNumberFormatting()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val mode by preferences.theme.collectAsStateWithLifecycle()
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val account by session.account.collectAsStateWithLifecycle()
            HisabKhataTheme(darkTheme = dark) {
                // v1.3.0 requires sign-in at every launch: accounts are created by the
                // owner with self sign-up disabled, and no session is persisted to disk,
                // so a shared phone never reopens another account's ledger.
                if (account == null) SignInScreen() else HisabKhataRoot()
            }
        }
    }
}
