package com.naaammme.bbspace.core.data.repository

import com.naaammme.bbspace.core.data.published.PublishedRecordDao
import com.naaammme.bbspace.core.data.published.PublishedRecordEntity
import com.naaammme.bbspace.core.data.published.buildPublishedRecordPageQuery
import com.naaammme.bbspace.core.data.published.toEntity
import com.naaammme.bbspace.core.data.published.toModel
import com.naaammme.bbspace.core.domain.published.PublishedRecordRepository
import com.naaammme.bbspace.core.model.PublishedRecord
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class PublishedRecordRepoImpl @Inject constructor(
    private val dao: PublishedRecordDao
) : PublishedRecordRepository {

    override suspend fun save(record: PublishedRecord) {
        dao.upsert(record.toEntity())
    }

    override suspend fun getRecords(
        keyword: String,
        sortDesc: Boolean,
        limit: Int,
        lastItem: PublishedRecord?
    ): List<PublishedRecord> {
        return dao.getPage(
            buildPublishedRecordPageQuery(
                keyword = keyword,
                sortDesc = sortDesc,
                limit = limit,
                lastItem = lastItem
            )
        ).map(PublishedRecordEntity::toModel)
    }

    override suspend fun getCount(): Int {
        return dao.getCount()
    }

    override suspend fun delete(key: String): Boolean {
        return dao.deleteByKey(key) > 0
    }

    override suspend fun exportJson(): String {
        val items = dao.getAll()
        return withContext(Dispatchers.Default) {
            JSONArray().apply {
                items.forEach { item ->
                    put(
                        JSONObject().apply {
                            put("key", item.key)
                            put("kind", item.kind)
                            put("item_id", item.itemId)
                            put("target_id", item.targetId)
                            put("target_type", item.targetType)
                            put("sender_mid", item.senderMid)
                            put("sender_name", item.senderName)
                            put("sender_avatar", item.senderAvatar)
                            put("content", item.content)
                            put("ctime", item.ctime)
                            put("root_id", item.rootId)
                            put("parent_id", item.parentId)
                            put("image_list_json", item.imageListJson ?: JSONObject.NULL)
                        }
                    )
                }
            }.toString()
        }
    }

    override suspend fun importJson(json: String): Int {
        val items = withContext(Dispatchers.Default) {
            val array = JSONArray(json)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        PublishedRecord(
                            key = item.getString("key"),
                            kind = item.getInt("kind"),
                            itemId = item.getLong("item_id"),
                            targetId = item.getLong("target_id"),
                            targetType = item.getLong("target_type"),
                            senderMid = item.getLong("sender_mid"),
                            senderName = item.getString("sender_name"),
                            senderAvatar = item.getString("sender_avatar"),
                            content = item.getString("content"),
                            ctime = item.getLong("ctime"),
                            rootId = item.optLong("root_id"),
                            parentId = item.optLong("parent_id"),
                            imageListJson = item.takeIf {
                                it.has("image_list_json") && !it.isNull("image_list_json")
                            }?.getString("image_list_json")
                        ).toEntity()
                    )
                }
            }
        }
        if (items.isEmpty()) return 0
        dao.upsertAll(items)
        return items.size
    }
}
