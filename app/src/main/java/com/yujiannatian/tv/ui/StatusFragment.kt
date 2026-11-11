package com.yujiannatian.tv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import com.yujiannatian.tv.R
import com.yujiannatian.tv.core.SiteConfig
import com.yujiannatian.tv.core.SiteError
import com.yujiannatian.tv.core.advice
import com.yujiannatian.tv.core.title

/**
 * 统一的加载 / 网络失败 / 解析异常 / 地址问题反馈页。
 *
 * 所有页面都通过 [BaseTvActivity.showLoading] 进入加载态，因此这里统一呈现
 * 「屏幕正中旋转小动画 + 下方文字说明」：主文案就是调用方传入的 text，
 * 副文案是通用提示，不再像旧版那样写死「首次加载会抓取站点地图」。
 * 错误态仍保留感叹号、标题、原因说明与可方向键聚焦的按钮，并额外提供「返回」
 * （首页回主菜单、其它页面回上一级），避免全屏覆盖层挡住下层标题栏的返回入口。
 */
class StatusFragment : Fragment() {

    companion object {
        private const val K_LOADING = "loading"
        private const val K_TITLE = "title"
        private const val K_MSG = "msg"
        private const val K_DETAIL = "detail"
        private const val K_ADVICE = "advice"

        fun loading(text: String): StatusFragment = StatusFragment().apply {
            arguments = bundleOf(K_LOADING to true, K_TITLE to text)
        }

        fun error(e: SiteError): StatusFragment = StatusFragment().apply {
            arguments = bundleOf(
                K_LOADING to false,
                K_TITLE to e.title(),
                K_MSG to (e.message ?: ""),
                K_DETAIL to e.detail,
                K_ADVICE to e.advice()
            )
        }

        /** 上次运行崩溃的现场报告；用户可直接看到原因，不必接 adb */
        fun crash(summary: String): StatusFragment = StatusFragment().apply {
            arguments = bundleOf(
                K_LOADING to false,
                K_TITLE to "上次运行异常退出",
                K_MSG to "",
                K_DETAIL to summary,
                K_ADVICE to "按「重试」清除记录并重新加载；若反复出现，请拍下此页反馈。"
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_status, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val args = arguments ?: bundleOf()
        val loading = args.getBoolean(K_LOADING, false)
        val loadingBlock = view.findViewById<View>(R.id.loading_block)
        val loadingText = view.findViewById<TextView>(R.id.loading_text)
        val icon = view.findViewById<View>(R.id.icon)
        val titleView = view.findViewById<TextView>(R.id.title)
        val message = view.findViewById<TextView>(R.id.message)
        val actions = view.findViewById<View>(R.id.actions)
        val errorViews = listOf(icon, titleView, message, actions)

        if (loading) {
            loadingBlock.visibility = View.VISIBLE
            errorViews.forEach { it.visibility = View.GONE }
            loadingText.text = args.getString(K_TITLE).orEmpty().ifBlank { "正在加载…" }
            // 加载层自身接管焦点，避免方向键落到被遮挡的下层列表
            view.isFocusable = true
            view.post { view.requestFocus() }
            return
        }

        loadingBlock.visibility = View.GONE
        errorViews.forEach { it.visibility = View.VISIBLE }
        titleView.text = args.getString(K_TITLE).orEmpty()
        val detail = args.getString(K_DETAIL).orEmpty().take(700)
        message.text = buildString {
            append(args.getString(K_MSG).orEmpty())
            if (detail.isNotBlank()) append("\n\n原因：").append(detail)
            append("\n\n").append(args.getString(K_ADVICE).orEmpty())
            append("\n\n当前站点：").append(SiteConfig.baseUrl)
        }.trim()

        val host = activity as? StatusHost
        // 覆盖层遮住了标题栏的返回按钮，错误态必须自带一个可聚焦的出口
        view.findViewById<Button>(R.id.btn_status_back)
            .setOnClickListener { host?.onStatusBack() }
        view.findViewById<Button>(R.id.btn_retry).setOnClickListener { host?.onStatusRetry() }
        view.findViewById<Button>(R.id.btn_change).setOnClickListener { host?.onStatusChangeSite() }
        view.findViewById<Button>(R.id.btn_reset).setOnClickListener { host?.onStatusResetSite() }
        view.post { view.findViewById<Button>(R.id.btn_retry).requestFocus() }
    }
}
