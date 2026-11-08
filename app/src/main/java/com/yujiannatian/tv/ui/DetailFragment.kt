package com.yujiannatian.tv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yujiannatian.tv.R
import com.yujiannatian.tv.core.AppSettings
import com.yujiannatian.tv.core.DownloadState
import com.yujiannatian.tv.core.DownloadStatus
import com.yujiannatian.tv.core.Episode
import com.yujiannatian.tv.core.LibraryEntry
import com.yujiannatian.tv.core.LibraryStore
import com.yujiannatian.tv.core.LoadResult
import com.yujiannatian.tv.core.SiteRepository
import com.yujiannatian.tv.core.VideoDetail
import com.yujiannatian.tv.core.VideoDownloader
import com.yujiannatian.tv.core.VideoItem

/**
 * 紧凑详情页：顶部影片信息、播放源切换行、选集网格。
 * 每一集都有「播放」和「下载」两个独立可聚焦按钮，下载进度实时回显。
 */
class DetailFragment : Fragment() {

    companion object {
        private const val K_PATH = "path"
        private const val K_TITLE = "title"
        private const val K_COVER = "cover"
        private const val K_RESUME_PATH = "resume_path"
        private const val K_RESUME_POSITION = "resume_position"

        fun newInstance(path: String, title: String, cover: String): DetailFragment =
            DetailFragment().apply {
                arguments = bundleOf(K_PATH to path, K_TITLE to title, K_COVER to cover)
            }

        /** 从历史 / 收藏进入：带上次观看的剧集与进度，加载后直接续播 */
        fun newInstance(
            path: String,
            title: String,
            cover: String,
            resumeEpisodePath: String,
            resumePosition: Long
        ): DetailFragment =
            DetailFragment().apply {
                arguments = bundleOf(
                    K_PATH to path,
                    K_TITLE to title,
                    K_COVER to cover,
                    K_RESUME_PATH to resumeEpisodePath,
                    K_RESUME_POSITION to resumePosition
                )
            }
    }

    private lateinit var path: String
    private lateinit var fallbackTitle: String
    private lateinit var fallbackCover: String
    private var resumeEpisodePath: String = ""
    private var resumePosition: Long = 0L

    private lateinit var titleView: TextView
    private lateinit var metaView: TextView
    private lateinit var descView: TextView
    private lateinit var coverView: ImageView
    private lateinit var sourceTabs: RecyclerView
    private lateinit var episodeGrid: RecyclerView
    private lateinit var episodeCount: TextView
    private lateinit var favoriteButton: Button
    private lateinit var sourceAdapter: SourceTabAdapter
    private lateinit var episodeAdapter: EpisodeAdapter

    private var detail: VideoDetail? = null
    private var selectedSource = 0
    private var started = false

    private val downloadListener: (String, DownloadState) -> Unit = { key, _ ->
        updateDownloadButton(key)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = arguments?.getString(K_PATH) ?: "/"
        fallbackTitle = arguments?.getString(K_TITLE).orEmpty()
        fallbackCover = arguments?.getString(K_COVER).orEmpty()
        resumeEpisodePath = arguments?.getString(K_RESUME_PATH).orEmpty()
        resumePosition = arguments?.getLong(K_RESUME_POSITION, 0L) ?: 0L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        titleView = view.findViewById(R.id.detail_title)
        metaView = view.findViewById(R.id.detail_meta)
        descView = view.findViewById(R.id.detail_desc)
        coverView = view.findViewById(R.id.detail_cover)
        sourceTabs = view.findViewById(R.id.source_tabs)
        episodeGrid = view.findViewById(R.id.episode_grid)
        episodeCount = view.findViewById(R.id.episode_count)
        favoriteButton = view.findViewById(R.id.btn_favorite)

        titleView.text = fallbackTitle
        ImageLoader.load(coverView, fallbackCover, fallbackTitle)
        updateFavoriteButton()
        favoriteButton.setOnClickListener { toggleFavorite() }

        sourceTabs.layoutManager = LinearLayoutManager(
            requireContext(), LinearLayoutManager.HORIZONTAL, false
        )
        sourceAdapter = SourceTabAdapter(::fillEpisodes)
        sourceTabs.adapter = sourceAdapter

        val density = resources.displayMetrics.density
        val cellPx = (168 * density).toInt().coerceAtLeast(1)
        val columns = (resources.displayMetrics.widthPixels / cellPx).coerceIn(2, 6)
        episodeGrid.layoutManager = GridLayoutManager(requireContext(), columns)
        episodeGrid.addItemDecoration(GridSpacingDecoration(columns, (6 * density).toInt()))
        episodeAdapter = EpisodeAdapter(::startPlayback, ::onDownload)
        episodeGrid.adapter = episodeAdapter

        VideoDownloader.observe(downloadListener)
        if (!started) {
            started = true
            load()
        }
    }

