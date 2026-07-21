package com.naaammme.bbspace.core.video

import com.naaammme.bbspace.core.auth.AuthStore
import com.naaammme.bbspace.core.common.BiliConstants
import com.naaammme.bbspace.core.model.FavoriteFolder
import com.naaammme.bbspace.infra.network.BiliRestClient
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoActionRepository @Inject constructor(
    private val restClient: BiliRestClient,
    private val authStore: AuthStore
) {
    suspend fun likeVideo(aid: Long) {
        check(aid > 0L) { "视频参数无效" }
        val credential = requireWebCredential()
        restClient.postForm(
            url = "${BiliConstants.BASE_URL_API}$LIKE_ENDPOINT",
            params = mapOf(
                "aid" to aid.toString(),
                "like" to "1",
                "csrf" to credential.csrf
            ),
            headers = webHeaders(credential.cookieHeader)
        )
    }

    suspend fun isVideoLiked(aid: Long): Boolean {
        check(aid > 0L) { "视频参数无效" }
        val credential = requireWebCredential()
        val json = restClient.get(
            url = "${BiliConstants.BASE_URL_API}$RELATION_ENDPOINT",
            params = mapOf("aid" to aid.toString()),
            headers = webHeaders(
                cookieHeader = credential.cookieHeader,
                referer = videoReferer(aid, bvid = null)
            )
        )
        val data = json.optJSONObject("data") ?: return false
        return data.optPositiveFlag("like")
    }

    suspend fun toggleFavoriteVideo(aid: Long): Boolean {
        check(aid > 0L) { "视频参数无效" }
        val folders = fetchFavoriteFolderStates(aid)
        val favoritedFolderIds = folders.filter { it.favorited }.map { it.folder.fid }
        return if (favoritedFolderIds.isNotEmpty()) {
            updateFavoriteFolders(aid = aid, addIds = emptyList(), delIds = favoritedFolderIds)
            false
        } else {
            val defaultFolderId = folders.firstOrNull()?.folder?.fid ?: error("暂无可用收藏夹")
            updateFavoriteFolders(aid = aid, addIds = listOf(defaultFolderId), delIds = emptyList())
            true
        }
    }

    suspend fun isVideoFavorited(aid: Long): Boolean {
        check(aid > 0L) { "视频参数无效" }
        return fetchFavoriteFolderStates(aid).any { it.favorited }
    }

    suspend fun favoriteVideoToFolder(aid: Long, fid: Long) {
        check(aid > 0L) { "视频参数无效" }
        check(fid > 0L) { "收藏夹参数无效" }
        updateFavoriteFolders(aid = aid, addIds = listOf(fid), delIds = emptyList())
    }

    suspend fun fetchFavoriteFolders(aid: Long): List<FavoriteFolder> {
        check(aid > 0L) { "视频参数无效" }
        return fetchFavoriteFolderStates(aid).map { it.folder }
    }

    private suspend fun fetchFavoriteFolderStates(aid: Long): List<FavoriteFolderState> {
        val credential = requireWebCredential()
        val json = restClient.get(
            url = "${BiliConstants.BASE_URL_API}$FAVORITE_FOLDER_LIST_ENDPOINT",
            params = mapOf(
                "up_mid" to credential.mid.toString(),
                "rid" to aid.toString(),
                "type" to VIDEO_RESOURCE_TYPE.toString()
            ),
            headers = webHeaders(credential.cookieHeader)
        )
        val list = json.getJSONObject("data").optJSONArray("list")
            ?: error("暂无可用收藏夹")
        return buildList {
            for (index in 0 until list.length()) {
                mapFavoriteFolderState(list.optJSONObject(index))?.let(::add)
            }
        }.ifEmpty { error("暂无可用收藏夹") }
    }

    private suspend fun updateFavoriteFolders(
        aid: Long,
        addIds: List<Long>,
        delIds: List<Long>
    ) {
        check(addIds.isNotEmpty() || delIds.isNotEmpty()) { "收藏夹参数无效" }
        val credential = requireWebCredential()
        restClient.postForm(
            url = "${BiliConstants.BASE_URL_API}$FAVORITE_DEAL_ENDPOINT",
            params = mapOf(
                "rid" to aid.toString(),
                "type" to VIDEO_RESOURCE_TYPE.toString(),
                "add_media_ids" to addIds.joinToString(","),
                "del_media_ids" to delIds.joinToString(","),
                "csrf" to credential.csrf
            ),
            headers = webHeaders(credential.cookieHeader)
        )
    }

    private fun videoReferer(aid: Long, bvid: String?): String {
        val id = bvid?.takeIf(String::isNotBlank) ?: aid.takeIf { it > 0L }?.let { "av$it" }
        return if (id != null) {
            "https://www.bilibili.com/video/$id"
        } else {
            "https://www.bilibili.com/"
        }
    }

    private fun JSONObject.optPositiveFlag(name: String): Boolean {
        return when (val value = opt(name)) {
            is Boolean -> value
            is Number -> value.toInt() > 0
            is String -> value.equals("true", ignoreCase = true) ||
                value.toIntOrNull()?.let { it > 0 } == true
            else -> false
        }
    }

    private fun mapFavoriteFolderState(item: JSONObject?): FavoriteFolderState? {
        item ?: return null
        val fid = item.optLong("id").takeIf { it > 0L }
            ?: item.optLong("fid").takeIf { it > 0L }
            ?: return null
        val title = item.optString("title").takeIf(String::isNotBlank) ?: return null
        return FavoriteFolderState(
            folder = FavoriteFolder(
                fid = fid,
                title = title,
                cover = item.optString("cover").takeIf(String::isNotBlank),
                attrDesc = item.optString("attr_desc").takeIf(String::isNotBlank),
                mediaCount = item.optInt("media_count"),
                createdAtSec = item.optLong("ctime"),
                isTop = item.optBoolean("is_top")
            ),
            favorited = item.optInt("fav_state") > 0
        )
    }

    private fun requireWebCredential(): WebCredential {
        val credential = authStore.getSavedCredential() ?: error("请先登录")
        val csrf = credential.cookies.firstOrNull { it.name == "bili_jct" }?.value
            ?: error("登录凭证缺少 csrf，请重新登录")
        val cookieHeader = credential.cookies.joinToString("; ") { "${it.name}=${it.value}" }
        return WebCredential(credential.mid, cookieHeader, csrf)
    }

    private fun webHeaders(
        cookieHeader: String,
        referer: String = "https://www.bilibili.com/"
    ): Map<String, String> {
        return mapOf(
            "cookie" to cookieHeader,
            "origin" to "https://www.bilibili.com",
            "referer" to referer
        )
    }

    private data class FavoriteFolderState(
        val folder: FavoriteFolder,
        val favorited: Boolean
    )

    private data class WebCredential(
        val mid: Long,
        val cookieHeader: String,
        val csrf: String
    )

    private companion object {
        const val LIKE_ENDPOINT = "/x/web-interface/archive/like"
        const val RELATION_ENDPOINT = "/x/web-interface/archive/relation"
        const val FAVORITE_DEAL_ENDPOINT = "/x/v3/fav/resource/deal"
        const val FAVORITE_FOLDER_LIST_ENDPOINT = "/x/v3/fav/folder/created/list-all"
        const val VIDEO_RESOURCE_TYPE = 2
    }
}
