package com.yujiannatian.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yujiannatian.tv.R
import com.yujiannatian.tv.core.CategoryGroup
import com.yujiannatian.tv.core.LibraryEntry
import com.yujiannatian.tv.core.LibraryStore
import com.yujiannatian.tv.core.LoadResult
import com.yujiannatian.tv.core.PageResult
import com.yujiannatian.tv.core.SiteRepository
import com.yujiannatian.tv.core.VideoItem

/**
 * 首页双栏：左侧窄分类栏（宽度 = 屏幕 1/6 的 60%，单行小字），
 * 右侧展示当前分类的热播剧集封面网格，或「历史记录 / 我的收藏」的本地列表。
 */
class HomeFragment : Fragment() {

    companion object {
        private const val K_FORCE = "force_refresh"
        fun newInstance(forceRefresh: Boolean): HomeFragment = HomeFragment().apply {
            arguments = bundleOf(K_FORCE to forceRefresh)
        }
    }

    private lateinit var sidebar: RecyclerView
    private lateinit var grid: RecyclerView
    private lateinit var sectionTitle: TextView
    private lateinit var sectionInfo: TextView
    private lateinit var sidebarAdapter: SidebarAdapter
    private lateinit var gridAdapter: VideoGridAdapter
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnClear: Button

    private var groups: List<CategoryGroup> = emptyList()
    private var featured: List<VideoItem> = emptyList()
    private var current: SidebarItem? = null
    private var page = 1
    private var totalPages = 1
    private var loading = false
    private var started = false
    /** 当前本地列表（历史 / 收藏）的原始记录，用于点击时取出续播进度 */
    private var localEntries: List<LibraryEntry> = emptyList()
    /** 用户点过「上一页 / 下一页」后关闭无限滚动，避免追加逻辑把整页替换弄乱 */
    private var autoLoad = true
    private var pendingClear = false

    private val clearResetRunnable = Runnable {
        pendingClear = false
        if (::btnClear.isInitialized) btnClear.text = getString(R.string.action_clear)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        sidebar = view.findViewById(R.id.sidebar)
        grid = view.findViewById(R.id.video_grid)
        sectionTitle = view.findViewById(R.id.section_title)
        sectionInfo = view.findViewById(R.id.section_info)
        btnPrev = view.findViewById(R.id.btn_prev)
        btnNext = view.findViewById(R.id.btn_next)
        btnRefresh = view.findViewById(R.id.btn_refresh)
        btnClear = view.findViewById(R.id.btn_clear)

        // 左栏宽度 = 原来的 1/6 再乘 60%（即屏幕宽度约 1/10）
        val density = resources.displayMetrics.density
        val w = (resources.displayMetrics.widthPixels / 6 * 0.6f).toInt()
            .coerceAtLeast((72 * density).toInt())
        view.findViewById<LinearLayout>(R.id.left_pane).layoutParams.width = w

        val cardPx = (132 * density).toInt().coerceAtLeast(1)
        val rightPx = resources.displayMetrics.widthPixels - w - (14 * density).toInt()
        val columns = (rightPx / cardPx).coerceIn(3, 6)
        val space = (8 * density).toInt()

        sidebar.layoutManager = LinearLayoutManager(requireContext())
        sidebarAdapter = SidebarAdapter(::onSidebarClick)
        sidebar.adapter = sidebarAdapter
        sidebar.addItemDecoration(GridSpacingDecoration(1, (4 * density).toInt()))

        grid.layoutManager = GridLayoutManager(requireContext(), columns)
        grid.addItemDecoration(GridSpacingDecoration(columns, space))
        gridAdapter = VideoGridAdapter(
            onItemClick = ::onGridClick,
            onNeedMore = { if (autoLoad && !loading && page < totalPages) loadPage(page + 1) }
        )
        grid.adapter = gridAdapter

        btnPrev.setOnClickListener { stepPage(-1) }
        btnNext.setOnClickListener { stepPage(1) }
        btnRefresh.setOnClickListener {
            SiteRepository.clearCache()
            load(true)
        }
        btnClear.setOnClickListener { clearLocalSection() }

        if (!started) {
            started = true
            load(arguments?.getBoolean(K_FORCE, false) ?: false)
        }
    }

    override fun onResume() {
        super.onResume()
        // 从详情页 / 播放页返回时，历史与收藏可能已经变化，重读本地数据
        val item = current
        if (item != null && isLocalSection(item) && ::gridAdapter.isInitialized) {
            loadLocalSection(item)
        }
    }

