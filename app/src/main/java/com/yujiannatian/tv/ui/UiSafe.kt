package com.yujiannatian.tv.ui

import android.util.Log
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.yujiannatian.tv.core.ErrorKind
import com.yujiannatian.tv.core.SiteError
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 把任意 Throwable 归一化成可展示、可重试的错误 */
fun Throwable.toSiteError(): SiteError = when (this) {
    is SiteError -> this
    is OutOfMemoryError ->
        SiteError(ErrorKind.PARSE, "内存不足，页面渲染未完成", "建议重启应用后重试")
    is NoSuchMethodError, is NoClassDefFoundError, is ExceptionInInitializerError ->
        SiteError(
            ErrorKind.PARSE,
            "系统版本与依赖库不兼容",
            "${javaClass.simpleName}: ${message ?: ""}"
        )
    else ->
        SiteError(
            ErrorKind.PARSE,
            "界面加载或渲染失败",
            "${javaClass.simpleName}: ${message ?: ""}".take(240)
        )
}

/**
 * Fragment 内协程的统一兜底：数据层之外的异常（例如卡片渲染抛错）
 * 一律转为可聚焦的错误页，而不是让进程直接退出。
 */
fun Fragment.guardedLaunch(block: suspend CoroutineScope.() -> Unit) =
    viewLifecycleOwner.lifecycleScope.launch(
        CoroutineExceptionHandler { _, t ->
            Log.e("YuJianTV", "界面加载异常", t)
            runCatching {
                if (isAdded) (activity as? BaseTvActivity)?.showError(t.toSiteError())
            }
        },
        block = block
    )
