package com.storysaver.app.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.storysaver.app.MainActivity
import com.storysaver.app.appContainer
import com.storysaver.app.ui.theme.StorySaverTheme

/**
 * "Share → Story Saver" target. Opens as a bottom sheet over the app the user came from
 * (Instagram, YouTube, Gallery, …) with download options for what was shared.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val input = parse(intent)
        if (input == null) {
            Toast.makeText(this, "There's nothing Story Saver can download in what was shared.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val container = appContainer
        setContent {
            StorySaverTheme {
                val vm: QuickSaveViewModel = viewModel(factory = viewModelFactory {
                    initializer { QuickSaveViewModel(input, container) }
                })
                QuickSaveSheet(
                    vm,
                    onClose = { finish() },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    },
                )
            }
        }
    }

    private fun parse(intent: Intent?): ShareInput? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val type = intent.type.orEmpty()
        if (type.startsWith("image/") || type.startsWith("video/")) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            return uri?.let { ShareInput.File(it, type) }
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return null
        return ShareInput.Text(text)
    }
}
