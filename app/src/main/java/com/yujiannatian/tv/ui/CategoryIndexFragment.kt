package com.yujiannatian.tv.ui

import android.os.Bundle
import android.view.View
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import com.yujiannatian.tv.R
import com.yujiannatian.tv.core.Category
import com.yujiannatian.tv.core.LoadResult
import com.yujiannatian.tv.core.SiteRepository

/** 站点地图的全部子分类，按大类分行；卡片文字已统一缩小且不放大 */
class CategoryIndexFragment : BrowseSupportFragment() {

    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        headersState = HEADERS_DISABLED
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 全局标题栏已显示「分类索引」，这里不再重复设置 Leanback 标题
        setOnItemViewClickedListener(object : OnItemViewClickedListener {
            override fun onItemClicked(
                itemViewHolder: Presenter.ViewHolder?,
                item: Any?,
                rowViewHolder: RowPresenter.ViewHolder?,
                row: Row?
            ) {
                if (item is Category) GridActivity.start(requireContext(), item)
            }
        })
        if (!started) {
            started = true
            load()
        }
    }

    private fun load() {
        val act = activity as? BaseTvActivity ?: return
        act.showLoading("正在读取分类索引…")
        guardedLaunch {
            val result = SiteRepository.categories(false)
            if (result is LoadResult.Err) {
                if (isAdded) act.showError(result.error)
                return@guardedLaunch
            }
            val groups = (result as LoadResult.Ok<List<com.yujiannatian.tv.core.CategoryGroup>>).value
            if (!isAdded) return@guardedLaunch
            val rows = ArrayObjectAdapter(ListRowPresenter(FocusHighlight.ZOOM_FACTOR_NONE))
            groups.forEach { g ->
                val cards = ArrayObjectAdapter(categoryCardPresenter())
                cards.addAll(0, g.categories)
                rows.add(ListRow(HeaderItem("${g.name} · ${g.categories.size} 项"), cards))
            }
            adapter = rows
            act.clearOverlay()
        }
    }

    fun retry() = load()
}
