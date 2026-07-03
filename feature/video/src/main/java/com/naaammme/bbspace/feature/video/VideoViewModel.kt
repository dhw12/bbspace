package com.naaammme.bbspace.feature.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.naaammme.bbspace.core.settings.AppSettings
import com.naaammme.bbspace.core.playback.VideoPlaybackController
import com.naaammme.bbspace.core.video.VideoActionRepository
import com.naaammme.bbspace.core.model.CommentSubject
import com.naaammme.bbspace.core.model.CommentSubjectTool
import com.naaammme.bbspace.core.model.DanmakuConfig
import com.naaammme.bbspace.core.model.PlayBiz
import com.naaammme.bbspace.core.model.PlaybackProgress
import com.naaammme.bbspace.core.model.PlayerBufferProfile
import com.naaammme.bbspace.core.model.VideoCdnMode
import com.naaammme.bbspace.core.model.VideoDownloadKind
import com.naaammme.bbspace.core.model.VideoDownloadMeta
import com.naaammme.bbspace.core.model.VideoDownloadRequest
import com.naaammme.bbspace.core.model.VideoPlaybackState
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.model.isSameEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class VideoViewModel @Inject constructor(
    private val playbackController: VideoPlaybackController,
    private val playerSettings: AppSettings,
    private val videoActionRepository: VideoActionRepository
) : ViewModel() {

    private val _targetStack = MutableStateFlow<List<VideoTarget>>(emptyList())
    private val _videoActionState = MutableStateFlow(VideoActionUiState())

    val player: StateFlow<Player?> = playbackController.player
    val videoState: StateFlow<VideoPlaybackState> = playbackController.videoState
    val playbackProgress: StateFlow<PlaybackProgress> = playbackController.playbackProgress
    val videoActionState: StateFlow<VideoActionUiState> = _videoActionState
    val settingsState = playerSettings.state

    val commentSubject: CommentSubject?
        get() {
            val src = currentTarget()?.src ?: return null
            val aid = videoState.value.ids.aid.takeIf { it > 0L } ?: return null
            return CommentSubjectTool.video(aid, src)
        }

    internal val danmakuState = playbackController.danmakuState

    fun openRoot(target: VideoTarget) {
        _targetStack.value = listOf(target)
        resetVideoActions()
        playbackController.openVideo(target)
    }

    fun openTarget(target: VideoTarget) {
        val current = currentTarget()
        if (current == target) return
        _targetStack.value = when {
            current == null -> listOf(target)
            current.isSameEntry(target) -> _targetStack.value.dropLast(1) + target
            else -> _targetStack.value + target
        }
        resetVideoActions()
        playbackController.openVideo(target)
    }

    fun popPage(): Boolean {
        val stack = _targetStack.value
        if (stack.size <= 1) return false
        val nextStack = stack.dropLast(1)
        val nextTarget = nextStack.last()
        _targetStack.value = nextStack
        resetVideoActions()
        playbackController.openVideo(nextTarget)
        return true
    }

    fun togglePlayPause() {
        if (videoState.value.isPlaying) {
            playbackController.pause()
        } else {
            playbackController.play()
        }
    }

    fun switchQuality(quality: Int) {
        playbackController.switchVideoQuality(quality)
    }

    fun switchAudio(audioId: Int) {
        playbackController.switchVideoAudio(audioId)
    }

    fun updateVideoCdnMode(mode: VideoCdnMode) {
        viewModelScope.launch {
            playerSettings.setVideoCdnMode(mode)
        }
    }

    fun seekTo(positionMs: Long) {
        playbackController.seekTo(positionMs)
    }

    fun setSpeed(speed: Float) {
        playbackController.setSpeed(speed)
    }

    fun toggleLooping() {
        playbackController.setLooping(!videoState.value.isLooping)
    }

    fun likeVideo() {
        if (_videoActionState.value.liked) return
        runVideoAction(VideoUserAction.LIKE) { aid ->
            videoActionRepository.likeVideo(aid)
            _videoActionState.value = _videoActionState.value.copy(
                liked = true,
                pending = null,
                message = "已点赞"
            )
        }
    }

    fun coinVideo() {
        if (_videoActionState.value.coined) return
        runVideoAction(VideoUserAction.COIN) { aid ->
            videoActionRepository.coinVideo(aid)
            _videoActionState.value = _videoActionState.value.copy(
                coined = true,
                pending = null,
                message = "已投币"
            )
        }
    }

    fun favoriteVideo() {
        if (_videoActionState.value.favorited) return
        runVideoAction(VideoUserAction.FAVORITE) { aid ->
            videoActionRepository.favoriteVideo(aid)
            _videoActionState.value = _videoActionState.value.copy(
                favorited = true,
                pending = null,
                message = "已收藏"
            )
        }
    }

    fun clearVideoActionMessage() {
        _videoActionState.value = _videoActionState.value.copy(message = null)
    }

    fun updateBackgroundPlayback(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setBackgroundPlayback(enabled)
        }
    }

    fun updateInAppMiniPlayer(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setInAppMiniPlayer(enabled)
        }
    }

    fun updateReportPlayback(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setReportPlayback(enabled)
        }
    }

    fun updateBufferProfile(profile: PlayerBufferProfile) {
        viewModelScope.launch {
            playerSettings.setBufferProfile(profile)
        }
    }

    fun updatePreferSoftwareDecode(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setPreferSoftwareDecode(enabled)
        }
    }

    fun updateDecoderFallback(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setDecoderFallback(enabled)
        }
    }

    fun updateAutoRotateFullscreen(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setAutoRotateFullscreen(enabled)
        }
    }

    fun updateGestureSpeed(speed: Float) {
        viewModelScope.launch {
            playerSettings.setGestureSpeed(speed)
        }
    }

    fun updateDanmaku(config: DanmakuConfig) {
        viewModelScope.launch {
            playerSettings.setDanmaku(config)
        }
    }

    fun switchPage(cid: Long) {
        val pageTarget = currentTarget() as? VideoTarget.Ugc ?: return
        val ids = videoState.value.ids
        if (ids.cid == cid) return
        if (ids.aid <= 0L || cid <= 0L) return
        val nextTarget = VideoTarget.Ugc(
            aid = ids.aid,
            cid = cid,
            bvid = ids.bvid,
            src = pageTarget.src
        )
        _targetStack.value = _targetStack.value.dropLast(1) + nextTarget
        resetVideoActions()
        playbackController.openVideo(nextTarget)
    }

    fun switchEpisode(target: VideoTarget) {
        val cur = currentTarget() ?: return
        if (cur == target) return
        _targetStack.value = _targetStack.value.dropLast(1) + target
        resetVideoActions()
        playbackController.openVideo(target)
    }

    fun currentDownloadRequest(
        kind: VideoDownloadKind,
        videoQuality: Int,
        audioQuality: Int
    ): VideoDownloadRequest? {
        val state = videoState.value
        state.detail ?: return null
        currentTarget() ?: return null
        val ids = state.ids
        if (!ids.hasAny) return null
        val meta = buildDownloadMeta()
        return VideoDownloadRequest(
            biz = state.biz,
            aid = ids.aid,
            cid = ids.cid,
            bvid = ids.bvid,
            epId = ids.epId,
            seasonId = ids.seasonId,
            kind = kind,
            videoQuality = videoQuality,
            audioQuality = audioQuality,
            meta = meta
        )
    }

    private fun buildDownloadMeta(): VideoDownloadMeta {
        val detail = videoState.value.detail
        val cid = videoState.value.ids.cid.takeIf { it > 0L }
        val part = detail?.pages?.firstOrNull { it.cid == cid }
        val title = detail?.let {
            listOfNotNull(
                it.title.takeIf(String::isNotBlank),
                part?.part?.takeIf(String::isNotBlank)
            ).joinToString(" - ").takeIf(String::isNotBlank)
        }
        return VideoDownloadMeta(
            title = title,
            cover = detail?.cover,
            ownerUid = detail?.owner?.mid?.takeIf { it > 0L },
            ownerName = detail?.owner?.name?.takeIf(String::isNotBlank)
        )
    }

    private fun runVideoAction(
        action: VideoUserAction,
        block: suspend (Long) -> Unit
    ) {
        if (_videoActionState.value.pending != null) return
        val aid = videoState.value.ids.aid
        viewModelScope.launch {
            _videoActionState.value = _videoActionState.value.copy(
                pending = action,
                message = null
            )
            runCatching { block(aid) }
                .onFailure { error ->
                    _videoActionState.value = _videoActionState.value.copy(
                        pending = null,
                        message = error.message ?: "操作失败"
                    )
                }
        }
    }

    private fun resetVideoActions() {
        _videoActionState.value = VideoActionUiState()
    }

    private fun currentTarget(): VideoTarget? {
        return _targetStack.value.lastOrNull()
    }
}

internal data class VideoActionUiState(
    val liked: Boolean = false,
    val coined: Boolean = false,
    val favorited: Boolean = false,
    val pending: VideoUserAction? = null,
    val message: String? = null
)

internal enum class VideoUserAction {
    LIKE,
    COIN,
    FAVORITE
}
