package com.mja.reyamf.xposed.services

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.IPackageManagerHidden
import android.content.pm.PackageManagerHidden
import android.content.pm.UserInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import androidx.constraintlayout.widget.ConstraintLayout
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAs
import com.mja.reyamf.BuildConfig
import com.mja.reyamf.common.gson
import com.mja.reyamf.common.model.AppInfo
import com.mja.reyamf.common.model.Config
import com.mja.reyamf.common.model.StartCmd
import com.mja.reyamf.common.runMain
import com.mja.reyamf.xposed.IAppIconCallback
import com.mja.reyamf.xposed.IAppListCallback
import com.mja.reyamf.xposed.IOpenCountListener
import com.mja.reyamf.xposed.IYAMFManager
import com.mja.reyamf.xposed.hook.HookLauncher
import com.mja.reyamf.xposed.ui.window.AppWindow
import com.mja.reyamf.xposed.utils.Instances
import com.mja.reyamf.xposed.utils.Instances.systemContext
import com.mja.reyamf.xposed.utils.Instances.systemUiContext
import com.mja.reyamf.xposed.utils.componentName
import com.mja.reyamf.xposed.utils.createContext
import com.mja.reyamf.xposed.utils.getActivityInfoCompat
import com.mja.reyamf.xposed.utils.getTopRootTask
import com.mja.reyamf.xposed.utils.log
import com.mja.reyamf.xposed.utils.registerReceiver
import com.mja.reyamf.xposed.utils.startAuto
import com.qauxv.ui.CommonContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import rikka.hidden.compat.ActivityManagerApis
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * reYAMF核心管理器
 *
 * 这是reYAMF模块的核心服务管理器，负责：
 * 1. 管理浮动窗口的创建、销毁和状态跟踪
 * 2. 处理系统广播和配置管理
 * 3. 提供AIDL接口供其他组件调用
 * 4. 协调Xposed Hook和窗口管理功能
 *
 * 该类作为单例对象运行在系统进程中，通过Xposed框架注入到Android系统中。
 * 它实现了IYAMFManager接口，提供跨进程通信能力。
 */
object YAMFManager : IYAMFManager.Stub() {
    private const val TAG = "reYAMFManager"

    // 广播Action常量定义
    /** 获取启动器配置的广播Action */
    const val ACTION_GET_LAUNCHER_CONFIG = "com.mja.reyamf.ACTION_GET_LAUNCHER_CONFIG"
    /** 打开应用的广播Action */
    const val ACTION_OPEN_APP = "com.mja.reyamf.action.OPEN_APP"
    /** 将当前应用转为窗口模式的广播Action */
    private const val ACTION_CURRENT_TO_WINDOW = "com.mja.reyamf.action.CURRENT_TO_WINDOW"
    /** 打开应用列表的广播Action */
    private const val ACTION_OPEN_APP_LIST = "com.mja.reyamf.action.OPEN_APP_LIST"
    /** 在YAMF中打开应用的广播Action */
    const val ACTION_OPEN_IN_YAMF = "com.mja.reyamf.ACTION_OPEN_IN_YAMF"

    // Intent Extra键名常量
    /** 组件名称的Extra键 */
    const val EXTRA_COMPONENT_NAME = "componentName"
    /** 用户ID的Extra键 */
    const val EXTRA_USER_ID = "userId"
    /** 任务ID的Extra键 */
    const val EXTRA_TASK_ID = "taskId"
    /** 来源标识的Extra键 */
    const val EXTRA_SOURCE = "source"

    // 来源类型常量定义
    /** 未指定来源 */
    private const val SOURCE_UNSPECIFIED = 0
    /** 来自最近任务 */
    const val SOURCE_RECENT = 1
    /** 来自任务栏 */
    const val SOURCE_TASKBAR = 2
    /** 来自弹出菜单 */
    const val SOURCE_POPUP = 3

    // 核心状态变量
    /** 窗口ID列表，按创建顺序排列，第一个为最顶层窗口 */
    private val windowList = mutableListOf<Int>()
    /** 全局配置对象 */
    lateinit var config: Config
    /** 配置文件路径，存储在系统数据目录 */
    private val configFile = File("/data/system/reYAMF.json")
    /** 当前打开的窗口数量 */
    private var openWindowCount = 0
    /** 窗口数量变化监听器集合 */
    private val iOpenCountListenerSet = mutableSetOf<IOpenCountListener>()
    /** ActivityManagerService实例引用 */
    lateinit var activityManagerService: Any

