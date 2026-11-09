package com.yujiannatian.tv.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yujiannatian.tv.R
import com.yujiannatian.tv.core.Pinyin
import com.yujiannatian.tv.core.SearchIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 屏上字母键盘 + 实时结果网格；支持拼音首字母模糊匹配与中文片名直搜 */
class SearchFragment : Fragment() {

    private lateinit var edit: EditText
    private lateinit var status: TextView
    private lateinit var resultTitle: TextView
    private lateinit var keyboard: LinearLayout
    private lateinit var grid: RecyclerView
    private lateinit var adapter: VideoGridAdapter

    private var remoteJob: Job? = null
    private var indexJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_search, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        edit = view.findViewById(R.id.query_edit)
        // 电视上方向键误落到输入框时不弹出软键盘；只有点「中文输入」才主动调起
        edit.showSoftInputOnFocus = false
        status = view.findViewById(R.id.search_status)
        resultTitle = view.findViewById(R.id.result_title)
        keyboard = view.findViewById(R.id.keyboard)
        grid = view.findViewById(R.id.result_grid)

        val density = resources.displayMetrics.density
        val cardPx = (132 * density).toInt().coerceAtLeast(1)
        val rightPx = (resources.displayMetrics.widthPixels * 0.55f).toInt()
        val columns = (rightPx / cardPx).coerceIn(2, 5)

        grid.layoutManager = GridLayoutManager(requireContext(), columns)
        grid.addItemDecoration(GridSpacingDecoration(columns, (8 * density).toInt()))
        adapter = VideoGridAdapter(
            onItemClick = { DetailActivity.start(requireContext(), it) }
        )
        grid.adapter = adapter

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = onQueryChanged(s?.toString().orEmpty())
        })

        buildKeyboard()
        ensureIndex(false)
        view.post {
            if (isAdded) firstKey()?.requestFocus()
        }
    }

    private fun firstKey(): View? = (keyboard.getChildAt(0) as? LinearLayout)?.getChildAt(0)

    private fun buildKeyboard() {
        val keys = buildList {
            addAll(('A'..'Z').map { it.toString() })
            addAll(('0'..'9').map { it.toString() })
            add("删除")
            add("清空")
            add("中文输入")
            add("返回")
        }
        val rows = keys.chunked(7)
        val gap = (2 * resources.displayMetrics.density).toInt()
        keyboard.removeAllViews()
        keyboard.weightSum = rows.size.toFloat()
        rows.forEachIndexed { index, rowKeys ->
            // 每行高度 = 键盘可用高度的 1/行数，随屏幕高度自动缩放
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 7f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                ).apply { if (index > 0) topMargin = gap }
            }
            // 最后一行只有 5 个功能键，按 7 列总权重放大，保证「中文输入」等多字标签放得下
            val keyWeight = 7f / rowKeys.size
            rowKeys.forEach { label ->
                LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_key, row, true)
                val btn = row.getChildAt(row.childCount - 1) as Button
                (btn.layoutParams as LinearLayout.LayoutParams).apply {
                    width = 0
                    height = LinearLayout.LayoutParams.MATCH_PARENT
                    weight = keyWeight
                }
                btn.text = label
                btn.setOnClickListener { onKey(label) }
            }
            keyboard.addView(row)
        }
    }

    private fun onKey(label: String) {
        when (label) {
            "删除" -> {
                val s = edit.text.toString()
                if (s.isNotEmpty()) edit.setText(s.dropLast(1))
                edit.setSelection(edit.text.length)
            }
            "清空" -> edit.setText("")
            "中文输入" -> {
                edit.requestFocus()
                val imm = ContextCompat.getSystemService(
                    requireContext(), InputMethodManager::class.java
                )
                imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            }
            "返回" -> activity?.finish()
            else -> {
                edit.setText(edit.text.toString() + label.lowercase())
                edit.setSelection(edit.text.length)
            }
        }
    }

    /** 支持外接键盘 / 遥控语音直接输入字符 */
    fun appendChar(c: Char) {
        edit.setText(edit.text.toString() + c.lowercaseChar())
        edit.setSelection(edit.text.length)
    }

    private fun onQueryChanged(raw: String) {
        refreshResults(raw)
        remoteJob?.cancel()
        val q = Pinyin.normalize(raw)
        if (q.length < 2) return
        remoteJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(450)
            if (q.all { it in 'a'..'z' }) {
                // 纯拼音首字母：站点搜索只认中文，直接搜 q 必然零结果。
                // 一律用候选汉字反查（例：ZMSR → 取 Z 对应的「走」等常见首字搜索 →
                // 找到《走马上任》并补进索引），不能因本地有模糊匹配结果就跳过：
                // 曾出现长片名被子序列误命中、反查被挡住导致真正目标搜不到的情况。
                // SearchIndex 内部按「站点 + 关键词」去重，重复输入不会重复发请求。
                runCatching { SearchIndex.remoteByInitials(q) }
            } else {
                // 中文关键词：直接用站内搜索补齐长尾片名
                runCatching { SearchIndex.remote(q, 2) }
            }
            if (isAdded) refreshResults(edit.text.toString(), synced = true)
        }
    }

    private fun refreshResults(raw: String, synced: Boolean = false) {
        val q = Pinyin.normalize(raw)
        val results = if (q.isEmpty()) emptyList()
        else Pinyin.filter(q, SearchIndex.snapshot(), 120)
        adapter.replace(results)
        resultTitle.text = if (q.isEmpty()) "搜索结果" else "搜索结果 · ${results.size} 部"
        status.text = if (q.isEmpty()) {
            SearchIndex.message
        } else {
            "关键词：$raw  ·  命中 ${results.size} 部  ·  本地索引 ${SearchIndex.size()} 部" +
                when {
                    synced -> "\n已同步站内搜索结果"
                    results.isEmpty() -> "\n本地未命中，正在用站内搜索反查…"
                    else -> "\n正在同步站内搜索结果…"
                }
        }
    }

    private fun ensureIndex(force: Boolean) {
        indexJob?.cancel()
        indexJob = guardedLaunch {
            SearchIndex.ensure(force) { text ->
                if (edit.text.isNullOrEmpty()) status.text = text
            }
            if (isAdded) refreshResults(edit.text.toString())
        }
    }

    fun retry() = ensureIndex(true)

    override fun onDestroyView() {
        remoteJob?.cancel()
        indexJob?.cancel()
        super.onDestroyView()
    }
}
