package com.yujiannatian.tv.ui

interface StatusHost {
    fun onStatusRetry()
    fun onStatusChangeSite()
    fun onStatusResetSite()

    /** 状态页「返回」：主页面回主菜单，其它页面回上一级 */
    fun onStatusBack()
}