    /**
     * 系统就绪时的初始化方法
     *
     * 当Android系统完全启动后调用此方法进行初始化工作：
     * 1. 初始化系统服务实例
     * 2. 注册各种广播接收器
     * 3. 加载配置文件
     *
     * 此方法在系统进程中运行，具有系统级权限。
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun systemReady() {
        // 初始化系统服务实例，传入ActivityManagerService引用
        Instances.init(activityManagerService)

        // 注册在YAMF中打开应用的广播接收器
        systemContext.registerReceiver(ACTION_OPEN_IN_YAMF, OpenInYAMFBroadcastReceiver)

        // 注册将当前应用转为窗口模式的广播接收器
        systemContext.registerReceiver(ACTION_CURRENT_TO_WINDOW) { _, _ ->
            currentToWindow()
        }

        // 注册打开应用列表的广播接收器
        systemContext.registerReceiver(ACTION_OPEN_APP_LIST) { _, _ ->
            val componentName = ComponentName("com.mja.reyamf", "com.mja.reyamf.manager.applist.AppListWindow")
            val intent = Intent().setComponent(componentName)
            AndroidAppHelper.currentApplication().startService(intent)
        }

        // 注册打开指定应用的广播接收器
        systemContext.registerReceiver(ACTION_OPEN_APP) { _, intent ->
            val componentName = intent.getParcelableExtra<ComponentName>(EXTRA_COMPONENT_NAME)
                ?: return@registerReceiver
            val userId = intent.getIntExtra(EXTRA_USER_ID, 0)
            createWindow(StartCmd(componentName = componentName, userId = userId))
        }

        // 注册获取启动器配置的广播接收器
        // 用于向启动器发送Hook配置信息
        systemContext.registerReceiver(ACTION_GET_LAUNCHER_CONFIG) { _, intent ->
            ActivityManagerApis.broadcastIntent(Intent(HookLauncher.ACTION_RECEIVE_LAUNCHER_CONFIG).apply {
                log(TAG, "send config: ${config.hookLauncher}")
                putExtra(HookLauncher.EXTRA_HOOK_RECENT, config.hookLauncher.hookRecents)
                putExtra(HookLauncher.EXTRA_HOOK_TASKBAR, config.hookLauncher.hookTaskbar)
                putExtra(HookLauncher.EXTRA_HOOK_POPUP, config.hookLauncher.hookPopup)
                putExtra(HookLauncher.EXTRA_HOOK_TRANSIENT_TASKBAR, config.hookLauncher.hookTransientTaskbar)
                `package` = intent.getStringExtra("sender")
            }, 0)
        }

        // 初始化配置文件
        configFile.createNewFile()
        // 尝试从文件加载配置，失败则使用默认配置
        config = runCatching {
            gson.fromJson(configFile.readText(), Config::class.java)
        }.getOrNull() ?: Config()
        log(TAG, "config: $config")
    }

    /**
     * 添加新窗口到管理列表
     *
     * @param id 窗口的显示ID（Display ID）
     *
     * 功能：
     * 1. 将新窗口ID添加到列表头部（表示最顶层）
     * 2. 增加窗口计数
     * 3. 通知所有监听器窗口数量变化
     * 4. 清理失效的监听器
     */
    private fun addWindow(id: Int) {
        // 将新窗口添加到列表头部，表示它是最顶层的窗口
        windowList.add(0, id)
        openWindowCount++

        // 通知所有监听器窗口数量变化，同时清理失效的监听器
        val toRemove = mutableSetOf<IOpenCountListener>()
        iOpenCountListenerSet.forEach {
            runCatching {
                it.onUpdate(openWindowCount)
            }.onFailure { _ ->
                // 如果回调失败，说明监听器已失效，标记为待移除
                toRemove.add(it)
            }
        }
        iOpenCountListenerSet.removeAll(toRemove)
    }

    /**
     * 从管理列表中移除窗口
     *
     * @param id 要移除的窗口显示ID
     *
     * 注意：此方法只从列表中移除，不更新计数器
     * 计数器的更新通常在窗口实际销毁时进行
     */
    fun removeWindow(id: Int) {
        windowList.remove(id)
    }

    /**
     * 检查指定窗口是否为顶层窗口
     *
     * @param id 窗口显示ID
     * @return 如果是顶层窗口或没有窗口则返回true
     */
    fun isTop(id: Int) = if (windowList.isNotEmpty()) windowList[0] == id else true

