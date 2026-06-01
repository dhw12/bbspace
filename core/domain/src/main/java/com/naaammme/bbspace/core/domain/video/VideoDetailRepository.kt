package com.naaammme.bbspace.core.domain.video

import com.naaammme.bbspace.core.model.VideoDetailResult
import com.naaammme.bbspace.core.model.VideoRequestIds
import com.naaammme.bbspace.core.model.VideoSrc

interface VideoDetailRepository {
    suspend fun fetchVideoDetail(
        ids: VideoRequestIds,
        src: VideoSrc
    ): VideoDetailResult
}
