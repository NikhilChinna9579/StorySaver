@file:OptIn(ExperimentalMaterial3Api::class)

package com.storysaver.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storysaver.app.AppContainer
import com.storysaver.app.BuildConfig
import com.storysaver.app.ui.components.SectionLabel

@Composable
fun SettingsScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = container.settings
    val custom by settings.customDestination.collectAsStateWithLifecycle()
    val statusFolder by settings.statusFolder.collectAsStateWithLifecycle()

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                settings.setCustomDestination(uri)
            } catch (e: SecurityException) {
                Toast.makeText(context, "Couldn't get write access to that folder.", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(modifier = modifier, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionLabel("Default download destination")
            SettingsCard {
                RadioRow(
                    selected = custom == null,
                    title = "Gallery (recommended)",
                    subtitle = "Images → Pictures/StorySaver, videos → Movies/StorySaver",
                    onClick = { settings.setCustomDestination(null) },
                )
                RadioRow(
                    selected = custom != null,
                    title = "A folder I choose",
                    subtitle = custom?.let { "Current: ${container.saver.folderLabel(it)}" } ?: "Pick any folder on your device",
                    onClick = { folderPicker.launch(null) },
                )
                if (custom != null) {
                    TextButton(onClick = { folderPicker.launch(null) }) { Text("Change folder") }
                }
            }

            SectionLabel("WhatsApp status folder")
            SettingsCard {
                Text(
                    statusFolder?.let { "Connected: ${container.saver.folderLabel(it)}" } ?: "Not connected",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (statusFolder != null) {
                    TextButton(onClick = { settings.setStatusFolder(null) }) { Text("Remove access") }
                }
            }

            SectionLabel("Privacy")
            SettingsCard {
                Text(
                    "• No accounts, logins or passwords — ever.\n" +
                        "• No analytics, ads or tracking SDKs.\n" +
                        "• Your files never leave your phone. Story Saver only copies media you choose into your gallery " +
                        "or chosen folder.\n" +
                        "• Network access is used only to download a direct media link you paste, over HTTPS.\n" +
                        "• Download history is stored only on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            SectionLabel("About")
            SettingsCard {
                Text("Story Saver ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    "Saves WhatsApp statuses you've viewed (from your phone's status folder) and Instagram media from " +
                        "direct media links. It doesn't bypass logins, private accounts or platform restrictions. " +
                        "Respect the rights and privacy of the people whose content you save.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun RadioRow(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
