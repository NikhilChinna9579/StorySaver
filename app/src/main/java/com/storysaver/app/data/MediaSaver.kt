package com.storysaver.app.data

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.storysaver.app.model.MediaType
import com.storysaver.app.model.Platform
import com.storysaver.app.model.SavedMedia
import com.storysaver.app.model.StorageKind
import com.storysaver.app.util.FileNaming
import com.storysaver.app.util.MimeUtils
import com.storysaver.app.util.StreamCopier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Copies a readable source URI (content:// or file://) into the user's chosen destination:
 *  - Android 10+: MediaStore, Pictures/StorySaver or Movies/StorySaver (no permission needed)
 *  - Android 8–9: the same public folders via the legacy write permission
 *  - Any version: a user-picked SAF folder, if set in Settings
 * Every save is verified (size check) before it is reported as successful.
 */
class MediaSaver(private val context: Context, private val settings: SettingsRepository) {

    sealed interface Outcome {
        data class Saved(val media: SavedMedia) : Outcome
        data class Failed(val message: String) : Outcome
        data object NeedsStoragePermission : Outcome
    }

    sealed interface DeleteOutcome {
        data object Deleted : DeleteOutcome
        data class NeedsConsent(val intentSender: IntentSender) : DeleteOutcome
        data class Failed(val message: String) : DeleteOutcome
    }

    private val resolver get() = context.contentResolver

    /** True only on Android 8–9 when saving to the gallery without the write permission. */
    fun needsLegacyPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            settings.usableCustomDestination() == null &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    fun destinationLabel(type: MediaType): String =
        destinationLabel(if (type == MediaType.IMAGE) Kind.IMAGE else Kind.VIDEO)

    /** Where a file of this MIME type will be saved, e.g. "Music/StorySaver". */
    fun destinationLabelFor(mime: String): String = destinationLabel(kindOf(mime) ?: Kind.VIDEO)

    private fun destinationLabel(kind: Kind): String {
        settings.usableCustomDestination()?.let { return folderLabel(it) }
        return "${kind.directory}/$FOLDER"
    }

    /** Which public collection a file belongs in. */
    private enum class Kind(val label: String, val directory: String) {
        IMAGE("image", Environment.DIRECTORY_PICTURES),
        VIDEO("video", Environment.DIRECTORY_MOVIES),
        AUDIO("audio file", Environment.DIRECTORY_MUSIC);

        @RequiresApi(Build.VERSION_CODES.Q)
        fun collection(): Uri = when (this) {
            IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        fun legacyCollection(): Uri = when (this) {
            IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
    }

    private fun kindOf(mime: String): Kind? = when {
        MimeUtils.isAudio(mime) -> Kind.AUDIO
        MimeUtils.typeFromMime(mime) == MediaType.IMAGE -> Kind.IMAGE
        MimeUtils.typeFromMime(mime) == MediaType.VIDEO -> Kind.VIDEO
        else -> null
    }

    suspend fun save(
        source: Uri,
        mimeType: String,
        platform: Platform,
        onProgress: (Float?) -> Unit = {},
    ): Outcome = withContext(Dispatchers.IO) {
        val type = kindOf(mimeType)
            ?: return@withContext Outcome.Failed("Only images, videos and audio can be saved.")
        if (needsLegacyPermission()) return@withContext Outcome.NeedsStoragePermission

        val sourceMeta = UriInfo.meta(context, source)
        val custom = settings.usableCustomDestination()
        if (custom == null && sourceMeta.size > 0 && !hasGallerySpaceFor(sourceMeta.size)) {
            return@withContext Outcome.Failed(
                "Not enough free storage to save this ${type.label} " +
                    "(${com.storysaver.app.util.formatBytes(sourceMeta.size)} needed)."
            )
        }
        val name = FileNaming.generate(platform, mimeType, System.currentTimeMillis())

        try {
            when {
                custom != null -> saveToFolder(custom, source, name, mimeType, sourceMeta.size, platform, onProgress)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    saveToMediaStore(source, name, mimeType, type, sourceMeta.size, platform, onProgress)
                else -> saveLegacy(source, name, mimeType, type, sourceMeta.size, platform, onProgress)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileNotFoundException) {
            Outcome.Failed("The source file is no longer accessible. It may have been removed or its permission expired.")
        } catch (e: SecurityException) {
            Outcome.Failed("Permission to read or write this file was denied.")
        } catch (e: IOException) {
            Outcome.Failed(friendlyIo(e))
        } catch (e: IllegalArgumentException) {
            Outcome.Failed("The destination folder is no longer available. Choose it again in Settings.")
        }
    }

    // ---- Android 10+ -------------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun saveToMediaStore(
        source: Uri, name: String, mime: String, type: Kind, total: Long,
        platform: Platform, onProgress: (Float?) -> Unit,
    ): Outcome {
        val collection = type.collection()
        val relativePath = type.directory + "/" + FOLDER
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val item = resolver.insert(collection, values)
            ?: return Outcome.Failed("The gallery refused to create a new file.")

        val written = try {
            val w = writeInto(source, item, total, onProgress)
            resolver.update(item, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            w
        } catch (t: Throwable) {
            runCatching { resolver.delete(item, null, null) }
            throw t
        }
        return verifyAndRecord(item, written, name, mime, platform, StorageKind.MEDIA_STORE, relativePath) {
            runCatching { resolver.delete(item, null, null) }
        }
    }

    // ---- Android 8–9 -------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private suspend fun saveLegacy(
        source: Uri, name: String, mime: String, type: Kind, total: Long,
        platform: Platform, onProgress: (Float?) -> Unit,
    ): Outcome {
        val base = Environment.getExternalStoragePublicDirectory(type.directory)
        val dir = File(base, FOLDER)
        if (!dir.exists() && !dir.mkdirs()) return Outcome.Failed("Couldn't create the folder ${dir.path}.")
        val finalName = FileNaming.dedupe(name) { File(dir, it).exists() }
        val file = File(dir, finalName)

        val written = try {
            (resolver.openInputStream(source) ?: throw FileNotFoundException("source")).use { input ->
                FileOutputStream(file).use { output -> StreamCopier.copy(input, output, total, onProgress) }
            }
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
        if (written <= 0 || file.length() != written) {
            file.delete()
            return Outcome.Failed("The saved file could not be verified, so it was removed. Please try again.")
        }

        val uri = withTimeoutOrNull(10_000) { scan(file, mime) } ?: insertLegacy(file, finalName, mime, type)
            ?: return Outcome.Failed("The file was written to ${file.path} but couldn't be added to the gallery.")

        val media = SavedMedia(
            id = UUID.randomUUID().toString(), uri = uri.toString(), displayName = finalName,
            mimeType = mime, sizeBytes = written, savedAt = System.currentTimeMillis(),
            source = platform, storage = StorageKind.MEDIA_STORE,
            locationLabel = "${base.name}/$FOLDER",
        )
        return Outcome.Saved(media)
    }

    private suspend fun scan(file: File, mime: String): Uri? = suspendCancellableCoroutine { cont ->
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime)) { _, uri ->
            if (cont.isActive) cont.resume(uri)
        }
    }

    @Suppress("DEPRECATION")
    private fun insertLegacy(file: File, name: String, mime: String, type: Kind): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DATA, file.absolutePath)
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.SIZE, file.length())
        }
        val collection = type.legacyCollection()
        return runCatching { resolver.insert(collection, values) }.getOrNull()
    }

    // ---- User-chosen SAF folder --------------------------------------------------------------

    private suspend fun saveToFolder(
        tree: Uri, source: Uri, name: String, mime: String, total: Long,
        platform: Platform, onProgress: (Float?) -> Unit,
    ): Outcome {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: return Outcome.Failed("Couldn't create a file in your chosen folder. Pick another folder in Settings.")
        val written = try {
            writeInto(source, doc, total, onProgress)
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw t
        }
        return verifyAndRecord(doc, written, name, mime, platform, StorageKind.DOCUMENT, folderLabel(tree)) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
        }
    }

    // ---- Shared helpers ----------------------------------------------------------------------

    private suspend fun writeInto(source: Uri, target: Uri, total: Long, onProgress: (Float?) -> Unit): Long {
        val input = resolver.openInputStream(source) ?: throw FileNotFoundException("source")
        return input.use { i ->
            val output = resolver.openOutputStream(target, "w") ?: throw IOException("Cannot open destination")
            output.use { o -> StreamCopier.copy(i, o, total, onProgress) }
        }
    }

    private fun verifyAndRecord(
        uri: Uri, written: Long, fallbackName: String, mime: String, platform: Platform,
        storage: StorageKind, location: String, cleanup: () -> Unit,
    ): Outcome {
        val meta = UriInfo.meta(context, uri)
        val sizeOk = written > 0 && (meta.size <= 0 || meta.size == written)
        if (!sizeOk || !UriInfo.exists(context, uri)) {
            cleanup()
            return Outcome.Failed("The saved file could not be verified, so it was removed. Please try again.")
        }
        return Outcome.Saved(
            SavedMedia(
                id = UUID.randomUUID().toString(),
                uri = uri.toString(),
                displayName = meta.name ?: fallbackName,
                mimeType = mime,
                sizeBytes = written,
                savedAt = System.currentTimeMillis(),
                source = platform,
                storage = storage,
                locationLabel = location,
            )
        )
    }

    @Suppress("DEPRECATION")
    private fun hasGallerySpaceFor(bytes: Long): Boolean = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes > bytes + SPACE_MARGIN
    } catch (_: Exception) {
        true // can't tell — let the write itself fail if space runs out
    }

    private fun friendlyIo(e: IOException): String {
        val msg = e.message.orEmpty()
        return if (msg.contains("ENOSPC") || msg.contains("No space", ignoreCase = true)) {
            "Your device ran out of storage while saving. Free up some space and try again."
        } else {
            "Couldn't save the file${if (msg.isNotBlank()) ": $msg" else "."}"
        }
    }

    fun folderLabel(tree: Uri): String = runCatching {
        DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifEmpty { "Selected folder" }
    }.getOrDefault("Selected folder")

    // ---- Delete ------------------------------------------------------------------------------

    suspend fun delete(media: SavedMedia): DeleteOutcome = withContext(Dispatchers.IO) {
        val uri = Uri.parse(media.uri)
        if (!UriInfo.exists(context, uri)) return@withContext DeleteOutcome.Deleted
        try {
            when (media.storage) {
                StorageKind.DOCUMENT -> DocumentsContract.deleteDocument(resolver, uri)
                StorageKind.MEDIA_STORE -> resolver.delete(uri, null, null)
            }
            if (UriInfo.exists(context, uri)) {
                DeleteOutcome.Failed("Couldn't delete this file. Try deleting it from your gallery or file manager.")
            } else {
                DeleteOutcome.Deleted
            }
        } catch (e: SecurityException) {
            // Happens on Android 10+ after a reinstall: the app no longer "owns" the gallery entry.
            consentFor(e, uri, media.storage)
                ?: DeleteOutcome.Failed("Permission denied. Delete it from your gallery or file manager instead.")
        } catch (e: Exception) {
            DeleteOutcome.Failed("Couldn't delete this file: ${e.message ?: "unknown error"}")
        }
    }

    private fun consentFor(e: SecurityException, uri: Uri, storage: StorageKind): DeleteOutcome? {
        if (storage != StorageKind.MEDIA_STORE) return null
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> deleteRequest(uri)
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> recoverable(e)
            else -> null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun deleteRequest(uri: Uri): DeleteOutcome =
        DeleteOutcome.NeedsConsent(MediaStore.createDeleteRequest(resolver, listOf(uri)).intentSender)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun recoverable(e: SecurityException): DeleteOutcome? =
        (e as? RecoverableSecurityException)?.let { DeleteOutcome.NeedsConsent(it.userAction.actionIntent.intentSender) }

    companion object {
        const val FOLDER = "StorySaver"
        private const val SPACE_MARGIN = 2L * 1024 * 1024
    }
}
