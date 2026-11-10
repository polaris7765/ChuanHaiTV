package com.yujiannatian.tv.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.yujiannatian.tv.core.DeviceKind

/**
 * 触屏 / 遥控器的点击行为分流。
 *
 * 症状：手机上按钮、侧栏、剧集卡片要点两次才生效。根因是 TvButton / TvChip 样式与
 * item_sidebar、item_video、card_text 都声明了 `focusableInTouchMode=true`——触摸模式下
 * 第一次点按只把焦点交给控件，第二次点按才触发 OnClickListener。
 *
 * 处理：
 * · TV：`DeviceKind.isTv` 为真时直接返回，一个焦点属性都不改，完整保留原有
 *   「方向键聚焦 → 确认键生效」的 D-pad 链路；
 * · 手机：只清掉 focusableInTouchMode，保留 focusable=true，单击即触发点击；
 *   非触摸模式（外接键盘、蓝牙遥控器）下的焦点链不受影响。
 *
 * 输入框例外：EditText 必须保留触摸聚焦，否则点击地址框 / 搜索框无法唤起输入法。
 *
 * 通过 Application.ActivityLifecycleCallbacks 统一挂载，并在每次布局变化时重扫一遍，
 * 覆盖 Fragment 切换、状态层、RecyclerView 动态创建的行以及播放页动态倍速按钮，
 * 不需要在每个 Adapter 里重复处理。只有属性确实为 true 时才写入，不会引起布局循环。
 */
object TouchFocus {

    /** 纯函数：只有非 TV 设备才改写触摸焦点属性，便于 JVM 单测覆盖设备分流 */
    fun shouldAdjust(isTv: Boolean): Boolean = !isTv

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (!shouldAdjust(DeviceKind.isTv(activity))) return
                val decor = activity.window?.decorView ?: return
                decor.viewTreeObserver.addOnGlobalLayoutListener { applyPhone(decor) }
                decor.post { applyPhone(decor) }
            }

            override fun onActivityResumed(activity: Activity) {
                if (!shouldAdjust(DeviceKind.isTv(activity))) return
                val decor = activity.window?.decorView ?: return
                decor.post { applyPhone(decor) }
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun applyPhone(root: View) = applyPhoneInternal(root)

    private fun applyPhoneInternal(v: View) {
        if (v is EditText) return
        if (v.isFocusableInTouchMode) v.isFocusableInTouchMode = false
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) applyPhoneInternal(v.getChildAt(i))
        }
    }
}
