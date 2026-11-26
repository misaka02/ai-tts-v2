package com.aitts.v2.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aitts.v2.AiTtsApplication
import com.aitts.v2.permission.PermissionHelper
import com.aitts.v2.ui.navigation.AiTtsAppRoot
import com.aitts.v2.ui.theme.AiTtsTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PermissionHelper.requestNotificationIfNeeded(this)

        val container = (application as AiTtsApplication).container
        setContent {
            val settings by container.preferences.settingsFlow.collectAsStateWithLifecycle()
            AiTtsTheme(
                themeMode = settings.themeMode,
                palette = settings.themePalette,
                amoledPureBlack = settings.isAmoledPureBlack
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AiTtsAppRoot(container = container)
                }
            }
        }
    }
}
