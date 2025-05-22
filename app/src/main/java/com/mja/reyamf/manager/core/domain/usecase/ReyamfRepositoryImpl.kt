package com.mja.reyamf.manager.core.domain.usecase

import com.mja.reyamf.common.model.AppInfo
import com.mja.reyamf.manager.core.domain.repository.IReyamfRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ReyamfRepositoryImpl @Inject constructor(
    private val iReyamfRepository: IReyamfRepository
) : ReyamfRepositoryUseCase {
    override fun insertAppInfo(appInfo: AppInfo) =
        iReyamfRepository.insertAppInfo(appInfo)

    override fun getAppInfoList(): Flow<List<AppInfo>> =
        iReyamfRepository.getAppInfoList()

    override fun deleteAppInfo(appInfo: AppInfo) =
        iReyamfRepository.deleteAppInfo(appInfo)
}