@file:OptIn(ExperimentalMaterial3Api::class)

package com.storysaver.app.ui.trim

import androidx.annotation.OptIn as AndroidxOptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.storysaver.app.media.TrimRange
import kotlinx.coroutines.delay

/** What the trim screen previews. */
data class TrimSource(
    /** https://, content:// or file:// */
    val mediaUrl: String,
    /** Separate sound stream to play along (YouTube above 360p). */
    val audioUrl: String? = null,
    /** User-Agent for http(s) URLs. */
    val userAgent: String? = null,
    /** False → shows a music icon instead of the picture. */
    val showVideo: Boolean = true,
    /** Duration if already known (YouTube), so times can be picked even if the preview fails. */
    val knownDurationMs: Long = 0,
)

/** Small "Trim" button placed next to a Download button. */
@Composable
fun TrimButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(Icons.Outlined.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp)); Text("Trim")
    }
}

/**
 * Full-screen trim screen: preview, pick start and end, then [onConfirm] with the range
 * (null = whole file) and whether to keep only the sound. Opens only when the user taps Trim.
 */
@AndroidxOptIn(UnstableApi::class)
@Composable
fun TrimDialog(
    source: TrimSource,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (range: TrimRange?, audioOnly: Boolean) -> Unit,
    offerAudioChoice: Boolean = false,
    initialAudioOnly: Boolean = false,
) {
    val context = LocalContext.current
    val player = remember(source) {
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
        source.userAgent?.let { http.setUserAgent(it) }
        val factory = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context, http))
        val main = factory.createMediaSource(MediaItem.fromUri(source.mediaUrl))
        val media = source.audioUrl?.let { MergingMediaSource(main, factory.createMediaSource(MediaItem.fromUri(it))) } ?: main
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(media)
            prepare()
            playWhenReady = false
        }
    }
    var durationMs by remember { mutableLongStateOf(source.knownDurationMs.coerceAtLeast(0)) }
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var start by remember { mutableLongStateOf(0L) }
    var end by remember { mutableLongStateOf(0L) }
    var audioOnly by remember { mutableStateOf(initialAudioOnly) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    ready = true
                    val d = player.duration
                    if (d != C.TIME_UNSET && d > 0) durationMs = d
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlayerError(error: PlaybackException) { failed = true }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.pause() }
    LaunchedEffect(durationMs) { if (durationMs > 0 && (end <= start || end > durationMs)) end = durationMs }
    // Keeps the position readout fresh and loops the preview inside the selection.
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            if (player.isPlaying && end > 0 && position >= end) { player.pause(); player.seekTo(start) }
            delay(100)
        }
    }

    val min = TrimRange.MIN_LENGTH_MS
    val canPick = durationMs >= min && end > start
    fun seek(ms: Long) { player.pause(); player.seekTo(ms) }
    fun setStart(ms: Long) { start = ms.coerceIn(0, (end - min).coerceAtLeast(0)); seek(start) }
    fun setEnd(ms: Long) { end = ms.coerceAtMost(durationMs).coerceAtLeast(minOf(start + min, durationMs)); seek(end) }
    val showPicture = source.showVideo && !audioOnly

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("trim_dialog"), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(if (showPicture) "Trim video" else "Trim audio") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss, modifier = Modifier.testTag("trim_close")) { Icon(Icons.Outlined.Close, "Close") }
                    },
                )
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // ---- Preview ----
                    Box(Modifier.fillMaxWidth().height(if (showPicture) 260.dp else 140.dp).background(Color.Black),
                        contentAlignment = Alignment.Center) {
                        if (showPicture) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        useController = false
                                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        this.player = player
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(72.dp))
                        }
                        if (!ready && !failed) CircularProgressIndicator()
                    }
                    if (failed) {
                        Text(
                            if (durationMs > 0) "The preview couldn't load, but you can still choose the start and end times."
                            else "This couldn't be loaded for trimming. Close this and download the whole file instead.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("trim_error"),
                        )
                    }

                    // ---- Play / position ----
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(
                            onClick = {
                                if (player.isPlaying) player.pause() else {
                                    if (position < start || position >= end - 150) player.seekTo(start)
                                    player.play()
                                }
                            },
                            enabled = ready && canPick,
                            modifier = Modifier.testTag("trim_play"),
                        ) { Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "Pause" else "Play selection") }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (durationMs > 0) "${TrimRange.clock(position)} / ${TrimRange.clock(durationMs)}" else "Loading…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    // ---- Range ----
                    if (canPick) {
                        RangeSlider(
                            value = start.toFloat()..end.toFloat(),
                            onValueChange = { r ->
                                val ns = r.start.toLong()
                                val ne = r.endInclusive.toLong()
                                if (ne - ns < min) return@RangeSlider
                                if (ns != start) { start = ns; seek(ns) } else if (ne != end) { end = ne; seek(ne) }
                            },
                            valueRange = 0f..durationMs.toFloat(),
                            modifier = Modifier.fillMaxWidth().testTag("trim_slider"),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TimeEditor("Start", start, "trim_start", enabled = canPick,
                            onMinus = { setStart(start - STEP) }, onPlus = { setStart(start + STEP) })
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Length", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(TrimRange.clock((end - start).coerceAtLeast(0)), style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("trim_length"))
                        }
                        TimeEditor("End", end, "trim_end", enabled = canPick,
                            onMinus = { setEnd(end - STEP) }, onPlus = { setEnd(end + STEP) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { setStart(position) }, enabled = ready && canPick,
                            modifier = Modifier.weight(1f).testTag("trim_set_start")) { Text("Start here") }
                        TextButton(onClick = { setEnd(position) }, enabled = ready && canPick,
                            modifier = Modifier.weight(1f).testTag("trim_set_end")) { Text("End here") }
                    }

                    if (offerAudioChoice) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(selected = !audioOnly, onClick = { audioOnly = false },
                                shape = SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.testTag("trim_opt_video")) { Text("Video") }
                            SegmentedButton(selected = audioOnly, onClick = { audioOnly = true },
                                shape = SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.testTag("trim_opt_audio")) { Text("Audio only") }
                        }
                    }
                    Text(
                        if (showPicture) "Play the selection to check it. Videos are cut without re-encoding (no quality loss), " +
                            "so a clip starts at the nearest keyframe — sometimes up to a second or two before your start."
                        else "Play the selection to check it. The sound is cut without re-encoding (no quality loss).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { player.pause(); onConfirm(TrimRange.normalize(start, end, durationMs), audioOnly) },
                    enabled = canPick,
                    modifier = Modifier.fillMaxWidth().padding(20.dp).height(54.dp).testTag("trim_confirm"),
                ) {
                    Icon(Icons.Outlined.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (canPick) "$confirmLabel (${TrimRange.clock(end - start)})" else confirmLabel)
                }
            }
        }
    }
}

private const val STEP = 500L

@Composable
private fun TimeEditor(label: String, ms: Long, tag: String, enabled: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMinus, enabled = enabled, modifier = Modifier.size(36.dp).testTag("${tag}_minus")) {
                Text("−", style = MaterialTheme.typography.titleLarge)
            }
            Text(TrimRange.clock(ms), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                modifier = Modifier.testTag(tag))
            IconButton(onClick = onPlus, enabled = enabled, modifier = Modifier.size(36.dp).testTag("${tag}_plus")) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
