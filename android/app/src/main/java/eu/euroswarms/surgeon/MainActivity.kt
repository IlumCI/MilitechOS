package eu.euroswarms.surgeon

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.euroswarms.surgeon.ui.App
import eu.euroswarms.surgeon.ui.AppViewModel
import eu.euroswarms.surgeon.ui.theme.SurgeonTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SurgeonTheme {
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { /* result ignored; notifications are best-effort */ }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                val vm: AppViewModel = viewModel()
                val initialTab = intent?.getIntExtra(
                    eu.euroswarms.surgeon.work.Notifier.EXTRA_OPEN_TAB, 0,
                ) ?: 0
                App(vm, initialTab)
            }
        }
    }
}
