package com.yujiannatian.tv.ui

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.leanback.widget.VerticalGridPresenter
import com.yujiannatian.tv.R
import com.yujiannatian.tv.core.LoadResult
import com.yujiannatian.tv.core.PageResult
import com.yujiannatian.tv.core.SiteRepository
import com.yujiannatian.tv.core.VideoItem

class GridFragment : VerticalGridSupportFragment() {

    companion object {
        private const val K_PATH = "path"
        fun newInstance(path: String): GridFragment = GridFragment().apply {
            arguments = bundleOf(K_PATH to path)
        }
    }

    private lateinit var path: String
    private lateinit var gridAdapter: ArrayObjectAdapter
    private var page = 1
    private var totalPages = 1
    private var loading = false
    private var failedPage = 1
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = arguments?.getString(K_PATH) ?: "/"

        // 需求：卡片聚焦/点击时不放大，只保留描边高亮
        val presenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_NONE, false)
        presenter.numberOfColumns = 5
        gridPresenter = presenter

        val selector = ClassPresenterSelector()
            .addClassPresenter(VideoItem::class.java, VideoCardPresenter())
            .addClassPresenter(ActionItem::class.java, actionCardPresenter())
        gridAdapter = ArrayObjectAdapter(selector)
        adapter = gridAdapter

        setOnItemViewClickedListener(object : OnItemViewClickedListener {
            override fun onItemClicked(
                itemViewHolder: Presenter.ViewHolder?,
                item: Any?,
                rowViewHolder: RowPresenter.ViewHolder?,
                row: Row?
            ) {
                when (item) {
                    is VideoItem -> DetailActivity.start(requireContext(), item)
                    is ActionItem -> if (item.id == "more" && !loading) load(page + 1)
                }
            }
        })

        // 焦点接近列表尾部时自动预加载下一页（也可点「下一页」卡片）
        setOnItemViewSelectedListener(object : OnItemViewSelectedListener {
            override fun onItemSelected(
                itemViewHolder: Presenter.ViewHolder?,
                item: Any?,
                rowViewHolder: RowPresenter.ViewHolder?,
                row: Row?
            ) {
                if (item == null || loading || page >= totalPages) return
                val pos = gridAdapter.indexOf(item)
                if (pos >= gridAdapter.size() - 8) load(page + 1)
            }
        })
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!started) {
            started = true
            load(1)
        }
    }

    private fun load(targetPage: Int) {
        if (loading) return
        val act = activity as? GridActivity ?: return
        loading = true
        failedPage = targetPage
        act.showLoading(if (targetPage == 1) "正在加载资源列表…" else "正在加载第 $targetPage 页…")
        guardedLaunch {
            val result = when (val r = SiteRepository.list(path, targetPage)) {
                is LoadResult.Err -> {
                    if (isAdded) act.showError(r.error)
                    loading = false
                    return@guardedLaunch
                }
                is LoadResult.Ok -> r.value
            }
            if (!isAdded) {
                loading = false
                return@guardedLaunch
            }
            page = targetPage
            render(result)
            act.clearOverlay()
            loading = false
        }
    }

    private fun render(result: PageResult) {
        removeMore()
        val existing = gridAdapter.unmodifiableList<Any>()
        result.items.forEach { item ->
            if (existing.none { it is VideoItem && it.path == item.path }) gridAdapter.add(item)
        }
        totalPages = result.totalPages
        (activity as? GridActivity)?.onPageInfo(result.total, page, totalPages)
        if (page < totalPages) {
            gridAdapter.add(ActionItem("more", "下一页  ${page + 1}/$totalPages"))
        }
    }

    private fun removeMore() {
        val list = gridAdapter.unmodifiableList<Any>()
        for (i in list.size - 1 downTo 0) {
            if (list[i] is ActionItem) gridAdapter.removeItems(i, 1)
        }
    }

    fun retry() = load(failedPage)
}
