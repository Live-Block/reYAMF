package com.mja.reyamf.xposed.ui.window

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.ITaskStackListenerProxy
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.DISPLAY_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.IPackageManagerHidden
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.IRotationWatcher
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManagerHidden
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.RelativeLayout
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.dynamicanimation.animation.FlingAnimation
import androidx.dynamicanimation.animation.flingAnimationOf
import androidx.wear.widget.RoundedDrawable
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.getObject
import com.github.kyuubiran.ezxhelper.utils.getObjectAs
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.google.android.material.color.MaterialColors
import com.mja.reyamf.R
import com.mja.reyamf.common.getAttr
import com.mja.reyamf.common.onException
import com.mja.reyamf.common.runMain
import com.mja.reyamf.databinding.WindowAppBinding
import com.mja.reyamf.databinding.WindowAppFlymeBinding
import com.mja.reyamf.xposed.services.YAMFManager
import com.mja.reyamf.xposed.services.YAMFManager.config
import com.mja.reyamf.xposed.utils.Instances
import com.mja.reyamf.xposed.utils.RunMainThreadQueue
import com.mja.reyamf.xposed.utils.TipUtil
import com.mja.reyamf.xposed.utils.animateAlpha
import com.mja.reyamf.xposed.utils.animateResize
import com.mja.reyamf.xposed.utils.animateScaleThenResize
import com.mja.reyamf.xposed.utils.dpToPx
import com.mja.reyamf.xposed.utils.getActivityInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt


