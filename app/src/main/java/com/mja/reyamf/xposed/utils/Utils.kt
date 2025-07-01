package com.mja.reyamf.xposed.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.ActivityTaskManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.IPackageManagerHidden
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.UserHandle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.github.kyuubiran.ezxhelper.utils.newInstance
import com.mja.reyamf.common.model.StartCmd
import com.mja.reyamf.common.onException
import com.mja.reyamf.xposed.services.YAMFManager
import de.robv.android.xposed.XposedBridge
import net.bytebuddy.android.AndroidClassLoadingStrategy
import java.io.File

// ========== 日志工具函数 ==========

/**
 * 记录普通日志消息
 *
 * @param tag 日志标签
 * @param message 日志消息
 */
fun log(tag: String, message: String) {
    XposedBridge.log("[$tag] $message")
}

/**
 * 记录带异常的日志消息
 *
 * @param tag 日志标签
 * @param message 日志消息
 * @param t 异常对象
 */
fun log(tag: String, message: String, t: Throwable) {
    XposedBridge.log("[$tag] $message")
    XposedBridge.log(t)
}

// ========== 任务管理工具函数 ==========

/**
 * 移动任务到指定显示器
 *
 * @param taskId 任务ID
 * @param displayId 目标显示器ID
 *
 * 功能：
 * 1. 将任务的根任务栈移动到指定显示器
 * 2. 将任务移动到前台显示
 */
@SuppressLint("MissingPermission")
fun moveTask(taskId: Int, displayId: Int) {
    Instances.activityTaskManager.moveRootTaskToDisplay(taskId, displayId)
    Instances.activityManager.moveTaskToFront(taskId, 0)
}

// ========== UI工具函数 ==========

/**
 * 将dp值转换为px值的扩展函数
 *
 * @return 转换后的像素值
 */
fun Number.dpToPx() =
    TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), Resources.getSystem().displayMetrics
    )

// ========== Context工具函数 ==========

/** 空的Context参数，用于创建Context */
val emptyContextParams = ContextParams.Builder().build()

/**
 * Context扩展函数，创建新的Context实例
 *
 * @return 新的Context实例
 */
fun Context.createContext() = createContext(emptyContextParams)

// ========== 应用启动工具函数 ==========

/**
 * 在指定显示器上启动应用活动
 *
 * @param context 上下文对象
 * @param componentName 要启动的组件名称
 * @param userId 用户ID
 * @param displayId 目标显示器ID
 *
 * 功能：
 * 1. 创建启动Intent并设置必要标志
 * 2. 配置ActivityOptions指定目标显示器
 * 3. 以指定用户身份启动活动
 */
fun startActivity(context: Context, componentName: ComponentName, userId: Int, displayId: Int) {
    context.invokeMethod(
        "startActivityAsUser",
        args(
            Intent().apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                component = componentName
                `package` = component!!.packageName
                action = Intent.ACTION_VIEW
            },
            ActivityOptions.makeBasic().apply {
                launchDisplayId = displayId
                this.invokeMethod("setCallerDisplayId", args(displayId), argTypes(Integer.TYPE))
            }.toBundle(),
            UserHandle::class.java.newInstance(
                args(userId),
                argTypes(Integer.TYPE)
            )
        ), argTypes(Intent::class.java, Bundle::class.java, UserHandle::class.java)
    )
}

/**
 * 将应用移动到指定显示器（智能策略）
 *
 * @param context 上下文对象
 * @param taskId 任务ID
 * @param componentName 组件名称
 * @param userId 用户ID
 * @param displayId 目标显示器ID
 *
 * 根据配置的窗口化策略执行不同操作：
 * - 策略0: 仅移动任务
 * - 策略1: 仅启动活动
 * - 策略2: 移动任务，失败时回退到启动活动
 */
fun moveToDisplay(context: Context, taskId: Int, componentName: ComponentName, userId: Int, displayId: Int) {
    when (YAMFManager.config.windowfy) {
        0 -> {
            // 策略0: 仅移动任务
            runCatching {
                moveTask(taskId, displayId)
            }.onException {
                TipUtil.showToast("Unable to move task $taskId")
            }
        }
        1 -> {
            // 策略1: 仅启动活动
            runCatching {
                startActivity(context, componentName, userId, displayId)
            }.onException {
                TipUtil.showToast("Unable to start activity $componentName")
            }
        }
        2 -> {
            // 策略2: 移动任务，失败时回退到启动活动
            runCatching {
                moveTask(taskId, displayId)
            }.onException {
                TipUtil.showToast("Unable to move task $taskId")
                runCatching {
                    startActivity(context, componentName, userId, displayId)
                }.onException {
                    TipUtil.showToast("Unable to start activity $componentName")
                }
            }
        }
    }
}