    override fun onDestroyView() {
        if (::btnClear.isInitialized) btnClear.removeCallbacks(clearResetRunnable)
        super.onDestroyView()
    }

    private fun isLocalSection(item: SidebarItem): Boolean =
        item.type == NavType.HISTORY || item.type == NavType.FAVORITES

    private fun load(forceRefresh: Boolean) {
        val act = activity as? MainActivity ?: return
        act.showLoading("正在抓取站点分类…")
        guardedLaunch {
            val result = SiteRepository.categories(forceRefresh)
            if (result is LoadResult.Err) {
                if (isAdded) act.showError(result.error)
                return@guardedLaunch
            }
            groups = (result as LoadResult.Ok<List<CategoryGroup>>).value
            featured = (SiteRepository.featured(false) as? LoadResult.Ok)?.value ?: emptyList()
            if (!isAdded) return@guardedLaunch

            val items = buildList {
                add(SidebarItem("hot", getString(R.string.row_hot), NavType.HOT))
                // 本地分类放最前面，左栏不用滚动就能看到
                add(SidebarItem("history", getString(R.string.nav_history), NavType.HISTORY))
                add(SidebarItem("favorites", getString(R.string.nav_favorites), NavType.FAVORITES))
                groups.forEach {
                    add(SidebarItem("g_${it.path}", it.name, NavType.GROUP, it.path))
                }
                add(SidebarItem("search", getString(R.string.nav_search), NavType.SEARCH))
                add(SidebarItem("categories", getString(R.string.nav_categories), NavType.CATEGORIES))
                add(SidebarItem("downloads", getString(R.string.nav_downloads), NavType.DOWNLOADS))
                add(SidebarItem("settings", getString(R.string.nav_settings), NavType.SETTINGS))
            }
            sidebarAdapter.submit(items)
            act.clearOverlay()

            // 默认进入第一个大类的热播榜；热门推荐保留为左栏首项
            val initial = (current?.let { items.firstOrNull { i -> i.id == it.id } }
                ?: items.firstOrNull { it.type == NavType.GROUP }) ?: items.first()
            selectSection(initial)
            sidebar.post {
                if (!isAdded) return@post
                val index = items.indexOf(initial)
                val target = if (index >= 0) {
                    sidebar.findViewHolderForAdapterPosition(index)?.itemView
                } else null
                (target ?: sidebar.getChildAt(0))?.requestFocus()
            }
        }
    }

    private fun onSidebarClick(item: SidebarItem) {
        when (item.type) {
            NavType.SEARCH -> startActivity(Intent(requireContext(), SearchActivity::class.java))
            NavType.CATEGORIES -> startActivity(Intent(requireContext(), CategoryIndexActivity::class.java))
            NavType.DOWNLOADS -> startActivity(Intent(requireContext(), DownloadsActivity::class.java))
            NavType.SETTINGS -> (activity as? MainActivity)?.showSettings()
            NavType.HOT, NavType.GROUP, NavType.HISTORY, NavType.FAVORITES -> selectSection(item)
        }
    }

    private fun selectSection(item: SidebarItem) {
        current = item
        sidebarAdapter.select(item.id)
        page = 1
        totalPages = 1
        autoLoad = true
        cancelPendingClear()
        gridAdapter.replace(emptyList())
        grid.scrollToPosition(0)
        sectionTitle.text = item.label

        if (isLocalSection(item)) {
            btnPrev.visibility = View.GONE
            btnNext.visibility = View.GONE
            btnRefresh.visibility = View.GONE
            btnClear.visibility = View.VISIBLE
            loadLocalSection(item)
        } else {
            btnPrev.visibility = View.VISIBLE
            btnNext.visibility = View.VISIBLE
            btnRefresh.visibility = View.VISIBLE
            btnClear.visibility = View.GONE
            btnPrev.alpha = 0.45f
            btnNext.alpha = 1.0f
            sectionInfo.text = "正在加载热播剧集…"
            loadPage(1)
        }
    }

