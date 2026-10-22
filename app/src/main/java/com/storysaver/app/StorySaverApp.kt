package com.storysaver.app

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.storysaver.app.data.DirectMediaDownloader
import com.storysaver.app.downloads.DownloadJobs
import com.storysaver.app.downloads.DownloadNotifications
import com.storysaver.app.data.HistoryRepository
import com.storysaver.app.data.MediaSaver
import com.storysaver.app.data.SettingsRepository
import com.storysaver.app.data.ShareInbox
import com.storysaver.app.data.StatusRepository
import com.storysaver.app.instagram.InstagramExtractor
import com.storysaver.app.youtube.ChunkedDownloader
import com.storysaver.app.youtube.YouTubeExtractor
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class StorySaverApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.downloader.clearCache()
        container.youtubeCacheDir.deleteRecursively()
        java.io.File(cacheDir, "jobs").deleteRecursively() // leftovers from a killed process
        DownloadNotifications.createChannel(this)
    }

    /** Coil loader that can also draw a frame from videos for thumbnails. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
}

/** Simple manual dependency container (no DI framework needed at this size). */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    private val app = appContext

    val settings = SettingsRepository(app)
    val history = HistoryRepository(app)
    val saver = MediaSaver(app, settings)
    val statuses = StatusRepository(app)
    val shareInbox = ShareInbox()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false) // never downgrade https -> http
        .build()

    val downloader = DirectMediaDownloader(httpClient, File(app.cacheDir, "downloads"))
    val instagram = InstagramExtractor(httpClient)
    val youtube = YouTubeExtractor(httpClient)

    /** Long media downloads: generous read timeout, chunked by [ChunkedDownloader]. */
    val chunkedDownloader = ChunkedDownloader(
        httpClient.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    )
    val youtubeCacheDir = File(app.cacheDir, "youtube")

    /** Background downloads (keep running when the app is closed). */
    val jobs = DownloadJobs(app, history)
}

val Context.appContainer: AppContainer
    get() = (applicationContext as StorySaverApp).container
