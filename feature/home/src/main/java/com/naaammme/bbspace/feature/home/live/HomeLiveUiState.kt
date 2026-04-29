package com.naaammme.bbspace.feature.home.live

import androidx.compose.runtime.Immutable
import com.naaammme.bbspace.core.model.LiveRecommendItem

@Immutable
data class HomeLiveUiState(
    val items: List<LiveRecommendItem> = emptyList(),
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null
)
