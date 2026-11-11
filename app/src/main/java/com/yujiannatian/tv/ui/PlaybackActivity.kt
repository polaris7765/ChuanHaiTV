package com.yujiannatian.tv.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.yujiannatian.tv.R
import com.yujiannatian.tv.core.CastController
import com.yujiannatian.tv.core.CastDevice
import com.yujiannatian.tv.core.DeviceKind
import com.yujiannatian.tv.core.DownloadsStore
import com.yujiannatian.tv.core.LibraryEntry
import com.yujiannatian.tv.core.LibraryStore
import com.yujiannatian.tv.core.LoadResult
import com.yujiannatian.tv.core.SiteConfig
import com.yujiannatian.tv.core.SiteHttpClient
import com.yujiannatian.tv.core.SiteRepository
import com.yujiannatian.tv.core.VideoItem
import com.yujiannatian.tv.core.advice
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import java.io.File

/**
 * 全屏 HLS/MP4 播放。左上角返回、右上角投屏、右下角设置与 PlayerView 控制条严格同显隐：
 * 关闭 media3 自动显隐，由本页自己的定时器统一管理——触屏或任意遥控器按键唤起控制层
 * （四角按钮 + 控制条一起出现），4000ms 后一起隐藏，杜绝「再次唤起后按钮不出来」的问题。
 * 设置菜单提供倍速播放与投屏入口；播放期间保持屏幕常亮。
 * TV 端隐藏所有投屏入口；手机端投屏成功后暂停本机播放，可手动恢复。
 * 遥控器：OK 暂停、左右快退快进、上到顶部按钮、下到右下角设置、播完自动下一集。
 */
@UnstableApi
class PlaybackActivity : FragmentActivity() {

    private val uiHandler = CoroutineExceptionHandler { _, t ->
        Log.e("YuJianTV", "播放页加载异常", t)
        val e = t.toSiteError()
        showPlayerError("加载失败：${e.message}\n${e.advice()}")
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_EPISODE = "episode"
        private const val EXTRA_URL = "url"
        private const val EXTRA_PATHS = "paths"
        private const val EXTRA_NAMES = "names"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_VIDEO_PATH = "video_path"
        private const val EXTRA_VIDEO_COVER = "video_cover"
        private const val EXTRA_START_POSITION = "start_position"
        /** 本地下载文件对应的下载 key：用它把播放进度写回 DownloadsStore */
        private const val EXTRA_DOWNLOAD_KEY = "download_key"

        fun start(
            context: Context,
            title: String,
            episodeName: String,
            url: String,
            paths: List<String>,
            names: List<String>,
            index: Int,
            videoPath: String = "",
            cover: String = "",
            startPositionMs: Long = 0L,
            downloadKey: String = ""
        ) {
            context.startActivity(
                Intent(context, PlaybackActivity::class.java)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_EPISODE, episodeName)
                    .putExtra(EXTRA_URL, url)
                    .putStringArrayListExtra(EXTRA_PATHS, ArrayList(paths))
                    .putStringArrayListExtra(EXTRA_NAMES, ArrayList(names))
                    .putExtra(EXTRA_INDEX, index)
                    .putExtra(EXTRA_VIDEO_PATH, videoPath)
                    .putExtra(EXTRA_VIDEO_COVER, cover)
                    .putExtra(EXTRA_START_POSITION, startPositionMs)
                    .putExtra(EXTRA_DOWNLOAD_KEY, downloadKey)
            )
        }

