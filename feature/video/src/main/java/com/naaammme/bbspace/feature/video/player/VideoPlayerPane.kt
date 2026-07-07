package com.naaammme.bbspace.feature.video.player

import android.media.AudioManager
import android.os.BatteryManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.R as Media3UiR
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.naaammme.bbspace.infra.player.danmaku.DanmakuLayer
import com.naaammme.bbspace.infra.player.danmaku.DanmakuOverlayState
import com.naaammme.bbspace.infra.player.danmaku.rememberDanmakuOverlayState
import com.naaammme.bbspace.infra.player.PlayerViewTargetBinder
import com.naaammme.bbspace.core.model.PlaybackAudio
import com.naaammme.bbspace.core.model.PlayerSettingsState
import com.naaammme.bbspace.core.model.QualityOption
import com.naaammme.bbspace.core.model.VideoPlaybackState
import com.naaammme.bbspace.feature.video.detail.QualityOptionItem
import com.naaammme.bbspace.feature.video.VideoViewModel
import com.naaammme.bbspace.feature.video.formatDuration
import com.naaammme.bbspace.feature.video.formatPlaybackTime
import com.naaammme.bbspace.feature.video.formatSpeed
import com.naaammme.bbspace.feature.video.getAudioName
import com.naaammme.bbspace.feature.video.getQualityName
import com.naaammme.bbspace.feature.video.speedOps
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class PlayerVideoResizeMode {
    Fit,
    Zoom,
    Fill
}

private enum class PlayerDialog {
    Quality,
    Audio,
    Speed,
    SleepTimer
}

internal val LocalVideoResizeModeState = compositionLocalOf<MutableState<PlayerVideoResizeMode>> {
    error("Missing player video resize mode state")
}

