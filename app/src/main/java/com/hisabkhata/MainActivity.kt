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
import com.hisabkhata.data.settings.AppMode
import com.hisabkhata.data.settings.AppPreferences
import com.hisabkhata.data.settings.ThemeMode
import javax.inject.Inject
import com.hisabkhata.data.cloud.CloudSession
import com.hisabkhata.ui.auth.ModeSelectionScreen
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
            val appMode by preferences.appMode.collectAsStateWithLifecycle()
            val rememberMe by preferences.rememberMe.collectAsStateWithLifecycle()
            HisabKhataTheme(darkTheme = dark) {
                when (appMode) {
                    AppMode.UNSET -> ModeSelectionScreen(
                        onSelectLocalOnly = { preferences.setAppMode(AppMode.LOCAL_ONLY) },
                        onSelectFull = { preferences.setAppMode(AppMode.FULL) }
                    )
                    AppMode.LOCAL_ONLY -> HisabKhataRoot()
                    AppMode.FULL -> if (account == null && !rememberMe) SignInScreen() else HisabKhataRoot()
                }
            }
        }
    }
}
