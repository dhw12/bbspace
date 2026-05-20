package com.naaammme.bbspace.feature.dynamic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.BiliPullToRefreshBox
import com.naaammme.bbspace.core.model.LiveRoute
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.feature.dynamic.feed.DynamicFeed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicScreen(
    onOpenVideo: (VideoTarget) -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit,
    onOpenLive: (LiveRoute) -> Unit,
    onOpenDynamic: (String) -> Unit,
    viewModel: DynamicViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val shouldLoadMore by remember(
        state.items.size,
        state.canLoadMore,
        state.isLoadingMore,
        state.loadMoreError,
        listState
    ) {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            state.canLoadMore &&
                state.loadMoreError.isNullOrBlank() &&
                total > 0 &&
                last >= total - LOAD_MORE_TRIGGER_OFFSET
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("动态") }
            )
        }
    ) { padding ->
        BiliPullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                !state.isLoggedIn -> {
                    DynamicEmptyState(
                        text = "请先登录后查看动态",
                        modifier = Modifier.fillMaxSize()
                    )
                }

                state.isLoading && state.items.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                state.errorMessage != null && state.items.isEmpty() -> {
                    DynamicEmptyState(
                        text = state.errorMessage.orEmpty().ifBlank { "加载动态失败" },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                state.items.isEmpty() -> {
                    DynamicEmptyState(
                        text = "暂无动态",
                        modifier = Modifier.fillMaxSize()
                    )
                }

                else -> {
                    DynamicFeed(
                        upList = state.upList,
                        items = state.items,
                        listState = listState,
                        isLoadingMore = state.isLoadingMore,
                        errorMessage = state.errorMessage,
                        loadMoreError = state.loadMoreError,
                        onRetryRefresh = viewModel::refresh,
                        onRetryLoadMore = viewModel::loadMore,
                        onOpenVideo = onOpenVideo,
                        onOpenSpace = onOpenSpace,
                        onOpenLive = onOpenLive,
                        onOpenDynamic = onOpenDynamic,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun DynamicEmptyState(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private const val LOAD_MORE_TRIGGER_OFFSET = 4
