package com.yujiannatian.tv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yujiannatian.tv.R
import com.yujiannatian.tv.core.LoadResult
import com.yujiannatian.tv.core.PageResult
import com.yujiannatian.tv.core.SiteRepository
import com.yujiannatian.tv.core.VideoItem

/**
 * 分类下的影片列表：与首页「热播榜」一致的**多列封面网格**。
 *
 * 列数按屏幕宽度动态计算（卡片基准 132dp，3~6 列），直接复用首页的 `item_video.xml`
 * 小卡片与 `VideoGridAdapter`，因此海报尺寸、圆角、字号、焦点高亮都与首页一致。
 * 遥控器方向键可上下换行、左右换列，选中整行任意一列；列表本身仍禁止横向滚动
 * （`canScrollHorizontally=false`、`overScrollMode=never`），不会出现整页左右滑动。
 *
 * 分页：焦点或滚动接近列表尾部时自动加载下一页，页眉显示页码与总数。
 */
class GridFragment : Fragment() {

    companion object {
        private const val K_PATH = "path"
        private const val CARD_WIDTH_DP = 132
        private const val GRID_SPACE_DP = 8
        private const val MIN_COLUMNS = 3
        private const val MAX_COLUMNS = 6
        private const val PREFETCH_THRESHOLD = 12

        fun newInstance(path: String): GridFragment = GridFragment().apply {
            arguments = bundleOf(K_PATH to path)
        }
    }

    private lateinit var path: String
    private lateinit var list: RecyclerView
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var adapter: VideoGridAdapter
    private var page = 1
    private var totalPages = 1
    private var loading = false
    private var failedPage = 1
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = arguments?.getString(K_PATH) ?: "/"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_grid, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        list = view.findViewById(R.id.grid_list)

        // 与首页同一套列数算法；分类页没有左栏，可用宽度是整屏减去两侧过扫描边距
        val density = resources.displayMetrics.density
        val cardPx = (CARD_WIDTH_DP * density).toInt().coerceAtLeast(1)
        val overscan = resources.getDimensionPixelSize(R.dimen.overscan_h)
        val usablePx = (resources.displayMetrics.widthPixels - overscan * 2).coerceAtLeast(1)
        val columns = (usablePx / cardPx).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        val space = (GRID_SPACE_DP * density).toInt()

        layoutManager = object : GridLayoutManager(
            requireContext(), columns, RecyclerView.VERTICAL, false
        ) {
            // 网格内左右键只换列，不允许把整个列表横向拖动
            override fun canScrollHorizontally(): Boolean = false
        }
        list.layoutManager = layoutManager
        list.isHorizontalScrollBarEnabled = false
        list.isVerticalScrollBarEnabled = false
        list.overScrollMode = View.OVER_SCROLL_NEVER
        list.addItemDecoration(GridSpacingDecoration(columns, space))

        adapter = VideoGridAdapter(
            onItemClick = { item: VideoItem -> DetailActivity.start(requireContext(), item) },
            onNeedMore = { requestNextPage() }
        )
        list.adapter = adapter

        // 触屏 / 鼠标滚动到接近底部时翻页（TV 上主要由上面的焦点回调触发）
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dx != 0 || dy <= 0) return
                if (layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - PREFETCH_THRESHOLD) {
                    requestNextPage()
                }
            }
        })

        if (!started) {
            started = true
            load(1)
        }
    }

    private fun requestNextPage() {
        if (!loading && page < totalPages) load(page + 1)
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
            val firstPage = adapter.itemCount == 0
            page = targetPage
            render(result)
            act.clearOverlay()
            loading = false
            // clearOverlay 移除状态层时焦点会回到顶部「返回」按钮，
            // 稍等一帧再把焦点交给列表第一项，TV 上进入即可直接上下选择。
            if (firstPage) focusFirstItem()
        }
    }

    /**
     * 状态层移除、RecyclerView 布局、系统把初始焦点交给顶部「返回」按钮是分几帧完成的，
     * 只延迟一次容易被覆盖。这里分两波尝试；若焦点已经在列表内部（用户已开始操作），
     * 就不再抢焦点。
     */
    private fun focusFirstItem() {
        list.postDelayed({ tryFocusFirst() }, 120L)
        list.postDelayed({ tryFocusFirst() }, 600L)
    }

    private fun tryFocusFirst() {
        if (!isAdded || adapter.itemCount == 0) return
        if (list.findFocus() != null) return // 焦点已在列表内，尊重用户当前位置
        val target = list.findViewHolderForAdapterPosition(0)?.itemView ?: list.getChildAt(0)
        target?.requestFocus()
    }

    private fun render(result: PageResult) {
        adapter.append(result.items) // 内部按 path 去重，翻页不会重复插入
        totalPages = result.totalPages
        (activity as? GridActivity)?.onPageInfo(result.total, page, totalPages)
    }

    fun retry() = load(failedPage)
}
