@file:OptIn(ExperimentalMaterial3Api::class)

package com.storysaver.app.ui.download

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storysaver.app.data.StatusItem
import com.storysaver.app.data.StatusRepository
import com.storysaver.app.model.MediaType
import com.storysaver.app.ui.components.BannerKind
import com.storysaver.app.ui.components.LegacyStoragePermissionEffect
import com.storysaver.app.ui.components.MediaThumb
import com.storysaver.app.ui.components.MediaTypeToggle
import com.storysaver.app.ui.components.MessageBanner
import com.storysaver.app.ui.components.ProgressBlock
import com.storysaver.app.ui.components.SavedResultCard
import com.storysaver.app.ui.theme.Accents
import com.storysaver.app.util.formatBytes

@Composable
fun WhatsAppScreen(vm: LocalSaveViewModel, statuses: StatusRepository, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onFolderPicked(it) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.onFilePicked(it) }
    val pickFile = {
        filePicker.launch(arrayOf(if (state.mediaType == MediaType.IMAGE) "image/*" else "video/*"))
    }

    LegacyStoragePermissionEffect(state.requestStoragePermission, vm::onStoragePermissionResult)

    // Coming back from WhatsApp after viewing more statuses: pick them up automatically.
    LifecycleResumeEffect(state.folderGranted) {
        if (state.folderGranted) vm.refresh(quiet = true)
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Forum, contentDescription = null, tint = Accents.WhatsApp)
                        Spacer(Modifier.width(10.dp))
                        Text("WhatsApp Status")
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (state.folderGranted) {
                        IconButton(onClick = vm::refresh, enabled = !state.loading) { Icon(Icons.Outlined.Refresh, "Refresh") }
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 108.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            full { MediaTypeToggle(state.mediaType, vm::onMediaTypeChange) }

            state.error?.let { full { MessageBanner(BannerKind.ERROR, "Something went wrong", it, onDismiss = vm::dismissMessages) } }
            state.notice?.let { full { MessageBanner(BannerKind.INFO, "Note", it, onDismiss = vm::dismissMessages) } }

            // Selected item: preview + save, pinned at the top so it's always visible.
            state.selected?.let { sel ->
                full {
                    val saved = state.saved
                    if (saved != null) {
                        SavedResultCard(saved, onSaveAnother = vm::clearSelection)
                    } else {
                        LocalPreviewCard(
                            item = sel, destination = state.destinationLabel, saving = state.saving,
                            progress = state.progress, onSave = vm::save, onCancel = vm::cancelSave,
                            onClose = vm::clearSelection,
                        )
                    }
                }
            }

            if (!state.folderGranted) {
                full {
                    GrantFolderCard(
                        onGrant = { folderPicker.launch(statuses.suggestedPickerStart(StatusRepository.Variant.WHATSAPP)) },
                        onGrantBusiness = { folderPicker.launch(statuses.suggestedPickerStart(StatusRepository.Variant.BUSINESS)) },
                        onPickFile = pickFile,
                    )
                }
            } else {
                full {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Accents.WhatsApp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Folder: ${state.folderLabel ?: "Status folder"}",
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { folderPicker.launch(statuses.suggestedPickerStart(StatusRepository.Variant.WHATSAPP)) }) {
                            Text("Change")
                        }
                    }
                }
                if (state.listedOnce && !state.loading) {
                    full {
                        val skipped = if (state.skippedCount > 0) {
                            " · ${state.skippedCount} other file${if (state.skippedCount == 1) "" else "s"} not shown (unfinished downloads or not media)"
                        } else ""
                        Text(
                            "Found ${state.imageCount} image${if (state.imageCount == 1) "" else "s"} · " +
                                "${state.videoCount} video${if (state.videoCount == 1) "" else "s"}$skipped",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                when {
                    state.loading -> full {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    state.listedOnce && state.items.isEmpty() -> full {
                        MessageBanner(
                            BannerKind.INFO,
                            "No ${state.mediaType.label.lowercase()} statuses found",
                            "WhatsApp only saves a status to this folder after it has downloaded it on your phone, and removes it after about 24 hours. " +
                                "Video statuses are often only downloaded once you play them to the end. " +
                                "Open WhatsApp, view the status fully, then come back — the list updates automatically.\n\n" +
                                "If you chose the wrong folder, tap Change and select the “.Statuses” folder " +
                                "(Android/media/com.whatsapp/WhatsApp/Media/.Statuses on most phones).",
                        )
                    }
                    else -> items(state.items, key = { it.uri.toString() }) { item ->
                        StatusTile(item, selected = item.uri == state.selected?.uri, onClick = { vm.select(item) })
                    }
                }
            }

            full {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Text("Other options", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = pickFile, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.InsertDriveFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Pick a file")
                        }
                        OutlinedButton(onClick = { vm.onPastedText(clipboard.getText()?.text) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Paste link")
                        }
                    }
                }
            }
        }
    }
}

private fun LazyGridScope.full(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun GrantFolderCard(onGrant: () -> Unit, onGrantBusiness: () -> Unit, onPickFile: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Connect your WhatsApp status folder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Android only lets Story Saver see a folder you choose. Grant access once:\n\n" +
                    "1. View the statuses you want in WhatsApp first.\n" +
                    "2. Tap “Grant folder access”. The picker should open at the “.Statuses” folder.\n" +
                    "3. Tap “Use this folder”, then “Allow”.\n\n" +
                    "If the picker opens somewhere else, navigate to Android › media › com.whatsapp › WhatsApp › " +
                    "Media › .Statuses (older phones: WhatsApp › Media › .Statuses). Some phones hide folders " +
                    "starting with a dot — use the picker menu to show hidden files, or use “Pick a file”.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onGrant, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Grant folder access")
            }
            TextButton(onClick = onGrantBusiness, modifier = Modifier.fillMaxWidth()) { Text("Using WhatsApp Business?") }
            TextButton(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) { Text("Or pick a single file instead") }
        }
    }
}

@Composable
private fun StatusTile(item: StatusItem, selected: Boolean, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    MediaThumb(
        model = item.uri,
        isVideo = item.mediaType == MediaType.VIDEO,
        modifier = Modifier
            .aspectRatio(0.75f)
            .then(if (selected) Modifier.border(BorderStroke(3.dp, Accents.WhatsApp), shape) else Modifier)
            .clickable(onClick = onClick),
    )
}

@Composable
internal fun LocalPreviewCard(
    item: StatusItem,
    destination: String,
    saving: Boolean,
    progress: Float?,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onClose: (() -> Unit)?,
) {
    val type = item.mediaType ?: MediaType.IMAGE
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Preview", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (onClose != null && !saving) TextButton(onClick = onClose) { Text("Close") }
            }
            MediaThumb(
                model = item.uri, isVideo = type == MediaType.VIDEO, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            Text(
                "${type.label} · ${item.name}\n${formatBytes(item.size)} · will be saved to $destination",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            if (saving) {
                ProgressBlock("Saving…", progress, onCancel = onCancel)
            } else {
                Button(onClick = onSave, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text("Save ${type.label.lowercase()}")
                }
            }
        }
    }
}
