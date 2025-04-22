package com.mja.reyamf.xposed.hook

import android.app.AndroidAppHelper
import android.content.ComponentName
import android.content.Intent
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.qauxv.util.Initiator
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


class HookRotation : IXposedHookZygoteInit, IXposedHookLoadPackage {

    private var isRotating = false

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        EzXHelperInit.initZygote(startupParam)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        EzXHelperInit.initHandleLoadPackage(lpparam)
        Initiator.init(lpparam.classLoader)

        findMethod("com.android.server.wm.DisplayRotation") {
            name == "applyCurrentRotation"
        }.hookBefore {
            try {
                handleSidebar("KILL")

                if (!isRotating) {
                    isRotating = true

                    CoroutineScope(Dispatchers.Main).launch {
                        delay(5000)

                        handleSidebar("LAUNCH")
                        isRotating = false
                    }

                }
            } catch (e: Exception) {
                XposedBridge.log("Error starting sidebar: $e")
            }
        }
    }

    private fun handleSidebar(action: String) {
        val serviceIntent = Intent().apply {
            component = ComponentName(
                "com.mja.reyamf",
                "com.mja.reyamf.manager.services.SidebarHiderService"
            )
            putExtra("act", action)
        }
        AndroidAppHelper.currentApplication().startService(serviceIntent)
    }
}