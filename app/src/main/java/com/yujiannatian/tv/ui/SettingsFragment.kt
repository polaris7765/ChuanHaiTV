package com.yujiannatian.tv.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.yujiannatian.tv.R
import com.yujiannatian.tv.core.AppSettings
import com.yujiannatian.tv.core.LoadResult
import com.yujiannatian.tv.core.SiteConfig
import com.yujiannatian.tv.core.SiteRepository
import com.yujiannatian.tv.core.VideoDetail
import com.yujiannatian.tv.core.VideoItem
import com.yujiannatian.tv.core.advice
import com.yujiannatian.tv.core.title

/** 地址配置：先抓取 + 解析验证，成功才提交；失败保留原地址并给出原因 */
class SettingsFragment : Fragment() {

    private lateinit var editUrl: EditText
    private lateinit var status: TextView
    private lateinit var current: TextView
    private lateinit var btnAllowDownload: Button
    private lateinit var btnSource: Button
    /** 「自动」+ 已知播放源；后台探测到真实线路后会重建 */
    private val sourceItems = mutableListOf<String>()
    private var sourceSelected = 0

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        editUrl = view.findViewById(R.id.edit_url)
        status = view.findViewById(R.id.status)
        current = view.findViewById(R.id.current)
        btnAllowDownload = view.findViewById(R.id.btn_allow_download)
        refreshCurrent()
        refreshDownloadBtn()

        btnAllowDownload.setOnClickListener {
            val next = !AppSettings.allowDownload(requireContext())
            AppSettings.setAllowDownload(requireContext(), next)
            refreshDownloadBtn()
            status.text = if (next) {
                "已允许下载：详情页单集会显示「下载」按钮。"
            } else {
                "已关闭下载：详情页单集「下载」按钮将隐藏（电视端默认关闭）。"
            }
        }

        // 新地址输入框默认留空（仅显示提示），进入设置时也不抢焦点，避免弹出输入法选择器；
        // 需要修改时再向下移动焦点到输入框即可。
        editUrl.setText("")

