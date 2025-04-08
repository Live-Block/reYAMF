package com.mja.reyamf.xposed.hook

import android.content.res.Configuration
import com.mja.reyamf.xposed.utils.log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers.findAndHookMethod
import de.robv.android.xposed.callbacks.XC_LoadPackage


class RotationHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        findAndHookMethod("com.android.server.wm.DisplayRotation", lpparam!!.classLoader,
            "rotationForOrientation", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    log("test", "changed1")
                }
            })

        findAndHookMethod("com.android.server.wm.WindowManagerService",
            lpparam.classLoader,
            "updateRotationUnchecked",
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    log("test", "changed2")
                }
            })

        findAndHookMethod("com.android.server.wm.ActivityTaskManagerService",
            lpparam.classLoader,
            "updateConfigurationLocked",
            Configuration::class.java,
            "com.android.server.wm.ActivityRecord",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    log("test", "changed3")
                }
            })

    }
}