    /** 本地列表：卡片副标题显示最近看到的剧集，点开进详情页 */
    private fun loadLocalSection(item: SidebarItem) {
        val entries = if (item.type == NavType.HISTORY) {
            LibraryStore.history(requireContext())
        } else {
            LibraryStore.favorites(requireContext())
        }
        localEntries = entries
        val list = entries.map { e ->
            val note = if (item.type == NavType.HISTORY && e.episode.isNotBlank()) {
                "看到 ${e.episode}"
            } else e.video.note
            e.video.copy(note = note)
        }
        gridAdapter.replace(list)
        grid.scrollToPosition(0)
        sectionInfo.text = if (list.isEmpty()) {
            if (item.type == NavType.HISTORY) {
                "还没有观看记录 · 播放任意剧集后会自动出现在这里"
            } else {
                "还没有收藏 · 在详情页点「收藏」即可加入"
            }
        } else {
            "共 ${list.size} 部 · 确认键进入详情" +
                if (item.type == NavType.HISTORY) " · 按「清空」删除全部记录" else " · 按「清空」取消全部收藏"
        }
    }

    private fun clearLocalSection() {
        val item = current ?: return
        if (!isLocalSection(item)) return
        if (!pendingClear) {
            pendingClear = true
            btnClear.text = getString(R.string.action_clear_confirm)
            btnClear.removeCallbacks(clearResetRunnable)
            btnClear.postDelayed(clearResetRunnable, 3_000L)
            toast("再按一次「清空」确认")
            return
        }
        cancelPendingClear()
        if (item.type == NavType.HISTORY) LibraryStore.clearHistory(requireContext())
        else LibraryStore.clearFavorites(requireContext())
        loadLocalSection(item)
        toast("已清空")
    }

    private fun cancelPendingClear() {
        pendingClear = false
        if (::btnClear.isInitialized) {
            btnClear.removeCallbacks(clearResetRunnable)
            btnClear.text = getString(R.string.action_clear)
        }
    }

    /** 网格点击：历史 / 收藏里的影片优先按上次进度直接续播，其余进详情页 */
    private fun onGridClick(item: VideoItem) {
        val cur = current
        if (cur != null && isLocalSection(cur)) {
            val entry = localEntries.firstOrNull { it.video.path == item.path }
            val resumeEntry = entry?.takeIf { it.episodePath.isNotBlank() }
                ?: LibraryStore.history(requireContext())
                    .firstOrNull { it.video.path == item.path && it.episodePath.isNotBlank() }
            if (resumeEntry != null) {
                DetailActivity.startResume(requireContext(), resumeEntry)
                return
            }
        }
        DetailActivity.start(requireContext(), item)
    }

    /** 右上角上一页 / 下一页：整页替换 + 回到顶部，视觉上立刻换一批内容 */
    private fun stepPage(delta: Int) {
        val item = current ?: return
        if (isLocalSection(item)) return
        if (loading) {
            toast("正在加载，请稍候")
            return
        }
        val target = page + delta
        if (target < 1) {
            toast("已经是第一页")
            return
        }
        if (target > totalPages) {
            toast("已经是最后一页")
            return
        }
        autoLoad = false
        loadPage(target, replace = true)
    }

    private fun loadPage(target: Int, replace: Boolean = false) {
        val item = current ?: return
        if (loading) return
        loading = true
        val act = activity as? MainActivity ?: return
        sectionInfo.text = "正在加载第 $target 页…"
        guardedLaunch {
            val result: LoadResult<PageResult> = if (item.type == NavType.HOT) {
                LoadResult.Ok(
                    PageResult(
                        items = featured,
                        page = 1,
                        totalPages = 1,
                        total = featured.size,
                        requestedPage = 1
                    )
                )
            } else {
                SiteRepository.hotList(item.path, target)
            }
            if (!isAdded) {
                loading = false
                return@guardedLaunch
            }
            if (result is LoadResult.Err) {
                loading = false
                act.showError(result.error)
                return@guardedLaunch
            }
            val pageResult = (result as LoadResult.Ok<PageResult>).value
            page = target
            totalPages = pageResult.totalPages
            if (target == 1 || replace) {
                gridAdapter.replace(pageResult.items)
                if (replace) grid.scrollToPosition(0)
            } else {
                gridAdapter.append(pageResult.items)
            }
            sectionInfo.text = buildString {
                append(if (item.type == NavType.HOT) "首页推荐  ·  " else "热播榜  ·  ")
                append("第 $page/$totalPages 页  ·  共 ${pageResult.total} 部")
                append(if (autoLoad) "  ·  下移自动加载更多" else "  ·  用上一页/下一页翻页")
            }
            btnPrev.alpha = if (page > 1) 1.0f else 0.45f
            btnNext.alpha = if (page < totalPages) 1.0f else 0.45f
            loading = false
        }
    }

    private fun toast(text: String) {
        if (isAdded) Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }

    fun retry() {
        loading = false
        load(true)
    }
}
