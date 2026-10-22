@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.storysaver.app.ui.downloads

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storysaver.app.downloads.ActiveJob
import com.storysaver.app.downloads.JobState
import com.storysaver.app.model.MediaType
import com.storysaver.app.model.SavedMedia
import com.storysaver.app.ui.MediaIntents
import com.storysaver.app.ui.components.MediaThumb
import com.storysaver.app.ui.components.ProgressBlock
import com.storysaver.app.util.formatBytes
import java.text.DateFormat
import java.util.Date

@Composable
fun DownloadsScreen(vm: DownloadsViewModel, onOpen: (SavedMedia) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val consent by vm.consent.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf<List<SavedMedia>?>(null) }

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
        topBar = {
            if (ui.selecting) {
                TopAppBar(
                    title = { Text("${ui.selected.size} selected", modifier = Modifier.testTag("dl_selected_count")) },
                    navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Outlined.Close, "Cancel selection") } },
                    actions = {
                        IconButton(onClick = vm::selectAllShown) { Icon(Icons.Outlined.SelectAll, "Select all") }
                        IconButton(onClick = {
                            MediaIntents.shareMany(context, vm.selectedMedia().map { Uri.parse(it.uri) to it.mimeType })
                        }, modifier = Modifier.testTag("dl_share_selected")) { Icon(Icons.Outlined.Share, "Share selected") }
                        IconButton(onClick = { confirmDelete = vm.selectedMedia() }, modifier = Modifier.testTag("dl_delete_selected")) {
                            Icon(Icons.Outlined.Delete, "Delete selected")
                        }
                    },
                )
            } else {
                TopAppBar(title = { Text("Downloads") }, actions = { SortMenu(ui.query.sort, vm::setSort) })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (ui.totalCount == 0 && active.isEmpty()) {
            EmptyState(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item {
                OutlinedTextField(
                    value = ui.query.text, onValueChange = vm::setText,
                    placeholder = { Text("Search downloads") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (ui.query.text.isNotEmpty()) IconButton(onClick = { vm.setText("") }) { Icon(Icons.Outlined.Close, "Clear search") }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("dl_search"),
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SourceFilter.entries) { f ->
                        FilterChip(selected = ui.query.source == f, onClick = { vm.setSource(f) }, label = { Text(f.label) },
                            modifier = Modifier.testTag("dl_src_${f.name}"))
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TypeFilter.entries) { f ->
                        FilterChip(selected = ui.query.type == f, onClick = { vm.setType(f) }, label = { Text(f.label) },
                            modifier = Modifier.testTag("dl_type_${f.name}"))
                    }
                }
            }
            item {
                val filtered = ui.shownCount != ui.totalCount
                Text(
                    "${ui.shownCount} file${if (ui.shownCount == 1) "" else "s"} · ${formatBytes(ui.shownBytes)}" +
                        if (filtered) " (of ${ui.totalCount})" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("dl_summary"),
                )
            }

            if (active.isNotEmpty()) {
                item { SectionHeader("In progress") }
                items(active, key = { it.id.toString() }) { job -> ActiveRow(job, onCancel = { vm.cancelJob(job.id) }) }
            }

            if (ui.groups.isEmpty() && ui.totalCount > 0) {
                item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Nothing matches", style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = vm::clearFilters) { Text("Clear search and filters") }
                    }
                }
            }
            ui.groups.forEach { group ->
                if (group.title.isNotEmpty()) item(key = "h_${group.title}") { SectionHeader(group.title) }
                items(group.items, key = { it.id }) { media ->
                    DownloadRow(
                        media,
                        selected = media.id in ui.selected,
                        selecting = ui.selecting,
                        onClick = { if (ui.selecting) vm.toggleSelect(media.id) else onOpen(media) },
                        onLongClick = { vm.toggleSelect(media.id) },
                        onDelete = { confirmDelete = listOf(media) },
                        onRemoveFromList = { vm.removeFromHistoryOnly(media) },
                    )
                }
            }
        }
    }

    confirmDelete?.let { list ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(if (list.size == 1) "Delete this file?" else "Delete ${list.size} files?") },
            text = {
                Text(
                    if (list.size == 1) "“${list[0].displayName}” will be removed from your device, not just from this list."
                    else "They will be removed from your device, not just from this list."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (list.size == 1) vm.delete(list[0]) else vm.deleteSelected()
                    confirmDelete = null
                }, modifier = Modifier.testTag("dl_confirm_delete")) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SortMenu(current: SortOrder, onSort: (SortOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("dl_sort")) { Icon(Icons.Outlined.SwapVert, "Sort") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortOrder.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label, fontWeight = if (s == current) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { onSort(s); open = false },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ActiveRow(job: ActiveJob, onCancel: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp).testTag("active_job")) {
            Text(job.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val (step, progress) = when (val st = job.state) {
                is JobState.Running -> st.step to st.progress
                else -> "Waiting" to null
            }
            ProgressBlock("$step…", progress, onCancel = onCancel)
        }
    }
}

@Composable
private fun DownloadRow(
    media: SavedMedia, selected: Boolean, selecting: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onDelete: () -> Unit, onRemoveFromList: () -> Unit,
) {
    val context = LocalContext.current
    val uri = Uri.parse(media.uri)
    val date = remember(media.savedAt) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(media.savedAt))
    }
    var menu by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick).testTag("dl_row"),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                MediaThumb(model = uri, isVideo = media.mediaType == MediaType.VIDEO, isAudio = media.isAudio,
                    modifier = Modifier.size(64.dp))
                if (selected) {
                    Icon(
                        Icons.Outlined.CheckCircle, contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopStart).size(22.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(media.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("$date · ${formatBytes(media.sizeBytes)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(media.source.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (!selecting) {
                IconButton(onClick = { MediaIntents.share(context, uri, media.mimeType) }) { Icon(Icons.Outlined.Share, "Share") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Open with…") }, onClick = { menu = false; MediaIntents.open(context, uri, media.mimeType) })
                        DropdownMenuItem(text = { Text("Select") }, onClick = { menu = false; onLongClick() })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                        DropdownMenuItem(text = { Text("Remove from list only") }, onClick = { menu = false; onRemoveFromList() })
                    }
                }
            }
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
                "Images, videos and audio you save will show up here.",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}
