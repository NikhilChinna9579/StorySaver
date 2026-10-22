package com.storysaver.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stores the optional custom download folder and the WhatsApp status folder grant.
 * Both are Storage Access Framework tree URIs with persisted permissions.
 */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver

    private val _customDestination = MutableStateFlow(readUri(KEY_DEST))
    /** null = default (Pictures/StorySaver and Movies/StorySaver). */
    val customDestination: StateFlow<Uri?> = _customDestination.asStateFlow()

    private val _statusFolder = MutableStateFlow(readUri(KEY_STATUS))
    val statusFolder: StateFlow<Uri?> = _statusFolder.asStateFlow()

    /** Custom destination only if we still hold write permission for it. */
    fun usableCustomDestination(): Uri? =
        _customDestination.value?.takeIf { hasPersisted(it, write = true) }

    fun setCustomDestination(uri: Uri?) {
        val old = _customDestination.value
        if (uri != null) {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        if (old != null && old != uri && old != _statusFolder.value) release(old)
        prefs.edit { if (uri == null) remove(KEY_DEST) else putString(KEY_DEST, uri.toString()) }
        _customDestination.value = uri
    }

    fun setStatusFolder(uri: Uri?) {
        val old = _statusFolder.value
        if (old != null && old != uri && old != _customDestination.value) release(old)
        prefs.edit { if (uri == null) remove(KEY_STATUS) else putString(KEY_STATUS, uri.toString()) }
        _statusFolder.value = uri
    }

    fun hasPersisted(uri: Uri, write: Boolean = false): Boolean =
        resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && (!write || it.isWritePermission)
        }

    private fun release(uri: Uri) {
        try {
            resolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            try {
                resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) { }
        }
    }

    private fun readUri(key: String): Uri? = prefs.getString(key, null)?.let(Uri::parse)

    private companion object {
        const val KEY_DEST = "custom_destination"
        const val KEY_STATUS = "status_folder"
    }
}