fun StartCmd.startAuto(displayId: Int) {
    when {
        canStartActivity && canMoveTask ->
            moveToDisplay(Instances.systemContext, taskId!!, componentName!!, userId!!, displayId)
        canMoveTask -> {
            runCatching {
                moveTask(taskId!!, displayId)
            }.onException {
                TipUtil.showToast("can't move task $taskId")
            }
        }
        canStartActivity -> {
            runCatching {
                startActivity(Instances.systemContext, componentName!!, userId!!, displayId)
            }.onException {
                TipUtil.showToast("can't start activity $componentName")
            }
        }
    }
}

fun getTopRootTask(displayId: Int): ActivityTaskManager.RootTaskInfo? {
    Instances.activityTaskManager.getAllRootTaskInfosOnDisplay(displayId).forEach { task ->
        if (task.visible)
            return task
    }
    return null
}

fun Context.registerReceiver(action: String, onReceive: BroadcastReceiver.(Context, Intent) -> Unit) {
    registerReceiver(object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onReceive(this, context, intent)
        }
    }, android.content.IntentFilter(action), Context.RECEIVER_EXPORTED)
}


val ActivityInfo.componentName: ComponentName
    get() = ComponentName(packageName, name)

fun IPackageManagerHidden.getActivityInfoCompat(className: ComponentName, flags: Int, userId: Int): ActivityInfo =
    getActivityInfo(className, flags.toLong(), userId)

fun vibratePhone(context: Context) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    vibrator.vibrate(VibrationEffect.createOneShot(5, VibrationEffect.DEFAULT_AMPLITUDE))
}

fun animateResize(
    view: View,
    startWidth: Int,
    endWidth: Int,
    startHeight: Int,
    endHeight: Int,
    context: Context,
    baseDuration: Long = 300L,
    onEnd: (() -> Unit)? = null
) {
    val scale = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
    } catch (e: Settings.SettingNotFoundException) {
        1.0f // fallback to normal scale if not found
    }

    val adjustedDuration = (baseDuration * scale).toLong()

    val widthAnimator = ValueAnimator.ofInt(startWidth, endWidth).apply {
        addUpdateListener { animator ->
            val value = animator.animatedValue as Int
            val params = view.layoutParams
            params.width = value
            view.layoutParams = params
        }
    }

    val heightAnimator = ValueAnimator.ofInt(startHeight, endHeight).apply {
        addUpdateListener { animator ->
            val value = animator.animatedValue as Int
            val params = view.layoutParams
            params.height = value
            view.layoutParams = params
        }
    }

    AnimatorSet().apply {
        playTogether(widthAnimator, heightAnimator)
        duration = adjustedDuration
        interpolator = AccelerateDecelerateInterpolator()
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                onEnd?.invoke()
            }
        })
        start()
    }
}

fun animateScaleThenResize(
    view: View,
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    pivotX: Float,
    pivotY: Float,
    endWidth: Int,
    endHeight: Int,
    context: Context,
    baseDuration: Long = 300L,
    onEnd: (() -> Unit)? = null
) {
    val scale = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
    } catch (e: Settings.SettingNotFoundException) {
        1.0f
    }

    val adjustedDuration = (baseDuration * scale).toLong()

    val scaleAnimation = ScaleAnimation(
        startX, endX,
        startY, endY,
        Animation.RELATIVE_TO_SELF, pivotX,
        Animation.RELATIVE_TO_SELF, pivotY
    ).apply {
        duration = adjustedDuration
        fillAfter = false
        interpolator = AccelerateDecelerateInterpolator()
        setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) {}

            override fun onAnimationEnd(animation: Animation?) {
                val params = view.layoutParams
                params.width = endWidth
                params.height = endHeight
                view.layoutParams = params
                onEnd?.invoke()
            }

            override fun onAnimationRepeat(animation: Animation?) {}
        })
    }

    view.startAnimation(scaleAnimation)
}


fun animateAlpha(view: View, startAlpha: Float, endAlpha: Float, onEnd: (() -> Unit)? = null) {
    if (endAlpha == 1F) view.visibility = View.VISIBLE
    val animation1 = AlphaAnimation(startAlpha, endAlpha)
    animation1.duration = 300

    animation1.setAnimationListener(object : Animation.AnimationListener {
        override fun onAnimationStart(animation: Animation?) {}

        override fun onAnimationEnd(animation: Animation?) {
            onEnd?.invoke()
        }

        override fun onAnimationRepeat(animation: Animation?) {}
    })

    view.startAnimation(animation1)
    if (endAlpha == 1F) view.visibility = View.VISIBLE else view.visibility = View.GONE
}

fun drawableToBitmap(drawable: Drawable): Bitmap {
    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 1
    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 1

    val bitmap = Bitmap.createBitmap(
        width,
        height,
        Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}


val byteBuddyStrategy = AndroidClassLoadingStrategy.Wrapping(File("/data/system/reYAMF").also { it.mkdirs() })