/**
 * 浮动应用窗口实现类
 *
 * 这是reYAMF的核心UI组件，负责创建和管理浮动窗口。主要功能包括：
 * 1. 创建虚拟显示器用于应用渲染
 * 2. 管理窗口的移动、缩放、最小化等交互
 * 3. 处理屏幕旋转和配置变化
 * 4. 提供窗口控制按钮（关闭、全屏、最小化等）
 * 5. 支持TextureView和SurfaceView两种渲染模式
 *
 * @param context 上下文对象，用于创建UI组件
 * @param flags 虚拟显示器标志位，控制显示器行为
 * @param onVirtualDisplayCreated 虚拟显示器创建完成的回调，传入显示器ID
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class AppWindow(
    val context: Context,
    private val flags: Int,
    private val onVirtualDisplayCreated: (Int) -> Unit
) :
    TextureView.SurfaceTextureListener, SurfaceHolder.Callback {

    companion object {
        const val TAG = "reYAMF_AppWindow"
        /** 重置所有窗口的广播Action */
        const val ACTION_RESET_ALL_WINDOW = "com.mja.reyamf.ui.window.action.ACTION_RESET_ALL_WINDOW"
    }

    // ========== UI组件和显示相关 ==========
    /** 窗口布局绑定对象 */
    lateinit var binding: WindowAppFlymeBinding
    /** 虚拟显示器实例，用于渲染应用内容 */
    private lateinit var virtualDisplay: VirtualDisplay

    // ========== 监听器和回调 ==========
    /** 任务栈变化监听器，监听应用切换和描述变化 */
    private val taskStackListener =
        ITaskStackListenerProxy.newInstance(context.classLoader) { args, method ->
            when (method.name) {
                "onTaskMovedToFront" -> {
                    onTaskMovedToFront(args[0] as ActivityManager.RunningTaskInfo)
                }
                "onTaskDescriptionChanged" -> {
                    onTaskDescriptionChanged(args[0] as ActivityManager.RunningTaskInfo)
                }
            }
        }
    /** 屏幕旋转监听器 */
    private val rotationWatcher = RotationWatcher()
    /** Surface触摸事件监听器 */
    private val surfaceOnTouchListener = SurfaceOnTouchListener()
    /** Surface通用动作事件监听器 */
    private val surfaceOnGenericMotionListener = SurfaceOnGenericMotionListener()

    // ========== 窗口状态变量 ==========
    /** 虚拟显示器ID */
    var displayId = -1
    /** 是否锁定旋转 */
    var rotateLock = false
    /** 是否为迷你模式 */
    var isMini = false
    /** 是否已折叠 */
    var isCollapsed = false
    /** 窗口半宽 */
    private var halfWidth = 0
    /** 窗口半高 */
    private var halfHeight = 0
    /** Surface视图实例（TextureView或SurfaceView） */
    lateinit var surfaceView: View

    // ========== 显示参数计算 ==========
    /**
     * 新的DPI值，根据窗口尺寸和屏幕英寸数计算
     * 减去用户配置的DPI减少值以优化显示效果
     */
    private var newDpi = calculateDpi(
        config.defaultWindowWidth, config.defaultWindowHeight,
        calculateScreenInches(config.defaultWindowWidth, config.defaultWindowHeight)
    ) - config.reduceDPI

    // ========== 窗口尺寸和状态 ==========
    /** 原始窗口宽度 */
    private var originalWidth: Int = 0
    /** 原始窗口高度 */
    private var originalHeight: Int = 0
    /** 是否处于调整大小模式 */
    private var isResize: Boolean = true
    /** 屏幕方向（0=竖屏，1=横屏） */
    private var orientation = 0
    /** 窗口布局参数 */
    private var params = WindowManager.LayoutParams()

    /**
     * 广播接收器，处理窗口重置命令
     *
     * 监听ACTION_RESET_ALL_WINDOW广播，执行以下操作：
     * 1. 重置窗口位置到屏幕中心
     * 2. 恢复窗口到默认尺寸（200x300dp）
     * 3. 更新Surface视图尺寸
     */
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_RESET_ALL_WINDOW) {
                // 重置窗口位置到屏幕中心
                val lp = binding.root.layoutParams as WindowManager.LayoutParams
                lp.apply {
                    x = 0
                    y = 0
                }
                Instances.windowManager.updateViewLayout(binding.root, lp)

                // 计算默认窗口尺寸（200x300dp）
                val width = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 200F, context.resources.displayMetrics).toInt()
                val height = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 300F, context.resources.displayMetrics).toInt()

                // 更新Surface视图尺寸
                surfaceView.updateLayoutParams {
                    this.width = width
                    this.height = height
                }
            }
        }
    }

    /**
     * 构造函数初始化块
     *
     * 尝试创建窗口布局，如果失败则显示错误提示
     * 成功后调用doInit()进行详细初始化
     */
    init {
        runCatching {
            binding = WindowAppFlymeBinding.inflate(LayoutInflater.from(context))
        }.onException { e ->
            Log.e(TAG, "Failed to create new window, did you reboot?", e)
            TipUtil.showToast("Failed to create new window, did you reboot?")
        }.onSuccess {
            doInit()
        }
    }

    /**
     * 详细初始化方法
     *
     * 执行窗口的详细初始化工作：
     * 1. 根据配置选择Surface视图类型（TextureView或SurfaceView）
     * 2. 设置窗口布局参数和标志位
     * 3. 根据屏幕旋转调整窗口位置和控制器显示
     */
    private fun doInit() {
        // 根据配置选择Surface视图类型
        when(config.surfaceView) {
            0 -> {
                // 使用TextureView
                surfaceView = binding.viewSurface
                binding.viewTexture.visibility = View.GONE
            }
            1 -> {
                // 使用SurfaceView
                surfaceView = binding.viewTexture
                binding.viewSurface.visibility = View.GONE
            }
        }

        // 设置窗口布局参数
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 组合标志位：
            // FLAG_LAYOUT_NO_LIMITS: 允许窗口超出屏幕边界
            // FLAG_NOT_FOCUSABLE: 窗口不获取焦点
            // FLAG_NOT_TOUCH_MODAL: 允许触摸事件穿透到下层窗口
            // FLAG_HARDWARE_ACCELERATED: 启用硬件加速
            // FLAG_ALT_FOCUSABLE_IM: 允许输入法获取焦点
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.RGBA_8888
        )

        // 根据屏幕旋转调整控制器显示
        val displayManager = context.getSystemService(DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = display?.rotation ?: Surface.ROTATION_0
        when (rotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                // 竖屏模式：显示底部控制器，隐藏侧边控制器
                orientation = 0
                binding.rlBarControllerBottom.isVisible = true
                binding.rlBarControllerSide.isVisible = false
            }
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                // 横屏模式：显示侧边控制器，隐藏底部控制器
                orientation = 1
                binding.rlBarControllerBottom.isVisible = false
                binding.rlBarControllerSide.isVisible = true
            }
        }

        // 设置窗口占满整个屏幕
        params.apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // 注释掉的代码：设置圆角覆盖标志，可能在某些版本中需要
