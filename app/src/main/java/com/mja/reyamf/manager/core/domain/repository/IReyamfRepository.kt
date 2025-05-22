package com.mja.reyamf.manager.core.domain.repository

import com.mja.reyamf.common.model.AppInfo
import kotlinx.coroutines.flow.Flow

interface IReyamfRepository {
    fun insertAppInfo(appInfo: AppInfo)

    fun getAppInfoList(): Flow<List<AppInfo>>

    fun deleteAppInfo(appInfo: AppInfo)
}