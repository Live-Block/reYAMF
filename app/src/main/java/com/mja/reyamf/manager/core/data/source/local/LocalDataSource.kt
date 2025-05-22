package com.mja.reyamf.manager.core.data.source.local

import com.mja.reyamf.manager.core.data.source.local.room.dao.AppInfoDao
import com.mja.reyamf.manager.core.data.source.local.room.entity.AppInfoEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalDataSource @Inject constructor(
    private val appInfoDao: AppInfoDao
){
    fun insertAppInfo(appInfo: AppInfoEntity) {
        appInfoDao.insertAppInfo(appInfo)
    }

    fun getAllAppInfo(): List<AppInfoEntity> =
        appInfoDao.getAppInfoList()

    fun deleteAppInfo(appInfoEntity: AppInfoEntity) =
        appInfoDao.deleteAppInfo(appInfoEntity)
}