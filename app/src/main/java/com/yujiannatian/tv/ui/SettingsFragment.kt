package com.yujiannatian.tv.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.yujiannatian.tv.R
import com.yujiannatian.tv.core.AppSettings
import com.yujiannatian.tv.core.LoadResult
import com.yujiannatian.tv.core.SiteConfig
import com.yujiannatian.tv.core.SiteRepository
import com.yujiannatian.tv.core.advice
import com.yujiannatian.tv.core.title

/** 地址配置：先抓取 + 解析验证，成功才提交；失败保留原地址并给出原因 */
class SettingsFragment : Fragment() {

    private lateinit var editUrl: EditText
    private lateinit var status: TextView
    private lateinit var current: TextView
    private lateinit var btnAllowDownload: Button

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

        editUrl.setText(SiteConfig.baseUrl)
        editUrl.setSelection(editUrl.text.length)

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
        setupSourceSpinner(view)
        view.post { editUrl.requestFocus() }
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

    /** 默认播放源选择：列出该站点出现过的播放源，选择后每次进入详情默认选中 */
    private fun setupSourceSpinner(view: View) {
        val spinner = view.findViewById<Spinner>(R.id.spinner_source)
        val sources = AppSettings.knownSources(requireContext())
        val items = mutableListOf(getString(R.string.settings_source_auto))
        items.addAll(sources)
        val adapter = ArrayAdapter(
            requireContext(),
            R.layout.spinner_item,
            items
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        spinner.adapter = adapter
        val def = AppSettings.defaultSource(requireContext())
        val sel = if (def.isBlank()) 0 else (items.indexOf(def).takeIf { it >= 0 } ?: 0)
        spinner.setSelection(sel)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>, v: View?, pos: Int, id: Long
            ) {
                AppSettings.setDefaultSource(
                    requireContext(),
                    if (pos == 0) "" else items[pos]
                )
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }
}
