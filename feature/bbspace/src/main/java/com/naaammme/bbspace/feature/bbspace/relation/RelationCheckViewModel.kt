package com.naaammme.bbspace.feature.bbspace.relation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naaammme.bbspace.feature.bbspace.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class RelationCheckUiState(
    val upInput: String = "",
    val userInput: String = "",
    val isLoading: Boolean = false,
    val result: String? = null,
    val ttl: Int = 0,
    val error: String? = null
)

@HiltViewModel
class RelationCheckViewModel @Inject constructor(
    private val okHttpClient: OkHttpClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(RelationCheckUiState())
    val uiState: StateFlow<RelationCheckUiState> = _uiState.asStateFlow()
    private var cache: CacheEntry? = null

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

    fun swapAndQuery() {
        val state = uiState.value
        if (state.upInput.isBlank() || state.userInput.isBlank()) return
        _uiState.update {
            it.copy(
                upInput = state.userInput,
                userInput = state.upInput,
                error = null
            )
        }
        query()
    }

    fun query() {
        val up = uiState.value.upInput.toLongOrNull()
        val user = uiState.value.userInput.toLongOrNull()
        if (up == null || up <= 0L || user == null || user <= 0L) {
            _uiState.update {
                it.copy(
                    result = null,
                    ttl = 0,
                    error = "请输入两个有效 UID"
                )
            }
            return
        }
        val now = System.currentTimeMillis()
        cache?.takeIf {
            it.up == up && it.user == user && it.expireAtMs > now
        }?.let { cached ->
            val ttl = ((cached.expireAtMs - now) / 1000L).toInt().coerceAtLeast(1)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    result = cached.result,
                    ttl = ttl,
                    error = null
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null
                )
            }
            val response = try {
                fetchRelation(up, user)
            } catch (err: CancellationException) {
                throw err
            } catch (err: Throwable) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        result = null,
                        ttl = 0,
                        error = err.message ?: "查询失败"
                    )
                }
                return@launch
            }
            val ttl = response.second.coerceAtLeast(0)
            val text = if (response.first) {
                "$up 已拉黑 $user"
            } else {
                "$up 未拉黑 $user"
            }
            cache = if (ttl > 0) {
                CacheEntry(
                    up = up,
                    user = user,
                    result = text,
                    expireAtMs = System.currentTimeMillis() + ttl * 1000L
                )
            } else {
                null
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    result = text,
                    ttl = ttl,
                    error = null
                )
            }
        }
    }

    private suspend fun fetchRelation(
        up: Long,
        user: Long
    ): Pair<Boolean, Int> {
        val url = API_ENDPOINT.toHttpUrl()
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

    private companion object {
        const val API_ENDPOINT = "https://api.vtb.cat/black"
        val USER_AGENT = "bbspace/${BuildConfig.APP_VERSION_NAME}"
    }

    private data class CacheEntry(
        val up: Long,
        val user: Long,
        val result: String,
        val expireAtMs: Long
    )
}
