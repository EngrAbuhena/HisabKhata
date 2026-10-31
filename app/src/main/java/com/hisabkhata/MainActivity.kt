package com.hisabkhata

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hisabkhata.data.settings.AppPreferences
import com.hisabkhata.data.settings.ThemeMode
import javax.inject.Inject
import com.hisabkhata.ui.navigation.HisabKhataRoot
import com.hisabkhata.ui.theme.HisabKhataTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var preferences: AppPreferences
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
            HisabKhataTheme(darkTheme = dark) { HisabKhataRoot() }
        }
    }
}
