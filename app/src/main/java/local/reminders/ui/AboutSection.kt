package local.reminders.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import local.reminders.R

@Composable
internal fun AboutSection() {
    val context = LocalContext.current
    val installed = remember(context) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    val versionCode = PackageInfoCompat.getLongVersionCode(installed)
    val versionName = installed.versionName
    var browserUnavailable by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Details(stringResource(R.string.about_title)) {
            Text(
                if (versionName != null)
                    stringResource(R.string.installed_version, versionName, versionCode)
                else stringResource(R.string.installed_build, versionCode),
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = {
                browserUnavailable = false
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        "https://github.com/mikeliddle/AutoExecSec/releases/latest".toUri())
                        .addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: ActivityNotFoundException) {
                    browserUnavailable = true
                }
            }) { Text(stringResource(R.string.latest_release)) }
            if (browserUnavailable) Text(stringResource(R.string.browser_unavailable),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}