        view.findViewById<Button>(R.id.btn_save).setOnClickListener {
            check(editUrl.text.toString(), applyChange = true)
        }
        view.findViewById<Button>(R.id.btn_test).setOnClickListener {
            check(editUrl.text.toString(), applyChange = false)
        }
        view.findViewById<Button>(R.id.btn_default).setOnClickListener {
            editUrl.setText(SiteConfig.DEFAULT_SITE)
            check(SiteConfig.DEFAULT_SITE, applyChange = true)
        }
        view.findViewById<Button>(R.id.btn_prev).setOnClickListener {
            val prev = SiteConfig.previousUrl
            if (prev.isBlank()) {
                status.text = "没有可回退的地址（尚未成功变更过站点）。"
            } else {
                editUrl.setText(prev)
                check(prev, applyChange = true)
            }
        }
        setupSourceButton(view)
        probeRealSources()
        // 进入设置默认聚焦左上角「返回」按钮（而非地址输入框），避免输入法/键盘弹窗干扰
        view.post { focusBackOnEntry() }
    }

    private fun refreshCurrent() {
        val prev = SiteConfig.previousUrl.ifBlank { "无" }
        current.text = "当前地址：${SiteConfig.baseUrl}\n上次可用：$prev  ·  配置版本 #${SiteConfig.version}"
    }

    private fun refreshDownloadBtn() {
        val allow = AppSettings.allowDownload(requireContext())
        btnAllowDownload.text = getString(
            if (allow) R.string.settings_download_on else R.string.settings_download_off
        )
        btnAllowDownload.isSelected = allow
    }

    private fun check(raw: String, applyChange: Boolean) {        status.text = "正在连接并解析站点地图，请稍候…"
        guardedLaunch {
            when (val r = SiteRepository.probe(raw)) {
                is LoadResult.Ok -> {
                    if (applyChange) {
                        SiteConfig.commit(raw)
                        refreshCurrent()
                        status.text = "检测成功：解析到 ${r.value} 个分类项，新地址已生效，正在重新加载…"
                        (activity as? MainActivity)?.showHome(true)
                    } else {
                        status.text = "检测成功：解析到 ${r.value} 个分类项，该地址可用（当前地址未改动）。"
                    }
                }
                is LoadResult.Err -> {
                    val e = r.error
                    status.text = buildString {
                        append("检测失败：").append(e.title()).append(" — ").append(e.message ?: "")
                        append('\n').append(e.advice())
                        if (e.detail.isNotBlank()) append("\n详情：").append(e.detail.take(160))
                        append("\n\n当前地址保持不变，可修改后重试。")
                    }
                }
            }
        }
    }

    /**
     * 默认播放源选择（TV 友好）：用按钮 + AlertDialog 单选列表替代 Spinner。
     * Spinner 的下拉弹层在 Android TV 上无法用方向键选中条目，改用系统对话框列表，
     * 方向键可正常上下移动并确认，遥控器「确认键」打开列表。
     */
    private fun setupSourceButton(view: View) {
        btnSource = view.findViewById(R.id.btn_source)
        rebuildSourceItems()
        btnSource.setOnClickListener {
            hideSoftInput()
            val items = sourceItems.toTypedArray()
            val checked = sourceSelected.coerceIn(0, items.lastIndex)
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_source_title)
                .setSingleChoiceItems(items, checked) { d, which ->
                    sourceSelected = which
                    AppSettings.setDefaultSource(
                        requireContext(),
                        if (which == 0) "" else items[which]
                    )
                    refreshSourceButton()
                    d.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** 依据当前已保存的设置重建列表，保持已选项不丢失 */
    private fun rebuildSourceItems() {
        val def = AppSettings.defaultSource(requireContext())
        sourceItems.clear()
        sourceItems.add(getString(R.string.settings_source_auto))
        sourceItems.addAll(AppSettings.knownSources(requireContext()))
        sourceSelected = if (def.isBlank()) 0 else (sourceItems.indexOf(def).takeIf { it >= 0 } ?: 0)
        refreshSourceButton()
    }

    private fun refreshSourceButton() {
        if (!::btnSource.isInitialized || sourceItems.isEmpty()) return
        val name = sourceItems[sourceSelected.coerceIn(0, sourceItems.lastIndex)]
        btnSource.text = getString(R.string.settings_source_current, name)
    }

    /**
     * 后台主动探测站点真实播放线路：取首页推荐的第一部影片解析详情页，
     * 把线路名累积进已知列表并刷新可选源（内置保底源保证探测失败时也不止一项）。
     * 走 SiteRepository 缓存，不额外阻塞 UI；失败静默忽略。
     */
    private fun probeRealSources() {
        guardedLaunch {
            val featured = SiteRepository.featured(false)
            if (featured !is LoadResult.Ok) return@guardedLaunch
            val first = (featured.value as? List<VideoItem>)?.firstOrNull()
                ?: return@guardedLaunch
            val detail = SiteRepository.detail(first.path)
            if (detail !is LoadResult.Ok) return@guardedLaunch
            val names = (detail.value as? VideoDetail)?.sources
                ?.map { it.name }?.filter { it.isNotBlank() } ?: return@guardedLaunch
            if (names.isEmpty()) return@guardedLaunch
            AppSettings.addKnownSources(requireContext(), names)
            if (isAdded) rebuildSourceItems()
        }
    }

    /** 进入设置时把焦点放到左上角「返回」按钮，避免输入框抢焦点弹出输入法 */
    private fun focusBackOnEntry() {
        hideSoftInput()
        editUrl.clearFocus()
        activity?.findViewById<View>(R.id.btn_back)?.requestFocus()
    }

    /** 隐藏软键盘（离开设置或打开选择列表时调用），避免「输入法选择器」残留 */
    private fun hideSoftInput() {
        val imm = ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
        val token = activity?.currentFocus?.windowToken ?: view?.windowToken
        imm?.hideSoftInputFromWindow(token, 0)
    }

    override fun onPause() {
        super.onPause()
        hideSoftInput()
    }
}
