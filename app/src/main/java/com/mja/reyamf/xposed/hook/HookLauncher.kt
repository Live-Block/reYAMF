package com.mja.reyamf.xposed.hook

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.app.Application
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.UserHandle
import android.util.Log
import android.view.SurfaceControl
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.init.InitFields.moduleRes
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.findAllMethods
import com.github.kyuubiran.ezxhelper.utils.findConstructor
import com.github.kyuubiran.ezxhelper.utils.findField
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.findMethodOrNull
import com.github.kyuubiran.ezxhelper.utils.getObject
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.hookReplace
import com.github.kyuubiran.ezxhelper.utils.hookReturnConstant
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAuto
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAutoAs
import com.github.kyuubiran.ezxhelper.utils.loadClass
import com.github.kyuubiran.ezxhelper.utils.loadClassOrNull
import com.github.kyuubiran.ezxhelper.utils.newInstance
import com.github.kyuubiran.ezxhelper.utils.paramCount
import com.mja.reyamf.R
import com.mja.reyamf.xposed.utils.log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Proxy
import com.mja.reyamf.xposed.utils.registerReceiver
import com.mja.reyamf.xposed.services.YAMFManager

/**
 * 启动器Hook实现类
 *
 * 负责Hook Android启动器（Launcher3）的各种功能，为reYAMF提供集成入口：
 * 1. Hook最近任务界面，添加窗口化选项
 * 2. Hook任务栏，提供快速窗口化功能
 * 3. Hook应用长按弹出菜单，添加窗口化选项
 * 4. Hook透明任务栏功能
 *
 * 该类通过动态配置决定启用哪些Hook功能，提供灵活的集成方案。
 */
class HookLauncher : IXposedHookLoadPackage, IXposedHookZygoteInit {
    companion object {
        const val TAG = "reYAMF_HookLauncher"

        // 广播Action和Extra键常量
        /** 接收启动器配置的广播Action */
        const val ACTION_RECEIVE_LAUNCHER_CONFIG =
            "com.mja.reyamf.ACTION_RECEIVE_LAUNCHER_CONFIG"

        /** Hook最近任务的配置键 */
        const val EXTRA_HOOK_RECENT = "hookRecent"
        /** Hook任务栏的配置键 */
        const val EXTRA_HOOK_TASKBAR = "hookTaskbar"
        /** Hook弹出菜单的配置键 */
        const val EXTRA_HOOK_POPUP = "hookPopup"
        /** Hook透明任务栏的配置键 */
        const val EXTRA_HOOK_TRANSIENT_TASKBAR = "hookTransientTaskbar"
    }

