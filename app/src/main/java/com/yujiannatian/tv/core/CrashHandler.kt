package com.yujiannatian.tv.core

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 兜底崩溃记录：即使出现未捕获的 Error，也把机型/系统版本/堆栈落到私有文件，
 * 下次启动时以可聚焦的错误页展示，便于在没有 adb 的电视上定位问题。
 */
object CrashHandler {

    private const val TAG = "YuJianTV"
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { save(app, thread, throwable) }
            runCatching { Log.e(TAG, "未捕获异常，进程即将退出", throwable) }
            runCatching {
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    Process.killProcess(Process.myPid())
                    Runtime.getRuntime().exit(10)
                }
            }
        }
    }

    private fun save(app: Context, thread: Thread, t: Throwable) {
        val text = buildString {
            append("时间：")
                .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()))
                .append('\n')
            append("系统：Android ").append(Build.VERSION.RELEASE)
                .append(" / API ").append(Build.VERSION.SDK_INT).append('\n')
            append("机型：").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append('\n')
            append("站点：")
                .append(runCatching { SiteConfig.baseUrl }.getOrDefault("未初始化"))
                .append('\n')
            append("线程：").append(thread.name).append("\n\n")
            append(Log.getStackTraceString(t))
        }
        runCatching { File(app.filesDir, FILE).writeText(text) }
    }

    fun lastCrash(context: Context): String? = runCatching {
        val f = File(context.applicationContext.filesDir, FILE)
        if (f.exists() && f.length() > 0) f.readText() else null
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, FILE).delete() }
    }

    /** 电视上可读的摘要：异常原因放最前面，避免长文本被截断时看不到根因 */
    fun summary(raw: String): String {
        val lines = raw.lines()
        val fatal = lines.filter {
            it.contains("Exception") || it.contains("Error") || it.startsWith("Caused by")
        }.take(3)
        val frames = lines.filter { it.contains("com.yujiannatian") }.take(4)
        val env = lines.take(5)
        return (fatal + frames + listOf("") + env).joinToString("\n").take(600)
    }
}
