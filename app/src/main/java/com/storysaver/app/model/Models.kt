package com.storysaver.app.model

enum class MediaType(val label: String) {
    IMAGE("Image"),
    VIDEO("Video");
}

enum class Platform(val label: String, val tag: String) {
    WHATSAPP("WhatsApp Status", "WA"),
    INSTAGRAM("Instagram Story", "IG"),
    YOUTUBE("YouTube", "YT"),
    SHARED("Shared media", "SHARE");
}

/** Where a saved file physically lives — decides how it is deleted later. */
enum class StorageKind {
    /** Public Pictures/ or Movies/ folder, registered in MediaStore. */
    MEDIA_STORE,

    /** A folder the user picked via the Storage Access Framework. */
    DOCUMENT,
}

/** One successfully saved and verified file, as kept in download history. */
data class SavedMedia(
    val id: String,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val savedAt: Long,
    val source: Platform,
    val storage: StorageKind,
    val locationLabel: String,
) {
    val mediaType: MediaType
        get() = if (mimeType.startsWith("video/")) MediaType.VIDEO else MediaType.IMAGE
}
