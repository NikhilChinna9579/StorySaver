package com.storysaver.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.storysaver.app.model.MediaType
import com.storysaver.app.model.Platform
import com.storysaver.app.ui.components.SectionLabel
import com.storysaver.app.ui.components.SelectableCard
import com.storysaver.app.ui.theme.Accents

@Composable
fun HomeScreen(onContinue: (Platform, MediaType) -> Unit, modifier: Modifier = Modifier) {
    var mediaType by rememberSaveable { mutableStateOf<MediaType?>(null) }
    var platform by rememberSaveable { mutableStateOf<Platform?>(null) }
    val primary = MaterialTheme.colorScheme.primary

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Story Saver", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            "Save accessible stories and status media.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        QuickLinkBox()
        Spacer(Modifier.height(8.dp))
        Text(
            "Or choose step by step",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionLabel("1  ·  What do you want to save?")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectableCard(
                title = "Image", subtitle = "Photos & pictures", icon = Icons.Outlined.Image,
                selected = mediaType == MediaType.IMAGE, accent = primary,
                onClick = {
                    mediaType = MediaType.IMAGE
                    if (platform == Platform.YOUTUBE) platform = null // YouTube is video-only
                }, modifier = Modifier.weight(1f),
            )
            SelectableCard(
                title = "Video", subtitle = "Clips & recordings", icon = Icons.Outlined.Videocam,
                selected = mediaType == MediaType.VIDEO, accent = primary,
                onClick = { mediaType = MediaType.VIDEO }, modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(8.dp))
        SectionLabel("2  ·  Where is it from?")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectableCard(
                title = "WhatsApp Status", subtitle = "From your phone's status folder", icon = Icons.Outlined.Forum,
                selected = platform == Platform.WHATSAPP, accent = Accents.WhatsApp,
                onClick = { platform = Platform.WHATSAPP }, modifier = Modifier.weight(1f),
            )
            SelectableCard(
                title = "Instagram Story", subtitle = "Paste a reel or post link", icon = Icons.Outlined.PhotoCamera,
                selected = platform == Platform.INSTAGRAM, accent = Accents.Instagram,
                onClick = { platform = Platform.INSTAGRAM }, modifier = Modifier.weight(1f),
            )
        }
        if (mediaType == MediaType.VIDEO) {
            SelectableCard(
                title = "YouTube", subtitle = "Paste a video link and pick a resolution", icon = Icons.Outlined.OndemandVideo,
                selected = platform == Platform.YOUTUBE, accent = Accents.YouTube,
                onClick = { platform = Platform.YOUTUBE }, modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                "Choose Video to also download from YouTube.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(20.dp))
        val ready = mediaType != null && platform != null
        Button(
            onClick = { onContinue(platform!!, mediaType!!) },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(if (ready) "Continue" else "Choose a type and a source", style = MaterialTheme.typography.titleMedium)
        }
    }
}
