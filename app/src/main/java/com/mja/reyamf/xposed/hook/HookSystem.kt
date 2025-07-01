package com.mja.reyamf.xposed.hook

import android.content.Intent
import android.content.pm.IPackageManager
import android.os.Build
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.mja.reyamf.BuildConfig
import com.mja.reyamf.xposed.services.UserService
import com.mja.reyamf.xposed.services.YAMFManager
import com.mja.reyamf.xposed.utils.log
import com.qauxv.util.Initiator
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlin.concurrent.thread

/**
 * reYAMF系统Hook入口类
 *
 * 这是reYAMF模块的主要Hook入口点，负责：
 * 1. 初始化Xposed框架和EzXHelper
 * 2. Hook Android系统服务的关键方法
 * 3. 启动用户服务和YAMF管理器
 * 4. 绕过系统广播检查限制
 *
 * 该类实现了Xposed的两个核心接口：
 * - IXposedHookZygoteInit: 在Zygote进程初始化时调用
 * - IXposedHookLoadPackage: 在应用包加载时调用
 */
class HookSystem : IXposedHookZygoteInit, IXposedHookLoadPackage {
    companion object {
        private const val TAG = "reYAMF_HookSystem"
    }

    /**
     * Zygote进程初始化Hook
     *
     * @param startupParam Xposed启动参数
     *
     * 在Android系统启动的早期阶段调用，用于初始化EzXHelper框架
     */
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        EzXHelperInit.initZygote(startupParam)
    }

    /**
     * 应用包加载Hook处理
     *
     * @param lpparam 包加载参数，包含包名和类加载器信息
     *
     * 只处理"android"系统包，执行以下初始化：
     * 1. 初始化EzXHelper和类加载器
     * 2. Hook系统服务注册过程
     * 3. Hook ActivityManagerService的systemReady方法
     * 4. 绕过广播检查限制
     */
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 只处理Android系统包
        if (lpparam.packageName != "android") return

        log(TAG, "xposed init")
        log(TAG, "buildtype: ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")

        // 初始化EzXHelper和类加载器
        EzXHelperInit.initHandleLoadPackage(lpparam)
        Initiator.init(lpparam.classLoader)

        // ========== Hook ServiceManager.addService ==========
        /**
         * Hook ServiceManager的addService方法
         *
         * 目的：在PackageManagerService注册时获取其实例并启动UserService
         *
         * 工作流程：
         * 1. 监听"package"服务的注册
         * 2. 获取PackageManagerService实例
         * 3. 在新线程中启动UserService
         * 4. 完成后自动取消Hook
         */
         var serviceManagerHook: XC_MethodHook.Unhook? = null
         serviceManagerHook = findMethod("android.os.ServiceManager") {
             name == "addService"
         }.hookBefore { param ->
             // 检查是否为PackageManagerService的注册
             if (param.args[0] == "package") {
                 // 取消Hook，避免重复执行
                 serviceManagerHook?.unhook()
                 val pms = param.args[1] as IPackageManager
                 log(TAG, "Got pms: $pms")

                 // 在新线程中启动UserService，避免阻塞主线程
                 thread {
                     runCatching {
                         UserService.register(pms)
                         log(TAG, "UserService started")
                     }.onFailure {
                         log(TAG, "UserService failed to start", it)
                     }
                 }
             }
         }

        // ========== Hook ActivityManagerService.systemReady ==========
        /**
         * Hook ActivityManagerService的systemReady方法
         *
         * 目的：在系统完全就绪后初始化YAMFManager
         *
         * 工作流程：
         * 1. 等待系统完全启动
         * 2. 保存ActivityManagerService实例引用
         * 3. 调用YAMFManager.systemReady()进行初始化
         * 4. 完成后自动取消Hook
         */
         var activityManagerServiceSystemReadyHook: XC_MethodHook.Unhook? = null
         activityManagerServiceSystemReadyHook = findMethod("com.android.server.am.ActivityManagerService") {
             name == "systemReady"
         }.hookAfter {
             // 取消Hook，避免重复执行
             activityManagerServiceSystemReadyHook?.unhook()
             // 保存ActivityManagerService实例
             YAMFManager.activityManagerService = it.thisObject
             // 初始化YAMF管理器
             YAMFManager.systemReady()
             log(TAG, "system ready")
         }

        // ========== 绕过系统广播检查限制 ==========
        /**
         * 绕过系统广播检查，允许reYAMF的内部广播通过
         *
         * 背景：Android系统对某些广播有安全检查，可能阻止第三方应用发送特定广播
         * 解决方案：Hook checkBroadcastFromSystem方法，对reYAMF的配置广播放行
         *
         * 目标类：
         * - ActivityManagerService: 主要的广播管理服务
         * - BroadcastController: 广播控制器（较新版本Android）
         */
        val targetClasses = listOf(
            "com.android.server.am.ActivityManagerService",
            "com.android.server.am.BroadcastController"
        )

        // Hook ActivityManagerService的广播检查
        runCatching {
            findMethod(targetClasses[0]) {
                name == "checkBroadcastFromSystem"
            }.hookBefore {
                val intent = it.args[0] as Intent
                // 如果是reYAMF的启动器配置广播，则跳过检查
                if (intent.action == HookLauncher.ACTION_RECEIVE_LAUNCHER_CONFIG)
                    it.result = Unit  // 返回Unit表示检查通过
            }
        }.onFailure {
            log(TAG, "ActivityManagerService checkBroadcastFromSystem fail")
        }

        // Hook BroadcastController的广播检查（适用于较新版本Android）
        runCatching {
            findMethod(targetClasses[1]) {
                name == "checkBroadcastFromSystem"
            }.hookBefore {
                val intent = it.args[0] as Intent
                // 如果是reYAMF的启动器配置广播，则跳过检查
                if (intent.action == HookLauncher.ACTION_RECEIVE_LAUNCHER_CONFIG)
                    it.result = Unit  // 返回Unit表示检查通过
            }
        }.onFailure {
            log(TAG, "BroadcastController checkBroadcastFromSystem fail")
        }

        // 注释掉的代码：更简洁的实现方式，但可能在某些情况下不够稳定
        // 保留作为备用方案
//        targetClasses.firstNotNullOfOrNull { className ->
//            runCatching {
//                findMethod(className) { name == "checkBroadcastFromSystem" }
//            }.getOrNull()
//        }?.hookBefore {
//            val intent = it.args[0] as Intent
//            if (intent.action == HookLauncher.ACTION_RECEIVE_LAUNCHER_CONFIG)
//                it.result = Unit
//        }
    }
}