package com.yujiannatian.tv.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yujiannatian.tv.R
import com.yujiannatian.tv.ui.DownloadsActivity

/**
 * 下载前台服务：让 App 退到后台 / 熄屏后，下载协程仍能持续运行。
 *
 * 下载本身跑在 [VideoDownloader] 的 Application 级协程里，只要进程活着就会继续；
 * 但 Android 8+ 会限制纯后台进程、低内存时优先回收，电视盒子还会在待机时停 CPU。
 * 这里用前台服务把进程优先级提到「用户可感知」级别，并持有 PARTIAL_WAKE_LOCK，
 * 通知常驻显示任务数与平均进度；所有任务结束后由 VideoDownloader 主动 stopService。
 *
 * Android 12+ 禁止「从后台」启动前台服务，下载都由用户在前台点击发起，
 * 且 [ensureStarted] 对异常做了兜底，即使启动失败也不会中断下载本身。
 */
class DownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ID = 1001

        fun ensureStarted(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, DownloadService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(app, intent)
                } else {
                    app.startService(intent)
                }
            }
        }

        fun requestStop(context: Context) {
            runCatching {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, DownloadService::class.java)
                )
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val listener: (String, DownloadState) -> Unit = { _, _ -> refreshNotification() }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 必须在 startForegroundService 后尽快调用，否则系统会杀掉服务
        startForeground(NOTIF_ID, buildNotification())
        VideoDownloader.observe(listener)
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChuanHaiTV:downloads")
            ?.apply {
                setReferenceCounted(false)
                runCatching { acquire(60 * 60 * 1000L) } // 一小时上限，防止异常残留
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        VideoDownloader.unobserve(listener)
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID, "视频下载", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台下载进度"
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun refreshNotification() {
        // Android 13+ 未授予通知权限时 notify 可能被限制，不能让它影响下载主流程
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIF_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val (count, progress) = VideoDownloader.activeSummary()
        val intent = Intent(this, DownloadsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(this, 0, intent, piFlags)
        val text = if (count > 0) "$count 个任务进行中 · 平均 $progress%" else "正在整理下载任务…"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name) + " · 后台下载")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), count == 0)
            .build()
    }
}
