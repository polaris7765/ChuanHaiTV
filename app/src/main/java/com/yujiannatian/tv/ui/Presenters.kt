package com.yujiannatian.tv.ui

import android.text.TextUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.R as LbR
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.yujiannatian.tv.R
import com.yujiannatian.tv.core.Category
import com.yujiannatian.tv.core.VideoDetail
import com.yujiannatian.tv.core.VideoItem

private fun dp(parent: ViewGroup, value: Int): Int =
    (value * parent.resources.displayMetrics.density).toInt()

/** 影片封面卡片：统一缩小尺寸与文字，焦点态只描边（放大由 zoomFactor = NONE 关闭） */
class VideoCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(dp(parent, 132), dp(parent, 184))
            setMainImageScaleType(ImageView.ScaleType.CENTER_CROP)
            setInfoAreaBackgroundColor(ContextCompat.getColor(context, R.color.bg_elevated))
            findViewById<TextView>(LbR.id.title_text)?.apply {
                textSize = 13f
                maxLines = 1
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
            findViewById<TextView>(LbR.id.content_text)?.apply {
                textSize = 11f
                maxLines = 1
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val video = item as VideoItem
        val card = viewHolder.view as ImageCardView
        card.titleText = video.title
        card.contentText = buildList {
            if (video.note.isNotBlank()) add(video.note)
            if (video.score.isNotBlank()) add("评分 ${video.score}")
        }.joinToString("  ").ifBlank { " " }
        ImageLoader.load(card.mainImageView, video.cover)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val image = (viewHolder.view as? ImageCardView)?.mainImageView ?: return
        image.setTag(R.id.tag_image_url, null)
        image.setImageDrawable(null)
    }
}

/** 纯文字卡片：分类、操作按钮、播放源共用；焦点态由 bg_card 提供 */
class TextCardPresenter(
    private val widthDp: Int = 170,
    private val heightDp: Int = 68,
    private val primary: (Any) -> String,
    private val secondary: (Any) -> String = { "" }
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.card_text, parent, false) as TextView
        view.layoutParams = ViewGroup.LayoutParams(dp(parent, widthDp), dp(parent, heightDp))
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val tv = viewHolder.view as TextView
        val sub = runCatching { secondary(item) }.getOrDefault("")
        tv.text = if (sub.isBlank()) primary(item) else "${primary(item)}\n$sub"
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
}

fun categoryCardPresenter(): Presenter = TextCardPresenter(
    170, 68,
    { (it as Category).name },
    { (it as Category).tag }
)

fun actionCardPresenter(): Presenter = TextCardPresenter(
    220, 64,
    { (it as ActionItem).label },
    { "按确认键执行" }
)

fun sourceCardPresenter(): Presenter = TextCardPresenter(
    180, 68,
    { (it as SourceItem).name },
    { "${(it as SourceItem).count} 集" }
)

/** 详情大卡片（备用）；字号随 card_info.xml 一起缩小 */
class InfoPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.card_info, parent, false)
        view.layoutParams = ViewGroup.LayoutParams(dp(parent, 1000), dp(parent, 150))
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val d = item as VideoDetail
        val v = viewHolder.view
        v.findViewById<TextView>(R.id.info_title).text = d.title
        v.findViewById<TextView>(R.id.info_meta).text =
            d.meta.ifBlank { "可用播放源：${d.sources.size} 个" }
        v.findViewById<TextView>(R.id.info_desc).text =
            d.description.ifBlank { "该站点未提供简介。" }
        ImageLoader.load(v.findViewById(R.id.info_cover), d.cover)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
}
