package com.naaammme.bbspace.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.data.AppSettings
import com.naaammme.bbspace.core.domain.feed.FeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val feedRepo: FeedRepository,
    private val appSettings: AppSettings
) : ViewModel() {

    companion object {
        private const val TAG = "HomeViewModel"
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState = _uiState.asStateFlow()

    private var flush = 0

    init {
        viewModelScope.launch {
            feedRepo.toastFlow.collect { toast ->
                if (toast.hasToast) {
                    _uiState.update { it.copy(toastMessage = toast.message) }
                }
            }
        }
        loadInitial()
    }

    private fun loadInitial() {
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        isRefreshing = true,
                        errorMessage = null
                    )
                }
                flush = 0
                val feed = feedRepo.fetchFeed(idx = 0L, pull = true, flush = flush)
                _uiState.update { it.copy(items = feed.items) }
                flush++
                if (feed.interestChoose != null) {
                    val done = appSettings.interestDone.first()
                    if (!done) {
                        _uiState.update { it.copy(interestChoose = feed.interestChoose) }
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, e) { "加载首页失败" }
                _uiState.update { it.copy(errorMessage = e.message) }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun dismissInterest() {
        _uiState.update { it.copy(interestChoose = null) }
    }

    fun submitInterest(interestId: Int, interestResult: String, interestPosIds: String) {
        viewModelScope.launch {
            try {
                appSettings.markInterestDone()
                _uiState.update {
                    it.copy(
                        interestChoose = null,
                        isRefreshing = true,
                        errorMessage = null
                    )
                }
                val feed = feedRepo.fetchFeedWithInterest(
                    idx = 0L, pull = true, flush = flush,
                    interestId = interestId,
                    interestResult = interestResult,
                    interestPosIds = interestPosIds
                )
                _uiState.update { it.copy(items = feed.items) }
                flush++
            } catch (e: Exception) {
                Logger.e(TAG, e) { "提交兴趣失败" }
                _uiState.update { it.copy(errorMessage = e.message) }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        isRefreshing = true,
                        errorMessage = null
                    )
                }
                val currentTopIdx = _uiState.value.items.firstOrNull()?.idx ?: 0L
                flush = 0
                val feed = feedRepo.fetchFeed(idx = currentTopIdx, pull = true, flush = flush)
                if (feed.items.isNotEmpty()) {
                    val merged = withContext(Dispatchers.Default) {
                        feed.items + _uiState.value.items
                    }
                    _uiState.update { it.copy(items = merged) }
                    flush++
                }
            } catch (e: Exception) {
                Logger.e(TAG, e) { "刷新失败" }
                _uiState.update { it.copy(errorMessage = e.message) }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoadingMore || state.isRefreshing) return
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoadingMore = true) }
                val lastIdx = _uiState.value.items.lastOrNull()?.idx ?: 0L
                val feed = feedRepo.fetchFeed(idx = lastIdx, pull = false, flush = flush)
                _uiState.update { it.copy(items = it.items + feed.items) }
                flush++
            } catch (e: Exception) {
                Logger.e(TAG, e) { "加载更多失败" }
                _uiState.update { it.copy(errorMessage = e.message) }
            } finally {
                _uiState.update { it.copy(isLoadingMore = false) }
            }
        }
    }
}
