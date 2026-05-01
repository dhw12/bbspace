package com.naaammme.bbspace.core.data.player

import androidx.media3.common.Player
import com.naaammme.bbspace.core.common.AuthProvider
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.domain.live.LiveRepository
import com.naaammme.bbspace.core.domain.player.StreamPlaybackSession
import com.naaammme.bbspace.core.domain.player.VideoPlayerRepository
import com.naaammme.bbspace.core.domain.history.PlaybackHistoryRepository
import com.naaammme.bbspace.core.data.AppSettings
import com.naaammme.bbspace.core.model.LivePlaybackError
import com.naaammme.bbspace.core.model.LivePlaybackSource
import com.naaammme.bbspace.core.model.LivePlaybackViewState
import com.naaammme.bbspace.core.model.LiveRoute
import com.naaammme.bbspace.core.model.LiveRouteTool
import com.naaammme.bbspace.core.model.PlayBiz
import com.naaammme.bbspace.core.model.PlaybackControlMode
import com.naaammme.bbspace.core.model.PlaybackAudio
import com.naaammme.bbspace.core.model.PlaybackError
import com.naaammme.bbspace.core.model.PlaybackHistory
import com.naaammme.bbspace.core.model.PlaybackHistoryKey
import com.naaammme.bbspace.core.model.PlaybackHistoryMeta
import com.naaammme.bbspace.core.model.PlaybackRequest
import com.naaammme.bbspace.core.model.PlaybackSource
import com.naaammme.bbspace.core.model.PlaybackState
import com.naaammme.bbspace.core.model.PlaybackStream
import com.naaammme.bbspace.core.model.PlaybackViewState
import com.naaammme.bbspace.core.model.PlayerSessionState
import com.naaammme.bbspace.core.model.StreamPlaybackSessionState
import com.naaammme.bbspace.core.model.StreamPlaybackTarget
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.model.buildPlaybackCdns
import com.naaammme.bbspace.core.model.toPlayableParams
import com.naaammme.bbspace.infra.player.DecoderMode
import com.naaammme.bbspace.infra.player.EngineDiscontinuityReason
import com.naaammme.bbspace.infra.player.EnginePlaybackState
import com.naaammme.bbspace.infra.player.EngineSource
import com.naaammme.bbspace.infra.player.PlaybackSnapshot
import com.naaammme.bbspace.infra.player.PlayerConfig
import com.naaammme.bbspace.infra.player.PlayerEngine
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class StreamPlaybackSessionImpl @Inject constructor(
    private val videoRepository: VideoPlayerRepository,
    private val liveRepository: LiveRepository,
    private val appSettings: AppSettings,
    private val playerSettingsStore: PlayerSettingsStore,
    private val reporter: PlaybackReporter,
    private val authProvider: AuthProvider,
    private val playbackHistoryRepo: PlaybackHistoryRepository,
    private val playerEngine: PlayerEngine
) : StreamPlaybackSession {

    override val player: StateFlow<Player?> = playerEngine.player

    private val _currentTarget = MutableStateFlow<StreamPlaybackTarget?>(null)
    override val currentTarget: StateFlow<StreamPlaybackTarget?> = _currentTarget.asStateFlow()

    private val _sessionState = MutableStateFlow(StreamPlaybackSessionState())
    override val sessionState: StateFlow<StreamPlaybackSessionState> = _sessionState.asStateFlow()

    private val videoSession = MutableStateFlow(PlayerSessionState())
    private val _videoState = MutableStateFlow(PlaybackViewState())
    override val videoState: StateFlow<PlaybackViewState> = _videoState.asStateFlow()

    private val _liveState = MutableStateFlow(LivePlaybackViewState())
    override val liveState: StateFlow<LivePlaybackViewState> = _liveState.asStateFlow()

    private val _pageMeta = MutableStateFlow<PlaybackHistoryMeta?>(null)
    override val pageMeta: StateFlow<PlaybackHistoryMeta?> = _pageMeta.asStateFlow()

    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prepMu = Mutex()
    private val openId = AtomicLong(0L)
    private var lastVideoDiscontinuitySeq = 0L
    private var nextPlayWhenReady = true

    init {
        runtimeScope.launch {
            playerEngine.snapshot.collect { snapshot ->
                when (_currentTarget.value) {
                    is StreamPlaybackTarget.Video -> onVideoSnapshot(snapshot)
                    is StreamPlaybackTarget.Live -> onLiveSnapshot(snapshot)
                    null -> Unit
                }
            }
        }
    }

    override suspend fun prepare() {
        prepMu.withLock {
            val config = withContext(Dispatchers.IO) { buildPlayerConfig() }
            withContext(Dispatchers.Main.immediate) {
                playerEngine.updateConfig(config)
            }
        }
    }

    override fun openVideo(target: VideoTarget) {
        runtimeScope.launch {
            openVideoInternal(target)
        }
    }

    private suspend fun openVideoInternal(
        target: VideoTarget,
        seekToMs: Long? = null,
        preferredQuality: Int? = null,
        controlMode: PlaybackControlMode = PlaybackControlMode.Default
    ) {
        val request = target.toPlayableParams().getResolveParams(
            seekToMs = seekToMs,
            preferredQuality = preferredQuality,
            controlMode = controlMode
        )
        val state = videoSession.value
        val currentVideoTarget = (_currentTarget.value as? StreamPlaybackTarget.Video)?.target
        if (
            currentVideoTarget == target &&
            state.error == null &&
            (state.playbackSource != null || state.isPreparing)
        ) {
            _currentTarget.value = StreamPlaybackTarget.Video(target)
            return
        }

        val token = openId.incrementAndGet()
        finishCurrentPlayback(
            invalidateOpen = false,
            releasePlayer = false,
            nextTarget = StreamPlaybackTarget.Video(target)
        )
        nextPlayWhenReady = true
        _pageMeta.value = null
        reporter.bindOwner(token)
        videoSession.value = PlayerSessionState(
            isPreparing = true
        )
        _videoState.value = PlaybackViewState(isPreparing = true)
        _liveState.value = LivePlaybackViewState()
        syncSessionState()

        try {
            val openCfg = coroutineScope {
                val prepareJob = async { prepare() }
                val sourceJob = async { videoRepository.fetchPlaybackSource(request) }
                val localResumeJob = async(Dispatchers.IO) { readLocalResumeIfResolvable(request) }
                val qualityJob = async {
                    request.preferredQuality ?: appSettings.defaultVideoQuality.first()
                }
                val audioJob = async { appSettings.defaultAudioQuality.first() }
                val cdnJob = async { playerSettingsStore.playerCdnIndex.first() }

                prepareJob.await()
                OpenConfig(
                    source = sourceJob.await(),
                    preferredQuality = qualityJob.await(),
                    preferredAudioId = audioJob.await(),
                    preferredCdnIndex = cdnJob.await(),
                    localResume = localResumeJob.await()
                )
            }
            if (openId.get() != token) return

            val source = openCfg.source
            val stream = source.streams.firstOrNull { it.quality == openCfg.preferredQuality }
                ?: source.streams.firstOrNull()
            val audio = selectAudio(stream, source.audios, openCfg.preferredAudioId)
            val engineSource = buildVideoEngineSource(stream, audio, openCfg.preferredCdnIndex)
                ?: throw NoPlayableStreamException("暂无可用播放流")
            val startMs = resolveStartMs(request, source, openCfg.localResume)
            if (openId.get() != token) return

            videoSession.value = PlayerSessionState(
                playbackSource = source,
                currentStream = stream,
                currentAudio = audio,
                cdnIndex = engineSource.second,
                isPreparing = true
            )
            syncSessionState()
            playerEngine.setSource(
                source = engineSource.first,
                startPositionMs = startMs,
                playWhenReady = nextPlayWhenReady
            )
            if (openId.get() != token) return

            videoSession.value = PlayerSessionState(
                playbackSource = source,
                currentStream = stream,
                currentAudio = audio,
                cdnIndex = engineSource.second,
                isPreparing = false
            )
            syncSessionState()
            reporter.startSession(
                request = request,
                state = videoSession.value,
                startPositionMs = startMs ?: 0L
            )
        } catch (t: Throwable) {
            if (t is CancellationException) {
                if (openId.get() == token && videoSession.value.playbackSource == null) {
                    nextPlayWhenReady = true
                    videoSession.value = PlayerSessionState()
                    _videoState.value = PlaybackViewState()
                    syncSessionState()
                }
                throw t
            }
            if (openId.get() != token) return

            nextPlayWhenReady = true
            Logger.e(TAG, t) {
                "load playback source failed biz=${request.playable.biz.biz} " +
                    "aid=${request.videoId.aid} cid=${request.videoId.cid} " +
                    "epId=${request.playable.biz.epId} seasonId=${request.playable.biz.seasonId} " +
                    "q=${request.preferredQuality} msg=${t.message}"
            }
            videoSession.value = videoSession.value.copy(
                isPreparing = false,
                error = when (t) {
                    is NoPlayableStreamException -> PlaybackError.NoPlayableStream(
                        t.message ?: "暂无可用播放流"
                    )
                    else -> PlaybackError.RequestFailed(
                        t.message ?: "Failed to load playback source",
                        t
                    )
                }
            )
            _videoState.value = videoSession.value.toViewState(
                snapshot = latestSnapshot(),
                prev = _videoState.value,
                isNewSeekEvent = false
            )
            syncSessionState()
        }
    }

    override suspend fun openLive(
        route: LiveRoute,
        preferredQuality: Int,
        reportEntry: Boolean
    ) {
        val live = _currentTarget.value as? StreamPlaybackTarget.Live
        if (
            live?.route?.roomId == route.roomId &&
            (preferredQuality <= 0 || _liveState.value.playbackSource?.currentQn == preferredQuality) &&
            _liveState.value.error == null &&
            (_liveState.value.playbackSource != null || _liveState.value.isPreparing)
        ) {
            _currentTarget.value = StreamPlaybackTarget.Live(route)
            return
        }

        val token = openId.incrementAndGet()
        finishCurrentPlayback(
            invalidateOpen = false,
            releasePlayer = false,
            nextTarget = StreamPlaybackTarget.Live(route)
        )
        _pageMeta.value = null
        _videoState.value = PlaybackViewState()
        _liveState.value = LivePlaybackViewState(isPreparing = true)
        syncSessionState()

        try {
            prepare()
            val source = liveRepository.fetchPlaybackSource(route.roomId, preferredQuality)
            if (openId.get() != token) return

            playerEngine.setSource(
                source = EngineSource.LiveFlv(source.primaryUrl),
                playWhenReady = true
            )
            if (openId.get() != token) return

            _liveState.value = _liveState.value.copy(
                isPreparing = false,
                playbackSource = source,
                error = null
            )
            syncSessionState()
            if (reportEntry) {
                reportRoomEntryAction(
                    roomId = route.roomId,
                    jumpFrom = route.jumpFrom
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (openId.get() != token) return

            Logger.e(TAG, t) {
                "load live source failed roomId=${route.roomId} qn=$preferredQuality msg=${t.message}"
            }
            _liveState.value = _liveState.value.copy(
                isPreparing = false,
                error = t.toLiveError(),
                playbackSource = _liveState.value.playbackSource.takeIf { it?.roomId == route.roomId }
            )
            syncSessionState()
        }
    }

    override fun play() {
        nextPlayWhenReady = true
        playerEngine.play()
    }

    override fun pause() {
        nextPlayWhenReady = false
        playerEngine.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (_currentTarget.value !is StreamPlaybackTarget.Video) return
        playerEngine.seekTo(positionMs)
    }

    override fun setSpeed(speed: Float) {
        if (_currentTarget.value !is StreamPlaybackTarget.Video) return
        playerEngine.setSpeed(speed)
    }

    override fun switchVideoQuality(quality: Int) {
        if (_currentTarget.value !is StreamPlaybackTarget.Video) return
        val state = videoSession.value
        val snapshot = latestSnapshot()
        val source = state.playbackSource ?: return
        val stream = source.streams.firstOrNull { it.quality == quality } ?: return
        val audio = selectAudio(stream, source.audios, state.currentAudio?.id ?: 0)
        val engineSource = buildVideoEngineSource(stream, audio, state.cdnIndex) ?: return
        playerEngine.setSource(engineSource.first, snapshot.positionMs, snapshot.playWhenReady)
        videoSession.value = state.copy(
            currentStream = stream,
            currentAudio = audio,
            cdnIndex = engineSource.second
        )
        syncSessionState()
    }

    override fun switchVideoAudio(audioId: Int) {
        if (_currentTarget.value !is StreamPlaybackTarget.Video) return
        val state = videoSession.value
        val snapshot = latestSnapshot()
        val source = state.playbackSource ?: return
        val audio = source.audios.firstOrNull { it.id == audioId } ?: return
        val engineSource = buildVideoEngineSource(state.currentStream, audio, state.cdnIndex) ?: return
        playerEngine.setSource(engineSource.first, snapshot.positionMs, snapshot.playWhenReady)
        videoSession.value = state.copy(
            currentAudio = audio,
            cdnIndex = engineSource.second
        )
        syncSessionState()
    }

    override fun switchVideoCdn(index: Int) {
        if (_currentTarget.value !is StreamPlaybackTarget.Video) return
        val state = videoSession.value
        val snapshot = latestSnapshot()
        val engineSource = buildVideoEngineSource(state.currentStream, state.currentAudio, index) ?: return
        playerEngine.setSource(engineSource.first, snapshot.positionMs, snapshot.playWhenReady)
        videoSession.value = state.copy(cdnIndex = engineSource.second)
        syncSessionState()
        runtimeScope.launch {
            playerSettingsStore.updatePlayerCdnIndex(engineSource.second)
        }
    }

    override fun switchVideoPage(cid: Long) {
        val target = (_currentTarget.value as? StreamPlaybackTarget.Video)?.target as? VideoTarget.Ugc
            ?: return
        if (target.cid == cid) return
        val videoId = videoSession.value.playbackSource?.videoId
        val nextTarget = target.copy(
            aid = videoId?.aid?.takeIf { it > 0L } ?: target.aid,
            cid = cid,
            bvid = videoId?.bvid?.takeIf(String::isNotBlank) ?: target.bvid
        )
        val preferredQuality = videoSession.value.currentStream?.quality
        runtimeScope.launch {
            openVideoInternal(
                target = nextTarget,
                preferredQuality = preferredQuality
            )
        }
    }

    override fun switchLiveQuality(quality: Int) {
        val route = (_currentTarget.value as? StreamPlaybackTarget.Live)?.route ?: return
        if (_liveState.value.playbackSource?.currentQn == quality) return
        runtimeScope.launch {
            openLive(
                route = route,
                preferredQuality = quality,
                reportEntry = false
            )
        }
    }

    override fun updatePlaybackMeta(meta: PlaybackHistoryMeta?) {
        if (_currentTarget.value !is StreamPlaybackTarget.Video) return
        _pageMeta.value = meta
        reporter.updatePlaybackMeta(meta)
    }

    override fun close() {
        runtimeScope.launch {
            finishCurrentPlayback(
                invalidateOpen = true,
                releasePlayer = true
            )
        }
    }

    private suspend fun onVideoSnapshot(snapshot: PlaybackSnapshot) {
        val isNewSeekEvent = snapshot.discontinuitySeq != lastVideoDiscontinuitySeq &&
            snapshot.discontinuityReason.isSeek()
        lastVideoDiscontinuitySeq = snapshot.discontinuitySeq
        _videoState.value = videoSession.value.toViewState(
            snapshot = snapshot,
            prev = _videoState.value,
            isNewSeekEvent = isNewSeekEvent
        )
        syncSessionState()
        reporter.onPlaybackState(videoSession.value, snapshot)
    }

    private fun onLiveSnapshot(snapshot: PlaybackSnapshot) {
        _liveState.value = _liveState.value.copy(
            isPlaying = snapshot.isPlaying,
            playWhenReady = snapshot.playWhenReady,
            playbackState = snapshot.playbackState.toModelState(),
            videoWidth = snapshot.videoWidth,
            videoHeight = snapshot.videoHeight,
            hasRenderedFirstFrame = snapshot.firstFrameSeq > 0L,
            playerError = snapshot.errorMessage
        )
        syncSessionState()
    }

    private fun syncSessionState() {
        _sessionState.value = when (val target = _currentTarget.value) {
            is StreamPlaybackTarget.Video -> {
                val state = _videoState.value
                StreamPlaybackSessionState(
                    target = target,
                    isPreparing = state.isPreparing,
                    isPlaying = state.isPlaying,
                    playWhenReady = state.playWhenReady,
                    playbackState = state.playbackState,
                    videoWidth = state.videoWidth,
                    videoHeight = state.videoHeight,
                    hasRenderedFirstFrame = state.hasRenderedFirstFrame,
                    playerError = state.playerError
                )
            }

            is StreamPlaybackTarget.Live -> {
                val state = _liveState.value
                StreamPlaybackSessionState(
                    target = target,
                    isPreparing = state.isPreparing,
                    isPlaying = state.isPlaying,
                    playWhenReady = state.playWhenReady,
                    playbackState = state.playbackState,
                    videoWidth = state.videoWidth,
                    videoHeight = state.videoHeight,
                    hasRenderedFirstFrame = state.hasRenderedFirstFrame,
                    playerError = state.playerError
                )
            }

            null -> StreamPlaybackSessionState()
        }
    }

    private suspend fun finishCurrentPlayback(
        invalidateOpen: Boolean,
        releasePlayer: Boolean,
        nextTarget: StreamPlaybackTarget? = null
    ) {
        val target = _currentTarget.value
        val snapshot = latestSnapshot()
        val hadVideo = videoSession.value.playbackSource != null
        val hadLive = _liveState.value.playbackSource != null
        if (invalidateOpen) {
            openId.incrementAndGet()
        }
        _currentTarget.value = nextTarget
        nextPlayWhenReady = true
        _pageMeta.value = null
        videoSession.value = PlayerSessionState()
        _videoState.value = PlaybackViewState()
        _liveState.value = LivePlaybackViewState()
        _sessionState.value = when (val activeTarget = nextTarget) {
            null -> StreamPlaybackSessionState()
            else -> StreamPlaybackSessionState(target = activeTarget)
        }
        if (hadVideo || hadLive) {
            if (releasePlayer) {
                playerEngine.release()
            } else {
                playerEngine.stopForReuse(resetPosition = true)
            }
        }
        if (target is StreamPlaybackTarget.Video && hadVideo) {
            reporter.finishSession(snapshot)
        }
    }

    private fun latestSnapshot(): PlaybackSnapshot {
        val player = player.value ?: return playerEngine.snapshot.value
        val snapshot = playerEngine.snapshot.value
        return snapshot.copy(
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L),
            totalBufferedDurationMs = player.totalBufferedDuration.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0L } ?: snapshot.durationMs,
            speed = player.playbackParameters.speed
        )
    }

    private fun selectAudio(
        stream: PlaybackStream?,
        audios: List<PlaybackAudio>,
        preferredId: Int
    ): PlaybackAudio? {
        if (audios.isEmpty()) return null
        val linkedId = (stream as? PlaybackStream.Dash)?.audioId
        return audios.firstOrNull { it.id == preferredId && preferredId > 0 }
            ?: audios.firstOrNull { it.id == linkedId }
            ?: audios.firstOrNull()
    }

    private fun buildVideoEngineSource(
        stream: PlaybackStream?,
        audio: PlaybackAudio?,
        cdnIndex: Int
    ): Pair<EngineSource, Int>? {
        return when (stream) {
            is PlaybackStream.Dash -> {
                val cdns = buildPlaybackCdns(stream, audio)
                if (cdns.isEmpty()) return null
                val index = cdnIndex.coerceIn(0, cdns.lastIndex)
                EngineSource.Dash(cdns[index].videoUrl, cdns[index].audioUrl) to index
            }

            is PlaybackStream.Progressive -> {
                EngineSource.Progressive(
                    stream.segments.map { EngineSource.ProgressiveSegment(it.url, it.durationMs) }
                ) to 0
            }

            null -> null
        }
    }

    private suspend fun buildPlayerConfig(): PlayerConfig {
        return PlayerConfig(
            minBufferMs = playerSettingsStore.playerMinBufferMs.first(),
            maxBufferMs = playerSettingsStore.playerMaxBufferMs.first(),
            playBufferMs = playerSettingsStore.playerPlaybackBufferMs.first(),
            rebufferMs = playerSettingsStore.playerRebufferMs.first(),
            backBufferMs = playerSettingsStore.playerBackBufferMs.first(),
            decoderMode = if (playerSettingsStore.preferSoftwareDecode.first()) {
                DecoderMode.Soft
            } else {
                DecoderMode.Hard
            },
            decoderFallback = playerSettingsStore.decoderFallback.first()
        )
    }

    private suspend fun resolveStartMs(
        request: PlaybackRequest,
        source: PlaybackSource,
        prefetchedLocal: PlaybackHistory?
    ): Long? {
        request.seekToMs?.let { return it }
        val key = PlaybackHistoryKey.video(source.report)
        val local = when {
            prefetchedLocal?.key == key -> prefetchedLocal
            else -> playbackHistoryRepo.getVideo(authProvider.mid, key)
        }
        if (local != null && canResume(local.progressMs, local.finished, source.durationMs)) {
            return local.progressMs
        }
        if (canResume(source.resumePositionMs, finished = false, durationMs = source.durationMs)) {
            return source.resumePositionMs
        }
        return 0L
    }

    private suspend fun readLocalResumeIfResolvable(request: PlaybackRequest): PlaybackHistory? {
        if (request.seekToMs != null) return null
        val report = request.playable.getReportCommonParams()
        val key = when {
            report.cid <= 0L -> null
            report.biz == PlayBiz.PGC && (report.epId ?: 0L) > 0L -> PlaybackHistoryKey.video(report)
            report.biz != PlayBiz.PGC && report.aid > 0L -> PlaybackHistoryKey.video(report)
            else -> null
        } ?: return null
        return playbackHistoryRepo.getVideo(authProvider.mid, key)
    }

    private fun canResume(
        progressMs: Long?,
        finished: Boolean,
        durationMs: Long
    ): Boolean {
        val progress = progressMs?.coerceAtLeast(0L) ?: return false
        if (progress <= 0L || finished) return false
        if (durationMs <= 0L) return true
        return durationMs - progress > COMPLETE_THRESHOLD_MS
    }

    private suspend fun reportRoomEntryAction(
        roomId: Long,
        jumpFrom: Int
    ) {
        runCatching {
            liveRepository.reportRoomEntryAction(
                roomId = roomId,
                jumpFrom = jumpFrom
            )
        }.onFailure { error ->
            Logger.w(TAG) {
                "report room entry failed roomId=$roomId jumpFrom=$jumpFrom msg=${error.message}"
            }
        }
    }

    private fun Throwable.toLiveError(): LivePlaybackError {
        val msg = message ?: "直播取流失败"
        return when (this) {
            is IllegalStateException -> LivePlaybackError.NoPlayableStream(msg)
            else -> LivePlaybackError.RequestFailed(msg, this)
        }
    }

    private fun PlayerSessionState.toViewState(
        snapshot: PlaybackSnapshot,
        prev: PlaybackViewState,
        isNewSeekEvent: Boolean
    ): PlaybackViewState {
        return PlaybackViewState(
            isPreparing = isPreparing,
            playbackSource = playbackSource,
            currentStream = currentStream,
            currentAudio = currentAudio,
            cdnIndex = cdnIndex,
            error = error,
            isPlaying = snapshot.isPlaying,
            playWhenReady = snapshot.playWhenReady,
            playbackState = snapshot.playbackState.toModelState(),
            positionMs = snapshot.positionMs,
            bufferedPositionMs = snapshot.bufferedPositionMs,
            totalBufferedDurationMs = snapshot.totalBufferedDurationMs,
            durationMs = snapshot.durationMs,
            speed = snapshot.speed,
            videoWidth = snapshot.videoWidth,
            videoHeight = snapshot.videoHeight,
            hasRenderedFirstFrame = snapshot.firstFrameSeq > 0L,
            seekEventId = if (isNewSeekEvent) prev.seekEventId + 1L else prev.seekEventId,
            playerError = snapshot.errorMessage
        )
    }

    private fun EnginePlaybackState.toModelState(): PlaybackState {
        return when (this) {
            EnginePlaybackState.Buffering -> PlaybackState.Buffering
            EnginePlaybackState.Ready -> PlaybackState.Ready
            EnginePlaybackState.Ended -> PlaybackState.Ended
            EnginePlaybackState.Idle -> PlaybackState.Idle
        }
    }

    private fun EngineDiscontinuityReason?.isSeek(): Boolean {
        return this == EngineDiscontinuityReason.Seek ||
            this == EngineDiscontinuityReason.SeekAdjustment
    }

    private companion object {
        const val TAG = "StreamPlayback"
        const val COMPLETE_THRESHOLD_MS = 3_000L
    }
}

private data class OpenConfig(
    val source: PlaybackSource,
    val preferredQuality: Int,
    val preferredAudioId: Int,
    val preferredCdnIndex: Int,
    val localResume: PlaybackHistory?
)

private class NoPlayableStreamException(
    message: String
) : IllegalStateException(message)
