package com.mja.reyamf.manager.core.data.source.local.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mja.reyamf.manager.core.data.source.local.room.dao.ActivityInfoConverter
import com.mja.reyamf.manager.core.data.source.local.room.dao.AppInfoDao
import com.mja.reyamf.manager.core.data.source.local.room.entity.AppInfoEntity

@Database(entities = [
    AppInfoEntity::class,
], version = 2, exportSchema = false)
@TypeConverters(ActivityInfoConverter::class)
abstract class ReyamfDatabase : RoomDatabase() {
    abstract fun  appInfoDao(): AppInfoDao
}