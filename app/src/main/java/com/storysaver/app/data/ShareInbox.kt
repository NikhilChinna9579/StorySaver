package com.storysaver.app.data

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** Something another app handed us through the system share sheet. */
sealed interface SharedItem {
    data class Text(val text: String) : SharedItem
    data class Media(val uri: Uri, val mimeType: String?) : SharedItem
}

/** One-slot mailbox between MainActivity (receives the intent) and the screen that handles it. */
class ShareInbox {
    private val _pending = MutableStateFlow<SharedItem?>(null)
    val pending: StateFlow<SharedItem?> = _pending.asStateFlow()

    fun offer(item: SharedItem) {
        _pending.value = item
    }

    fun take(): SharedItem? = _pending.getAndUpdate { null }
}