    /**
     * Zygote进程初始化Hook
     *
     * @param startupParam Xposed启动参数
     */
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        EzXHelperInit.initZygote(startupParam)
    }

    /**
     * 应用包加载Hook处理
     *
     * @param lpparam 包加载参数
     *
     * 工作流程：
     * 1. 检查是否为Launcher3应用
     * 2. Hook Application.onCreate方法
     * 3. 注册配置接收器，根据配置动态启用Hook功能
     * 4. 向系统发送配置请求广播
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        EzXHelperInit.initHandleLoadPackage(lpparam)

        // 检查是否为Launcher3应用，如果不是则直接返回
        loadClassOrNull("com.android.launcher3.Launcher") ?: return

        // Hook Application的onCreate方法
        Application::class.java.findMethod {
            name == "onCreate"
        }.hookAfter {
            val application = it.thisObject as Application

            // 注册配置接收器，接收来自系统的Hook配置
            application.registerReceiver(ACTION_RECEIVE_LAUNCHER_CONFIG) { _, intent ->
                // 解析配置参数
                val hookRecent = intent.getBooleanExtra(EXTRA_HOOK_RECENT, false)
                val hookTaskbar = intent.getBooleanExtra(EXTRA_HOOK_TASKBAR, false)
                val hookPopup = intent.getBooleanExtra(EXTRA_HOOK_POPUP, false)
                val hookTransientTaskbar =
                    intent.getBooleanExtra(EXTRA_HOOK_TRANSIENT_TASKBAR, false)

                log(
                    TAG,
                    "receive config hookRecent=$hookRecent hookTaskbar=$hookTaskbar hookPopup=$hookPopup hookTranslucentTaskbar=$hookTransientTaskbar"
                )

                // 根据配置动态启用相应的Hook功能
                if (hookRecent) runCatching { hookRecent(lpparam) }.onFailure { e ->
                    log(TAG, "hook recent failed", e) }
                if (hookTaskbar) runCatching { hookTaskbar(lpparam) }.onFailure { e ->
                    log(TAG, "hook taskbar failed", e) }
                if (hookPopup) runCatching { hookPopup(lpparam) }.onFailure { e ->
                    log(TAG, "hook popup failed", e) }
                if (hookTransientTaskbar) runCatching { hookTransientTaskbar(lpparam) }.onFailure { e ->
                    log(TAG, "hook transient failed", e) }

                // 配置接收完成后取消注册，避免重复处理
                application.unregisterReceiver(this)
            }

            // 向系统发送配置请求广播
            application.sendBroadcast(Intent(YAMFManager.ACTION_GET_LAUNCHER_CONFIG).apply {
                `package` = "android"
                putExtra("sender", application.packageName)
            })
        }
    }

    private fun hookRecent(lpparam: XC_LoadPackage.LoadPackageParam) {
        log(TAG, "hooking recent ${lpparam.packageName}")
        XposedBridge.hookAllMethods(
            XposedHelpers.findClass(
                "com.android.quickstep.TaskOverlayFactory",
                lpparam.classLoader
            ), "getEnabledShortcuts", object : XC_MethodHook() {
                @SuppressLint("UseCompatLoadingForDrawables")
                override fun afterHookedMethod(param: MethodHookParam) {
                    val taskView = param.args[0] as View
                    val shortcuts = param.result as MutableList<Any>
                    val itemInfo = XposedHelpers.getObjectField(shortcuts[0], "mItemInfo")

                    var task: Any = Unit

                    runCatching {
                        task = XposedHelpers.callMethod(taskView, "getTask")
                    }.onFailure {
                        val taskContainers = XposedHelpers.getObjectField(taskView, "taskContainers") as List<*>
                        val firstContainer = taskContainers[0]
                        task = XposedHelpers.getObjectField(firstContainer, "task")
                    }

                    val activity = taskView.context
                    val key = XposedHelpers.getObjectField(task, "key")
                    val taskId = XposedHelpers.getIntField(key, "id")

                    val userId = XposedHelpers.getIntField(key, "userId")

                    val classRemoteActionShortcut = XposedHelpers.findClass(
                        "com.android.launcher3.popup.RemoteActionShortcut",
                        lpparam.classLoader
                    )

                    val intent = Intent(YAMFManager.ACTION_OPEN_IN_YAMF).apply {
                        setPackage("android")
                    }

                    runCatching {
                        val itemInfoTmp =
                            itemInfo.javaClass.newInstance(args(itemInfo), argTypes(itemInfo.javaClass))
                        val topComponent = XposedHelpers.callMethod(itemInfoTmp, "getTargetComponent") as ComponentName
                        intent.putExtra(YAMFManager.EXTRA_COMPONENT_NAME, topComponent)
                    }.onFailure {
                        val topComponent = extractComponentInfo(itemInfo.toString()).toString()
                        intent.putExtra(YAMFManager.EXTRA_COMPONENT_NAME, topComponent)
                    }
                    intent.putExtra(YAMFManager.EXTRA_TASK_ID, taskId)
                    intent.putExtra(YAMFManager.EXTRA_USER_ID, userId)
                    intent.putExtra(YAMFManager.EXTRA_SOURCE, YAMFManager.SOURCE_RECENT)

                    val action = RemoteAction(
                        Icon.createWithBitmap(
                            moduleRes.getDrawable(R.drawable.ic_picture_in_picture_alt_24, null)
                                .toBitmap()
                        ),
                        moduleRes.getString(R.string.open_with_yamf), // + if (BuildConfig.DEBUG) " ($taskId)" else "",
                        "",
                        PendingIntent.getBroadcast(
                            AndroidAppHelper.currentApplication(),
                            1345,
                            intent,
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                    val c = classRemoteActionShortcut.constructors[0]
                    val shortcut = when (c.parameterCount) {
                        4 -> c.newInstance(action, activity, itemInfo, null)
                        3 -> c.newInstance(action, activity, itemInfo)
                        else -> {
                            log(
                                TAG,
                                "unknown RemoteActionShortcut constructor: ${c.toGenericString()}"
                            )
                            null
                        }
                    }

                    if (shortcut != null) {
                        shortcuts.add(shortcut)
                    }
                }
            })
    }

    private fun hookTaskbar(lpparam: XC_LoadPackage.LoadPackageParam) {
        log(TAG, "hooking taskbar ${lpparam.packageName}")
        loadClass("com.android.launcher3.taskbar.TaskbarActivityContext").apply {
            findMethodOrNull { name == "startItemInfoActivity" }
                ?.hookReplace {
                    val infoIntent = it.args[0].invokeMethodAutoAs<Intent>("getIntent")!!
                    val intent = Intent(YAMFManager.ACTION_OPEN_IN_YAMF).apply {
                        setPackage("android")
                        putExtra(YAMFManager.EXTRA_COMPONENT_NAME, infoIntent.component)
                        putExtra(YAMFManager.EXTRA_SOURCE, YAMFManager.SOURCE_TASKBAR)
                    }
                    AndroidAppHelper.currentApplication().sendBroadcast(intent)
                }
            val classWorkspaceiteminfo =
                loadClass("com.android.launcher3.model.data.WorkspaceItemInfo")
            findMethod { name == "onTaskbarIconClicked" }
                .hookBefore {
                    val tag = it.args[0].invokeMethodAuto("getTag")!!
                    if (classWorkspaceiteminfo.isInstance(tag)) {
                        val infoIntent = tag.invokeMethodAutoAs<Intent>("getIntent")!!
                        val intent = Intent(YAMFManager.ACTION_OPEN_IN_YAMF).apply {
                            setPackage("android")
                            putExtra(YAMFManager.EXTRA_COMPONENT_NAME, infoIntent.component)
                            putExtra(YAMFManager.EXTRA_SOURCE, YAMFManager.SOURCE_TASKBAR)
                        }
                        AndroidAppHelper.currentApplication().sendBroadcast(intent)
                        it.result = Unit
                    }
                }
        }

    }

    private var proxyClass: Any? = null

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun handleProxyMethod(param: XC_MethodHook.MethodHookParam) {
        val methodName = param.method.name
        val _this = param.thisObject
        when (methodName) {
            "onClick" -> {
                val mItemInfo = _this.getObject("mItemInfo")
                val componentName = mItemInfo.invokeMethod("getTargetComponent") as ComponentName
                val userId = (mItemInfo.getObject("user") as UserHandle)
                AndroidAppHelper.currentApplication()
                    .sendBroadcast(Intent(YAMFManager.ACTION_OPEN_APP).apply {
                        setPackage("android")
                        putExtra(YAMFManager.EXTRA_COMPONENT_NAME, componentName)
                        putExtra(YAMFManager.EXTRA_USER_ID, userId)
                        putExtra(YAMFManager.EXTRA_SOURCE, YAMFManager.SOURCE_POPUP)
                    })
                _this.invokeMethodAuto("dismissTaskMenuView")
                param.result = Unit
            }

            "setIconAndContentDescriptionFor" -> {
                val view = param.args[0] as ImageView
                view.setImageDrawable(
                    moduleRes.getDrawable(
                        R.drawable.ic_picture_in_picture_alt_24,
                        null
                    )
                )
                view.contentDescription = moduleRes.getString(R.string.open_with_yamf)
                param.result = Unit
            }

            "setIconAndLabelFor" -> {
                val iconView = param.args[0] as View
                val labelView = param.args[1] as TextView
                iconView.background =
                    moduleRes.getDrawable(R.drawable.ic_picture_in_picture_alt_24, null)
                labelView.text = moduleRes.getString(R.string.open_with_yamf)
                param.result = Unit
            }
        }
    }

    private fun hookPopup(lpparam: XC_LoadPackage.LoadPackageParam) {
        log(TAG, "hooking popup ${lpparam.packageName}")
//        loadClass("com.android.launcher3.Launcher")
//            .findMethod { name == "getSupportedShortcuts" }
//            .hookAfter {
//                val r = (it.result as Stream<*>).toArray()
//                it.result = Stream.of(*r, getOpenInYAMFSystemShortcutFactory(lpparam.classLoader))
//            }
        loadClass("com.android.launcher3.popup.SystemShortcut")
            .findField { name == "INSTALL" }
            .set(null, getOpenInYAMFSystemShortcutFactory(lpparam.classLoader))
        loadClass("com.android.launcher3.popup.SystemShortcut")
            .findAllMethods { true }
            .hookBefore {
                val thiz = it.thisObject
                if (thiz !== proxyClass) return@hookBefore
                handleProxyMethod(it)
            }
        loadClass("com.android.launcher3.popup.SystemShortcut\$Install")
            .findAllMethods { true }
            .hookBefore {
                val thiz = it.thisObject
                if (thiz !== proxyClass) return@hookBefore
                handleProxyMethod(it)
            }
    }

    private fun getOpenInYAMFSystemShortcutFactory(classLoader: ClassLoader): Any {
        return Proxy.newProxyInstance(
            classLoader, arrayOf(loadClass("com.android.launcher3.popup.SystemShortcut\$Factory"))
        ) { _, method, args ->
            if (method.name != "getShortcut") return@newProxyInstance Unit
            return@newProxyInstance loadClass("com.android.launcher3.popup.SystemShortcut\$Install")
                .findConstructor { paramCount == 3 }
                .newInstance(args[0], args[1], args[2])
                .also { proxyClass = it }
        }
    }

    private fun hookTransientTaskbar(lpparam: XC_LoadPackage.LoadPackageParam) {
        log(TAG, "hook transientTaskbar ${lpparam.packageName}")
        loadClass("com.android.launcher3.util.DisplayController")
            .findMethod { name == "isTransientTaskbar" }
            .hookReturnConstant(true)
    }

    fun extractComponentInfo(input: String): String? {
        val regex = Regex("""ComponentInfo\{[^}]+\}""")
        return regex.find(input)?.value
    }
}