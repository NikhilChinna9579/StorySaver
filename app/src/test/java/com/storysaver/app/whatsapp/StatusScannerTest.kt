package com.storysaver.app.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory folder tree. Paths use "/" ; a trailing "/" marks a directory. */
private class FakeTree(entries: Map<String, Long>, private val sniffed: Map<String, String> = emptyMap()) : TreeReader {
    private val nodes = entries.keys.toSet()
    private val sizes = entries
    var queries = 0
        private set

    override fun children(parentId: String): List<TreeEntry> {
        queries++
        val prefix = if (parentId.isEmpty()) "" else "$parentId/"
        return nodes.asSequence()
            .filter { it.startsWith(prefix) && it.length > prefix.length }
            .map { it.removePrefix(prefix) }
            .map { it.substringBefore('/') to it.contains('/') }
            .distinct()
            .map { (name, isDir) ->
                val id = prefix + name
                TreeEntry(id, name, if (isDir) "vnd.android.document/directory" else "", modified = id.hashCode().toLong(),
                    size = if (isDir) 0 else sizes[id] ?: 1000, isDir = isDir)
            }.toList()
    }

    override fun sniff(id: String): String? = sniffed[id]
}

class StatusScannerTest {

    private val A = "Android/media/com.whatsapp/WhatsApp"

    private fun tree(vararg files: String, sizes: Map<String, Long> = emptyMap(), sniffed: Map<String, String> = emptyMap()) =
        FakeTree(files.associateWith { sizes[it] ?: 1000L }, sniffed)

    @Test fun grantingStatusesFolderDirectly() {
        val t = tree("$A/Media/.Statuses/a.jpg", "$A/Media/.Statuses/b.mp4")
        val r = StatusScanner.scan(t, "$A/Media/.Statuses", ".Statuses")
        assertEquals(setOf("a.jpg", "b.mp4"), r.files.map { it.name }.toSet())
    }

    @Test fun grantingWhatsAppFolderFindsStatusesInside() {
        val t = tree(
            "$A/Media/.Statuses/a.jpg", "$A/Media/.Statuses/b.mp4",
            "$A/Media/WhatsApp Images/chat.jpg", "$A/Media/WhatsApp Video/chat.mp4", "$A/Databases/msgstore.db",
        )
        val r = StatusScanner.scan(t, A, "WhatsApp")
        assertEquals(setOf("a.jpg", "b.mp4"), r.files.map { it.name }.toSet())
        assertEquals(listOf("WhatsApp/Media/.Statuses"), r.folders)
    }

    /** The bug: extra accounts (WhatsApp/accounts/<id>/Media/.Statuses) used to be missed. */
    @Test fun includesEveryAccountsStatuses() {
        val t = tree(
            "$A/Media/.Statuses/main1.mp4",
            "$A/accounts/1234567890/Media/.Statuses/second1.mp4",
            "$A/accounts/1234567890/Media/.Statuses/second2.jpg",
        )
        val r = StatusScanner.scan(t, A, "WhatsApp")
        assertEquals(setOf("main1.mp4", "second1.mp4", "second2.jpg"), r.files.map { it.name }.toSet())
        assertEquals(2, r.folders.size)
    }

    @Test fun grantingMediaParentCoversWhatsAppAndBusiness() {
        val t = tree(
            "Android/media/com.whatsapp/WhatsApp/Media/.Statuses/w.mp4",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses/b.mp4",
            "Android/media/com.other.app/Stuff/.Statuses/not-whatsapp.mp4",
        )
        val r = StatusScanner.scan(t, "Android/media", "media")
        assertEquals(setOf("w.mp4", "b.mp4"), r.files.map { it.name }.toSet())
    }

    @Test fun legacyRootLayout() {
        val t = tree("WhatsApp/Media/.Statuses/old.jpg")
        assertEquals(listOf("old.jpg"), StatusScanner.scan(t, "WhatsApp", "WhatsApp").files.map { it.name })
    }

    @Test fun skipsNomediaEmptyAndNonMediaButReportsThem() {
        val s = "$A/Media/.Statuses"
        val t = tree("$s/.nomedia", "$s/ok.mp4", "$s/partial.mp4", "$s/notes.txt", sizes = mapOf("$s/partial.mp4" to 0L))
        val r = StatusScanner.scan(t, s, ".Statuses")
        assertEquals(listOf("ok.mp4"), r.files.map { it.name })
        assertEquals(2, r.skipped.size)
        assertTrue(r.skipped.any { it.startsWith("partial.mp4") && it.contains("not finished") })
        assertTrue(r.skipped.any { it.startsWith("notes.txt") })
    }

    @Test fun identifiesFilesWithoutExtensionByContent() {
        val s = "$A/Media/.Statuses"
        val t = tree("$s/3A7F2C", "$s/9B11", sniffed = mapOf("$s/3A7F2C" to "video/mp4"))
        val r = StatusScanner.scan(t, s, ".Statuses")
        assertEquals(listOf("3A7F2C"), r.files.map { it.name })
        assertEquals("video/mp4", r.files.single().mime)
        assertEquals(1, r.skipped.size)
    }

    @Test fun duplicatesAcrossAccountsAreShownOnce() {
        val t = tree("$A/Media/.Statuses/same.mp4", "$A/accounts/42/Media/.Statuses/same.mp4")
        assertEquals(1, StatusScanner.scan(t, A, "WhatsApp").files.size)
    }

    @Test fun folderWithoutStatusesIsReadDirectly() {
        val t = tree("Download/picked/a.jpg")
        val r = StatusScanner.scan(t, "Download/picked", "picked")
        assertEquals(listOf("a.jpg"), r.files.map { it.name })
        assertEquals(listOf("picked"), r.folders)
    }

    @Test fun walkDoesNotEnterUnrelatedOrChatMediaFolders() {
        assertTrue(StatusScanner.isWalkable("WhatsApp"))
        assertTrue(StatusScanner.isWalkable("accounts"))
        assertTrue(StatusScanner.isWalkable("1234567890"))
        assertTrue(StatusScanner.isWalkable("WhatsApp Business"))
        assertTrue(!StatusScanner.isWalkable("WhatsApp Images"))
        assertTrue(!StatusScanner.isWalkable("DCIM"))
        // Chat media trees are large; make sure we don't list them.
        val many = (1..200).map { "$A/Media/WhatsApp Images/Sent/img$it.jpg" }.toTypedArray()
        val t = tree("$A/Media/.Statuses/a.jpg", *many)
        StatusScanner.scan(t, A, "WhatsApp")
        assertTrue("queries=${t.queries}", t.queries < 10)
    }
}
