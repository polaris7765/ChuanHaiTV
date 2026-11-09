package com.yujiannatian.tv.ui

import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yujiannatian.tv.R
import com.yujiannatian.tv.core.VideoItem

/** 网格间距：列间距 + 行间距，避免卡片贴在一起 */
class GridSpacingDecoration(
    private val columns: Int,
    private val space: Int
) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val column = position % columns
        outRect.left = space * column / columns
        outRect.right = space * (columns - 1 - column) / columns
        outRect.top = if (position >= columns) space else 0
        outRect.bottom = space
    }
}

/** 左栏条目类型 */
enum class NavType { HOT, GROUP, HISTORY, FAVORITES, SEARCH, CATEGORIES, DOWNLOADS, SETTINGS, CONTACT }

data class SidebarItem(
    val id: String,
    val label: String,
    val type: NavType,
    val path: String = ""
)

class SidebarAdapter(
    private val onClick: (SidebarItem) -> Unit
) : RecyclerView.Adapter<SidebarAdapter.VH>() {

    private val items = mutableListOf<SidebarItem>()
    var selectedId: String = ""
        private set

    fun submit(list: List<SidebarItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun select(id: String) {
        val old = items.indexOfFirst { it.id == selectedId }
        selectedId = id
        val now = items.indexOfFirst { it.id == id }
        if (old >= 0) notifyItemChanged(old)
        if (now >= 0) notifyItemChanged(now)
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.sidebar_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sidebar, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.text.text = item.label
        holder.text.isSelected = item.id == selectedId
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}

/** 小尺寸封面网格；首页、搜索结果、分类页共用 */
class VideoGridAdapter(
    private val onItemClick: (VideoItem) -> Unit,
    private val onNeedMore: (() -> Unit)? = null
) : RecyclerView.Adapter<VideoGridAdapter.VH>() {

    private val data = mutableListOf<VideoItem>()

    fun items(): List<VideoItem> = data.toList()

    fun replace(list: List<VideoItem>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<VideoItem>) {
        if (list.isEmpty()) return
        val known = data.mapTo(HashSet()) { it.path }
        val fresh = list.filter { it.path !in known }
        if (fresh.isEmpty()) return
        val start = data.size
        data.addAll(fresh)
        notifyItemRangeInserted(start, fresh.size)
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.video_title)
        val note: TextView = v.findViewById(R.id.video_note)
        val cover: android.widget.ImageView = v.findViewById(R.id.video_cover)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = data[position]
        holder.title.text = item.title
        holder.note.text = listOf(item.note, item.score)
            .filter { it.isNotBlank() }
            .joinToString("  ")
            .ifBlank { " " }
        holder.itemView.setTag(R.id.tag_item_path, item.path)
        ImageLoader.load(holder.cover, item.cover, item.title)
        holder.itemView.setOnClickListener { onItemClick(item) }
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && onNeedMore != null && position >= data.size - 6) onNeedMore.invoke()
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.cover.setTag(R.id.tag_image_url, null)
        holder.cover.setImageDrawable(null)
        holder.itemView.setOnFocusChangeListener(null)
    }

    override fun getItemCount(): Int = data.size
}
