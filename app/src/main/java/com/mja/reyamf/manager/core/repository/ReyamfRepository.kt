package com.mja.reyamf.manager.core.repository

import com.mja.reyamf.common.model.AppInfo
import com.mja.reyamf.manager.core.data.source.local.LocalDataSource
import com.mja.reyamf.manager.core.domain.repository.IReyamfRepository
import com.mja.reyamf.manager.utils.DataMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import javax.inject.Inject

class ReyamfRepository @Inject constructor(
    private val localDataSource: LocalDataSource
) : IReyamfRepository {
    override fun insertAppInfo(appInfo: AppInfo) {
        CoroutineScope(Dispatchers.IO).launch {
            localDataSource.insertAppInfo(DataMapper.appInfoToEntity(appInfo))
        }
    }

    override fun getAppInfoList(): Flow<List<AppInfo>> =
        flow {
            emit(
                localDataSource.getAllAppInfo().map {
                    AppInfo(
                        it.activityInfo,
                        it.userId,
                        it.userName
                    )
                }
            )
        }.flowOn(Dispatchers.IO)

    override fun deleteAppInfo(appInfo: AppInfo) {
        localDataSource.deleteAppInfo(DataMapper.appInfoToEntity(appInfo))
    }
}