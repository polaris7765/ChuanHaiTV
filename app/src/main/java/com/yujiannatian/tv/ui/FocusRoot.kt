package com.yujiannatian.tv.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.yujiannatian.tv.R

/**
 * 双栏布局的焦点桥：左栏按右键落到右侧网格，右侧按左键回到左栏。
 * 记住各自上次聚焦的卡片，避免跨栏时焦点跳到页眉按钮或第一行第一个。
 */
class FocusRoot @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val sidebar: View? get() = findViewById(R.id.sidebar)
    private val rightPane: View? get() = findViewById(R.id.right_pane)
    private val grid: View? get() = findViewById(R.id.video_grid)

    private var lastLeft: View? = null

    init {
        viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus != null && isUnder(sidebar, newFocus)) lastLeft = newFocus
        }
    }

    override fun focusSearch(focused: View, direction: Int): View? {
        if (direction == FOCUS_RIGHT && isUnder(sidebar, focused)) {
            // 每次从左栏进入都回到网格顶部，避免落到上次滚动到的中间行
            (grid as? RecyclerView)?.scrollToPosition(0)
            return grid ?: super.focusSearch(focused, direction)
        }
        if (direction == FOCUS_LEFT && isUnder(rightPane, focused)) {
            return usable(lastLeft) ?: sidebar ?: super.focusSearch(focused, direction)
        }
        return super.focusSearch(focused, direction)
    }

    private fun usable(v: View?): View? = v?.takeIf {
        it.isAttachedToWindow && it.visibility == VISIBLE && it.isFocusable
    }

    private fun isUnder(root: View?, target: View): Boolean {
        var cur: View? = target
        while (cur != null) {
            if (cur === root) return true
            cur = cur.parent as? View
        }
        return false
    }
}
