package com.yujiannatian.tv.core

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * 运行设备类型判定。电视端本身就是大屏接收端，不需要「投屏」功能；
 * 手机端才作为 DLNA 发送端，且投屏成功后应停止本机播放，避免两边同时出声。
 */
object DeviceKind {

    /** 纯函数，便于 JVM 单测覆盖三种判定来源 */
    fun isTelevision(
        uiModeType: Int,
        hasTelevisionFeature: Boolean,
        hasLeanbackFeature: Boolean
    ): Boolean = uiModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        hasTelevisionFeature || hasLeanbackFeature

    fun isTv(context: Context): Boolean {
        val app = context.applicationContext
        val uiMode = (app.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
            ?.currentModeType
            ?: (app.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK)
        val pm = app.packageManager
        return isTelevision(
            uiMode,
            // FEATURE_TELEVISION 常量在 API 34 被标记废弃，直接用等价的 feature 名
            runCatching { pm.hasSystemFeature("android.hardware.type.television") }.getOrDefault(false),
            runCatching { pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }.getOrDefault(false)
        )
    }
}
