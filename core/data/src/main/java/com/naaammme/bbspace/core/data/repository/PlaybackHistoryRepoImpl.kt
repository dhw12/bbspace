package com.naaammme.bbspace.core.data.repository

import com.naaammme.bbspace.core.data.history.PlaybackHistoryDao
import com.naaammme.bbspace.core.data.history.PlaybackHistoryEntity
import com.naaammme.bbspace.core.domain.history.PlaybackHistoryRepository
import com.naaammme.bbspace.core.model.PlaybackHistory
import com.naaammme.bbspace.core.model.PlaybackHistoryKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class PlaybackHistoryRepoImpl @Inject constructor(
    private val dao: PlaybackHistoryDao
) : PlaybackHistoryRepository {

    override suspend fun upsertVideo(item: PlaybackHistory) {
        dao.upsertAndTrim(item.toEntity(), MAX_VIDEOS_PER_UID)
    }

    override suspend fun getVideo(
        uid: Long,
        key: String
    ): PlaybackHistory? {
        return dao.getById(PlaybackHistoryKey.videoId(uid, key))?.toModel()
    }

    override fun observeVideos(): Flow<List<PlaybackHistory>> {
        return dao.observeVideos().map { list -> list.map(PlaybackHistoryEntity::toModel) }
    }

    override suspend fun deleteVideo(id: String) {
        dao.deleteById(id)
    }

    override suspend fun clearVideos() {
        dao.clear()
    }

    private companion object {
        const val MAX_VIDEOS_PER_UID = 1000
    }
}

private fun PlaybackHistory.toEntity() = PlaybackHistoryEntity(
    id = PlaybackHistoryKey.videoId(uid, key),
    uid = uid,
    biz = biz,
    aid = aid,
    cid = cid,
    epId = epId,
    seasonId = seasonId,
    durationMs = durationMs,
    progressMs = progressMs,
    watchMs = watchMs,
    updatedAt = updatedAt,
    finished = finished
)

private fun PlaybackHistoryEntity.toModel() = PlaybackHistory(
    uid = uid,
    key = PlaybackHistoryKey.video(
        biz = biz,
        aid = aid,
        cid = cid,
        epId = epId
    ),
    biz = biz,
    aid = aid,
    cid = cid,
    epId = epId,
    seasonId = seasonId,
    durationMs = durationMs,
    progressMs = progressMs,
    watchMs = watchMs,
    updatedAt = updatedAt,
    finished = finished
)
