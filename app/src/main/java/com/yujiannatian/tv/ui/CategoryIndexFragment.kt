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
import com.yujiannatian.tv.core.Category
import com.yujiannatian.tv.core.CategoryGroup
import com.yujiannatian.tv.core.LoadResult
import com.yujiannatian.tv.core.SiteRepository

/**
 * 站点地图的全部子分类，按大类分组、纯垂直单列展示。
 *
 * 原来用 Leanback BrowseSupportFragment + ListRow，每行是横向 HorizontalGridView，
 * 遥控器左右键会在同一行里横向滚动，与「只允许上下滑动」的要求冲突。
 * 现在改为 RecyclerView(LinearLayoutManager.VERTICAL) + 单列按钮，
 * 结构上不存在横向滚动，左右方向键不会移动焦点。
 */
class CategoryIndexFragment : Fragment() {

    private sealed interface Row {
        data class Header(val name: String) : Row
        data class Entry(val category: Category) : Row
    }

    private lateinit var list: RecyclerView
    private lateinit var adapter: CategoryAdapter
    private var started = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_category_index, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        list = view.findViewById(R.id.category_list)
        // 只允许上下滑动：垂直单列 + 禁用横向滚动条
        list.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false)
        list.isHorizontalScrollBarEnabled = false
        list.isVerticalScrollBarEnabled = false
        list.overScrollMode = View.OVER_SCROLL_NEVER
        list.addItemDecoration(GridSpacingDecoration(1, 6))
        adapter = CategoryAdapter { GridActivity.start(requireContext(), it) }
        list.adapter = adapter
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
            val groups = (result as LoadResult.Ok<List<CategoryGroup>>).value
            if (!isAdded) return@guardedLaunch
            adapter.submit(flatten(groups))
            act.clearOverlay()
            list.post {
                if (isAdded) {
                    (list.findViewHolderForAdapterPosition(1)?.itemView
                        ?: list.getChildAt(0))?.requestFocus()
                }
            }
        }
    }

    private fun flatten(groups: List<CategoryGroup>): List<Row> = buildList {
        groups.forEach { g ->
            if (g.categories.isEmpty()) return@forEach
            add(Row.Header("${g.name} · ${g.categories.size} 项"))
            g.categories.forEach { add(Row.Entry(it)) }
        }
    }

    fun retry() = load()

    private class CategoryAdapter(
        private val onClick: (Category) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_HEADER = 0
            private const val TYPE_ENTRY = 1
        }

        private val rows = mutableListOf<Row>()

        fun submit(list: List<Row>) {
            rows.clear()
            rows.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Header -> TYPE_HEADER
            is Row.Entry -> TYPE_ENTRY
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                object : RecyclerView.ViewHolder(
                    inflater.inflate(R.layout.item_category_header, parent, false)
                ) {}
            } else {
                object : RecyclerView.ViewHolder(
                    inflater.inflate(R.layout.item_category, parent, false)
                ) {}
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> holder.itemView
                    .findViewById<TextView>(R.id.section_header)?.text = row.name
                is Row.Entry -> {
                    val btn = holder.itemView.findViewById<Button>(R.id.category_name)
                    val c = row.category
                    btn.text = if (c.tag.isBlank()) c.name else "${c.name}　·　${c.tag}"
                    btn.setOnClickListener { onClick(c) }
                }
            }
        }

        override fun getItemCount(): Int = rows.size
    }
}
