// IYAMFManager.aidl
package com.mja.reyamf.xposed;

import com.mja.reyamf.xposed.IOpenCountListener;
import com.mja.reyamf.common.model.AppInfo;
import com.mja.reyamf.xposed.IAppListCallback;
// Declare any non-default types here with import statements

interface IYAMFManager {
    String getVersionName();

    int getVersionCode();

    int getUid();

    void createWindow();

    long getBuildTime();

    String getConfigJson();

    void updateConfig(String newConfig);

    void registerOpenCountListener(IOpenCountListener iOpenCountListener);

    void unregisterOpenCountListener(IOpenCountListener iOpenCountListener);

    void currentToWindow();

    void resetAllWindow();

    List<AppInfo> getAppList();

    void createWindowUserspace(in AppInfo appInfo);

    void getAppListAsync(IAppListCallback callback);
}