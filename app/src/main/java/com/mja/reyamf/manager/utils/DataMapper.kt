package com.mja.reyamf.manager.utils

import com.mja.reyamf.common.model.AppInfo
import com.mja.reyamf.manager.core.data.source.local.room.entity.AppInfoEntity

object DataMapper {
    fun appInfoToEntity(appInfo: AppInfo): AppInfoEntity =
        AppInfoEntity(
            appInfo.activityInfo,
            appInfo.userId,
            appInfo.userName
        )

    fun appInfoEntityToModel(appInfoEntity: AppInfoEntity): AppInfo =
        AppInfo(
            appInfoEntity.activityInfo,
            appInfoEntity.userId,
            appInfoEntity.userName
        )
}