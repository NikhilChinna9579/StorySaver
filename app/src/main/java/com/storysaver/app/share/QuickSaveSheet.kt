@file:OptIn(ExperimentalMaterial3Api::class)

package com.storysaver.app.share

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storysaver.app.model.MediaType
import com.storysaver.app.ui.MediaIntents
import com.storysaver.app.ui.components.BannerKind
import com.storysaver.app.ui.components.LegacyStoragePermissionEffect
import com.storysaver.app.ui.components.MediaThumb
import com.storysaver.app.ui.components.MessageBanner
import com.storysaver.app.ui.components.ProgressBlock
import com.storysaver.app.ui.components.rememberNotificationPermissionAsker
import com.storysaver.app.ui.theme.Accents
import com.storysaver.app.ui.trim.TrimButton
import com.storysaver.app.ui.trim.TrimDialog
import com.storysaver.app.util.formatBytes
import com.storysaver.app.youtube.YtQuality

/** Keeps the options and Download/Close buttons on screen without scrolling. */
private val PREVIEW_HEIGHT = 240.dp

@Composable
fun QuickSaveSheet(vm: QuickSaveViewModel, onClose: () -> Unit, onOpenApp: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val askNotifications = rememberNotificationPermissionAsker()
    LegacyStoragePermissionEffect(state.requestStoragePermission, vm::onStoragePermissionResult)
    var trimming by remember { mutableStateOf(false) }
    val trimSource = vm.trimSource()

    if (trimming && trimSource != null) {
        TrimDialog(
            source = trimSource,
            confirmLabel = "Download clip",
            onDismiss = { trimming = false },
            onConfirm = { range, _ -> trimming = false; askNotifications(); vm.download(range) },
        )
    }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val progress = state.progress
            when (val c = state.content) {
                is QsContent.Loading -> {
                    Header(Icons.Outlined.Download, "Story Saver", null)
                    ProgressBlock(c.message, null)
                }
                is QsContent.Unsupported -> {
                    Header(Icons.Outlined.Download, "Can't download this", null)
                    MessageBanner(BannerKind.UNSUPPORTED, "Not supported", c.message, modifier = Modifier.testTag("qs_unsupported"))
                    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                }
                is QsContent.Instagram -> InstagramWindow(c, progress, vm)
                is QsContent.YouTube -> YouTubeWindow(c, progress, vm)
                is QsContent.LocalFile -> LocalWindow(c, progress, vm)
            }

            if (state.content !is QsContent.Loading && state.content !is QsContent.Unsupported) {
                ProgressArea(
                    progress,
                    onDownload = { askNotifications(); vm.download() },
                    downloadLabel = downloadLabel(state.content),
                    onTrim = if (trimSource != null) ({ trimming = true }) else null,
                    onCancel = vm::cancel, onClose = onClose, onOpenApp = onOpenApp,
                )
            }
        }
    }
}

@Composable
private fun Header(icon: ImageVector, title: String, subtitle: String?, tag: String = "qs_header") {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(tag)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------- Instagram ----------------

@Composable
private fun InstagramWindow(c: QsContent.Instagram, progress: QsProgress, vm: QuickSaveViewModel) {
    val items = c.post.items
    val locked = progress !is QsProgress.Idle && progress !is QsProgress.Failed
    val owner = c.post.owner?.let { "@$it on Instagram" } ?: "Instagram"
    when {
        c.isAllImages -> Header(Icons.Outlined.Image, if (items.size == 1) "Image" else "${items.size} images", owner)
        items.size == 1 -> Header(Icons.Outlined.Videocam, "Video", owner)
        else -> Header(Icons.Outlined.Videocam, "Post with ${items.size} items", owner)
    }
    if (items.size == 1) {
        val item = items.single()
        MediaThumb(item.thumbnailUrl ?: item.url, isVideo = item.type == MediaType.VIDEO,
            modifier = Modifier.fillMaxWidth().height(PREVIEW_HEIGHT))
    } else {
        Text("Choose what to download:", style = MaterialTheme.typography.bodyMedium)
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item ->
                    val selected = item.index in c.selected
                    Box(
                        Modifier.weight(1f).aspectRatio(0.8f)
                            .then(if (selected) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), MaterialTheme.shapes.medium) else Modifier)
                            .clickable(enabled = !locked) { vm.toggleItem(item.index) }
                            .testTag("qs_item_${item.index}"),
                    ) {
                        MediaThumb(item.thumbnailUrl ?: item.url, isVideo = item.type == MediaType.VIDEO, modifier = Modifier.matchParentSize())
                        Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.align(Alignment.TopEnd))
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    if (c.isVideo) {
        OptionRow("Video", "MP4, original quality", !c.audioOnly, !locked, "qs_opt_video") { vm.setAudioOnly(false) }
        OptionRow("Audio only", "M4A — just the sound", c.audioOnly, !locked, "qs_opt_audio") { vm.setAudioOnly(true) }
    }
}