@Suppress("UnsafeOptInUsageError")
@UnstableApi
@Composable
internal fun VideoPlayerPane(
    modifier: Modifier,
    viewModel: VideoViewModel,
    videoTitle: String?,
    isFull: Boolean,
    onToggleFull: () -> Unit,
    onBackClick: () -> Unit,
    onGoHome: () -> Unit
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val timeFmt = remember { android.text.format.DateFormat.getTimeFormat(context) }
    val state by viewModel.videoState.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val settingsState by viewModel.settingsState.collectAsStateWithLifecycle(initialValue = PlayerSettingsState())
    var activeDialog by remember { mutableStateOf<PlayerDialog?>(null) }
    var showPlaybackSheet by remember { mutableStateOf(false) }
    var showCtrl by remember { mutableStateOf(false) }
    val sleepTimerState by viewModel.sleepTimerState.collectAsStateWithLifecycle()
    val videoResizeMode = rememberSaveable { mutableStateOf(PlayerVideoResizeMode.Fit) }
    val topMetaText = remember(showCtrl, isFull) {
        if (showCtrl && isFull) {
            readPlayerTopMetaText(context, timeFmt)
        } else {
            null
        }
    }
    val danmakuOn = settingsState.danmaku.enabled
    val danmakuOverlayState = if (danmakuOn) {
        rememberDanmakuOverlayState(
            initialConfig = settingsState.danmaku,
            initialPositionMs = viewModel.playbackProgress.value.positionMs,
            initialIsPlaying = state.isPlaying,
            initialSpeed = state.speed
        )
    } else {
        null
    }
    val videoAspect = remember(state.currentStream) {
        val width = state.currentStream?.width?.takeIf { it > 0 } ?: return@remember null
        val height = state.currentStream?.height?.takeIf { it > 0 } ?: return@remember null
        width.toFloat() / height.toFloat()
    }
    val resizeMode = remember(isFull, videoResizeMode.value) {
        if (!isFull) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        } else {
            when (videoResizeMode.value) {
                PlayerVideoResizeMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                PlayerVideoResizeMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                PlayerVideoResizeMode.Fill -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
        }
    }
    val playerView = remember(context) {
        PlayerView(context).apply {
            useController = false
            setEnableComposeSurfaceSyncWorkaround(true)
            setKeepContentOnPlayerReset(true)
        }
    }
    var lastWarmAspect by remember(playerView) { mutableStateOf<Float?>(null) }

    LaunchedEffect(state.playWhenReady) {
        playerView.keepScreenOn = state.playWhenReady
    }

    DisposableEffect(owner, playerView, player) {
        val lifecycle = owner.lifecycle
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            PlayerViewTargetBinder.bind(playerView, player)
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> PlayerViewTargetBinder.bind(playerView, player)
                Lifecycle.Event.ON_STOP -> PlayerViewTargetBinder.unbind(playerView)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            PlayerViewTargetBinder.unbind(playerView)
        }
    }

    CompositionLocalProvider(LocalVideoResizeModeState provides videoResizeMode) {
        Box(
            modifier = modifier
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { playerView },
                update = { view ->
                    val content = view.findViewById<AspectRatioFrameLayout>(Media3UiR.id.exo_content_frame)
                    if (content != null) {
                        content.resizeMode = resizeMode
                        if (lastWarmAspect != videoAspect) {
                            content.setAspectRatio(videoAspect ?: 0f)
                            lastWarmAspect = videoAspect
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (danmakuOverlayState != null) {
                VideoDanmakuLayer(
                    viewModel = viewModel,
                    state = state,
                    settingsState = settingsState,
                    danmakuOverlayState = danmakuOverlayState,
                    playerView = playerView
                )
            }

            VideoPlayerOverlay(
                viewModel = viewModel,
                state = state,
                player = player,
                settingsState = settingsState,
                isFull = isFull,
                showCtrl = showCtrl,
                activeDialog = activeDialog,
                showPlaybackSheet = showPlaybackSheet,
                onShowCtrlChange = { showCtrl = it },
                onShowA = {
                    showCtrl = true
                    activeDialog = PlayerDialog.Audio
                },
                onShowQ = {
                    showCtrl = true
                    activeDialog = PlayerDialog.Quality
                },
                onShowSp = {
                    showCtrl = true
                    activeDialog = PlayerDialog.Speed
                },
                onShowTimer = {
                    showCtrl = true
                    activeDialog = PlayerDialog.SleepTimer
                },
                onToggleFull = {
                    showCtrl = true
                    onToggleFull()
                }
            )

            if (showCtrl) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.54f),
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(horizontal = 10.dp, vertical = 1.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = Color.White
                            )
                        }
                        if (!isFull) {
                            IconButton(onClick = onGoHome) {
                                Icon(
                                    imageVector = Icons.Default.Home,
                                    contentDescription = "首页",
                                    tint = Color.White
                                )
                            }
                        }
                        if (isFull) {
                            Column(modifier = Modifier.weight(1f)) {
                                videoTitle?.takeIf(String::isNotBlank)?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                topMetaText?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White.copy(alpha = 0.8f),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (danmakuOn) "弹幕" else "弹幕关",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            modifier = Modifier
                                .clickable {
                                    showCtrl = true
                                    viewModel.updateDanmaku(
                                        settingsState.danmaku.copy(enabled = !settingsState.danmaku.enabled)
                                    )
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                        IconButton(
                            onClick = {
                                showCtrl = true
                                showPlaybackSheet = true
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "更多信息",
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            if (isFull && showPlaybackSheet) {
                VideoPlaybackSidebar(
                    state = state,
                    viewModel = viewModel,
                    onDismiss = { showPlaybackSheet = false },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxSize()
                )
            }
        }

        if (activeDialog == PlayerDialog.Quality) {
            val src = state.playbackSource
            if (src != null) {
                QualitySelectionDialog(
                    options = src.qualityOptions,
                    curQuality = state.currentStream?.quality,
                    onDismiss = { activeDialog = null },
                    onSelect = { quality ->
                        viewModel.switchQuality(quality)
                        activeDialog = null
                    }
                )
            }
        }

        if (activeDialog == PlayerDialog.Audio) {
            val src = state.playbackSource
            if (src != null) {
                AudioSelectionDialog(
                    audios = src.audios,
                    curAudioId = state.currentAudio?.id,
                    onDismiss = { activeDialog = null },
                    onSelect = { audioId ->
                        viewModel.switchAudio(audioId)
                        activeDialog = null
                    }
                )
            }
        }

        if (activeDialog == PlayerDialog.Speed) {
            SpeedSelectionDialog(
                curSpeed = state.speed,
                onDismiss = { activeDialog = null },
                onSelect = { speed ->
                    viewModel.setSpeed(speed)
                    activeDialog = null
                }
            )
        }

        if (activeDialog == PlayerDialog.SleepTimer) {
            SleepTimerSelectionDialog(
                timerActive = sleepTimerState.isActive,
                remainingMs = sleepTimerState.remainingMs,
                onDismiss = { activeDialog = null },
                onStart = { minutes ->
                    viewModel.startSleepTimer(minutes)
                    activeDialog = null
                },
                onCancel = {
                    viewModel.cancelSleepTimer()
                    activeDialog = null
                }
            )
        }

        if (!isFull && showPlaybackSheet) {
            VideoPlaybackSheet(
                state = state,
                viewModel = viewModel,
                limitUnderPlayer = true,
                onDismiss = { showPlaybackSheet = false }
            )
        }
    }
}

@UnstableApi
@Composable
private fun VideoDanmakuLayer(
    viewModel: VideoViewModel,
    state: VideoPlaybackState,
    settingsState: PlayerSettingsState,
    danmakuOverlayState: DanmakuOverlayState,
    playerView: PlayerView
) {
    val danmakuState by viewModel.danmakuState.collectAsStateWithLifecycle()

    DanmakuLayer(
        playerView = playerView,
        overlayState = danmakuOverlayState,
        danmakuState = danmakuState,
        danmakuConfig = settingsState.danmaku,
        positionMs = viewModel.playbackProgress.value.positionMs,
        isPlaying = state.isPlaying,
        speed = state.speed,
        seekEventId = state.seekEventId,
        hasSource = state.playbackSource != null
    )
}

@Composable
private fun VideoPlayerOverlay(
    viewModel: VideoViewModel,
    state: VideoPlaybackState,
    player: Player?,
    settingsState: PlayerSettingsState,
    isFull: Boolean,
    showCtrl: Boolean,
    activeDialog: PlayerDialog?,
    showPlaybackSheet: Boolean,
    onShowCtrlChange: (Boolean) -> Unit,
    onShowA: () -> Unit,
    onShowQ: () -> Unit,
    onShowSp: () -> Unit,
    onShowTimer: () -> Unit,
    onToggleFull: () -> Unit
) {
    val context = LocalContext.current
    val gestureState = remember { VideoGestureState() }
    var dragMs by remember { mutableStateOf<Long?>(null) }
    var dragStartBrightness by remember { mutableFloatStateOf(0.5f) }
    var dragStartVolumeFrac by remember { mutableFloatStateOf(0f) }
    var speedBeforeGesture by remember { mutableFloatStateOf(1f) }
    var lastGestureBrightness by remember { mutableFloatStateOf(Float.NaN) }
    var lastGestureVolumeFrac by remember { mutableFloatStateOf(Float.NaN) }
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val act = LocalActivity.current

    LaunchedEffect(
        showCtrl,
        state.isPlaying,
        dragMs,
        gestureState.dragType,
        gestureState.showSpeedBadge,
        activeDialog,
        showPlaybackSheet
    ) {
        if (
            showCtrl &&
            state.isPlaying &&
            dragMs == null &&
            gestureState.dragType == DragType.None &&
            !gestureState.showSpeedBadge &&
            activeDialog == null &&
            !showPlaybackSheet
        ) {
            delay(2_000)
            onShowCtrlChange(false)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .videoGestures(
                    state = gestureState,
                    onToggleControls = { onShowCtrlChange(!showCtrl) },
                    onTogglePlay = viewModel::togglePlayPause,
                    onSeekTo = viewModel::seekTo,
                    onStartSpeedUp = {
                        speedBeforeGesture = state.speed
                        val speed = settingsState.playback.gestureSpeed
                        viewModel.setSpeed(speed)
                        formatSpeed(speed)
                    },
                    onStopSpeedUp = { viewModel.setSpeed(speedBeforeGesture) },
                    onBrightnessDelta = { deltaFrac ->
                        val next = (dragStartBrightness + deltaFrac).coerceIn(0.01f, 1f)
                        if (abs(next - lastGestureBrightness) >= 0.02f || lastGestureBrightness.isNaN()) {
                            adjustWindowBrightness(act, next)
                            lastGestureBrightness = next
                        }
                        next
                    },
                    onVolumeDelta = { deltaFrac ->
                        val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
                        if (maxVol <= 0) {
                            0f
                        } else {
                            val next = (dragStartVolumeFrac + deltaFrac).coerceIn(0f, 1f)
                            if (abs(next - lastGestureVolumeFrac) >= (1f / maxVol.toFloat()) || lastGestureVolumeFrac.isNaN()) {
                                val newVol = (next * maxVol).roundToInt()
                                adjustStreamVolume(audioManager, newVol)
                                lastGestureVolumeFrac = next
                            }
                            next
                        }
                    },
                    onSwipeUp = {
                        onShowCtrlChange(true)
                        onToggleFull()
                    },
                    onDragStart = { dragType ->
                        when (dragType) {
                            DragType.Brightness -> {
                                dragStartBrightness = act?.window?.attributes?.screenBrightness
                                    ?.takeIf { it in 0f..1f }
                                    ?.coerceIn(0.01f, 1f)
                                    ?: 0.5f
                                lastGestureBrightness = Float.NaN
                            }
                            DragType.Volume -> {
                                val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
                                val curVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                                dragStartVolumeFrac = if (maxVol > 0) {
                                    curVol.toFloat() / maxVol.toFloat()
                                } else {
                                    0f
                                }
                                lastGestureVolumeFrac = Float.NaN
                            }
                            else -> Unit
                        }
                    },
                    isPlaying = { state.isPlaying },
                    positionMs = {
                        val progress = viewModel.playbackProgress.value
                        dragMs
                            ?: gestureState.dragSeekPosMs
                            ?: progress.positionMs
                    },
                    durationMs = {
                        val progress = viewModel.playbackProgress.value
                        player?.duration
                            ?.takeIf { it > 0L }
                            ?: progress.durationMs.takeIf { it > 0L }
                            ?: state.playbackSource?.durationMs?.coerceAtLeast(0L)
                            ?: 0L
                    }
                )
        )

        VideoGestureFeedback(gestureState)

        if (showCtrl) {
            PlayerCtrlBarHost(
                viewModel = viewModel,
                state = state,
                player = player,
                dragMs = { dragMs },
                gestureSeekMs = gestureState.dragSeekPosMs,
                onDragMsChange = { dragMs = it },
                onShowCtrlChange = onShowCtrlChange,
                onShowA = onShowA,
                onShowQ = onShowQ,
                onShowSp = onShowSp,
                onShowTimer = onShowTimer,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun PlayerCtrlBarHost(
    viewModel: VideoViewModel,
    state: VideoPlaybackState,
    player: Player?,
    dragMs: () -> Long?,
    gestureSeekMs: Long?,
    onDragMsChange: (Long?) -> Unit,
    onShowCtrlChange: (Boolean) -> Unit,
    onShowA: () -> Unit,
    onShowQ: () -> Unit,
    onShowSp: () -> Unit,
    onShowTimer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress by viewModel.playbackProgress.collectAsStateWithLifecycle()
    val durationMs = player?.duration
        ?.takeIf { it > 0L }
        ?: progress.durationMs.takeIf { it > 0L }
        ?: state.playbackSource?.durationMs?.coerceAtLeast(0L)
        ?: 0L
    val dragSeekMs = dragMs()
    val barMs = dragSeekMs ?: gestureSeekMs ?: progress.positionMs
    val sliderVal = if (durationMs > 0L) {
        (barMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val sleepTimerState by viewModel.sleepTimerState.collectAsStateWithLifecycle()
    val timerText = if (sleepTimerState.isActive) {
        formatDuration(sleepTimerState.remainingMs)
    } else {
        "定时"
    }
    val timerOn = sleepTimerState.isActive

    PlayerCtrlBar(
        timeText = formatPlaybackTime(barMs, durationMs),
        audioText = state.currentAudio?.let { getAudioName(it.id, short = true) } ?: "音频",
        qualityText = getQualityName(state.playbackSource, state.currentStream),
        speedText = formatSpeed(state.speed),
        loopText = if (state.isLooping) "循环" else "单次",
        timerText = timerText,
        timerOn = timerOn,
        sliderVal = sliderVal,
        sliderOn = durationMs > 0L,
        audioOn = (state.playbackSource?.audios?.size ?: 0) > 1,
        qualityOn = (state.playbackSource?.qualityOptions?.size ?: 0) > 1,
        onAudioClick = onShowA,
        onQualityClick = onShowQ,
        onSpeedClick = onShowSp,
        onLoopClick = viewModel::toggleLooping,
        onTimerClick = onShowTimer,
        onSeekChange = { frac ->
            onShowCtrlChange(true)
            onDragMsChange((durationMs * frac).toLong())
        },
        onSeekDone = {
            val next = dragMs() ?: return@PlayerCtrlBar
            viewModel.seekTo(next)
            onDragMsChange(null)
        },
        modifier = modifier
    )
}

@Composable
private fun PlayerCtrlBar(
    timeText: String,
    audioText: String,
    qualityText: String,
    speedText: String,
    loopText: String,
    timerText: String,
    timerOn: Boolean,
    sliderVal: Float,
    sliderOn: Boolean,
    audioOn: Boolean,
    qualityOn: Boolean,
    onAudioClick: () -> Unit,
    onQualityClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onLoopClick: () -> Unit,
    onTimerClick: () -> Unit,
    onSeekChange: (Float) -> Unit,
    onSeekDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.54f)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Slider(
                value = sliderVal,
                onValueChange = onSeekChange,
                onValueChangeFinished = onSeekDone,
                enabled = sliderOn,
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.24f),
                    disabledThumbColor = Color.White.copy(alpha = 0.24f),
                    disabledActiveTrackColor = Color.White.copy(alpha = 0.16f),
                    disabledInactiveTrackColor = Color.White.copy(alpha = 0.1f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = timeText,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.widthIn(min = 64.dp)
                )
                CtrlBtn(
                    text = audioText,
                    on = audioOn,
                    onClick = onAudioClick,
                    modifier = Modifier.weight(1f)
                )
                CtrlBtn(
                    text = qualityText,
                    on = qualityOn,
                    onClick = onQualityClick,
                    modifier = Modifier.weight(1f)
                )
                CtrlBtn(
                    text = speedText,
                    on = true,
                    onClick = onSpeedClick,
                    modifier = Modifier.weight(1f)
                )
                CtrlBtn(
                    text = loopText,
                    on = true,
                    onClick = onLoopClick,
                    modifier = Modifier.weight(1f)
                )
                CtrlBtn(
                    text = timerText,
                    on = true,
                    onClick = onTimerClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun CtrlBtn(
    text: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (on) {
        Color.White.copy(alpha = 0.14f)
    } else {
        Color.White.copy(alpha = 0.08f)
    }
    val fg = if (on) {
        Color.White
    } else {
        Color.White.copy(alpha = 0.45f)
    }

    Box(
        modifier = modifier
            .heightIn(min = 28.dp)
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .clickable(enabled = on, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun QualitySelectionDialog(
    options: List<QualityOption>,
    curQuality: Int?,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择画质") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(options, key = { it.quality }) { option ->
                    QualityOptionItem(
                        option = option,
                        isSelected = option.quality == curQuality,
                        onClick = { onSelect(option.quality) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun AudioSelectionDialog(
    audios: List<PlaybackAudio>,
    curAudioId: Int?,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择音频") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(audios, key = { it.id }) { audio ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(audio.id) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = getAudioName(audio.id),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (audio.id == curAudioId) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun SpeedSelectionDialog(
    curSpeed: Float,
    onDismiss: () -> Unit,
    onSelect: (Float) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("播放速度") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(speedOps, key = { it }) { speed ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(speed) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatSpeed(speed),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (speed == curSpeed) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun readPlayerTopMetaText(
    context: android.content.Context,
    timeFmt: java.text.DateFormat
): String {
    val time = timeFmt.format(System.currentTimeMillis())
    val battery = context.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        ?.takeIf { it in 0..100 }
    return if (battery != null) "$time  ${battery}%" else time
}

@Composable
private fun SleepTimerSelectionDialog(
    timerActive: Boolean,
    remainingMs: Long,
    onDismiss: () -> Unit,
    onStart: (Int) -> Unit,
    onCancel: () -> Unit
) {
    var customMinutes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定时暂停播放") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (timerActive) {
                    Text(
                        text = "将在 ${formatDuration(remainingMs)} 后暂停播放",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presetTimeButton("10分钟", 10, onStart, Modifier.weight(1f))
                    presetTimeButton("15分钟", 15, onStart, Modifier.weight(1f))
                    presetTimeButton("30分钟", 30, onStart, Modifier.weight(1f))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customMinutes,
                        onValueChange = { value ->
                            customMinutes = value.filter { it.isDigit() }
                        },
                        label = { Text("自定义(分钟)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            val mins = customMinutes.toIntOrNull()
                            if (mins != null && mins > 0) {
                                onStart(mins)
                            }
                        },
                        enabled = customMinutes.toIntOrNull()?.let { it > 0 } == true
                    ) {
                        Text("确定")
                    }
                }

                if (timerActive) {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("取消定时", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun presetTimeButton(
    label: String,
    minutes: Int,
    onStart: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = { onStart(minutes) },
        modifier = modifier
    ) {
        Text(label)
    }
}

