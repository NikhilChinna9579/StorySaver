package com.storysaver.app.ui.components

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember

/**
 * Runs the legacy WRITE_EXTERNAL_STORAGE request (Android 8–9 only) when [requested] flips to true.
 * Android 10+ never sets [requested], because MediaStore needs no permission there.
 */
@Composable
fun LegacyStoragePermissionEffect(requested: Boolean, onResult: (Boolean) -> Unit) {
    val callback by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        callback(granted)
    }
    LaunchedEffect(requested) {
        if (requested) launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}

/**
 * Returns a function to call right before starting a download: on Android 13+ it asks once for
 * permission to show download notifications. Downloads work either way.
 */
@Composable
fun rememberNotificationPermissionAsker(): () -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(context) {
        {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }
}