    override fun onDestroyView() {
        VideoDownloader.unobserve(downloadListener)
        super.onDestroyView()
    }

    private fun load() {
        val act = activity as? DetailActivity ?: return
        act.showLoading("正在解析详情页与可用播放源…")
        guardedLaunch {
            val r = SiteRepository.detail(path)
            if (r is LoadResult.Err) {
                if (isAdded) act.showError(r.error)
                return@guardedLaunch
            }
            if (!isAdded) return@guardedLaunch
            val value = (r as LoadResult.Ok<VideoDetail>).value
            render(value)
            act.clearOverlay()
        }
    }

    private fun updateFavoriteButton() {
        favoriteButton.text = getString(
            if (LibraryStore.isFavorite(requireContext(), path)) {
                R.string.action_unfavorite
            } else {
                R.string.action_favorite
            }
        )
    }

    private fun toggleFavorite() {
        val entry = LibraryEntry(
            video = VideoItem(
                title = detail?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
                path = path,
                cover = detail?.cover?.takeIf { it.isNotBlank() } ?: fallbackCover
            )
        )
        val added = LibraryStore.toggleFavorite(requireContext(), entry)
        updateFavoriteButton()
        Toast.makeText(
            requireContext(),
            if (added) "已加入我的收藏" else "已取消收藏",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun render(d: VideoDetail) {
        detail = d
        titleView.text = d.title
        metaView.text = d.meta.ifBlank { "可用播放源 ${d.sources.size} 个" }
        descView.text = d.description.ifBlank { "该站点未提供简介。" }
        if (d.cover.isNotBlank()) ImageLoader.load(coverView, d.cover, d.title)
        AppSettings.addKnownSources(requireContext(), d.sources.map { it.name })
        sourceAdapter.submit(d.sources.mapIndexed { i, s -> SourceItem(i, s.name, s.episodes.size) })
        // 默认播放源：设置里选过的源名称若在本次影片中存在则优先选中，否则退回到第一个
        val def = AppSettings.defaultSource(requireContext())
        val startIndex = if (def.isNotBlank()) {
            d.sources.indexOfFirst { it.name == def }.takeIf { it >= 0 } ?: 0
        } else 0
        fillEpisodes(startIndex)
        // 从历史 / 收藏带着进度进来：加载完成后直接跳到上次那集续播
        if (resumeEpisodePath.isNotBlank()) attemptResume()
    }

    /** 在全部播放源里找到上次观看的那一集，直接开始播放并跳转进度 */
    private fun attemptResume() {
        val d = detail ?: return
        for (si in d.sources.indices) {
            val idx = d.sources[si].episodes.indexOfFirst { it.path == resumeEpisodePath }
            if (idx >= 0) {
                selectedSource = si
                sourceAdapter.select(si)
                val ep = d.sources[si].episodes[idx]
                startPlayback(ep, idx, resumePosition)
                return
            }
        }
        // 没找到（如播放源列表已变化）：停留在详情页，由用户手动选择
        resumeEpisodePath = ""
    }

    private fun fillEpisodes(index: Int) {
        val d = detail ?: return
        if (index !in d.sources.indices) return
        selectedSource = index
        sourceAdapter.select(index)
        val episodes = d.sources[index].episodes
        episodeAdapter.submit(episodes)
        episodeCount.text = "共 ${episodes.size} 集 · 当前线路：${d.sources[index].name}"
        episodeGrid.post {
            if (isAdded) {
                episodeGrid.getChildAt(0)
                    ?.findViewById<Button>(R.id.btn_play)?.requestFocus()
            }
        }
    }

    private fun startPlayback(episode: Episode, index: Int, startPositionMs: Long = 0L) {
        val act = activity as? DetailActivity ?: return
        val d = detail ?: return
        act.showLoading("正在获取播放地址…")
        guardedLaunch {
            val r = SiteRepository.playback(episode.path)
            if (r is LoadResult.Err) {
                if (isAdded) act.showError(r.error)
                return@guardedLaunch
            }
            if (!isAdded) return@guardedLaunch
            act.clearOverlay()
            val source = d.sources[selectedSource]
            PlaybackActivity.start(
                act,
                d.title,
                episode.name,
                (r as LoadResult.Ok<com.yujiannatian.tv.core.PlaybackInfo>).value.url,
                source.episodes.map { it.path },
                source.episodes.map { it.name },
                index,
                path,
                d.cover.ifBlank { fallbackCover },
                startPositionMs
            )
        }
    }

    private fun onDownload(episode: Episode) {
        val ctx = requireContext().applicationContext
        val state = VideoDownloader.state(ctx, episode.path)
        if (state.status == DownloadStatus.DONE && state.file.isNotBlank()) {
            PlaybackActivity.startLocal(
                requireContext(),
                detail?.title ?: fallbackTitle,
                episode.name,
                state.file
            )
            return
        }
        VideoDownloader.enqueue(
            ctx,
            episode.path,
            detail?.title ?: fallbackTitle,
            episode.name,
            episode.path
        )
    }

    private fun downloadLabel(state: DownloadState): String = when (state.status) {
        DownloadStatus.IDLE -> getString(R.string.action_download)
        DownloadStatus.RESOLVING -> "解析中"
        DownloadStatus.DOWNLOADING -> "${state.progress}%"
        DownloadStatus.DONE -> "已下载"
        DownloadStatus.FAILED -> "重试"
    }

    private fun updateDownloadButton(key: String) {
        if (!isAdded) return
        val label = downloadLabel(VideoDownloader.state(requireContext().applicationContext, key))
        for (i in 0 until episodeGrid.childCount) {
            val v = episodeGrid.getChildAt(i)
            if (v.getTag(R.id.tag_download_key) == key) {
                v.findViewById<Button>(R.id.btn_download)?.text = label
            }
        }
    }

    private inner class SourceTabAdapter(
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<SourceTabAdapter.VH>() {

        private val items = mutableListOf<SourceItem>()
        private var selected = 0

        fun submit(list: List<SourceItem>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        fun select(index: Int) {
            val old = selected
            selected = index
            if (old in items.indices) notifyItemChanged(old)
            if (index in items.indices) notifyItemChanged(index)
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val button: Button = v as Button
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_source, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.button.text = "${item.name} · ${item.count}集"
            holder.button.isSelected = position == selected
            holder.button.setOnClickListener { onClick(item.index) }
            holder.itemView.tag = item.index
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class EpisodeAdapter(
        private val onPlay: (Episode, Int) -> Unit,
        private val onDownload: (Episode) -> Unit
    ) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

        private val items = mutableListOf<Episode>()

        fun submit(list: List<Episode>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val play: Button = v.findViewById(R.id.btn_play)
            val download: Button = v.findViewById(R.id.btn_download)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_episode, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ep = items[position]
            holder.play.text = ep.name
            holder.itemView.setTag(R.id.tag_download_key, ep.path)
            holder.download.text = downloadLabel(
                VideoDownloader.state(holder.itemView.context.applicationContext, ep.path)
            )
            holder.play.setOnClickListener { onPlay(ep, position) }
            // 下载开关由「设置」控制（默认：电视端关闭、其余端开启）；
            // 关闭时隐藏该按钮并取消行内焦点桥接，否则左右方向键会卡在 GONE 按钮上。
            val allowDownload = AppSettings.allowDownload(holder.itemView.context)
            holder.download.visibility = if (allowDownload) View.VISIBLE else View.GONE
            if (allowDownload) {
                holder.download.setOnClickListener { onDownload(ep) }
                // GridLayoutManager 会拦截左右方向键用于跨格换列，导致同一集内的
                // 「下载」按钮永远聚焦不到。这里在焦点视图上直接接管左右键做行内桥接。
                holder.play.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    ) {
                        holder.download.requestFocus()
                        true
                    } else false
                }
                holder.download.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    ) {
                        holder.play.requestFocus()
                        true
                    } else false
                }
            } else {
                holder.play.setOnKeyListener(null)
                holder.download.setOnKeyListener(null)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    fun retry() = load()
}
