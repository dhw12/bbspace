package com.naaammme.bbspace.core.data.published

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PublishedRecordEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PublishedRecordDb : RoomDatabase() {
    abstract fun dao(): PublishedRecordDao
}
