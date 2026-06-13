package com.naaammme.bbspace.feature.bbspace.relation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naaammme.bbspace.feature.bbspace.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class RelationQueryResult(
    val title: String,
    val text: String? = null,
    val hint: String? = null,
    val error: String? = null
)

data class RelationCheckUiState(
    val upInput: String = "",
    val userInput: String = "",
    val isLoading: Boolean = false,
    val blockResult: RelationQueryResult? = null,
    val followResult: RelationQueryResult? = null,
    val error: String? = null
)

@HiltViewModel
class RelationCheckViewModel @Inject constructor(
    private val okHttpClient: OkHttpClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(RelationCheckUiState())
    val uiState: StateFlow<RelationCheckUiState> = _uiState.asStateFlow()

    // 采用多条目容量限制缓存（最多缓存20个），防止两个UID交替查询时缓存频繁被覆盖
    private val blockCache = LinkedHashMap<String, CacheEntry>(20, 0.75f, true)
    
    // 简易滑动时间窗口：记录一分钟内每次成功发起请求的时间戳
    private val requestTimestamps = mutableListOf<Long>()

    fun updateUpInput(value: String) {
        _uiState.update {
            it.copy(
                upInput = value.filter(Char::isDigit),
                error = null
            )
        }
    }

    fun updateUserInput(value: String) {
        _uiState.update {
            it.copy(
                userInput = value.filter(Char::isDigit),
                error = null
            )
        }
    }

    fun query() {
        val now = System.currentTimeMillis()
        
        // 1. 时间窗口限流校验：移除 1 分钟（60000毫秒）以前的所有记录
        val oneMinuteAgo = now - ONE_MINUTE_MS
        requestTimestamps.removeAll { it < oneMinuteAgo }
        
        if (requestTimestamps.size >= MAX_REQUESTS_PER_MINUTE) {
            _uiState.update {
                it.copy(error = "操作过于频繁，请稍后再试")
            }
            return
        }

        val up = uiState.value.upInput.toLongOrNull()
        val user = uiState.value.userInput.toLongOrNull()
        
        // 2. 参数合法性校验
        if (up == null || up <= 0L || user == null || user <= 0L) {
            _uiState.update {
                it.copy(
                    blockResult = null,
                    followResult = null,
                    error = "请输入两个有效 UID"
                )
            }
            return
        }

        // 本次请求通过限流和校验，记录当前请求时间
        requestTimestamps.add(now)

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    blockResult = null,
                    followResult = null,
                    error = null
                )
            }

            // 3. 并发异步请求：由子任务自带的 runCatching 捕获异常
            val (blockRes, followRes) = supervisorScope {
                val blockDeferred = async { runCatching { loadBlockResult(up, user) } }
                val followDeferred = async { runCatching { loadFollowResult(up, user) } }
                blockDeferred.await() to followDeferred.await()
            }

            // 4. 解析并行得到的结果
            val blockResult = blockRes.getOrElse { err ->
                RelationQueryResult(
                    title = "拉黑关系",
                    error = err.message ?: "拉黑关系查询失败"
                )
            }
            val followResult = followRes.getOrElse { err ->
                RelationQueryResult(
                    title = "关注关系",
                    error = err.message ?: "关注关系查询失败"
                )
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    blockResult = blockResult,
                    followResult = followResult,
                    error = null
                )
            }
        }
    }

    private suspend fun loadBlockResult(
        up: Long,
        user: Long
    ): RelationQueryResult {
        val now = System.currentTimeMillis()
        val cacheKey = "$up-$user"
        
        // 读取多项缓存，若未过期直接返回
        blockCache[cacheKey]?.takeIf { it.expireAtMs > now }?.let { cached ->
            val ttl = ((cached.expireAtMs - now) / 1000L).toInt().coerceAtLeast(1)
            return RelationQueryResult(
                title = "拉黑关系",
                text = cached.result,
                hint = "缓存剩余 ${ttl} 秒"
            )
        }

        val response = fetchBlockRelation(up, user)
        val ttl = response.second.coerceAtLeast(0)
        val text = if (response.first) {
            "$up 已拉黑 $user"
        } else {
            "$up 未拉黑 $user"
        }

        // 若接口支持 TTL 缓存，写入内存
        if (ttl > 0) {
            // 控制 LRU 缓存最大上限，避免极端的内存膨胀
            if (blockCache.size >= MAX_CACHE_SIZE) {
                val oldestKey = blockCache.keys.firstOrNull()
                if (oldestKey != null) blockCache.remove(oldestKey)
            }
            blockCache[cacheKey] = CacheEntry(
                result = text,
                expireAtMs = System.currentTimeMillis() + ttl * 1000L
            )
        } else {
            blockCache.remove(cacheKey)
        }

        return RelationQueryResult(
            title = "拉黑关系",
            text = text,
            hint = if (ttl > 0) "缓存剩余 ${ttl} 秒" else null
        )
    }

    private suspend fun loadFollowResult(
        up: Long,
        user: Long
    ): RelationQueryResult {
        val response = fetchFollowRelation(up, user)
        return RelationQueryResult(
            title = "关注关系",
            text = if (response.first) {
                "$up 已关注 $user"
            } else {
                "$up 未关注 $user"
            },
            hint = if (response.second == 1) "命中缓存值" else null
        )
    }

    private suspend fun fetchBlockRelation(
        up: Long,
        user: Long
    ): Pair<Boolean, Int> {
        val url = BLOCK_API_ENDPOINT.toHttpUrl()
            .newBuilder()
            .addQueryParameter("up", up.toString())
            .addQueryParameter("user", user.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("accept", "application/json, text/plain, */*")
            .addHeader("user-agent", USER_AGENT)
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "请求失败 HTTP ${response.code}" }
                val body = response.body?.string().orEmpty()
                check(body.isNotBlank()) { "响应为空" }
                val json = JSONObject(body)
                check(json.has("result")) { "响应缺少 result" }
                json.optBoolean("result") to json.optInt("ttl", 0)
            }
        }
    }

    private suspend fun fetchFollowRelation(
        up: Long,
        user: Long
    ): Pair<Boolean, Int> {
        val url = FOLLOW_API_ENDPOINT.toHttpUrl()
            .newBuilder()
            .addQueryParameter("uid", up.toString())
            .addQueryParameter("target_uid", user.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("accept", "application/json, text/plain, */*")
            .addHeader("user-agent", USER_AGENT)
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "请求失败 HTTP ${response.code}" }
                val body = response.body?.string().orEmpty()
                check(body.isNotBlank()) { "响应为空" }
                val json = JSONObject(body)
                check(json.optInt("code", -1) == 0) {
                    json.optString("message").ifBlank { "接口返回异常" }
                }
                check(json.has("data")) { "响应缺少 data" }
                json.optBoolean("data") to json.optInt("ttl", 0)
            }
        }
    }

    private companion object {
        const val BLOCK_API_ENDPOINT = "https://api.vtb.cat/black"
        const val FOLLOW_API_ENDPOINT = "https://kknd.online/api/follow-relation"
        val USER_AGENT = "bbspace/${BuildConfig.APP_VERSION_NAME}"
        
        // 升级后采用一分钟限流
        const val ONE_MINUTE_MS = 60000L
        const val MAX_REQUESTS_PER_MINUTE = 10
        
        // 缓存大小控制
        const val MAX_CACHE_SIZE = 20
    }

    private data class CacheEntry(
        val result: String,
        val expireAtMs: Long
    )
}