package com.naaammme.bbspace.infra.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.dash.manifest.AdaptationSet
import androidx.media3.exoplayer.dash.manifest.BaseUrl
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.Period
import androidx.media3.exoplayer.dash.manifest.Representation
import androidx.media3.exoplayer.dash.manifest.RangedUri
import androidx.media3.exoplayer.dash.manifest.SegmentBase
import java.io.EOFException
import java.net.URI
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal class SingleFileDashMediaSourceFactory(
    private val appContext: Context,
    private val okHttpClient: OkHttpClient
) {

    suspend fun create(
        source: EngineSource.SingleFileDash,
        metadata: MediaMetadata
    ): DashMediaSource {
        val streams = coroutineScope {
            buildList {
                add(
                    async {
                        buildDashStream(
                            url = source.videoUrl,
                            codecId = source.videoCodecId,
                            isAudio = false
                        )
                    }
                )
                source.audioUrl?.let { audioUrl ->
                    add(
                        async {
                            buildDashStream(
                                url = audioUrl,
                                codecId = source.audioCodecId,
                                isAudio = true
                            )
                        }
                    )
                }
            }.map { it.await() }
        }
        val dataSourceFactory = buildDataSourceFactory(
            source = source,
            prefetchedRanges = streams.mapNotNull { it.prefetchedRange }.associateBy(PrefetchedRange::url)
        )
        val manifestDurationMs = source.durationMs
            ?.coerceAtLeast(1L)
            ?: error("single-file dash 时长无效")
        val adaptationSets = streams.mapIndexed { index, stream ->
            AdaptationSet(
                index.toLong(),
                if (stream.isAudio) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_VIDEO,
                listOf(stream.representation),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
            )
        }
        val manifest = DashManifest(
            C.TIME_UNSET,
            manifestDurationMs,
            0,
            false,
            C.TIME_UNSET,
            C.TIME_UNSET,
            C.TIME_UNSET,
            C.TIME_UNSET,
            null,
            null,
            null,
            null,
            listOf(Period(null, 0, adaptationSets))
        )
        return DashMediaSource.Factory(
            DefaultDashChunkSource.Factory(dataSourceFactory, DASH_MAX_SEGMENTS_PER_LOAD),
            dataSourceFactory
        ).createMediaSource(
            manifest,
            MediaItem.Builder()
                .setUri(source.videoUrl)
                .setMediaMetadata(metadata)
                .build()
        )
    }

    private suspend fun buildDashStream(
        url: String,
        codecId: Int?,
        isAudio: Boolean
    ): DashStream {
        val indexResult = readIndex(url) ?: error("单文件 dash 索引缺失: $url")
        val index = indexResult.index
        val format = Format.Builder()
            .setContainerMimeType(if (isAudio) MimeTypes.AUDIO_MP4 else MimeTypes.VIDEO_MP4)
            .setSampleMimeType(resolveSampleMimeType(codecId, isAudio))
            .build()
        return DashStream(
            isAudio = isAudio,
            prefetchedRange = indexResult.prefetchedRange,
            representation = Representation.SingleSegmentRepresentation(
                0L,
                format,
                listOf(BaseUrl(url)),
                SegmentBase.SingleSegmentBase(
                    RangedUri(null, 0, index.initLength),
                    1,
                    0,
                    index.indexStart,
                    index.indexLength
                ),
                null,
                emptyList(),
                emptyList(),
                null,
                C.LENGTH_UNSET.toLong()
            )
        )
    }

    private fun buildDataSourceFactory(
        source: EngineSource.SingleFileDash,
        prefetchedRanges: Map<String, PrefetchedRange>
    ): DataSource.Factory {
        val requestSpec = source.toPlaybackRequestSpec()
        val upstreamFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(requestSpec.userAgent)
        if (requestSpec.headers.isNotEmpty()) {
            upstreamFactory.setDefaultRequestProperties(requestSpec.headers)
        }
        return PrefetchedDataSourceFactory(
            upstreamFactory = DefaultDataSource.Factory(appContext, upstreamFactory),
            prefetchedRanges = prefetchedRanges
        )
    }

    private suspend fun readIndex(url: String): SingleFileDashIndexResult? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        return when {
            uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true) -> {
                if (!uri.path.endsWith(".m4s", ignoreCase = true)) return null
                readHttpIndex(url)
            }

            else -> null
        }
    }

    private suspend fun readHttpIndex(url: String): SingleFileDashIndexResult? {
        val requestSpec = EngineSource.SingleFileDash(videoUrl = url).toPlaybackRequestSpec()
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-91535") // TODO:91536 是目前普遍最长视频索引大小,(tim还tm发了个100小时的视频以后有需要再兼容)
            .header("User-Agent", requestSpec.userAgent)
            .apply {
                requestSpec.headers.forEach(::header)
            }
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val data = resp.body?.bytes() ?: return@use null
                val index = parseIndex(data) ?: return@use null
                SingleFileDashIndexResult(
                    index = index,
                    prefetchedRange = PrefetchedRange(
                        url = url,
                        data = data
                    )
                )
            }
        }
    }

    private fun parseIndex(data: ByteArray): SingleFileDashIndex? {
        var pos = 0
        var moovEnd = -1L
        var sidxStart = -1L
        var sidxSize = -1L
        // TODO;支持 MP4 box 的 largesize 和 size == 0，避免扩展大小盒子被误判为非法
        while (pos + 8 <= data.size) {
            val size = readUInt32(data, pos).takeIf { it > 0 } ?: return null
            if (pos + size > data.size) return null
            when (String(data, pos + 4, 4)) {
                "moov" -> moovEnd = (pos + size).toLong()
                "sidx" -> {
                    sidxStart = pos.toLong()
                    sidxSize = size.toLong()
                    break
                }
            }
            pos += size
        }
        if (moovEnd <= 0L || sidxStart <= 0L || sidxSize <= 0L) return null
        return SingleFileDashIndex(
            initLength = moovEnd,
            indexStart = sidxStart,
            indexLength = sidxSize
        )
    }

    private fun readUInt32(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) throw EOFException("short mp4 header")
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun resolveSampleMimeType(codecId: Int?, isAudio: Boolean): String {
        return when {
            isAudio -> MimeTypes.AUDIO_AAC
            codecId == 7 -> MimeTypes.VIDEO_H264
            codecId == 12 -> MimeTypes.VIDEO_H265
            codecId == 13 -> MimeTypes.VIDEO_AV1
            else -> MimeTypes.VIDEO_H264
        }
    }

    private data class SingleFileDashIndex(
        val initLength: Long,
        val indexStart: Long,
        val indexLength: Long
    )

    private data class SingleFileDashIndexResult(
        val index: SingleFileDashIndex,
        val prefetchedRange: PrefetchedRange
    )

    private data class PrefetchedRange(
        val url: String,
        val data: ByteArray
    ) {
        val endExclusive: Long
            get() = data.size.toLong()
    }

    private data class DashStream(
        val isAudio: Boolean,
        val prefetchedRange: PrefetchedRange?,
        val representation: Representation.SingleSegmentRepresentation
    )

    private class PrefetchedDataSourceFactory(
        private val upstreamFactory: DataSource.Factory,
        private val prefetchedRanges: Map<String, PrefetchedRange>
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return PrefetchedDataSource(
                upstream = upstreamFactory.createDataSource(),
                prefetchedRanges = prefetchedRanges
            )
        }
    }

    private class PrefetchedDataSource(
        private val upstream: DataSource,
        private val prefetchedRanges: Map<String, PrefetchedRange>
    ) : DataSource {
        private var openedFromPrefetch = false
        private var currentUri: Uri? = null
        private var currentData: ByteArray? = null
        private var readPosition = 0
        private var bytesRemaining = 0

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val prefetched = prefetchedRanges[dataSpec.uri.toString()]
            if (prefetched != null && canServeFromPrefetch(dataSpec, prefetched)) {
                openedFromPrefetch = true
                currentUri = dataSpec.uri
                currentData = prefetched.data
                readPosition = dataSpec.position.toInt()
                bytesRemaining = resolveLength(dataSpec, prefetched).toInt()
                return bytesRemaining.toLong()
            }
            openedFromPrefetch = false
            currentUri = null
            currentData = null
            readPosition = 0
            bytesRemaining = 0
            return upstream.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!openedFromPrefetch) {
                return upstream.read(buffer, offset, length)
            }
            if (length == 0) {
                return 0
            }
            if (bytesRemaining == 0) {
                return C.RESULT_END_OF_INPUT
            }
            val data = currentData ?: return C.RESULT_END_OF_INPUT
            val bytesToRead = minOf(length, bytesRemaining)
            System.arraycopy(data, readPosition, buffer, offset, bytesToRead)
            readPosition += bytesToRead
            bytesRemaining -= bytesToRead
            return bytesToRead
        }

        override fun getUri(): Uri? {
            return if (openedFromPrefetch) currentUri else upstream.getUri()
        }

        override fun getResponseHeaders(): Map<String, List<String>> {
            return if (openedFromPrefetch) emptyMap() else upstream.getResponseHeaders()
        }

        override fun close() {
            if (openedFromPrefetch) {
                openedFromPrefetch = false
                currentUri = null
                currentData = null
                readPosition = 0
                bytesRemaining = 0
                return
            }
            upstream.close()
        }

        private fun canServeFromPrefetch(
            dataSpec: DataSpec,
            prefetched: PrefetchedRange
        ): Boolean {
            if (dataSpec.position < 0 || dataSpec.position >= prefetched.endExclusive) {
                return false
            }
            val requestedEndExclusive = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                prefetched.endExclusive
            } else {
                dataSpec.position + dataSpec.length
            }
            return requestedEndExclusive <= prefetched.endExclusive
        }

        private fun resolveLength(
            dataSpec: DataSpec,
            prefetched: PrefetchedRange
        ): Long {
            return if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                prefetched.endExclusive - dataSpec.position
            } else {
                dataSpec.length
            }
        }
    }

    private companion object {
        const val DASH_MAX_SEGMENTS_PER_LOAD = 4
    }
}
