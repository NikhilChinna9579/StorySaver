@file:OptIn(ExperimentalMaterial3Api::class)

package com.storysaver.app.ui.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storysaver.app.ui.components.BannerKind
import com.storysaver.app.ui.components.LegacyStoragePermissionEffect
import com.storysaver.app.ui.components.MessageBanner
import com.storysaver.app.ui.components.SavedResultCard

/** Handles "Share → Story Saver" for an image or video file sent by another app. */
@Composable
fun SharedMediaScreen(vm: LocalSaveViewModel, onDone: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LegacyStoragePermissionEffect(state.requestStoragePermission, vm::onStoragePermissionResult)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Save shared media") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.error?.let { MessageBanner(BannerKind.ERROR, "Couldn't save", it, onDismiss = vm::dismissMessages) }
            val saved = state.saved
            val selected = state.selected
            when {
                saved != null -> SavedResultCard(saved, onSaveAnother = onDone)
                selected != null -> LocalPreviewCard(
                    item = selected, destination = state.destinationLabel, saving = state.saving,
                    progress = state.progress, onSave = vm::save, onCancel = vm::cancelSave, onClose = null,
                )
                state.error == null -> MessageBanner(
                    BannerKind.INFO, "Nothing to save",
                    "Use your phone's Share button in another app and choose Story Saver to save an image or video here.",
                )
            }
        }
    }
}
