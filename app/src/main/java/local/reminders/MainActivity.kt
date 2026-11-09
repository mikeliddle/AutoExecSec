package local.reminders

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import local.reminders.ui.*

class MainActivity : ComponentActivity() {
    private var openImport by mutableStateOf(false)
    private var unlocked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        openImport = intent.getBooleanExtra("open_import", false)
        setContent {
            ReminderTheme {
                val model: ReminderViewModel = viewModel()
                val state by model.state.collectAsStateWithLifecycle()
                var authenticationError by remember { mutableStateOf<String?>(null) }
                val authenticate = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { unlocked = it.resultCode == Activity.RESULT_OK }
                DisposableEffect(lifecycle) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) model.refresh()
                        if (event == Lifecycle.Event.ON_STOP) unlocked = false
                    }
                    lifecycle.addObserver(observer)
                    model.refresh()
                    onDispose { lifecycle.removeObserver(observer) }
                }
                if (!state.loading && state.settings.screenLock && !unlocked) {
                    LockedScreen(authenticationError) {
                        val keyguard = getSystemService(KeyguardManager::class.java)
                        val request = keyguard.createConfirmDeviceCredentialIntent(
                            "Unlock appointment reminders", "Authenticate to view local schedules and recipients."
                        )
                        if (request != null) authenticate.launch(request)
                        else authenticationError = "Device credential is unavailable. Restore a device PIN or password in Android settings before viewing local data."
                    }
                } else {
                    ReminderApp(model, state, openImport) { openImport = false }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openImport = intent.getBooleanExtra("open_import", false)
    }
}
