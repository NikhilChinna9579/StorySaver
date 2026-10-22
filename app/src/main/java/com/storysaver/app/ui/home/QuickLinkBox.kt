package com.storysaver.app.ui.home

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.storysaver.app.share.ShareActivity
import com.storysaver.app.share.ShareRouter
import com.storysaver.app.share.ShareTarget
import com.storysaver.app.ui.components.BannerKind
import com.storysaver.app.ui.components.MessageBanner
import com.storysaver.app.youtube.YouTubeLinks
import com.storysaver.app.youtube.YtLink

/**
 * "Paste any link" box: detects Instagram or YouTube and opens the same download window as
 * Share → Story Saver. The step-by-step Image/Video → source flow below it is unchanged.
 */
@Composable
fun QuickLinkBox(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var text by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }

    fun go(link: String) {
        val target = ShareRouter.route(link)
        if (target is ShareTarget.Unsupported) {
            message = target.message
        } else {
            message = null
            openDownloadSheet(context, link)
        }
    }

    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Have a link?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "Paste an Instagram or YouTube link — it's detected automatically.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; message = null },
                placeholder = { Text("Paste link here") },
                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                trailingIcon = {
                    if (text.isNotEmpty()) IconButton(onClick = { text = ""; message = null }) { Icon(Icons.Outlined.Close, "Clear") }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (text.isNotBlank()) go(text) }),
                modifier = Modifier.fillMaxWidth().testTag("quick_link"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        val clip = clipboard.getText()?.text.orEmpty().trim()
                        if (clip.isEmpty()) {
                            message = "Your clipboard is empty. Copy a link first (Share → Copy link)."
                        } else {
                            text = clip
                            go(clip)
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Paste")
                }
                Button(
                    onClick = { go(text) }, enabled = text.isNotBlank(),
                    modifier = Modifier.weight(1f).height(48.dp).testTag("quick_go"),
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text(detectedLabel(text))
                }
            }
            message?.let { MessageBanner(BannerKind.UNSUPPORTED, "Can't download this", it, modifier = Modifier.testTag("quick_message")) }
        }
    }
}

/** Button label hints at what was detected while typing. */
private fun detectedLabel(text: String): String = when {
    text.isBlank() -> "Download"
    YouTubeLinks.parse(text) is YtLink.Video -> "YouTube"
    ShareRouter.route(text) is ShareTarget.Instagram -> "Instagram"
    else -> "Download"
}

private fun openDownloadSheet(context: Context, link: String) {
    context.startActivity(
        Intent(context, ShareActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, link)
    )
}