        /**
         * 播放已下载的本地文件：从下载记录里读出上次观看位置作为续播起点；
         * 播放完成会写 0，下次从头开始。进度不写进「历史记录」，避免与在线续播混淆。
         */
        fun startLocal(
            context: Context,
            title: String,
            episodeName: String,
            file: String,
            downloadKey: String
        ) {
            val resume = DownloadsStore.positionOf(context.applicationContext, downloadKey)
            start(
                context, title, episodeName,
                Uri.fromFile(File(file)).toString(),
                emptyList(), emptyList(), 0,
                startPositionMs = resume,
                downloadKey = downloadKey
            )
        }
    }

    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var errorPanel: View
    private lateinit var errorText: TextView
    private lateinit var castPanel: LinearLayout
    private lateinit var castStatus: TextView
    private lateinit var castDevices: LinearLayout
    private lateinit var castManual: EditText
    private lateinit var castAdd: Button
    private lateinit var castUrlHint: TextView
    private lateinit var btnCast: Button
    private lateinit var controlOverlay: View
    private lateinit var btnBackTop: Button
    private lateinit var btnSettings: Button
    private lateinit var settingsPanel: LinearLayout
    private lateinit var speedRow: LinearLayout
    private lateinit var speedHint: TextView
    private lateinit var btnResumeLocal: Button

    private val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var playbackSpeed = 1.0f
    private var castFromSettings = false
    private var castActive = false

    /** TV 本身是接收端，隐藏投屏入口；手机端投屏成功后停止本机播放 */
    private val isTv: Boolean by lazy { DeviceKind.isTv(this) }

    private var player: ExoPlayer? = null
    private var currentUrl: String = ""
    private var videoTitle: String = ""
    private var videoPath: String = ""
    private var videoCover: String = ""
    private var episodeName: String = ""
    /** 外部传入的续播起点（毫秒），>0 时准备完成后跳转到该进度 */
    private var startPositionMs = 0L
    /** 本地下载文件的 key：非空时把播放进度写回 DownloadsStore */
    private var downloadKey: String = ""
    /** 本集已播放到结尾：退出时进度清零，下次从头开始 */
    private var completed = false
    private var paths: List<String> = emptyList()
    private var names: List<String> = emptyList()
    private var currentIndex = 0
    private var lastDevice: CastDevice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)
        TvFullscreen.apply(this)
        // 视频播放中禁止锁屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = findViewById(R.id.player_view)
        titleView = findViewById(R.id.play_title)
        errorPanel = findViewById(R.id.error_panel)
        errorText = findViewById(R.id.error_text)
        castPanel = findViewById(R.id.cast_panel)
        castStatus = findViewById(R.id.cast_status)
        castDevices = findViewById(R.id.cast_devices)
        castManual = findViewById(R.id.cast_manual_url)
        castAdd = findViewById(R.id.btn_cast_add)
        castUrlHint = findViewById(R.id.cast_url_hint)
        btnCast = findViewById(R.id.btn_cast)
        controlOverlay = findViewById(R.id.control_overlay)
        btnBackTop = findViewById(R.id.btn_back_top)
        btnSettings = findViewById(R.id.btn_settings)
        settingsPanel = findViewById(R.id.settings_panel)
        speedRow = findViewById(R.id.speed_row)
        speedHint = findViewById(R.id.speed_hint)
        btnResumeLocal = findViewById(R.id.btn_cast_resume)

        videoTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        episodeName = intent.getStringExtra(EXTRA_EPISODE) ?: ""
        paths = intent.getStringArrayListExtra(EXTRA_PATHS) ?: arrayListOf()
        names = intent.getStringArrayListExtra(EXTRA_NAMES) ?: arrayListOf()
        currentIndex = intent.getIntExtra(EXTRA_INDEX, 0)
        currentUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH).orEmpty()
        videoCover = intent.getStringExtra(EXTRA_VIDEO_COVER).orEmpty()
        startPositionMs = intent.getLongExtra(EXTRA_START_POSITION, 0L)
        downloadKey = intent.getStringExtra(EXTRA_DOWNLOAD_KEY).orEmpty()

        // 控制层显隐完全由我们自己管理：关闭 media3 的自动显隐，避免两套计时器错开。
        // 触屏 / 遥控器任意按键都会唤起控制层（四角按钮 + PlayerView 控制条一起出现），
        // 并在 4000ms 后一起隐藏。这是「再次唤起后返回 / 设置按钮不出来」的根因修复。
        playerView.controllerAutoShow = false
        playerView.controllerShowTimeoutMs = 0
        playerView.setOnTouchListener { _, _ -> revealControls(); false }

        btnBackTop.setOnClickListener { finish() }
        btnSettings.setOnClickListener { showSettingsPanel() }
        findViewById<Button>(R.id.btn_settings_close).setOnClickListener { hideSettingsPanel() }
        findViewById<Button>(R.id.btn_settings_cast).setOnClickListener {
            if (isTv) return@setOnClickListener
            castFromSettings = true
            hideSettingsPanel()
            showCastPanel()
        }
        buildSpeedButtons()
        applyDeviceRules()

        updateTitle()
        findViewById<Button>(R.id.btn_retry).setOnClickListener { reloadCurrent() }
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        setupCastPanel()
        if (currentUrl.isNotBlank()) prepare(currentUrl)
        else showPlayerError("没有拿到播放地址，请返回选择其它播放源。")
    }

    override fun onStart() {
        super.onStart()
        if (player == null && currentUrl.isNotBlank() && errorPanel.visibility != View.VISIBLE) {
            prepare(currentUrl)
        } else if (player != null) {
            revealControls()
        }
    }

    override fun onStop() {
        saveProgress()
        saveLocalProgress()
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        controlOverlay.removeCallbacks(hideControlsRunnable)
        super.onDestroy()
    }

    /** 退出播放时（返回 / 换源 / 系统销毁）保存当前进度，供历史 / 收藏续播使用 */
    override fun finish() {
        saveProgress()
        saveLocalProgress()
        super.finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) TvFullscreen.apply(this)
    }

    private fun updateTitle() {
        titleView.text = listOf(videoTitle, episodeName).filter { it.isNotBlank() }
            .joinToString("  ·  ")
    }

    /**
     * 按设备类型调整控制层：两端都不显示右上角的独立投屏按钮
     * （手机端投屏入口在设置面板内，TV 端本身就是大屏接收端，不提供投屏）。
     * 「设置」入口统一放在右上角（已在 XML 中置于 top|end），与左上角「返回」呼应。
     */
    private fun applyDeviceRules() {
        btnCast.visibility = View.GONE
        // 设置面板里的「投屏」：手机端是发送端，必须保留；TV 是接收端才隐藏。
        // 之前这里无条件 GONE，导致手机端播放页设置里没有投屏功能。
        findViewById<Button>(R.id.btn_settings_cast).visibility =
            if (isTv) View.GONE else View.VISIBLE
        castPanel.visibility = View.GONE
        btnResumeLocal.visibility = View.GONE
        // 焦点导航两端一致：左上「返回」⇄ 右上「设置」，向下落到播放器
        btnBackTop.nextFocusRightId = R.id.btn_settings
        btnBackTop.nextFocusDownId = R.id.player_view
        btnSettings.nextFocusLeftId = R.id.btn_back_top
        btnSettings.nextFocusUpId = R.id.btn_back_top
        btnSettings.nextFocusDownId = R.id.player_view
    }

    /** 记入首页「历史记录」：同一部影片只留最新一集（初始进度为 0） */
    private fun recordHistory() {
        saveProgress(0L)
    }

    /** 保存（或更新）当前播放进度；position<=0 时取播放器实时进度 */
    private fun saveProgress(position: Long = player?.currentPosition ?: 0L) {
        if (videoPath.isBlank()) return
        LibraryStore.addHistory(
            this,
            LibraryEntry(
                video = VideoItem(title = videoTitle, path = videoPath, cover = videoCover),
                episode = episodeName,
                episodePath = paths.getOrNull(currentIndex).orEmpty(),
                position = if (position > 0L) position else (player?.currentPosition ?: 0L)
            )
        )
    }

    /**
     * 本地下载文件的播放进度写回 DownloadsStore，不进「历史记录」。
     * 播放完毕或剩余不足 3 秒视为「看完了」，保存 0，下次从头开始播。
     */
    private fun saveLocalProgress() {
        if (downloadKey.isBlank()) return
        val p = player?.currentPosition ?: return
        val duration = player?.duration ?: 0L
        val finished = completed || (duration > 0 && p >= duration - 3_000L)
        DownloadsStore.setPosition(
            this,
            downloadKey,
            if (finished) 0L else p.coerceAtLeast(0L)
        )
    }

    private fun prepare(url: String) {
        currentUrl = url
        completed = false // 换集 / 重试后重新按「未播完」计
        errorPanel.visibility = View.GONE
        releasePlayer()
        recordHistory()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(SiteHttpClient.USER_AGENT)
            .setDefaultRequestProperties(mapOf("Referer" to SiteConfig.baseUrl))
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        // DefaultDataSource 同时支持 http(s) 与本地 file://（离线播放下载文件）
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)

        val local = url.startsWith("file:", true) || url.startsWith("content:", true) ||
            url.startsWith("/")
        val mediaItem = if (local) MediaItem.fromUri(Uri.parse(url)) else MediaItem.fromUri(url)
        val source = if (!local && url.contains(".m3u8", ignoreCase = true)) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }

        player = ExoPlayer.Builder(this).build().also { p ->
            playerView.player = p
            // player 挂载后再唤起，避免 media3 在 player==null 时把控制条设为常驻
            revealControls()
            p.setPlaybackSpeed(playbackSpeed) // 换集 / 重试后保留已选倍速
            p.setMediaSource(source)
            p.prepare()
            p.playWhenReady = true
            // 续播：外部传入了上次退出时的进度，准备好后直接跳到该位置继续播放
            if (startPositionMs > 0L) {
                Log.i("YuJianTV", "续播跳转 ${startPositionMs}ms（下载记录或历史记录）")
                p.seekTo(startPositionMs)
            }
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    showPlayerError(
                        "播放失败：${error.message ?: error.errorCodeName}\n" +
                            "可能是线路失效或被防盗链拦截，可重试或返回换一个播放源。"
                    )
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState != Player.STATE_ENDED) return
                    completed = true
                    // 播放完不再保留进度：本地下载文件下次从头播
                    saveLocalProgress()
                    if (paths.isNotEmpty()) playNext()
                    else titleView.text = "$videoTitle  ·  已播完"
                }
            })
        }
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        playerView.player = null
    }

    private fun showPlayerError(message: String) {
        errorText.text = message
        errorPanel.visibility = View.VISIBLE
        // 出错时隐藏控制层，避免四角按钮与错误页抢焦点
        hideControls()
        errorPanel.post { findViewById<Button>(R.id.btn_retry).requestFocus() }
    }

    private fun reloadCurrent() {
        val episodePath = paths.getOrNull(currentIndex)
        if (episodePath.isNullOrBlank()) {
            showPlayerError("本地文件或当前剧集信息缺失，请返回重新选择。")
            return
        }
        SiteRepository.invalidatePlayback(episodePath)
        errorText.text = "正在重新获取播放地址…"
        lifecycleScope.launch(uiHandler) {
            when (val r = SiteRepository.playback(episodePath)) {
                is LoadResult.Ok -> prepare(r.value.url)
                is LoadResult.Err -> showPlayerError(
                    "重试失败：${r.error.message ?: ""}\n${r.error.advice()}"
                )
            }
        }
    }

    private fun playNext() {
        val next = currentIndex + 1
        if (next >= paths.size) {
            titleView.text = "$videoTitle  ·  已播完"
            return
        }
        currentIndex = next
        episodeName = names.getOrNull(next) ?: "第 ${next + 1} 集"
        updateTitle()
        recordHistory()
        errorText.text = "正在加载下一集…"
        errorPanel.visibility = View.VISIBLE
        lifecycleScope.launch(uiHandler) {
            when (val r = SiteRepository.playback(paths[next])) {
                is LoadResult.Ok -> prepare(r.value.url)
                is LoadResult.Err -> showPlayerError(
                    "下一集解析失败：${r.error.message ?: ""}\n${r.error.advice()}"
                )
            }
        }
    }

    // ------------------------------------------------- 控制层显隐 / 设置菜单

    /** 唤起控制层：四角按钮与 PlayerView 控制条一起出现，并重置 4 秒自动隐藏计时 */
    private fun revealControls() {
        if (isFinishing) return
        playerView.showController()
        controlOverlay.visibility = View.VISIBLE
        hideBuiltinSettings()
        scheduleHide()
    }

    /** 隐藏控制层：四角按钮与 PlayerView 控制条一起消失 */
    private fun hideControls() {
        controlOverlay.visibility = View.GONE
        playerView.hideController()
        controlOverlay.removeCallbacks(hideControlsRunnable)
    }

    private val hideControlsRunnable = Runnable { hideControls() }

    private fun scheduleHide() {
        controlOverlay.removeCallbacks(hideControlsRunnable)
        controlOverlay.postDelayed(hideControlsRunnable, 4_000L)
    }

    /** 隐藏 PlayerView 自带的右下角设置齿轮，避免与我们的「设置」菜单重复 */
    private fun hideBuiltinSettings() {
        runCatching {
            val builtinId = resources.getIdentifier("exo_settings", "id", packageName)
            if (builtinId != 0) {
                playerView.findViewById<View>(builtinId)?.let {
                    if (it.visibility != View.GONE) it.visibility = View.GONE
                }
            }
        }
    }

    private fun buildSpeedButtons() {
        speeds.forEach { speed ->
            LayoutInflater.from(this).inflate(R.layout.item_speed, speedRow, true)
            val btn = speedRow.getChildAt(speedRow.childCount - 1) as Button
            btn.text = "${speed}x"
            btn.tag = speed
            // 明确下移焦点，避免焦点系统跳到其它位置的按钮；
            // TV 端「投屏」入口已隐藏，直接落到「关闭」
            btn.nextFocusDownId = if (isTv) R.id.btn_settings_close else R.id.btn_settings_cast
            btn.setOnClickListener { setPlaybackSpeed(speed) }
        }
        updateSpeedUi()
    }

    private fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        player?.setPlaybackSpeed(speed)
        updateSpeedUi()
    }

    private fun updateSpeedUi() {
        for (i in 0 until speedRow.childCount) {
            val btn = speedRow.getChildAt(i) as Button
            btn.isSelected = (btn.tag as? Float) == playbackSpeed
        }
        speedHint.text = "倍速播放（当前 ${playbackSpeed}x）"
        btnSettings.text = "${getString(R.string.action_settings)} · ${playbackSpeed}x"
    }

    private fun showSettingsPanel() {
        castPanel.visibility = View.GONE
        // 面板打开时隐藏自定义四角按钮与 media3 控制条，避免叠在面板上抢焦点
        hideControls()
        settingsPanel.visibility = View.VISIBLE
        var index = 0
        for (i in speeds.indices) {
            if (speeds[i] == playbackSpeed) {
                index = i
                break
            }
        }
        speedRow.getChildAt(index)?.requestFocus()
    }

    private fun hideSettingsPanel() {
        settingsPanel.visibility = View.GONE
        revealControls()
        btnSettings.requestFocus()
    }

    // ---------------------------------------------------------------- 投屏

    private fun setupCastPanel() {
        // 电视端本身就是大屏接收端，不提供投屏，整个面板保持隐藏
        if (isTv) return
        castUrlHint.text = "当前播放地址：$currentUrl\n（点击本行可复制地址，供手动投屏使用）"
        castUrlHint.isFocusable = true
        castUrlHint.isClickable = true
        castUrlHint.setOnClickListener {
            val cm = ContextCompat.getSystemService(this, ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("video_url", currentUrl))
            Toast.makeText(this, "播放地址已复制", Toast.LENGTH_SHORT).show()
        }

        btnCast.setOnClickListener {
            castFromSettings = false
            showCastPanel()
        }
        findViewById<Button>(R.id.btn_cast_rescan).setOnClickListener { discoverDevices() }
        findViewById<Button>(R.id.btn_cast_close).setOnClickListener { hideCastPanel() }
        findViewById<Button>(R.id.btn_cast_stop).setOnClickListener { stopCast() }
        findViewById<Button>(R.id.btn_cast_manual).setOnClickListener {
            castManual.visibility = View.VISIBLE
            castAdd.visibility = View.VISIBLE
            castManual.requestFocus()
        }
        castAdd.setOnClickListener { addManualDevice() }
        btnResumeLocal.visibility = View.GONE
        btnResumeLocal.setOnClickListener { resumeLocalPlayback() }
    }

    private fun showCastPanel() {
        if (isTv) return
        settingsPanel.visibility = View.GONE
        hideControls()
        castPanel.visibility = View.VISIBLE
        castStatus.text = "正在搜索同一局域网内的投屏设备…"
        castPanel.requestFocus()
        discoverDevices()
    }

    private fun hideCastPanel() {
        castPanel.visibility = View.GONE
        castManual.visibility = View.GONE
        castAdd.visibility = View.GONE
        revealControls()
        if (castFromSettings) {
            castFromSettings = false
            showSettingsPanel()
        } else if (isTv) {
            btnSettings.requestFocus()
        } else {
            btnCast.requestFocus()
        }
    }

    private fun discoverDevices() {
        castStatus.text = "正在搜索同一局域网内的投屏设备…"
        castDevices.removeAllViews()
        lifecycleScope.launch(uiHandler) {
            val devices = CastController.discover(this@PlaybackActivity, 4000L)
            castDevices.removeAllViews()
            if (devices.isEmpty()) {
                castStatus.text = "未发现设备。请确认播放设备与本设备在同一 Wi-Fi，" +
                    "并已开启 DLNA / 投屏接收；也可点「手动添加」输入设备描述地址。"
            } else {
                castStatus.text = "发现 ${devices.size} 台设备，选择后按确认键投屏："
                devices.forEach { addDeviceButton(it) }
            }
            if (devices.isNotEmpty()) castDevices.getChildAt(0)?.requestFocus()
            else findViewById<Button>(R.id.btn_cast_rescan).requestFocus()
        }
    }

    private fun addDeviceButton(device: CastDevice) {
        LayoutInflater.from(this).inflate(R.layout.item_cast_device, castDevices, true)
        val btn = castDevices.getChildAt(castDevices.childCount - 1) as Button
        btn.text = device.name
        btn.setOnClickListener { doCast(device) }
    }

    private fun addManualDevice() {
        val raw = castManual.text.toString().trim()
        if (raw.isBlank()) {
            castStatus.text = "请填写设备描述地址（rootDesc.xml）。"
            return
        }
        val url = if (raw.startsWith("http", true)) raw else "http://$raw"
        castStatus.text = "正在读取设备描述…"
        lifecycleScope.launch(uiHandler) {
            val device = CastController.fromLocation(url)
            if (device == null) {
                castStatus.text = "无法读取设备描述，请检查地址是否正确、设备是否在线。"
            } else {
                castManual.visibility = View.GONE
                castAdd.visibility = View.GONE
                castStatus.text = "已添加设备：${device.name}"
                addDeviceButton(device)
                castDevices.getChildAt(castDevices.childCount - 1)?.requestFocus()
            }
        }
    }

    private fun doCast(device: CastDevice) {
        if (currentUrl.startsWith("file:", true)) {
            castStatus.text = "本地文件无法直接投屏，请使用在线播放源后再投屏。"
            return
        }
        castStatus.text = "正在向「${device.name}」发送播放指令…"
        lifecycleScope.launch(uiHandler) {
            val error = CastController.cast(device, currentUrl, "$videoTitle · $episodeName")
            lastDevice = device
            if (error == null) {
                // 手机作为发送端：投屏成功后停止本机播放，避免两端同时出声 / 重复计费流量
                if (!isTv) {
                    player?.pause()
                    castActive = true
                    btnResumeLocal.visibility = View.VISIBLE
                }
                castStatus.text = if (isTv) {
                    "已投屏到「${device.name}」。"
                } else {
                    "已投屏到「${device.name}」，本机播放已停止。\n" +
                        "点「本机继续播放」可恢复本机播放，或点「停止投屏」结束。"
                }
            } else {
                castStatus.text =
                    "投屏失败：$error\n设备可能不支持该视频格式，可换播放源或手动复制地址。"
            }
        }
    }

    /** 手机端投屏后想回到本机观看：结束远端播放并恢复本机 */
    private fun resumeLocalPlayback() {
        if (!castActive) return
        castActive = false
        btnResumeLocal.visibility = View.GONE
        player?.playWhenReady = true
        lastDevice?.let { device ->
            lifecycleScope.launch(uiHandler) { CastController.stop(device) }
        }
        lastDevice = null
        castStatus.text = "已恢复本机播放，并结束远端投屏。"
        Toast.makeText(this, "已恢复本机播放", Toast.LENGTH_SHORT).show()
    }

    private fun stopCast() {
        val device = lastDevice
        if (device == null) {
            castStatus.text = "尚未向任何设备投屏。"
            return
        }
        lifecycleScope.launch(uiHandler) {
            val error = CastController.stop(device)
            castStatus.text = if (error == null) "已停止投屏。" else "停止失败：$error"
            if (error == null) {
                lastDevice = null
                castActive = false
                btnResumeLocal.visibility = View.GONE
                // 结束远端播放后手机端恢复，避免停留在黑屏暂停状态
                if (!isTv) player?.playWhenReady = true
            }
        }
    }

    /**
     * 焦点是否落在播放器区域内。只有这时方向键 / 确认键才作为播放快捷键；
     * 焦点在控制层按钮（返回 / 投屏 / 设置）上时，必须把按键交还给焦点系统，
     * 否则确认键只会暂停播放，按钮永远点不中。
     */
    private fun focusInPlayer(): Boolean {
        val focused = currentFocus ?: return true
        var v: View? = focused
        while (v != null && v !== playerView) {
            v = v.parent as? View
        }
        return v === playerView
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
        // 返回键优先处理：面板打开时先关面板；电视端控制 UI 显示时只隐藏 UI、不退出播放；
        // 控制层已隐藏（或手机端）时，再交给系统退出。
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (settingsPanel.visibility == View.VISIBLE) {
                hideSettingsPanel()
                return true
            }
            if (castPanel.visibility == View.VISIBLE) {
                hideCastPanel()
                return true
            }
            if (isTv && controlOverlay.visibility == View.VISIBLE) {
                hideControls()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        // 任意其它按键先唤起控制层：返回 / 设置按钮与 PlayerView 控制条同显隐，
        // 并一起在 4000ms 后自动隐藏。
        if (castPanel.visibility != View.VISIBLE &&
            settingsPanel.visibility != View.VISIBLE &&
            errorPanel.visibility != View.VISIBLE
        ) revealControls()

        if (castPanel.visibility == View.VISIBLE) {
            // 面板打开时把方向键交给焦点系统，避免被播放器快捷键吃掉
            return super.dispatchKeyEvent(event)
        }
        if (settingsPanel.visibility == View.VISIBLE) {
            return super.dispatchKeyEvent(event)
        }

            // 焦点桥接：PlayerView 会自己消费方向键，必须在它之前把焦点送到控制层。
            // 上 → 左上角返回；下 → 右下角设置。
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && focusInPlayer()) {
                btnBackTop.requestFocus()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && focusInPlayer()) {
                btnSettings.requestFocus()
                return true
            }

            if (errorPanel.visibility != View.VISIBLE) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                        showSettingsPanel()
                        return true
                    }
                    KeyEvent.KEYCODE_PROG_YELLOW -> {
                        // TV 端已隐藏投屏，黄键不再打开面板
                        if (!isTv) {
                            castFromSettings = false
                            showCastPanel()
                        }
                        return true
                    }
                }
                val p = player
                if (p != null) {
                    // 遥控器方向键 / 确认键在焦点位于投屏按钮等控件时交还焦点系统；
                    // MEDIA_* 等专用媒体键始终作为播放快捷键。
                    val dpadKey = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        event.keyCode == KeyEvent.KEYCODE_ENTER ||
                        event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                        event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                        event.keyCode == KeyEvent.KEYCODE_SPACE
                    if (dpadKey && !focusInPlayer()) return super.dispatchKeyEvent(event)
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                            p.playWhenReady = !p.playWhenReady
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0))
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            val duration = p.duration
                            val target = p.currentPosition + 10_000
                            p.seekTo(if (duration > 0) target.coerceAtMost(duration - 1_000) else target)
                            return true
                        }
                        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_PAGE_DOWN -> {
                            playNext()
                            return true
                        }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
