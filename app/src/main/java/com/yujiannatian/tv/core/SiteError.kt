package com.yujiannatian.tv.core

enum class ErrorKind {
    INVALID_URL, NETWORK, TIMEOUT, HTTP, PARSE, EMPTY, PLAYBACK
}

/** 所有可预期的失败都归一化为 SiteError，UI 层据此给出中文提示与重试入口 */
class SiteError(
    val kind: ErrorKind,
    message: String,
    val detail: String = ""
) : Exception(message)

sealed interface LoadResult<out T> {
    data class Ok<T>(val value: T) : LoadResult<T>
    data class Err(val error: SiteError) : LoadResult<Nothing>
}

fun SiteError.title(): String = when (kind) {
    ErrorKind.INVALID_URL -> "地址格式不正确"
    ErrorKind.NETWORK -> "网络连接失败"
    ErrorKind.TIMEOUT -> "请求超时"
    ErrorKind.HTTP -> "站点返回错误"
    ErrorKind.PARSE -> "数据解析异常"
    ErrorKind.EMPTY -> "没有可用数据"
    ErrorKind.PLAYBACK -> "无法播放"
}

fun SiteError.advice(): String = when (kind) {
    ErrorKind.INVALID_URL -> "请检查是否包含 http:// 或 https://，并填写站点首页地址。"
    ErrorKind.NETWORK -> "请确认电视已联网，或稍后按「重试」。若站点已迁移，请更换地址。"
    ErrorKind.TIMEOUT -> "网络较慢或站点暂时不可达，可重试，也可以更换为其它线路地址。"
    ErrorKind.HTTP -> "站点可能开启了防护或已改版，可尝试重新加载或更换地址。"
    ErrorKind.PARSE -> "页面结构可能发生变化（改版、跳转验证页）。可重试，或更换为兼容的地址。"
    ErrorKind.EMPTY -> "该地址返回了页面但没有可用资源，建议确认站点首页或切换分类。"
    ErrorKind.PLAYBACK -> "该播放源可能已失效，请返回选择其它播放源。"
}
