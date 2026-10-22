@file:OptIn(ExperimentalMaterial3Api::class)

package com.storysaver.app.ui.download

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
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
fun InstagramScreen(vm: InstagramViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val busy = state.phase is IgPhase.Fetching || state.phase is IgPhase.Saving

    LegacyStoragePermissionEffect(state.requestStoragePermission, vm::onStoragePermissionResult)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PhotoCamera, contentDescription = null, tint = Accents.Instagram)
                        Spacer(Modifier.width(10.dp))
                        Text("Instagram Story")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
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
            MediaTypeToggle(state.mediaType, vm::onMediaTypeChange)

            OutlinedTextField(
                value = state.url,
                onValueChange = vm::onUrlChange,
                label = { Text("Instagram link") },
                placeholder = { Text("https://…") },
                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                trailingIcon = {
                    if (state.url.isNotEmpty() && !busy) {
                        IconButton(onClick = { vm.onUrlChange("") }) { Icon(Icons.Outlined.Close, "Clear") }
                    }
                },
                singleLine = true,
                enabled = !busy,
                isError = state.error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { vm.fetch() }),
                modifier = Modifier.fillMaxWidth(),
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
                    onClick = vm::fetch,
                    enabled = !busy && state.url.isNotBlank(),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text("Download")
                }
            }

            state.unsupported?.let {
                MessageBanner(BannerKind.UNSUPPORTED, it.title, it.message)
            }
            state.error?.let {
                MessageBanner(BannerKind.ERROR, "Couldn't continue", it, onDismiss = vm::dismissError)
            }

            when (val phase = state.phase) {
                IgPhase.Idle -> if (state.unsupported == null && state.error == null) HowItWorksCard()
                is IgPhase.Fetching -> ProgressBlock("Downloading…", phase.progress, onCancel = vm::cancel)
                is IgPhase.Ready -> PreviewCard(
                    phase, state.mediaType, state.destinationLabel,
                    isSaving = false, progress = null, onSave = vm::save,
                )
                is IgPhase.Saving -> PreviewCard(
                    phase.ready, state.mediaType, state.destinationLabel,
                    isSaving = true, progress = phase.progress, onSave = {}, onCancel = vm::cancel,
                )
                is IgPhase.Saved -> SavedResultCard(phase.media, onSaveAnother = vm::reset)
            }
        }
    }
}

@Composable
private fun PreviewCard(
    ready: IgPhase.Ready,
    type: MediaType,
    destination: String,
    isSaving: Boolean,
    progress: Float?,
    onSave: () -> Unit,
    onCancel: () -> Unit = {},
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Preview", style = MaterialTheme.typography.titleMedium)
            MediaThumb(
                model = ready.file, isVideo = type == MediaType.VIDEO,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            Text(
                "${type.label} · ${ready.mimeType} · ${formatBytes(ready.sizeBytes)}\nWill be saved to $destination",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isSaving) {
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

@Composable
private fun HowItWorksCard() {
    MessageBanner(
        BannerKind.INFO,
        "Which links work?",
        "Story Saver downloads direct media links — links that point straight at an image or video file " +
            "on Instagram's media servers (cdninstagram.com / fbcdn.net) that open without logging in.\n\n" +
            "Normal Story, post and reel links open a page rather than a file, so they can't be downloaded " +
            "with a supported method. You'll get an explanation if you paste one.",
    )
}
