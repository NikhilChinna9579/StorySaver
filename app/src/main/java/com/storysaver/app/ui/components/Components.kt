@file:OptIn(ExperimentalMaterial3Api::class)

package com.storysaver.app.ui.components

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.storysaver.app.model.MediaType
import com.storysaver.app.model.SavedMedia
import com.storysaver.app.ui.MediaIntents
import com.storysaver.app.ui.theme.Accents

@Composable
fun SelectableCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = if (selected) BorderStroke(2.dp, accent) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 132.dp),
        border = border,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Column {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, contentDescription = null, tint = accent) }
                Spacer(Modifier.padding(top = 12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) {
                Icon(
                    Icons.Outlined.CheckCircle, contentDescription = "Selected", tint = accent,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}

@Composable
fun MediaTypeToggle(selected: MediaType, onSelect: (MediaType) -> Unit, modifier: Modifier = Modifier) {
    val options = MediaType.entries
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, type ->
            SegmentedButton(
                selected = type == selected,
                onClick = { onSelect(type) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {
                    SegmentedButtonDefaults.Icon(active = type == selected) {
                        Icon(
                            if (type == MediaType.IMAGE) Icons.Outlined.Image else Icons.Outlined.Videocam,
                            contentDescription = null, modifier = Modifier.size(18.dp),
                        )
                    }
                },
            ) { Text(type.label) }
        }
    }
}

enum class BannerKind { INFO, ERROR, UNSUPPORTED, SUCCESS }

@Composable
fun MessageBanner(
    kind: BannerKind,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg, icon) = when (kind) {
        BannerKind.INFO -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, Icons.Outlined.Info)
        BannerKind.ERROR -> Triple(scheme.errorContainer, scheme.onErrorContainer, Icons.Outlined.ErrorOutline)
        BannerKind.UNSUPPORTED -> Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, Icons.Outlined.Info)
        BannerKind.SUCCESS -> Triple(Accents.Success.copy(alpha = 0.15f), scheme.onSurface, Icons.Outlined.CheckCircle)
    }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = if (kind == BannerKind.SUCCESS) Accents.Success else fg)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.padding(top = 4.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (onDismiss != null) {
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Dismiss") }
                }
            }
        }
    }
}

@Composable
fun ProgressBlock(label: String, progress: Float?, modifier: Modifier = Modifier, onCancel: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (progress != null) Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
        }
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (onCancel != null) {
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
        }
    }
}

/** Thumbnail/preview for an image or video (video shows its first frame + a play badge). */
@Composable
fun MediaThumb(model: Any, isVideo: Boolean, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    Box(modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant)) {
        SubcomposeAsyncImage(
            model = model,
            contentDescription = if (isVideo) "Video preview" else "Image preview",
            contentScale = contentScale,
            modifier = Modifier.matchParentSize(),
            error = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        if (isVideo) Icons.Outlined.Videocam else Icons.Outlined.Image,
                        contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
        if (isVideo) {
            Icon(
                Icons.Outlined.PlayCircle, contentDescription = null, tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(40.dp),
            )
        }
    }
}

/** Shown after a verified save: confirmation + Open / Share. */
@Composable
fun SavedResultCard(media: SavedMedia, onSaveAnother: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uri = Uri.parse(media.uri)
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Accents.Success.copy(alpha = 0.12f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Accents.Success)
                Spacer(Modifier.width(10.dp))
                Text("Saved", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "${media.displayName}\nin ${media.locationLabel}",
                style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { MediaIntents.open(context, uri, media.mimeType) }) {
                    Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Open")
                }
                FilledTonalButton(onClick = { MediaIntents.share(context, uri, media.mimeType) }) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Share")
                }
            }
            OutlinedButton(onClick = onSaveAnother, modifier = Modifier.fillMaxWidth()) { Text("Save another") }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold, modifier = modifier,
    )
}