    /**
     * 将指定窗口移动到顶层
     *
     * @param id 窗口显示ID
     *
     * 实现方式：先从列表中移除，再添加到头部
     */
    fun moveToTop(id: Int) {
        windowList.remove(id)
        windowList.add(0, id)
    }

    /**
     * 创建新的浮动窗口
     *
     * @param startCmd 启动命令，包含要启动的应用信息，可为null
     *
     * 功能：
     * 1. 收起状态栏面板
     * 2. 创建AppWindow实例
     * 3. 在窗口创建完成后执行启动命令
     */
    fun createWindow(startCmd: StartCmd?) {
        // 收起状态栏面板，避免遮挡新窗口
        Instances.iStatusBarService.collapsePanels()

        // 创建新的应用窗口
        AppWindow(
            CommonContextWrapper.createAppCompatContext(systemUiContext.createContext()),
            config.flags
        ) { displayId ->
            // 窗口创建完成的回调
            addWindow(displayId)
            // 如果有启动命令，则自动启动应用
            startCmd?.startAuto(displayId)
        }
    }

    /**
     * 侧边栏更新配置方法
     *
     * @param newConfig 新的配置JSON字符串
     *
     * 此方法专门用于侧边栏更新配置，与updateConfig方法功能相似
     * 但可能有不同的调用上下文或权限要求
     */
    fun sideBarUpdateConfig(newConfig: String) {
        config = gson.fromJson(newConfig, Config::class.java)
        runMain {
            configFile.writeText(newConfig)
            Log.d(TAG, "updateConfig: $config")
        }
    }

    init {
        log(TAG, "reYAMF service initialized")
    }

    // ========== AIDL接口实现方法 ==========

    /**
     * 获取版本名称
     * @return 应用版本名称字符串
     */
    override fun getVersionName(): String {
        return BuildConfig.VERSION_NAME
    }

    /**
     * 获取版本代码
     * @return 应用版本代码数字
     */
    override fun getVersionCode(): Int {
        return BuildConfig.VERSION_CODE
    }

    /**
     * 获取当前进程的UID
     * @return 进程UID，用于权限验证
     */
    override fun getUid(): Int {
        return Process.myUid()
    }

    /**
     * 创建空白窗口
     *
     * 通过AIDL接口调用，在主线程中创建一个不包含任何应用的空白窗口
     */
    override fun createWindow() {
        runMain {
            createWindow(null)
        }
    }

    /**
     * 获取构建时间
     * @return 应用构建时间戳
     */
    override fun getBuildTime(): Long {
        return BuildConfig.BUILD_TIME
    }

    /**
     * 获取配置JSON字符串
     * @return 当前配置的JSON表示
     */
    override fun getConfigJson(): String {
        return gson.toJson(config)
    }

    /**
     * 更新配置
     *
     * @param newConfig 新配置的JSON字符串
     *
     * 功能：
     * 1. 解析新配置
     * 2. 异步保存到文件
     * 3. 记录日志
     */
    override fun updateConfig(newConfig: String) {
        config = gson.fromJson(newConfig, Config::class.java)
        runMain {
            configFile.writeText(newConfig)
            Log.d(TAG, "updateConfig: $config")
        }
    }

    /**
     * 注册窗口数量变化监听器
     *
     * @param iOpenCountListener 监听器实例
     *
     * 注册后会立即回调当前窗口数量
     */
    override fun registerOpenCountListener(iOpenCountListener: IOpenCountListener) {
        iOpenCountListenerSet.add(iOpenCountListener)
        // 立即通知当前窗口数量
        iOpenCountListener.onUpdate(openWindowCount)
    }

    /**
     * 取消注册窗口数量变化监听器
     *
     * @param iOpenCountListener 要移除的监听器实例
     */
    override fun unregisterOpenCountListener(iOpenCountListener: IOpenCountListener?) {
        iOpenCountListenerSet.remove(iOpenCountListener)
    }

    /**
     * 将当前应用转为窗口模式
     *
     * 功能：
     * 1. 获取当前顶层任务
     * 2. 检查是否为启动器应用
     * 3. 如果不是启动器，则创建窗口并移动任务
     *
     * 注意：不会对启动器应用进行窗口化处理
     */
    override fun currentToWindow() {
        runMain {
            // 获取主显示器上的顶层任务
            val task = getTopRootTask(0) ?: return@runMain

            // 排除启动器应用，避免将桌面窗口化
            if (task.baseActivity?.packageName != "com.android.launcher3") {
                createWindow(StartCmd(taskId = task.taskId))
            }
        }
    }