// ---------------- YouTube ----------------

@Composable
private fun YouTubeWindow(c: QsContent.YouTube, progress: QsProgress, vm: QuickSaveViewModel) {
    val locked = progress !is QsProgress.Idle && progress !is QsProgress.Failed
    Header(Icons.Outlined.Videocam, "Video", c.video.channel?.let { "$it on YouTube" } ?: "YouTube")
    MediaThumb(c.video.thumbnailUrl ?: "", isVideo = true,
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).heightIn(max = PREVIEW_HEIGHT))
    Text(c.video.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text("Resolution", style = MaterialTheme.typography.titleSmall)
    c.video.qualities.forEach { q: YtQuality ->
        val detail = (if (q.audioOnly) "M4A" else "MP4") + if (q.approxBytes > 0) " · ${formatBytes(q.approxBytes)}" else ""
        OptionRow(q.label, detail, q == c.selected, !locked, "qs_q_${q.label}") { vm.selectQuality(q) }
    }
}

// ---------------- Shared file ----------------

@Composable
private fun LocalWindow(c: QsContent.LocalFile, progress: QsProgress, vm: QuickSaveViewModel) {
    val locked = progress !is QsProgress.Idle && progress !is QsProgress.Failed
    Header(if (c.isVideo) Icons.Outlined.Videocam else Icons.Outlined.Image, if (c.isVideo) "Video" else "Image",
        "${c.name} · ${formatBytes(c.size)}")
    MediaThumb(c.uri, isVideo = c.isVideo, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().height(PREVIEW_HEIGHT))
    if (c.isVideo && c.canExtractAudio) {
        OptionRow("Video", "Keep as video", !c.audioOnly, !locked, "qs_opt_video") { vm.setAudioOnly(false) }
        OptionRow("Audio only", "M4A — just the sound", c.audioOnly, !locked, "qs_opt_audio") { vm.setAudioOnly(true) }
    }
}

@Composable
private fun OptionRow(title: String, detail: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 2.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun downloadLabel(content: QsContent): String = when (content) {
    is QsContent.Instagram -> {
        val n = content.selected.size
        when {
            n == 0 -> "Select something to download"
            content.audioOnly && content.isVideo && content.isAllVideoSelection() -> "Download audio"
            content.isAllImages -> if (n == 1) "Download image" else "Download $n images"
            content.post.items.size == 1 -> "Download video"
            else -> "Download $n items"
        }
    }
    is QsContent.YouTube -> content.selected?.let { if (it.audioOnly) "Download audio" else "Download ${it.label}" } ?: "Download"
    is QsContent.LocalFile -> if (content.audioOnly) "Save audio" else if (content.isVideo) "Save video" else "Save image"
    else -> "Download"
}

private fun QsContent.Instagram.isAllVideoSelection() =
    post.items.filter { it.index in selected }.all { it.type == MediaType.VIDEO }

// ---------------- Progress / result ----------------

@Composable
private fun ProgressArea(
    progress: QsProgress, onDownload: () -> Unit, downloadLabel: String, onTrim: (() -> Unit)?,
    onCancel: () -> Unit, onClose: () -> Unit, onOpenApp: () -> Unit,
) {
    val context = LocalContext.current
    when (progress) {
        QsProgress.Idle, is QsProgress.Failed -> {
            if (progress is QsProgress.Failed) MessageBanner(BannerKind.ERROR, "Couldn't save", progress.message)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onDownload, enabled = !downloadLabel.startsWith("Select"),
                    modifier = Modifier.weight(1f).height(54.dp).testTag("qs_download"),
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text(if (progress is QsProgress.Failed) "Try again" else downloadLabel)
                }
                if (onTrim != null) TrimButton(onTrim, Modifier.height(54.dp).testTag("qs_trim"))
            }
        }
        is QsProgress.Saving -> ProgressBlock("${progress.step}…", null)
        is QsProgress.Background -> Column(Modifier.testTag("qs_started"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProgressBlock("${progress.step}…", progress.fraction, onCancel = onCancel)
            MessageBanner(
                BannerKind.INFO, "Downloading in the background",
                "You can close this and keep using the app you came from — the download continues and you'll get " +
                    "a notification when it's saved.",
            )
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth().testTag("qs_close")) { Text("Close") }
        }
        is QsProgress.Saved -> Column(Modifier.testTag("qs_saved"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Accents.Success)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (progress.media.size == 1) "Saved to ${progress.media[0].locationLabel}" else "Saved ${progress.media.size} files",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val first = progress.media.first()
                FilledTonalButton(onClick = { MediaIntents.open(context, Uri.parse(first.uri), first.mimeType) }) {
                    Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Open")
                }
                TextButton(onClick = onOpenApp) { Text("All downloads") }
            }
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}
