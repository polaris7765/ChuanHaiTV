package com.yujiannatian.tv.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yujiannatian.tv.R
import com.yujiannatian.tv.core.DownloadRecord
import com.yujiannatian.tv.core.DownloadState
import com.yujiannatian.tv.core.DownloadStatus
import com.yujiannatian.tv.core.DownloadsStore
import com.yujiannatian.tv.core.VideoDownloader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TYPE_HEADER = 0
private const val TYPE_ENTRY = 1

/** 我的下载：分「下载中」「下载完成」两组，下载中实时显示进度，支持取消 / 删除 */
class DownloadsFragment : Fragment() {

    private sealed interface Row {
        data class Header(val label: String) : Row
        data class Entry(val record: DownloadRecord) : Row
    }

    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var adapter: DlAdapter
    /** 只在首次进入时抢焦点，避免进度刷新时反复把焦点从用户手上抢走 */
    private var initialFocusDone = false

    private val downloadListener: (String, DownloadState) -> Unit = { _, _ ->
        view?.post { if (isAdded) refresh() }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_downloads, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        list = view.findViewById(R.id.dl_list)
        status = view.findViewById(R.id.dl_status)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.addItemDecoration(GridSpacingDecoration(1, 8))
        adapter = DlAdapter(
            onPlay = {
                PlaybackActivity.startLocal(requireContext(), it.title, it.episode, it.file)
            },
            onContinue = { record ->
                val app = requireContext().applicationContext
                VideoDownloader.enqueue(
                    app, record.key, record.title, record.episode, record.episodePath
                )
                refresh()
            },
            onDelete = { record ->
                val app = requireContext().applicationContext
                // 正在跑的任务只暂停（保留断点）；已完成 / 已暂停 / 失败则连同文件一起删除
                if (VideoDownloader.isActive(record.key)) VideoDownloader.cancel(app, record.key)
                else VideoDownloader.delete(app, record.key)
                refresh()
            }
        )
        list.adapter = adapter
        VideoDownloader.observe(downloadListener)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) refresh()
    }

    override fun onDestroyView() {
        VideoDownloader.unobserve(downloadListener)
        super.onDestroyView()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refresh() {
        if (!isAdded) return
        val app = requireContext().applicationContext
        val records = DownloadsStore.list(app)
        val doing = records.filter { it.status != "done" }
        val done = records.filter { it.status == "done" }

        adapter.submit(buildList {
            add(Row.Header("${getString(R.string.downloads_doing)} (${doing.size})"))
            doing.forEach { add(Row.Entry(it)) }
            add(Row.Header("${getString(R.string.downloads_done)} (${done.size})"))
            done.forEach { add(Row.Entry(it)) }
        }, list)

        status.text = buildString {
            append("下载中 ${doing.size} 项 · 已完成 ${done.size} 项")
            if (done.isNotEmpty()) append(" · 总大小 ${formatSize(done.sumOf { it.size })}")
        }
        if (!initialFocusDone) list.post {
            // 把焦点交给第一个可操作按钮，避免落在分组标题上
            if (isAdded) {
                for (i in 0 until list.childCount) {
                    val btn = list.getChildAt(i).findViewById<Button>(R.id.dl_delete)
                    if (btn != null && btn.visibility == View.VISIBLE) {
                        btn.requestFocus()
                        initialFocusDone = true
                        break
                    }
                }
            }
        }
    }

    private fun formatSize(bytes: Long): String =
        if (bytes >= 1024L * 1024L) "%.1f MB".format(bytes / 1024f / 1024f)
        else "${bytes / 1024L} KB"

    private inner class DlAdapter(
        private val onPlay: (DownloadRecord) -> Unit,
        private val onContinue: (DownloadRecord) -> Unit,
        private val onDelete: (DownloadRecord) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val rows = mutableListOf<Row>()
        private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        private fun rowKey(row: Row): String = when (row) {
            is Row.Header -> "H:${row.label}"
            is Row.Entry -> "E:${row.record.key}"
        }

        /**
         * 进度每秒会刷新多次。若每次都 notifyDataSetChanged()，整表重建会让
         * 界面始终处于非 idle 状态，并且「取消 / 删除」的点击会被 rebind 吃掉。
         * 结构不变时只原地更新屏幕上可见的条目，不触发任何 notify。
         */
        @SuppressLint("NotifyDataSetChanged")
        fun submit(newRows: List<Row>, host: RecyclerView) {
            val sameStructure = rows.size == newRows.size &&
                rows.indices.all { rowKey(rows[it]) == rowKey(newRows[it]) }
            rows.clear()
            rows.addAll(newRows)
            if (sameStructure) {
                for (i in 0 until host.childCount) {
                    val child = host.getChildAt(i) ?: continue
                    val vh = host.getChildViewHolder(child) ?: continue
                    if (vh.itemViewType != TYPE_ENTRY) continue
                    val pos = vh.bindingAdapterPosition
                    val entry = rows.getOrNull(pos) as? Row.Entry ?: continue
                    bindEntry(child, entry.record)
                }
                return
            }
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Header -> TYPE_HEADER
            is Row.Entry -> TYPE_ENTRY
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val v = if (viewType == TYPE_HEADER) {
                inflater.inflate(R.layout.item_category_header, parent, false)
            } else {
                inflater.inflate(R.layout.item_download, parent, false)
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            val v = holder.itemView
            if (row is Row.Header) {
                v.findViewById<TextView>(R.id.section_header)?.text = row.label
                return
            }
            bindEntry(v, (row as Row.Entry).record)
        }

        private fun bindEntry(v: View, r: DownloadRecord) {
            val active = r.status != "done"
            val state = VideoDownloader.state(v.context.applicationContext, r.key)
            // 记录是下载态但没有在跑的任务 = 已暂停 / 失败，可直接从断点续传
            val canResume = active && !VideoDownloader.isActive(r.key)

            v.findViewById<TextView>(R.id.dl_title).text = "${r.title}  ·  ${r.episode}"
            val info = v.findViewById<TextView>(R.id.dl_info)
            info.text = if (!active) {
                "${formatSize(r.size)}  ·  ${fmt.format(Date(r.time))}"
            } else when {
                canResume -> "已暂停 ${r.progress}%  ·  已缓存 ${formatSize(r.size)}，点「继续」从断点续传"
                state.status == DownloadStatus.FAILED -> "下载失败 · 点「继续」可重试（已下载部分自动续传）"
                else -> "下载中 ${state.progress}%  ·  已缓存 ${formatSize(r.size)}"
            }

            val bar = v.findViewById<ProgressBar>(R.id.dl_progress)
            bar.visibility = if (active) View.VISIBLE else View.GONE
            if (active) {
                bar.progress = (if (canResume) r.progress else state.progress).coerceIn(0, 100)
            }

            val play = v.findViewById<Button>(R.id.dl_play)
            play.visibility = if (!active || canResume) View.VISIBLE else View.GONE
            play.text = if (active) "继续" else getString(R.string.action_play)
            play.setOnClickListener { if (active) onContinue(r) else onPlay(r) }

            val delete = v.findViewById<Button>(R.id.dl_delete)
            delete.text = when {
                !active -> getString(R.string.action_delete)
                canResume -> getString(R.string.action_delete)
                else -> "取消"
            }
            delete.setOnClickListener { onDelete(r) }
        }

        override fun getItemCount(): Int = rows.size
    }
}
