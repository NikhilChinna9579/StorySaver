package com.storysaver.app.ui.components

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

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
