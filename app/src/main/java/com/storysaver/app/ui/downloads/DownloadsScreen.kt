@file:OptIn(ExperimentalMaterial3Api::class)

package com.storysaver.app.ui.downloads

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storysaver.app.model.MediaType
import com.storysaver.app.model.SavedMedia
import com.storysaver.app.ui.MediaIntents
import com.storysaver.app.ui.components.MediaThumb
import com.storysaver.app.util.formatBytes
import java.text.DateFormat
import java.util.Date

@Composable
fun DownloadsScreen(vm: DownloadsViewModel, modifier: Modifier = Modifier) {
    val items by vm.items.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val consent by vm.consent.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var toDelete by remember { mutableStateOf<SavedMedia?>(null) }

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onConsentResult(it.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(consent) {
        consent?.let { consentLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
    }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Downloads") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (items.isEmpty()) {
            EmptyState(Modifier.padding(padding))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                items(items, key = { it.id }) { media ->
                    DownloadRow(media, onDelete = { toDelete = media })
                }
            }
        }
    }

    toDelete?.let { media ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete this file?") },
            text = { Text("“${media.displayName}” will be removed from your device, not just from this list.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(media); toDelete = null }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { vm.removeFromHistoryOnly(media); toDelete = null }) { Text("Remove from list only") }
            },
        )
    }
}

@Composable
private fun DownloadRow(media: SavedMedia, onDelete: () -> Unit) {
    val context = LocalContext.current
    val uri = Uri.parse(media.uri)
    val date = remember(media.savedAt) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(media.savedAt))
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            MediaThumb(model = uri, isVideo = media.mediaType == MediaType.VIDEO, modifier = Modifier.size(64.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(media.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$date · ${formatBytes(media.sizeBytes)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(media.source.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { MediaIntents.open(context, uri, media.mimeType) }) { Icon(Icons.Outlined.OpenInNew, "Open") }
            IconButton(onClick = { MediaIntents.share(context, uri, media.mimeType) }) { Icon(Icons.Outlined.Share, "Share") }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Delete") }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.DownloadDone, contentDescription = null, modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("No downloads yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Images and videos you save will show up here.",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
