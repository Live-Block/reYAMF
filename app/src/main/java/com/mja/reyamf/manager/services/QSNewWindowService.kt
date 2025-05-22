package com.mja.reyamf.manager.services

import android.service.quicksettings.TileService
import androidx.preference.PreferenceManager

class QSNewWindowService : TileService() {
    override fun onClick() {
        super.onClick()
        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean("useAppList", true))
            //YAMFManagerProxy.openAppList() //TODO: Apply in userspace
        else YAMFManagerProxy.createWindow()
    }
}