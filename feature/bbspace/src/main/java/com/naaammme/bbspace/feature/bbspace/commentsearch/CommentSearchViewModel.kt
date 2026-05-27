package com.naaammme.bbspace.feature.bbspace.commentsearch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class CommentSearchUiState(
    val source: CommentSearchSource = CommentSearchSource.AICU,
    val uidInput: String = "",
    val keywordInput: String = "",
    val mode: CommentSearchMode = CommentSearchMode.ALL,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val allCount: Int? = null,
    val isEnd: Boolean = false,
    val queryPending: Boolean = false,
    val items: List<CommentSearchItem> = emptyList(),
    val error: String? = null,
    val appendError: String? = null
)

enum class CommentSearchSource(
    val title: String
) {
    AICU("AICU"),
    SYRDS("SYRDS")
}

enum class CommentSearchMode(
    val value: Int,
    val title: String
) {
    ALL(0, "全部"),
    ROOT(1, "一级"),
    CHILD(2, "二级")
}

data class CommentSearchItem(
    val id: String,
    val message: String,
    val title: String? = null,
    val metaLine: String,
    val timeText: String
)

@HiltViewModel
class CommentSearchViewModel @Inject constructor(
    private val okHttpClient: OkHttpClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(CommentSearchUiState())
    val uiState: StateFlow<CommentSearchUiState> = _uiState.asStateFlow()

    private var activeQuery: CommentSearchQuery? = null
    private var nextPage = FIRST_PAGE
    private var reqJob: Job? = null

    fun selectSource(source: CommentSearchSource) {
        if (uiState.value.source == source) return
        updateQueryInput {
            it.copy(source = source)
        }
    }

    fun updateUidInput(value: String) {
        updateQueryInput {
            it.copy(uidInput = value.filter(Char::isDigit))
        }
    }

    fun updateKeywordInput(value: String) {
        updateQueryInput {
            it.copy(keywordInput = value)
        }
    }

    fun selectMode(mode: CommentSearchMode) {
        if (uiState.value.mode == mode) return
        updateQueryInput {
            it.copy(mode = mode)
        }
    }

    fun query() {
        val query = buildCurrentQuery() ?: run {
            reqJob?.cancel()
            activeQuery = null
            nextPage = FIRST_PAGE
            _uiState.update {
                it.copy(
                    allCount = null,
                    isEnd = false,
                    queryPending = false,
                    items = emptyList(),
                    error = "请输入有效 UID",
                    appendError = null
                )
            }
            return
        }
        request(query = query, pageNum = FIRST_PAGE, append = false)
    }

    fun loadMore() {
        val query = activeQuery ?: return
        val state = uiState.value
        if (
            state.isLoading ||
            state.isLoadingMore ||
            state.queryPending ||
            state.isEnd ||
            state.items.isEmpty()
        ) {
            return
        }
        request(query = query, pageNum = nextPage, append = true)
    }

    private fun updateQueryInput(
        transform: (CommentSearchUiState) -> CommentSearchUiState
    ) {
        _uiState.update { state ->
            val next = transform(state)
            val queryPending = activeQuery?.let { hasQueryChanged(it, next) } ?: false
            next.copy(
                error = null,
                appendError = null,
                queryPending = queryPending
            )
        }
    }

    private fun request(
        query: CommentSearchQuery,
        pageNum: Int,
        append: Boolean
    ) {
        if (!append) {
            reqJob?.cancel()
            activeQuery = query
            nextPage = FIRST_PAGE
        }

        _uiState.update {
            it.copy(
                isLoading = !append,
                isLoadingMore = append,
                queryPending = false,
                error = if (append) it.error else null,
                appendError = null
            )
        }

        reqJob = viewModelScope.launch {
            val page = try {
                fetchComments(query = query, pageNum = pageNum)
            } catch (err: CancellationException) {
                throw err
            } catch (err: Throwable) {
                if (!append) {
                    activeQuery = null
                    nextPage = FIRST_PAGE
                }
                val friendlyError = getFriendlyErrorMessage(err)
                _uiState.update {
                    if (append) {
                        it.copy(
                            isLoadingMore = false,
                            appendError = friendlyError
                        )
                    } else {
                        it.copy(
                            isLoading = false,
                            allCount = null,
                            isEnd = false,
                            queryPending = false,
                            items = emptyList(),
                            error = friendlyError
                        )
                    }
                }
                return@launch
            }
            nextPage = pageNum + 1
            _uiState.update { state ->
                val queryPending = hasQueryChanged(query, state)
                val items = when {
                    !append -> page.items
                    queryPending -> state.items
                    else -> state.items + page.items
                }
                state.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    allCount = page.allCount,
                    isEnd = page.isEnd,
                    queryPending = queryPending,
                    items = items,
                    error = null,
                    appendError = null
                )
            }
        }
    }

    private suspend fun fetchComments(
        query: CommentSearchQuery,
        pageNum: Int
    ): CommentSearchPage {
        return when (query.source) {
            CommentSearchSource.AICU -> fetchAicuComments(query, pageNum)
            CommentSearchSource.SYRDS -> fetchSyrdsComments(query, pageNum)
        }
    }

    private fun executeRequest(
        url: HttpUrl,
        origin: String
    ): JSONObject {
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("origin", origin)
            .addHeader("user-agent", WEB_USER_AGENT)
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "请求失败 HTTP ${response.code}" }
            val body = response.body?.string().orEmpty()
            check(body.isNotBlank()) { "响应为空" }
            return JSONObject(body)
        }
    }

    private suspend fun fetchAicuComments(
        query: CommentSearchQuery,
        pageNum: Int
    ): CommentSearchPage = withContext(Dispatchers.IO) {
        val url = AICU_API_ENDPOINT.toHttpUrl()
            .newBuilder()
            .addQueryParameter("uid", query.uid.toString())
            .addQueryParameter("pn", pageNum.toString())
            .addQueryParameter("ps", AICU_PAGE_SIZE.toString())
            .addQueryParameter("mode", query.mode.value.toString())
            .addQueryParameter("keyword", query.keyword)
            .build()

        val json = executeRequest(url, AICU_ORIGIN)
        check(json.optInt("code", -1) == 0) { json.optString("message", "查询失败") }
        val data = json.optJSONObject("data") ?: error("响应缺少 data")
        val cursor = data.optJSONObject("cursor")
        val replies = data.optJSONArray("replies")

        val localFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        val items = buildList {
            if (replies != null) {
                for (i in 0 until replies.length()) {
                    val item = replies.optJSONObject(i) ?: continue
                    val dyn = item.optJSONObject("dyn")
                    val rpid = item.optString("rpid")
                    val timeSec = item.optLong("time")
                    add(
                        CommentSearchItem(
                            id = "aicu:${rpid.ifBlank { "$pageNum:$i" }}",
                            message = normalizeText(item.optString("message")),
                            metaLine = buildAicuMetaLine(
                                oid = dyn?.optString("oid").orEmpty(),
                                type = dyn?.optInt("type"),
                                rpid = rpid
                            ),
                            timeText = formatEpochTime(timeSec, localFormatter)
                        )
                    )
                }
            }
        }
        CommentSearchPage(
            allCount = cursor?.optInt("all_count") ?: items.size,
            isEnd = cursor?.optBoolean("is_end") ?: true,
            items = items
        )
    }

    private suspend fun fetchSyrdsComments(
        query: CommentSearchQuery,
        pageNum: Int
    ): CommentSearchPage = withContext(Dispatchers.IO) {
        val url = SYRDS_API_ENDPOINT.toHttpUrl()
            .newBuilder()
            .addQueryParameter("uid", query.uid.toString())
            .addQueryParameter("pageSize", SYRDS_PAGE_SIZE.toString())
            .addQueryParameter("pageNum", pageNum.toString())
            .addQueryParameter("keyword", query.keyword)
            .addQueryParameter("start_dt", "")
            .addQueryParameter("end_dt", "")
            .build()

        val json = executeRequest(url, SYRDS_ORIGIN)
        check(json.optInt("code", -1) == 0) { json.optString("msg", "查询失败") }
        val data = json.optJSONArray("data") ?: error("响应缺少 data")
        val allCount = json.optInt("review_num", data.length())

        val items = buildList {
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val link = item.optString("link")
                val bvid = item.optString("bvid")
                val ownerName = item.optString("video_owner_name")
                add(
                    CommentSearchItem(
                        id = buildSyrdsItemId(link, bvid, pageNum, i),
                        message = normalizeText(item.optString("content")),
                        title = item.optString("title").takeIf(String::isNotBlank),
                        metaLine = buildSyrdsMetaLine(
                            ownerName = ownerName,
                            bvid = bvid,
                            link = link
                        ),
                        timeText = item.optString("pubdate")
                            .takeIf(String::isNotBlank)
                            ?: item.optString("dt")
                    )
                )
            }
        }
        CommentSearchPage(
            allCount = allCount,
            isEnd = pageNum * SYRDS_PAGE_SIZE >= allCount || items.size < SYRDS_PAGE_SIZE,
            items = items
        )
    }

    private fun buildCurrentQuery(): CommentSearchQuery? {
        val state = uiState.value
        val uid = state.uidInput.toLongOrNull()
        if (uid == null || uid <= 0L) return null
        return CommentSearchQuery(
            source = state.source,
            uid = uid,
            mode = state.mode,
            keyword = state.keywordInput.trim()
        )
    }

    private fun hasQueryChanged(
        query: CommentSearchQuery,
        state: CommentSearchUiState
    ): Boolean {
        return state.source != query.source ||
                state.uidInput != query.uid.toString() ||
                state.keywordInput.trim() != query.keyword ||
                (query.source == CommentSearchSource.AICU && state.mode != query.mode)
    }

    private fun buildAicuMetaLine(
        oid: String,
        type: Int?,
        rpid: String
    ): String {
        return buildList {
            if (oid.isNotBlank()) add("oid $oid")
            if (type != null) add("type $type")
            if (rpid.isNotBlank()) add("rpid $rpid")
        }.joinToString(" · ")
    }

    private fun buildSyrdsMetaLine(
        ownerName: String,
        bvid: String,
        link: String
    ): String {
        return buildList {
            if (ownerName.isNotBlank()) add("UP $ownerName")
            if (bvid.isNotBlank()) add(bvid)
            if (isEmpty() && link.isNotBlank()) add(link)
        }.joinToString(" · ")
    }

    private fun buildSyrdsItemId(
        link: String,
        bvid: String,
        pageNum: Int,
        index: Int
    ): String {
        val rpid = if (link.contains("#reply")) {
            link.substringAfter("#reply").takeWhile { it.isDigit() }
        } else { "" }
        val rawId = rpid.takeIf(String::isNotBlank)
            ?: bvid.takeIf(String::isNotBlank)?.let { "${it}_${pageNum}_$index" }
            ?: "$pageNum:$index"
        return "syrds:$rawId"
    }

    private fun normalizeText(text: String): String {
        if (text.isBlank()) return ""
        return text.replace('\u00A0', ' ').trim()
    }

    private fun formatEpochTime(seconds: Long, formatter: SimpleDateFormat): String {
        if (seconds <= 0L) return ""
        return formatter.format(Date(seconds * 1000L))
    }

    private fun getFriendlyErrorMessage(err: Throwable): String {
        return when (err) {
            is java.net.UnknownHostException -> "无法访问网络，请确认网络连接是否正常"
            is java.net.SocketTimeoutException -> "连接请求超时，请稍后重试"
            else -> err.message ?: "网络请求异常，请稍后重试"
        }
    }

    private companion object {
        const val AICU_API_ENDPOINT = "https://api.aicu.cc/api/v3/search/getreply"
        const val AICU_ORIGIN = "https://www.aicu.cc"
        const val SYRDS_API_ENDPOINT = "https://api.syrds.pro/get_replies"
        const val SYRDS_ORIGIN = "https://syrds.pro"
        const val WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36 Edg/148.0.0.0"
        const val FIRST_PAGE = 1
        const val AICU_PAGE_SIZE = 100
        const val SYRDS_PAGE_SIZE = 75
    }

    private data class CommentSearchQuery(
        val source: CommentSearchSource,
        val uid: Long,
        val mode: CommentSearchMode,
        val keyword: String
    )

    private data class CommentSearchPage(
        val allCount: Int,
        val isEnd: Boolean,
        val items: List<CommentSearchItem>
    )
}