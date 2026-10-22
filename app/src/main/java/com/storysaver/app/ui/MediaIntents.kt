package com.storysaver.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Open / share helpers for saved (content://) media. */
object MediaIntents {

    fun open(context: Context, uri: Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launch(context, Intent.createChooser(intent, "Open with"), "No app found that can open this file.")
    }

    fun share(context: Context, uri: Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launch(context, Intent.createChooser(intent, "Share via"), "No app found to share with.")
    }

    /** Shares several files at once (e.g. selected downloads). */
    fun shareMany(context: Context, items: List<Pair<Uri, String>>) {
        if (items.isEmpty()) return
        if (items.size == 1) return share(context, items[0].first, items[0].second)
        val types = items.map { it.second.substringBefore('/') }.distinct()
        val type = if (types.size == 1) "${types[0]}/*" else "*/*"
        val uris = ArrayList(items.map { it.first })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType(type)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // ClipData carries the read permission for every file to the receiving app.
        val clip = android.content.ClipData.newRawUri(null, uris[0])
        uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
        intent.clipData = clip
        launch(context, Intent.createChooser(intent, "Share ${items.size} files"), "No app found to share with.")
    }

    private fun launch(context: Context, intent: Intent, failure: String) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, failure, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, "This file is no longer accessible.", Toast.LENGTH_SHORT).show()
        }
    }
}
