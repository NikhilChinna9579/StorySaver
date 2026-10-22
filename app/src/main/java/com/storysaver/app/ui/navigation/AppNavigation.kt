package com.storysaver.app.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.storysaver.app.AppContainer
import com.storysaver.app.data.SharedItem
import com.storysaver.app.model.MediaType
import com.storysaver.app.model.Platform
import com.storysaver.app.ui.download.InstagramScreen
import com.storysaver.app.ui.download.InstagramViewModel
import com.storysaver.app.ui.download.LocalSaveViewModel
import com.storysaver.app.ui.download.SharedMediaScreen
import com.storysaver.app.ui.download.WhatsAppScreen
import com.storysaver.app.ui.download.YouTubeScreen
import com.storysaver.app.ui.download.YouTubeViewModel
import com.storysaver.app.youtube.YouTubeLinks
import com.storysaver.app.ui.downloads.DownloadsScreen
import com.storysaver.app.ui.downloads.DownloadsViewModel
import com.storysaver.app.ui.home.HomeScreen
import com.storysaver.app.ui.settings.SettingsScreen
import com.storysaver.app.util.MimeUtils
import com.storysaver.app.util.UrlCheck
import com.storysaver.app.util.UrlValidator

private object Routes {
    const val HOME = "home"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val SHARED = "shared"
    const val SOURCE = "source/{platform}/{type}"
    fun source(platform: Platform, type: MediaType) = "source/${platform.name}/${type.name}"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Outlined.Home),
    Tab(Routes.DOWNLOADS, "Downloads", Icons.Outlined.Download),
    Tab(Routes.SETTINGS, "Settings", Icons.Outlined.Settings),
)

@Composable
fun StorySaverNavHost(container: AppContainer) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val pending by container.shareInbox.pending.collectAsStateWithLifecycle()

    // Route anything shared into the app to the right screen; that screen's ViewModel consumes it.
    LaunchedEffect(pending) {
        when (val item = pending) {
            is SharedItem.Text -> {
                if (YouTubeLinks.parse(item.text) != null) {
                    nav.navigate(Routes.source(Platform.YOUTUBE, MediaType.VIDEO))
                } else {
                    val type = (UrlValidator.check(item.text) as? UrlCheck.DirectMedia)?.guessedType ?: MediaType.IMAGE
                    nav.navigate(Routes.source(Platform.INSTAGRAM, type))
                }
            }
            is SharedItem.Media -> nav.navigate(Routes.SHARED)
            null -> Unit
        }
    }

    Scaffold(
        // Each screen handles the status bar itself; outer padding = bottom bar only.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (route in tabs.map { it.route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { outer ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.fillMaxSize()) {
            composable(Routes.HOME) {
                HomeScreen(
                    onContinue = { p, t -> nav.navigate(Routes.source(p, t)) },
                    modifier = Modifier.padding(outer),
                )
            }
            composable(Routes.DOWNLOADS) {
                val vm: DownloadsViewModel = viewModel(factory = viewModelFactory { initializer { DownloadsViewModel(container) } })
                DownloadsScreen(vm, modifier = Modifier.padding(outer))
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(container, modifier = Modifier.padding(outer))
            }
            composable(
                Routes.SOURCE,
                arguments = listOf(
                    navArgument("platform") { type = NavType.StringType },
                    navArgument("type") { type = NavType.StringType },
                ),
            ) { entry ->
                val platform = runCatching { Platform.valueOf(entry.arguments?.getString("platform").orEmpty()) }
                    .getOrDefault(Platform.WHATSAPP)
                val type = runCatching { MediaType.valueOf(entry.arguments?.getString("type").orEmpty()) }
                    .getOrDefault(MediaType.IMAGE)
                when (platform) {
                    Platform.YOUTUBE -> {
                        val vm: YouTubeViewModel = viewModel(factory = viewModelFactory {
                            initializer { YouTubeViewModel(container) }
                        })
                        YouTubeScreen(vm, onBack = { nav.popBackStack() })
                    }
                    Platform.INSTAGRAM -> {
                        val vm: InstagramViewModel = viewModel(factory = viewModelFactory {
                            initializer { InstagramViewModel(container) }
                        })
                        InstagramScreen(vm, onBack = { nav.popBackStack() })
                    }
                    else -> {
                        val vm: LocalSaveViewModel = viewModel(factory = viewModelFactory {
                            initializer { LocalSaveViewModel(Platform.WHATSAPP, type, container) }
                        })
                        WhatsAppScreen(vm, container.statuses, onBack = { nav.popBackStack() })
                    }
                }
            }
            composable(Routes.SHARED) {
                val vm: LocalSaveViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        val mime = (container.shareInbox.pending.value as? SharedItem.Media)?.mimeType
                        LocalSaveViewModel(Platform.SHARED, MimeUtils.typeFromMime(mime) ?: MediaType.IMAGE, container)
                    }
                })
                SharedMediaScreen(vm, onDone = { if (!nav.popBackStack()) nav.navigate(Routes.HOME) })
            }
        }
    }
}

private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
