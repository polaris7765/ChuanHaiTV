package com.yujiannatian.tv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yujiannatian.tv.R
import com.yujiannatian.tv.core.DownloadRecord
import com.yujiannatian.tv.core.DownloadsStore
import com.yujiannatian.tv.core.VideoDownloader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadsFragment : Fragment() {

    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var adapter: DlAdapter

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
            onPlay = { PlaybackActivity.startLocal(requireContext(), it.title, it.episode, it.file) },
            onDelete = {
                VideoDownloader.delete(requireContext().applicationContext, it.key)
                refresh()
            }
        )
        list.adapter = adapter
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) refresh()
    }

    fun refresh() {
        if (!isAdded) return
        val records = DownloadsStore.list(requireContext().applicationContext)
        adapter.submit(records)
        status.text = if (records.isEmpty()) {
            "暂无已下载剧集。进入节目详情，聚焦某一集的「下载」按钮按确认键即可保存。"
        } else {
            "共 ${records.size} 集 · 总大小 ${formatSize(records.sumOf { it.size })}"
        }
        list.post {
            if (isAdded && records.isNotEmpty()) {
                list.getChildAt(0)?.findViewById<Button>(R.id.dl_play)?.requestFocus()
            }
        }
    }

    private fun formatSize(bytes: Long): String =
        if (bytes >= 1024L * 1024L) "%.1f MB".format(bytes / 1024f / 1024f)
        else "${bytes / 1024L} KB"

    private inner class DlAdapter(
        private val onPlay: (DownloadRecord) -> Unit,
        private val onDelete: (DownloadRecord) -> Unit
    ) : RecyclerView.Adapter<DlAdapter.VH>() {

        private val items = mutableListOf<DownloadRecord>()
        private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun submit(list: List<DownloadRecord>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.dl_title)
            val info: TextView = v.findViewById(R.id.dl_info)
            val play: Button = v.findViewById(R.id.dl_play)
            val delete: Button = v.findViewById(R.id.dl_delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = items[position]
            holder.title.text = "${r.title}  ·  ${r.episode}"
            holder.info.text = "${formatSize(r.size)}  ·  ${fmt.format(Date(r.time))}"
            holder.play.setOnClickListener { onPlay(r) }
            holder.delete.setOnClickListener { onDelete(r) }
        }

        override fun getItemCount(): Int = items.size
    }
}
