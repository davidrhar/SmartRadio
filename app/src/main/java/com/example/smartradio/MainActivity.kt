package com.example.smartradio

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import com.example.smartradio.ui.RadioScreen
import com.example.smartradio.ui.RadioViewModel
import com.example.smartradio.ui.theme.SmartRadioTheme

@UnstableApi
class MainActivity : ComponentActivity() {

    private val viewModel: RadioViewModel by viewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Note: no manual startForegroundService() call here — the RadioViewModel's
        // MediaController connection binds RadioPlaybackService the standard way, and
        // Media3 promotes it to a foreground service itself once playback starts.

        setContent {
            SmartRadioTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RadioScreen(viewModel)
                }
            }
        }
    }
}