//            this as WindowLayoutParamsHidden
//            privateFlags = privateFlags or WindowLayoutParamsHidden.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY
            params.dimAmount = 0.3f
        }

        // 根据屏幕方向设置小窗的初始位置
        binding.cvParent.post {
            val initialTranslationY = if (orientation == 0) {
                // 竖屏模式：稍微向上偏移
                -80.dpToPx()
            } else {
                // 横屏模式：使用配置的偏移
                config.landscapeY.toFloat()
            }

            // 设置小窗和控制条的初始位置
            binding.cvParent.translationY = initialTranslationY
            binding.rlBarControllerBottom.translationY = initialTranslationY
            binding.rlBarControllerSide.translationY = initialTranslationY
        }

        // 将窗口布局添加到WindowManager中显示
        binding.root.let { layout ->
            Instances.windowManager.addView(layout, params)
        }

        // ========== 设置触摸事件监听器 ==========

        // 根部点击遮罩的触摸监听器：处理窗口拖拽和置顶
        binding.root.setOnTouchListener { _, event ->
            backgroundGestureDetector.onTouchEvent(event)
            true
        }

        binding.rootClickMask.setOnTouchListener { _, event ->
            moveGestureDetector.onTouchEvent(event)
            moveToTopIfNeed(event)
            true
        }

        // 侧边栏控制器点击遮罩的触摸监听器
        binding.cvBarSideClickMask.setOnTouchListener { _, event ->
            moveGestureDetector.onTouchEvent(event)
            moveToTopIfNeed(event)
            true
        }

        // 底部控制器点击遮罩的触摸监听器
        binding.cvBarClickMask.setOnTouchListener { _, event ->
            moveGestureDetector.onTouchEvent(event)
            moveToTopIfNeed(event)
            true
        }

        // 为Surface视图设置触摸和通用动作监听器
        surfaceView.setOnTouchListener(surfaceOnTouchListener)
        surfaceView.setOnGenericMotionListener(surfaceOnGenericMotionListener)
        // ========== 设置控制按钮点击事件 ==========

        /**
         * 返回按钮点击事件：向虚拟显示器注入BACK键事件
         * 模拟用户按下返回键，让应用执行返回操作

        binding.ibBack.setOnClickListener {
            // 创建按下事件
            val down = KeyEvent(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_BACK,
                0
            ).apply {
                source = InputDevice.SOURCE_KEYBOARD
                this.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
            }
            Instances.inputManager.injectInputEvent(down, 0)

            // 创建抬起事件
            val up = KeyEvent(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_BACK,
                0
            ).apply {
                source = InputDevice.SOURCE_KEYBOARD
                this.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
            }
            Instances.inputManager.injectInputEvent(up, 0)
        }


         * 返回按钮长按事件：向虚拟显示器注入HOME键事件
         * 模拟用户按下Home键，让应用返回桌面

        binding.ibBack.setOnLongClickListener {
            // 创建按下事件
            val down = KeyEvent(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_HOME,
                0
            ).apply {
                source = InputDevice.SOURCE_KEYBOARD
                this.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
            }
            Instances.inputManager.injectInputEvent(down, 0)

            // 创建抬起事件
            val up = KeyEvent(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_HOME,
                0
            ).apply {
                source = InputDevice.SOURCE_KEYBOARD
                this.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
            }
            Instances.inputManager.injectInputEvent(up, 0)
            true
        }

         * 关闭按钮点击事件：执行窗口关闭动画并销毁窗口

        binding.ibClose.setOnClickListener {
            isResize = false

            // 隐藏应用图标并淡出控制按钮
            binding.cvappIcon.visibility = View.INVISIBLE
            animateAlpha(binding.ibClose, 1f, 0f)
            animateAlpha(binding.ibBack, 1f, 0f)

            // 根据屏幕方向淡出相应的控制器
            if (orientation == 0) {
                animateAlpha(binding.rlBarControllerBottom, 1f, 0f)
            } else {
                animateAlpha(binding.rlBarControllerSide, 1f, 0f)
            }

            // 延迟执行缩放动画，然后销毁窗口
            CoroutineScope(Dispatchers.Main).launch {
                delay(200)

                animateScaleThenResize(
                    binding.cvParent,
                    1F, 1F,      // 起始缩放
                    0F, 0F,      // 结束缩放
                    0.5F, 0.5F,  // 缩放中心点
                    0, 0,        // 结束尺寸
                    context
                ) {
                    onDestroy()  // 动画完成后销毁窗口
                }
            }
        }


         * 全屏按钮点击事件：将应用移回主显示器并关闭窗口

        binding.ibFullscreen.setOnClickListener {
            getTopRootTask()?.runCatching {
                // 将当前任务移动到主显示器（displayId = 0）
                Instances.activityTaskManager.moveRootTaskToDisplay(taskId, 0)
            }?.onFailure { t ->
                if (t is Error) throw t
                TipUtil.showToast("${t.message}")
            }?.onSuccess {
                // 移动成功后关闭窗口
                binding.ibClose.callOnClick()
            }
        }


         * 最小化按钮点击事件：切换到迷你模式

        binding.ibMinimize.setOnClickListener {
            changeMini()
            binding.apply {
                ibSuper.visibility = View.VISIBLE      // 显示恢复按钮
                ibMinimize.visibility = View.INVISIBLE // 隐藏最小化按钮
                ibFullscreen.visibility = View.INVISIBLE // 隐藏全屏按钮
            }
        }


         * 最小化按钮长按事件：切换到折叠模式

        binding.ibMinimize.setOnLongClickListener {
            changeCollapsed()
            binding.apply {
                ibSuper.visibility = View.VISIBLE      // 显示恢复按钮
                ibMinimize.visibility = View.INVISIBLE // 隐藏最小化按钮
                ibFullscreen.visibility = View.INVISIBLE // 隐藏全屏按钮
            }
            true
        }


         * 恢复按钮点击事件：从最小化/折叠状态恢复到正常状态

        binding.ibSuper.setOnClickListener {
            binding.apply {
                ibSuper.visibility = View.INVISIBLE    // 隐藏恢复按钮
                ibMinimize.visibility = View.VISIBLE   // 显示最小化按钮
                ibFullscreen.visibility = View.VISIBLE // 显示全屏按钮
            }
        }

        */

        // ========== 创建和配置虚拟显示器 ==========

        /**
         * 创建虚拟显示器
         * 使用当前时间戳作为唯一标识符，避免名称冲突
         */
        virtualDisplay = Instances.displayManager.createVirtualDisplay(
            "yamf${System.currentTimeMillis()}",
            config.defaultWindowWidth,
            config.defaultWindowHeight,
            newDpi-config.reduceDPI,  // 应用DPI减少配置
            null,
            flags
        )
        displayId = virtualDisplay.display.displayId

        /**
         * 设置虚拟显示器的输入法策略
         * - LOCAL: 在窗口内显示输入法
         * - FALLBACK_DISPLAY: 在主显示器显示输入法
         */
        (Instances.windowManager as WindowManagerHidden).setDisplayImePolicy(
            displayId,
            if (config.showImeInWindow)
                WindowManagerHidden.DISPLAY_IME_POLICY_LOCAL
            else
                WindowManagerHidden.DISPLAY_IME_POLICY_FALLBACK_DISPLAY
        )

        // 注册任务栈监听器，监听应用切换和描述变化
        Instances.activityTaskManager.registerTaskStackListener(taskStackListener)

        // 根据Surface视图类型设置相应的监听器
        (surfaceView as? TextureView)?.surfaceTextureListener = this
        (surfaceView as? SurfaceView)?.holder?.addCallback(this)

        /**
         * 监听屏幕旋转变化
         * 使用递归重试机制确保监听器注册成功
         */
        var failCount = 0
        fun watchRotation() {
            runCatching {
                Instances.iWindowManager.watchRotation(rotationWatcher, displayId)
            }.onFailure {
                failCount++
                Log.d(TAG, "watchRotation: fail $failCount")
                watchRotation()  // 失败时递归重试
            }
        }
        watchRotation()

        // 注册广播接收器，监听窗口重置命令
        context.registerReceiver(broadcastReceiver, IntentFilter(ACTION_RESET_ALL_WINDOW), Context.RECEIVER_EXPORTED)

        // 设置Surface视图和尺寸预览器的初始尺寸
        val width = config.defaultWindowWidth.dpToPx().toInt()
        val height = config.defaultWindowHeight.dpToPx().toInt()
        surfaceView.updateLayoutParams {
            this.width = width
            this.height = height
        }

        // 通知虚拟显示器创建完成
        onVirtualDisplayCreated(displayId)

        // ========== 窗口初始化和显示动画 ==========

        // 初始状态：禁用调整大小功能
        isResize = false

        /**
         * 等待布局完成后执行初始化动画
         * 使用post确保在布局测量完成后获取正确的尺寸
         */
        binding.cvBackground.post {
            // 记录原始尺寸
            originalWidth = binding.cvBackground.width
            originalHeight = binding.cvBackground.height
            binding.cvBackground.visibility = View.VISIBLE

            // 设置圆角半径
            binding.cvBackground.radius = config.windowRoundedCorner.dpToPx()
            binding.cvappIcon.radius = config.windowRoundedCorner.dpToPx()

            // 父容器圆角稍大一些，形成边框效果
            binding.cvParent.radius = (config.windowRoundedCorner+2).dpToPx()
            // 强制设置父容器背景为透明
            binding.cvParent.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
            originalWidth = binding.cvParent.width
            originalHeight = binding.cvParent.height
            binding.cvParent.visibility = View.VISIBLE

            /**
             * 执行窗口出现动画
             * 从0缩放到1，创建平滑的出现效果
             */
            animateScaleThenResize(
                binding.cvBackground,
                0F, 0F,              // 起始缩放（完全缩小）
                1F, 1F,              // 结束缩放（正常大小）
                0.5F, 0.5F,          // 缩放中心点（中心）
                originalWidth, originalHeight,  // 目标尺寸
                context
            ) {
                // 动画完成后的回调
                setBackgroundWrapContent()

                // 延迟显示控制按钮，创建层次感
                CoroutineScope(Dispatchers.Main).launch {
                    delay(200)

                    // 注释掉边框设置，避免灰色边框
                    // binding.cvParent.strokeWidth = 2.dpToPx().toInt()
                }

                // 启用调整大小功能
                isResize = true
            }
        }
    }

    private val backgroundGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!isMini) {
                onDestroy()
            }
            return true
        }
    })

    /**
     * 销毁窗口并清理资源
     *
     * 执行完整的清理流程：
     * 1. 取消注册所有监听器和接收器
     * 2. 从管理器中移除窗口
     * 3. 释放虚拟显示器资源
     * 4. 从WindowManager中移除视图
     */
    private fun onDestroy() {
        // 取消注册广播接收器
        context.unregisterReceiver(broadcastReceiver)

        // 移除屏幕旋转监听器
        Instances.iWindowManager.removeRotationWatcher(rotationWatcher)

        // 取消注册任务栈监听器
        Instances.activityTaskManager.unregisterTaskStackListener(taskStackListener)

        // 从YAMF管理器中移除窗口记录
        YAMFManager.removeWindow(displayId)

        // 释放虚拟显示器资源
        virtualDisplay.release()

        // 从WindowManager中移除窗口视图
        Instances.windowManager.removeView(binding.root)
    }

    /**
     * 获取虚拟显示器上的顶层任务信息
     *
     * @return 当前可见的顶层任务信息，如果没有则返回null
     */
    private fun getTopRootTask(): ActivityTaskManager.RootTaskInfo? {
        Instances.activityTaskManager.getAllRootTaskInfosOnDisplay(displayId).forEach { task ->
            if (task.visible)
                return task
        }
        return null
    }

    private fun moveToTop() {
        Instances.windowManager.removeView(binding.root)
        Instances.windowManager.addView(binding.root, binding.root.layoutParams)
        YAMFManager.moveToTop(displayId)
    }

    private fun moveToTopIfNeed(event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_UP && YAMFManager.isTop(displayId).not()) {
            moveToTop()
        }
    }

    private fun updateTask(taskInfo: ActivityManager.RunningTaskInfo) {
        RunMainThreadQueue.add {
            if (taskInfo.isVisible.not()) {
                delay(500) // fixme: use a method that directly determines visibility
            }

            var backgroundColor = 0
            var statusBarColor = 0
            var navigationBarColor = 0
            var taskDescription: ActivityManager.TaskDescription?

            if (Build.VERSION.SDK_INT < 35) {
                val topActivity = taskInfo.topActivity ?: return@add
                taskDescription = Instances.activityTaskManager.getTaskDescription(taskInfo.taskId) ?: return@add
                val activityInfo = (Instances.iPackageManager as IPackageManagerHidden).getActivityInfoCompat(topActivity, 0, taskInfo.getObjectAs("userId"))

                backgroundColor = taskDescription.backgroundColor
                statusBarColor = taskDescription.backgroundColor
                navigationBarColor = taskDescription.backgroundColor
                binding.appIcon.setImageDrawable(RoundedDrawable().apply {
                    drawable = runCatching { taskDescription.icon }.getOrNull()?.let { BitmapDrawable(it) } ?: activityInfo.loadIcon(Instances.packageManager)
                    isClipEnabled = true
                    radius = 100
                })
            } else {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val runningTasks = activityManager.getRunningTasks(5)

                for (task in runningTasks) {
                    if (task.taskId == taskInfo.taskId) {
                        val packageName = task.baseActivity?.packageName
                        try {
                            val packageManager = context.packageManager
                            backgroundColor = task.taskDescription!!.backgroundColor
                            statusBarColor = task.taskDescription!!.backgroundColor
                            navigationBarColor = task.taskDescription!!.backgroundColor
                            binding.appIcon.setImageDrawable(packageManager.getApplicationIcon(
                                packageName!!
                            ))
                        } catch (e: PackageManager.NameNotFoundException) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            if (config.coloredController) {

                val onStateBar = if (MaterialColors.isColorLight(ColorUtils.compositeColors(statusBarColor, backgroundColor)) xor ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)) {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimaryContainer).data
                } else {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimary).data
                }

                binding.background.setBackgroundColor(navigationBarColor)

                val onNavigationBar = if (MaterialColors.isColorLight(ColorUtils.compositeColors(navigationBarColor, backgroundColor)) xor ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)) {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimaryContainer).data
                } else {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimary).data
                }

            }
        }
    }

    fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
        if (taskInfo.getObject("displayId") == displayId) {
            updateTask(taskInfo)
        }
    }

    fun onTaskDescriptionChanged(taskInfo: ActivityManager.RunningTaskInfo) {
        if (taskInfo.getObject("displayId") == displayId) {
            if(!taskInfo.isVisible){
                return
            }
            updateTask(taskInfo)
        }
    }

    /**
     * 屏幕旋转监听器内部类
     *
     * 监听虚拟显示器的旋转变化，并在未锁定旋转时执行相应的旋转操作
     */
    inner class RotationWatcher : IRotationWatcher.Stub() {
        /**
         * 屏幕旋转变化回调
         *
         * @param rotation 新的旋转角度
         *                 0: 竖屏
         *                 1: 逆时针90度（横屏）
         *                 2: 180度（倒置竖屏）
         *                 3: 顺时针90度（横屏）
         */
        override fun onRotationChanged(rotation: Int) {
            runMain {
                // 只有在未锁定旋转时才响应旋转变化
                if (rotateLock.not())
                    rotate(rotation)
            }
        }
    }

    /**
     * 执行窗口旋转操作
     *
     * @param rotation 旋转角度值
     *
     * 功能：
     * 1. 检测是否为横屏旋转（90度或270度）
     * 2. 交换宽高的半值偏移量
     * 3. 交换Surface视图和尺寸预览器的宽高
     */
    fun rotate(rotation: Int) {
        // 检查是否为横屏旋转（90度或270度）
        if (rotation == 1 || rotation == 3) {
            // 交换半宽和半高的值
            val t = halfHeight
            halfHeight = halfWidth
            halfWidth = t

            // 获取当前Surface视图的尺寸
            val surfaceWidth = surfaceView.width
            val surfaceHeight = surfaceView.height

            // 交换Surface视图的宽高
            surfaceView.updateLayoutParams {
                width = surfaceHeight
                height = surfaceWidth
            }
        }
    }

    // ========== TextureView.SurfaceTextureListener 实现 ==========

    /**
     * TextureView的Surface纹理可用时调用
     *
     * @param surface Surface纹理对象
     * @param width Surface宽度
     * @param height Surface高度
     *
     * 功能：
     * 1. 根据窗口状态计算合适的DPI
     * 2. 调整虚拟显示器尺寸
     * 3. 设置Surface缓冲区大小
     * 4. 将Surface关联到虚拟显示器
     */
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (isMini.not() && isCollapsed.not()) {
            // 正常模式：使用实际尺寸
            newDpi = calculateDpi(width, height, calculateScreenInches(width, height)) - config.reduceDPI
            virtualDisplay.resize(width, height, newDpi)
            surface.setDefaultBufferSize(width, height)
            halfWidth = width % 2
            halfHeight = height % 2
        } else if (isMini) {
            // 迷你模式：使用原始尺寸计算DPI，但虚拟显示器使用实际尺寸
            newDpi = calculateDpi(originalWidth, originalHeight,
                calculateScreenInches(originalWidth, originalHeight)) - config.reduceDPI
            virtualDisplay.resize(width, height, newDpi)
            surface.setDefaultBufferSize(width, height)
            halfWidth = width % 2
            halfHeight = height % 2
        } else {
            // 折叠模式：使用2倍尺寸以提高清晰度
            newDpi = calculateDpi(width, height, calculateScreenInches(width, height)) - config.reduceDPI
            virtualDisplay.resize(width * 2 + halfWidth, height * 2 + halfHeight, newDpi)
            surface.setDefaultBufferSize(width * 2 + halfWidth, height * 2 + halfHeight)
        }
        // 将Surface关联到虚拟显示器
        virtualDisplay.surface = Surface(surface)
    }

    /**
     * TextureView的Surface纹理尺寸变化时调用
     *
     * @param surface Surface纹理对象
     * @param width 新的宽度
     * @param height 新的高度
     *
     * 仅在允许调整大小时响应尺寸变化
     */
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (isResize) {
            if (isMini.not() && isCollapsed.not()) {
                // 正常模式：重新计算DPI并调整虚拟显示器
                newDpi = calculateDpi(width, height, calculateScreenInches(width, height)) - config.reduceDPI
                virtualDisplay.resize(width, height, newDpi)
                surface.setDefaultBufferSize(width, height)
                halfWidth = width % 2
                halfHeight = height % 2
            } else if (isMini) {
                // 迷你模式：使用原始尺寸计算DPI，保持清晰度
                newDpi = calculateDpi(originalWidth, originalHeight,
                    calculateScreenInches(originalWidth, originalHeight)) - config.reduceDPI
                virtualDisplay.resize(width, height, newDpi)
                surface.setDefaultBufferSize(width, height)
                halfWidth = width % 2
                halfHeight = height % 2
            } else {
                // 折叠模式：使用2倍尺寸
                newDpi = calculateDpi(width, height, calculateScreenInches(width, height)) - config.reduceDPI
                virtualDisplay.resize(width * 2 + halfWidth, height * 2 + halfHeight, newDpi)
                surface.setDefaultBufferSize(width * 2 + halfWidth, height * 2 + halfHeight)
            }
        }
    }

    /**
     * TextureView的Surface纹理销毁时调用
     *
     * @param surface Surface纹理对象
     * @return true表示不需要手动清理Surface
     */
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        return true
    }

    /**
     * TextureView的Surface纹理更新时调用
     *
     * @param surface Surface纹理对象
     *
     * 当前实现为空，可在此处添加帧更新相关逻辑
     */
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // 暂无实现
    }

    /**
     * 切换迷你模式状态
     *
     * 迷你模式特点：
     * - 窗口尺寸缩小到一半
     * - 隐藏顶部控制栏和调整大小按钮
     * - 禁用Surface的触摸事件
     * - 显示根部点击遮罩以便拖拽
     */
    private fun changeMini() {
        isCollapsed = false
        isResize = false

        if (isMini) {
            // ========== 从迷你模式恢复到正常模式 ==========
            isMini = false
            binding.rootClickMask.visibility = View.GONE

            if (surfaceView is SurfaceView) {
                // SurfaceView：直接调整尺寸，无动画
                binding.cvBackground.updateLayoutParams {
                    width = originalWidth
                    height = originalHeight
                }
                setBackgroundWrapContent()
                setParrentWrapContent()

                // 根据屏幕方向显示相应的控制器
                if (orientation == 0) {
                    binding.rlBarControllerBottom.visibility = View.VISIBLE
                    binding.rlBarControllerSide.visibility = View.GONE
                } else {
                    binding.rlBarControllerSide.visibility = View.VISIBLE
                    binding.rlBarControllerBottom.visibility = View.GONE
                }
            } else {
                // TextureView：使用动画恢复尺寸
                binding.cvBackground.updateLayoutParams {
                    width = originalWidth
                    height = originalHeight
                }
                animateScaleThenResize(
                    binding.cvBackground,
                    0.5F, 0.5F,      // 起始缩放（迷你状态）
                    1F, 1F,          // 结束缩放（正常状态）
                    0F, 0F,          // 缩放中心点
                    originalWidth, originalHeight,  // 目标尺寸
                    context
                ){
                    setBackgroundWrapContent()
                    setParrentWrapContent()
                }
            }

            // 恢复UI元素显示
            if (orientation == 0) {
                binding.rlBarControllerBottom.visibility = View.VISIBLE
            } else {
                binding.rlBarControllerSide.visibility = View.VISIBLE
            }
            surfaceView.visibility = View.VISIBLE
            // 恢复Surface触摸事件监听
            surfaceView.setOnTouchListener(surfaceOnTouchListener)
            surfaceView.setOnGenericMotionListener(surfaceOnGenericMotionListener)

            // 恢复调整大小功能
            isResize = true
            return
        }
        else if (!isMini) {
            // ========== 从正常模式切换到迷你模式 ==========
            binding.rootClickMask.visibility = View.VISIBLE
            binding.rlBarControllerBottom.visibility = View.GONE
            binding.rlBarControllerSide.visibility = View.GONE
            isMini = true

            if (config.surfaceView == 1) {
                // SurfaceView：直接调整到一半尺寸
                binding.cvBackground.updateLayoutParams {
                    width = originalWidth/2
                    height = originalHeight/2
                }
            } else {
                // TextureView：使用动画缩小到一半尺寸
                animateResize(
                    binding.cvBackground,
                    originalWidth, originalWidth/2,
                    originalHeight, originalHeight/2,
                    context
                ){
                    isResize = true
                }
            }

            // 隐藏UI元素
            // 移除Surface触摸事件监听
            surfaceView.setOnTouchListener(null)
            surfaceView.setOnGenericMotionListener(null)

            return
        }
    }

    /**
     * 设置背景容器为包裹内容模式
     *
     * 将背景容器的宽高设置为WRAP_CONTENT，使其自适应内容大小
     */
    private fun setBackgroundWrapContent() {
        val layoutParams = binding.cvBackground.layoutParams
        layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        binding.cvBackground.layoutParams = layoutParams
    }

    /**
     * 设置父容器为包裹内容模式
     *
     * 将父容器的宽高设置为WRAP_CONTENT，使其自适应内容大小
     */
    private fun setParrentWrapContent() {
        val layoutParams = binding.cvParent.layoutParams
        layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        newDpi = calculateDpi(originalWidth, originalHeight,
            calculateScreenInches(originalWidth, originalHeight)) - config.reduceDPI
        binding.cvParent.layoutParams = layoutParams
    }

    /**
     * 切换折叠模式状态
     *
     * 折叠模式特点：
     * - 窗口缩小为应用图标大小
     * - 隐藏所有控制器
     * - 显示根部点击遮罩以便拖拽
     * - 可通过点击图标恢复窗口
     */
    private fun changeCollapsed() {
        isResize = false
        if (isCollapsed) {
            // 从折叠状态恢复
            binding.rootClickMask.visibility = View.GONE
            expandWindow()
        } else {
            // 切换到折叠状态
            binding.rootClickMask.visibility = View.VISIBLE
            binding.rlBarControllerBottom.visibility = View.GONE
            binding.rlBarControllerSide.visibility = View.GONE

            collapseWindow()
        }
    }

    private fun expandWindow() {
        isCollapsed = false
        binding.background.visibility = View.VISIBLE

        animateResize(
            binding.appIcon, 40.dpToPx().toInt(), 0, 40.dpToPx().toInt(), 0, context) {
            binding.cvappIcon.visibility = View.GONE
            animateResize(binding.cvBackground, 0, originalWidth, 0, originalHeight, context) {
                setBackgroundWrapContent()
                setParrentWrapContent()
                binding.cvappIcon.visibility = View.VISIBLE

                CoroutineScope(Dispatchers.Main).launch {
                    delay(200)

                    if (orientation == 0) {
                        binding.rlBarControllerBottom.visibility = View.VISIBLE
                    } else {
                        binding.rlBarControllerSide.visibility = View.VISIBLE
                    }
                }

                binding.cvappIcon.visibility = View.GONE
                isResize = true
            }
        }
    }

    private fun collapseWindow() {
        isCollapsed = true

        CoroutineScope(Dispatchers.Main).launch {
            delay(200)

            animateResize(binding.cvBackground, binding.cvBackground.width, 0, binding.cvBackground.height, 0, context) {
                binding.cvappIcon.visibility = View.VISIBLE
                binding.background.visibility = View.GONE
                animateResize(binding.appIcon, 0, 40.dpToPx().toInt(), 0, 40.dpToPx().toInt(), context)

                isResize = true
            }
        }
    }

    private fun calculateScreenInches(width: Int, height: Int): Float {
        val x = (width / context.resources.displayMetrics.xdpi).pow(2)
        val y = (height / context.resources.displayMetrics.ydpi).pow(2)

        return sqrt(x + y)
    }

    private fun calculateDpi(width: Int, height: Int, screenSizeInInches: Float): Int {
        val widthSqr = width.toFloat().pow(2)
        val heightSqr = height.toFloat().pow(2)
        val diagonalPixels = sqrt(widthSqr + heightSqr)

        return floor(diagonalPixels / screenSizeInInches).toInt()
    }

    fun forwardMotionEvent(event: MotionEvent) {
        val newEvent = MotionEvent.obtain(event)
        newEvent.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
        Instances.inputManager.injectInputEvent(newEvent, 0)
        newEvent.recycle()
    }

    inner class SurfaceOnTouchListener : View.OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            forwardMotionEvent(event)
            moveToTopIfNeed(event)
            return true
        }
    }

    inner class SurfaceOnGenericMotionListener : View.OnGenericMotionListener {
        override fun onGenericMotion(v: View, event: MotionEvent): Boolean {
            forwardMotionEvent(event)
            return true
        }
    }

    // ========== SurfaceHolder.Callback 实现 ==========

    /**
     * SurfaceView的Surface创建时调用
     *
     * @param holder SurfaceHolder对象
     *
     * 将Surface关联到虚拟显示器
     */
    override fun surfaceCreated(holder: SurfaceHolder) {
        virtualDisplay.surface = holder.surface
    }

    /**
     * SurfaceView的Surface变化时调用
     *
     * @param holder SurfaceHolder对象
     * @param format Surface格式
     * @param width Surface宽度
     * @param height Surface高度
     *
     * 重新计算DPI并调整虚拟显示器尺寸
     */
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (isMini) {
            // 迷你模式：使用原始尺寸计算DPI，保持清晰度
            newDpi = calculateDpi(originalWidth, originalHeight,
                calculateScreenInches(originalWidth, originalHeight)) - config.reduceDPI
            virtualDisplay.resize(width, height, newDpi)
        } else {
            // 正常模式：使用实际尺寸计算DPI
            newDpi = calculateDpi(width, height, calculateScreenInches(width, height)) - config.reduceDPI
            virtualDisplay.resize(width, height, newDpi)
        }
    }

    /**
     * SurfaceView的Surface销毁时调用
     *
     * @param holder SurfaceHolder对象
     *
     * 断开虚拟显示器与Surface的关联
     */
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        virtualDisplay.surface = null
    }

    private val moveGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        var startX = 0F
        var startY = 0F
        var xAnimation: FlingAnimation? = null
        var yAnimation: FlingAnimation? = null
        var lastX = 0F
        var lastY = 0F
        var last2X = 0F
        var last2Y = 0F

        override fun onDown(e: MotionEvent): Boolean {
            xAnimation?.cancel()
            yAnimation?.cancel()
            startX = binding.cvParent.translationX
            startY = binding.cvParent.translationY
            return true
        }


        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            e1 ?: return false
            val newTranslationX = startX + (e2.rawX - e1.rawX)
            val newTranslationY = startY + (e2.rawY - e1.rawY)

            // 移动小窗
            binding.cvParent.translationX = newTranslationX
            binding.cvParent.translationY = newTranslationY

            // 手动移动控制条以跟随小窗
            binding.rlBarControllerBottom.translationX = newTranslationX
            binding.rlBarControllerBottom.translationY = newTranslationY
            binding.rlBarControllerSide.translationX = newTranslationX
            binding.rlBarControllerSide.translationY = newTranslationY

            last2X = lastX
            last2Y = lastY
            lastX = e2.rawX
            lastY = e2.rawY
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            e1 ?: return false
            if (e1.source == InputDevice.SOURCE_MOUSE) return false

            runCatching {
                if (sign(velocityX) != sign(e2.rawX - last2X)) return@runCatching
                xAnimation = flingAnimationOf({
                    binding.cvParent.translationX = it
                    binding.rlBarControllerBottom.translationX = it
                    binding.rlBarControllerSide.translationX = it
                }, {
                    binding.cvParent.translationX
                })
                    .setStartVelocity(velocityX)
                    .setMinValue(-binding.cvParent.x)
                    .setMaxValue(context.display.width.toFloat() - binding.cvParent.x - binding.cvParent.width)
                xAnimation?.start()
            }
            runCatching {
                if (sign(velocityY) != sign(e2.rawY - last2Y)) return@runCatching
                yAnimation = flingAnimationOf({
                    binding.cvParent.translationY = it
                    binding.rlBarControllerBottom.translationY = it
                    binding.rlBarControllerSide.translationY = it
                }, {
                    binding.cvParent.translationY
                })
                    .setStartVelocity(velocityY)
                    .setMinValue(-binding.cvParent.y)
                    .setMaxValue(context.display.height.toFloat() - binding.cvParent.y - binding.cvParent.height)
                yAnimation?.start()
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (isMini && !isCollapsed) changeMini()
            else if (!isMini && isCollapsed) changeCollapsed()
            return true
        }
    })
}
