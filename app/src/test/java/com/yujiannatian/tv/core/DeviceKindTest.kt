package com.yujiannatian.tv.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设备类型判定：TV 隐藏投屏入口、手机端投屏成功后停本机播放的依据 */
class DeviceKindTest {

    // Configuration.UI_MODE_TYPE_TELEVISION = 4，UI_MODE_TYPE_NORMAL = 1
    private val televisionMode = 4
    private val normalMode = 1

    @Test
    fun televisionUiModeIsTv() {
        assertTrue(DeviceKind.isTelevision(televisionMode, false, false))
    }

    @Test
    fun televisionSystemFeatureIsTv() {
        assertTrue(DeviceKind.isTelevision(normalMode, true, false))
    }

    @Test
    fun leanbackFeatureIsTv() {
        assertTrue(DeviceKind.isTelevision(normalMode, false, true))
    }

    @Test
    fun phoneIsNotTv() {
        assertFalse(DeviceKind.isTelevision(normalMode, false, false))
    }
}
