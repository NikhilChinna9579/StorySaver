@file:OptIn(ExperimentalMaterial3Api::class)

package com.storysaver.app.ui.download

import android.net.Uri
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storysaver.app.model.MediaType
import com.storysaver.app.ui.MediaIntents
import com.storysaver.app.ui.components.BannerKind
import com.storysaver.app.ui.components.LegacyStoragePermissionEffect
import com.storysaver.app.ui.components.rememberNotificationPermissionAsker
import com.storysaver.app.ui.components.MediaThumb
import com.storysaver.app.ui.components.MessageBanner
import com.storysaver.app.ui.components.ProgressBlock
import com.storysaver.app.instagram.InstagramExtractor
import com.storysaver.app.ui.theme.Accents
import com.storysaver.app.ui.trim.TrimDialog
import com.storysaver.app.ui.trim.TrimSource
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material3.TextButton

@Composable
fun InstagramScreen(vm: InstagramViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val busy = state.busy
    val askNotifications = rememberNotificationPermissionAsker()

    LegacyStoragePermissionEffect(state.requestStoragePermission, vm::onStoragePermissionResult)
    var trimIndex by remember { mutableStateOf<Int?>(null) }
    val trimItem = (state.phase as? IgPhase.Found)?.items?.firstOrNull { it.item.index == trimIndex }?.item
    if (trimItem != null) {
        TrimDialog(
            source = TrimSource(trimItem.url, userAgent = InstagramExtractor.APP_UA),
            confirmLabel = "Download clip",
            offerAudioChoice = true,
            onDismiss = { trimIndex = null },
            onConfirm = { range, audio -> trimIndex = null; askNotifications(); vm.save(trimItem.index, audio, range) },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PhotoCamera, contentDescription = null, tint = Accents.Instagram)
                        Spacer(Modifier.width(10.dp))
                        Text("Instagram")
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = state.url,
                onValueChange = vm::onUrlChange,
                label = { Text("Reel or post link") },
                placeholder = { Text("https://www.instagram.com/reel/…") },
                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                trailingIcon = {
                    if (state.url.isNotEmpty() && !busy) {
                        IconButton(onClick = vm::reset) { Icon(Icons.Outlined.Close, "Clear") }
                    }
                },
                singleLine = true,
                enabled = !busy,
                isError = state.error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.find() }),
                modifier = Modifier.fillMaxWidth().testTag("ig_url"),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { vm.onPaste(clipboard.getText()?.text) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text("Paste")
                }
                Button(
                    onClick = vm::find,
                    enabled = !busy && state.url.isNotBlank(),
                    modifier = Modifier.weight(1f).height(52.dp).testTag("ig_find"),
                ) {
                    Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text("Find media")
                }
            }

            state.unsupported?.let { MessageBanner(BannerKind.UNSUPPORTED, it.title, it.message) }
            state.error?.let { MessageBanner(BannerKind.ERROR, "Couldn't get this post", it, onDismiss = vm::dismissError) }

            when (val phase = state.phase) {
                IgPhase.Idle -> if (state.unsupported == null && state.error == null) HowItWorks()
                IgPhase.Resolving -> ProgressBlock("Looking up the post…", null, onCancel = vm::cancel)
                is IgPhase.Found -> FoundPost(
                    phase,
                    onSave = { i, audio -> askNotifications(); vm.save(i, audio) },
                    onSaveAll = { askNotifications(); vm.saveAll() },
                    onTrim = { i -> trimIndex = i },
                    onCancel = vm::cancel,
                )
            }
        }
    }
}

