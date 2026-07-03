package com.naaammme.bbspace.core.video

import com.naaammme.bbspace.core.auth.AuthStore
import com.naaammme.bbspace.core.common.BiliConstants
import com.naaammme.bbspace.infra.network.BiliRestClient
import com.naaammme.bbspace.infra.network.BiliRestParamBuilder
import com.naaammme.bbspace.infra.network.BiliRestProfile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoActionRepository @Inject constructor(
    private val restClient: BiliRestClient,
    private val restParamBuilder: BiliRestParamBuilder,
    private val authStore: AuthStore
) {
    suspend fun likeVideo(aid: Long) {
        check(aid > 0L) { "视频参数无效" }
        val accessToken = requireAccessToken()
        val ts = System.currentTimeMillis() / 1000
        restClient.postSigned(
            url = "${BiliConstants.BASE_URL_API}$LIKE_ENDPOINT",
            params = restParamBuilder.app(BiliRestProfile.APP, ts, accessToken) + mapOf(
                "aid" to aid.toString(),
                "like" to "1"
            ),
            profile = BiliRestProfile.APP
        )
    }

    suspend fun coinVideo(aid: Long) {
        check(aid > 0L) { "视频参数无效" }
        val accessToken = requireAccessToken()
        val ts = System.currentTimeMillis() / 1000
        restClient.postSigned(
            url = "${BiliConstants.BASE_URL_API}$COIN_ENDPOINT",
            params = restParamBuilder.app(BiliRestProfile.APP, ts, accessToken) + mapOf(
                "aid" to aid.toString(),
                "multiply" to "1",
                "select_like" to "0"
            ),
            profile = BiliRestProfile.APP
        )
    }

    suspend fun favoriteVideo(aid: Long) {
        check(aid > 0L) { "视频参数无效" }
        val accessToken = requireAccessToken()
        val fid = fetchDefaultFavoriteFolderId(accessToken)
        val ts = System.currentTimeMillis() / 1000
        restClient.postSigned(
            url = "${BiliConstants.BASE_URL_API}$FAVORITE_DEAL_ENDPOINT",
            params = restParamBuilder.app(BiliRestProfile.APP, ts, accessToken) + mapOf(
                "rid" to aid.toString(),
                "type" to VIDEO_RESOURCE_TYPE.toString(),
                "add_media_ids" to fid.toString(),
                "del_media_ids" to ""
            ),
            profile = BiliRestProfile.APP
        )
    }

    private suspend fun fetchDefaultFavoriteFolderId(accessToken: String): Long {
        val ts = System.currentTimeMillis() / 1000
        val json = restClient.getSigned(
            url = "${BiliConstants.BASE_URL_API}$MY_FAVORITE_ENDPOINT",
            params = restParamBuilder.app(BiliRestProfile.APP, ts, accessToken),
            profile = BiliRestProfile.APP
        )
        val list = json.getJSONObject("data").optJSONArray("list")
            ?: error("暂无可用收藏夹")
        for (index in 0 until list.length()) {
            val fid = list.optJSONObject(index)?.optLong("fid") ?: 0L
            if (fid > 0L) return fid
        }
        error("暂无可用收藏夹")
    }

    private fun requireAccessToken(): String {
        return authStore.accessToken.takeIf(String::isNotBlank) ?: error("请先登录")
    }

    private companion object {
        const val LIKE_ENDPOINT = "/x/v2/view/like"
        const val COIN_ENDPOINT = "/x/v2/view/coin/add"
        const val FAVORITE_DEAL_ENDPOINT = "/x/v3/fav/resource/deal"
        const val MY_FAVORITE_ENDPOINT = "/x/v3/fav/tab/my_fav"
        const val VIDEO_RESOURCE_TYPE = 2
    }
}
