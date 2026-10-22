@file:OptIn(ExperimentalMaterial3Api::class)

package com.storysaver.app.ui.download

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storysaver.app.ui.components.BannerKind
import com.storysaver.app.ui.components.LegacyStoragePermissionEffect
import com.storysaver.app.ui.components.MediaThumb
import com.storysaver.app.ui.components.MessageBanner
import com.storysaver.app.ui.components.ProgressBlock
import com.storysaver.app.ui.components.SavedResultCard
import com.storysaver.app.ui.theme.Accents
import com.storysaver.app.util.formatBytes
import com.storysaver.app.youtube.YtQuality
import com.storysaver.app.youtube.YtVideo

@Composable
fun YouTubeScreen(vm: YouTubeViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val busy = state.busy

    LegacyStoragePermissionEffect(state.requestStoragePermission, vm::onStoragePermissionResult)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.OndemandVideo, contentDescription = null, tint = Accents.YouTube)
                        Spacer(Modifier.width(10.dp))
                        Text("YouTube")
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = state.url,
                onValueChange = vm::onUrlChange,
                label = { Text("YouTube video link") },
                placeholder = { Text("https://youtu.be/…") },
                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                trailingIcon = {
                    if (state.url.isNotEmpty() && !busy) IconButton(onClick = vm::reset) { Icon(Icons.Outlined.Close, "Clear") }
                },
                singleLine = true,
                enabled = !busy,
                isError = state.error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.find() }),
                modifier = Modifier.fillMaxWidth().testTag("yt_url"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { vm.onPaste(clipboard.getText()?.text) }, enabled = !busy,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text("Paste")
                }
                Button(
                    onClick = vm::find, enabled = !busy && state.url.isNotBlank(),
                    modifier = Modifier.weight(1f).height(52.dp).testTag("yt_find"),
                ) {
                    Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text("Find video")
                }
            }

            state.error?.let { MessageBanner(BannerKind.ERROR, "Couldn't get this video", it, onDismiss = vm::dismissError) }

            val video = state.video
            when {
                state.resolving -> ProgressBlock("Looking up the video…", null, onCancel = vm::cancel)
                video != null -> VideoCard(video, state, vm)
                state.error == null -> MessageBanner(
                    BannerKind.INFO, "How to use",
                    "In YouTube, open a video → Share → Copy link, then tap Paste here. Pick a resolution and tap " +
                        "Download. You can also use Share → Story Saver straight from YouTube.\n\n" +
                        "Up to 1080p (MP4). Only download videos you have the right to keep — your own uploads, " +
                        "Creative Commons videos, or with the creator's permission.",
                )
            }
        }
    }
}

@Composable
private fun VideoCard(video: YtVideo, state: YtUiState, vm: YouTubeViewModel) {
    val job = state.job
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MediaThumb(
            model = video.thumbnailUrl ?: "", isVideo = true, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )
        Text(video.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("yt_title"))
        Text(
            listOfNotNull(video.channel, duration(video.durationSeconds)).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text("Resolution", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                video.qualities.forEach { q ->
                    QualityRow(q, selected = q == state.selected, enabled = job !is YtJob.Working, onClick = { vm.select(q) })
                }
            }
        }

        when (job) {
            YtJob.Ready -> Button(
                onClick = vm::download, enabled = state.selected != null,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("yt_download"),
            ) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Download ${state.selected?.label ?: ""}")
            }
            is YtJob.Working -> ProgressBlock("${job.step}…", job.progress, onCancel = vm::cancel,
                modifier = Modifier.testTag("yt_progress"))
            is YtJob.Saved -> Column(Modifier.testTag("yt_saved")) {
                SavedResultCard(job.media, onSaveAnother = vm::reset)
            }
            is YtJob.Failed -> {
                MessageBanner(BannerKind.ERROR, "Download failed", job.message)
                OutlinedButton(onClick = vm::download, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun QualityRow(q: YtQuality, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp).testTag("yt_q_${q.label}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(q.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            if (q.approxBytes > 0) "MP4 · ${formatBytes(q.approxBytes)}" else "MP4",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
    }
}

private fun duration(seconds: Long): String? {
    if (seconds <= 0) return null
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