@Composable
private fun FoundPost(
    found: IgPhase.Found, onSave: (Int, Boolean) -> Unit, onSaveAll: () -> Unit, onTrim: (Int) -> Unit, onCancel: () -> Unit,
) {
    val items = found.items
    val images = items.count { it.item.type == MediaType.IMAGE }
    val videos = items.size - images
    val anyActive = items.any { it.state is IgItemState.Downloading || it.state is IgItemState.Saving }
    val remaining = items.count { it.state !is IgItemState.Saved }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    found.post.owner?.let { "@$it" } ?: "Media found",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    listOfNotNull(
                        images.takeIf { it > 0 }?.let { "$it image${if (it == 1) "" else "s"}" },
                        videos.takeIf { it > 0 }?.let { "$it video${if (it == 1) "" else "s"}" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("ig_found_summary"),
                )
            }
            if (items.size > 1 && remaining > 0) {
                Button(onClick = onSaveAll, enabled = !anyActive) { Text("Save all") }
            }
        }
        items.forEach { ui ->
            ItemCard(
                ui, onSave = { audio -> onSave(ui.item.index, audio) }, onTrim = { onTrim(ui.item.index) }, onCancel = onCancel,
                label = if (items.size > 1) "${ui.item.index + 1}/${items.size}" else null, enabled = !anyActive,
            )
        }
    }
}

@Composable
private fun ItemCard(
    ui: IgItemUi, onSave: (audioOnly: Boolean) -> Unit, onTrim: () -> Unit, onCancel: () -> Unit, label: String?, enabled: Boolean,
) {
    val context = LocalContext.current
    val item = ui.item
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MediaThumb(
                model = item.thumbnailUrl ?: item.url,
                isVideo = item.type == MediaType.VIDEO,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(if (item.type == MediaType.VIDEO) 0.8f else 1f),
            )
            Text(
                listOfNotNull(label, item.type.label).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
            )
            when (val s = ui.state) {
                IgItemState.Ready -> Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onSave(false) }, enabled = enabled,
                            modifier = Modifier.weight(1f).height(50.dp).testTag("ig_save_${item.index}"),
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp)); Text("Save ${item.type.label.lowercase()}")
                        }
                        if (item.type == MediaType.VIDEO) {
                            OutlinedButton(
                                onClick = { onSave(true) }, enabled = enabled,
                                modifier = Modifier.weight(1f).height(50.dp).testTag("ig_audio_${item.index}"),
                            ) {
                                Icon(Icons.Outlined.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp)); Text("Audio only")
                            }
                        }
                    }
                    if (item.type == MediaType.VIDEO) {
                        TextButton(onClick = onTrim, enabled = enabled, modifier = Modifier.testTag("ig_trim_${item.index}")) {
                            Icon(Icons.Outlined.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp)); Text("Trim — save only a part")
                        }
                    }
                }
                is IgItemState.Downloading -> ProgressBlock("${s.step}…", s.progress, onCancel = onCancel)
                is IgItemState.Saving -> ProgressBlock("Saving…", s.progress)
                is IgItemState.Failed -> {
                    MessageBanner(BannerKind.ERROR, "Couldn't save", s.message)
                    OutlinedButton(onClick = { onSave(false) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                }
                is IgItemState.Saved -> {
                    val uri = Uri.parse(s.media.uri)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("ig_saved_${item.index}")) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Accents.Success)
                        Spacer(Modifier.width(8.dp))
                        Text("Saved to ${s.media.locationLabel}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { MediaIntents.open(context, uri, s.media.mimeType) }) {
                            Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Open")
                        }
                        FilledTonalButton(onClick = { MediaIntents.share(context, uri, s.media.mimeType) }) {
                            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Share")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HowItWorks() {
    MessageBanner(
        BannerKind.INFO,
        "How to use",
        "In Instagram, open a reel or post → tap Share (paper plane) → Copy link. Then tap Paste here.\n\n" +
            "Works for reels, posts and multi-photo posts from public accounts. Private accounts and Stories " +
            "can't be downloaded — Instagram only shows those to logged-in users, and Story Saver never logs in.\n\n" +
            "Tip: you can also tap Share → Story Saver directly from Instagram.",
    )
}
