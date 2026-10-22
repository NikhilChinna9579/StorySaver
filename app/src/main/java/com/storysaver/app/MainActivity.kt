package com.storysaver.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.storysaver.app.data.SharedItem
import com.storysaver.app.ui.navigation.StorySaverNavHost
import com.storysaver.app.ui.theme.StorySaverTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShareIntent(intent)
        val container = appContainer
        setContent {
            StorySaverTheme {
                StorySaverNavHost(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val type = intent.type.orEmpty()
        val inbox = appContainer.shareInbox
        when {
            type.startsWith("image/") || type.startsWith("video/") -> {
                streamExtra(intent)?.let { inbox.offer(SharedItem.Media(it, type)) }
            }
            type.startsWith("text/") -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let {
                    inbox.offer(SharedItem.Text(it))
                }
            }
        }
    }

    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
}