    /**
     * 重置所有窗口
     *
     * 功能：
     * 1. 收起状态栏面板
     * 2. 发送广播通知所有窗口重置
     *
     * 通常用于清理所有浮动窗口状态
     */
    override fun resetAllWindow() {
        runMain {
            Instances.iStatusBarService.collapsePanels()
            systemContext.sendBroadcast(Intent(AppWindow.ACTION_RESET_ALL_WINDOW))
        }
    }

    /**
     * 获取应用列表（同步方法）
     *
     * @return 空列表，实际功能由getAppListAsync实现
     *
     * 注意：此方法已废弃，建议使用异步版本getAppListAsync
     */
    override fun getAppList(): List<AppInfo?>? {
        return listOf()
    }

    /**
     * 为指定应用创建窗口（用户空间调用）
     *
     * @param appInfo 应用信息，包含组件名和用户ID
     *
     * 此方法专门用于用户空间的应用启动请求
     */
    override fun createWindowUserspace(appInfo: AppInfo?) {
        runMain {
            appInfo?.let {
                createWindow(StartCmd(it.activityInfo.componentName, it.userId))
            }
        }
    }

    override fun getAppListAsync(callback: IAppListCallback) {
        runMain {
            var apps: List<ActivityInfo>
            val showApps: MutableList<AppInfo> = mutableListOf()
            val users = mutableMapOf<Int, String>()
            Instances.userManager.invokeMethodAs<List<UserInfo>>(
                "getUsers",
                args(true, true, true),
                argTypes(java.lang.Boolean.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
            )!!
                .filter { it.isProfile || it.isPrimary }
                .forEach {
                    users[it.id] = it.name
                }

            users.forEach { usr ->
                apps = (Instances.packageManager as PackageManagerHidden).queryIntentActivitiesAsUser(
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }, 0, usr.key
                ).map {
                    (Instances.iPackageManager as IPackageManagerHidden).getActivityInfoCompat(
                        ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                        0, usr.key
                    )
                }

                apps.forEach { activityInfo ->
                    showApps.add(
                        AppInfo(
                            activityInfo, usr.key, usr.value
                        )
                    )
                }
            }

            showApps.chunked(5).forEach { chunk ->
                callback.onAppListReceived(chunk.toMutableList())
            }

            callback.onAppListFinished()
        }
    }

    //Might be useful in the future
    override fun getAppIcon(callback: IAppIconCallback, appInfo: AppInfo) {
        runMain {
            val drawable = appInfo.activityInfo.loadIcon(Instances.packageManager)

            val bitmap = when (drawable) {
                is BitmapDrawable -> drawable.bitmap
                is AdaptiveIconDrawable -> {
                    val size = 108
                    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bitmap
                }
                else -> {
                    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1
                    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bitmap
                }
            }

            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val byteArray = stream.toByteArray()
            callback.onResult(byteArray)
        }
    }

    private val OpenInYAMFBroadcastReceiver: BroadcastReceiver.(Context, Intent) -> Unit =
        { _: Context, intent: Intent ->
            val taskId = intent.getIntExtra(EXTRA_TASK_ID, 0)
            val componentName =
                intent.getParcelableExtra(EXTRA_COMPONENT_NAME, ComponentName::class.java)
            val userId = intent.getIntExtra(EXTRA_USER_ID, 0)
            val source = intent.getIntExtra(EXTRA_SOURCE, SOURCE_UNSPECIFIED)
            createWindow(StartCmd(componentName, userId, taskId))

            // TODO: better way to close recents
            if (source == SOURCE_RECENT && config.recentBackHome) {
                val down = KeyEvent(
                    SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_HOME,
                    0
                ).apply {
                    this.source = InputDevice.SOURCE_KEYBOARD
                    this.invokeMethod("setDisplayId", args(0), argTypes(Integer.TYPE))
                }
                Instances.inputManager.injectInputEvent(down, 0)
                val up = KeyEvent(
                    SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_HOME,
                    0
                ).apply {
                    this.source = InputDevice.SOURCE_KEYBOARD
                    this.invokeMethod("setDisplayId", args(0), argTypes(Integer.TYPE))
                }
                Instances.inputManager.injectInputEvent(up, 0)
            }
        }
}