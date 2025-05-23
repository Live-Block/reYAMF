package com.mja.reyamf.xposed;

import com.mja.reyamf.common.model.AppInfo;

interface IAppListCallback {
    void onAppListReceived(in List<AppInfo> appList);
    void onAppListFinished();
}