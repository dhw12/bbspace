package com.naaammme.bbspace.core.domain.published

import com.naaammme.bbspace.core.model.PublishedRecord

interface PublishedRecordRepository {
    suspend fun save(record: PublishedRecord)

    suspend fun getRecords(
        keyword: String,
        sortDesc: Boolean,
        limit: Int,
        lastItem: PublishedRecord? = null
    ): List<PublishedRecord>

    suspend fun getCount(): Int

    suspend fun delete(key: String): Boolean

    suspend fun exportJson(): String

    suspend fun importJson(json: String): Int
}
