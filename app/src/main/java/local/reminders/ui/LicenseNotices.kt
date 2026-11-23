package local.reminders.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun LicenseNotices() {
    val assets = LocalContext.current.assets
    Column(Modifier.fillMaxWidth()) {
            Details("Licenses") {
                Text("Original code: MIT. Dependencies retain their own terms.",
                    style = MaterialTheme.typography.bodyMedium)
                listOf(
                    "Attribution" to "third-party.txt",
                    "Auto ExecSec · MIT" to "LICENSE",
                    "SQLCipher Community" to "sqlcipher.txt",
                    "Apache License 2.0" to "apache-2.0.txt"
                ).forEach { (title, file) ->
                    Details(title) {
                        val notice = remember(assets, file) {
                            assets.open("licenses/$file").bufferedReader().use { it.readText() }
                        }
                        SelectionContainer(Modifier.fillMaxWidth()) {
                            Text(notice, style = MaterialTheme.typography.bodyMedium)
                        }
                }
            }
            }
        }
}
