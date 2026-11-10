package com.yujiannatian.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TV / 手机的点击分流：TV 一个焦点属性都不改，手机才清 focusableInTouchMode */
class TouchFocusTest {

    @Test
    fun televisionKeepsOriginalDpadFocus() {
        assertFalse(TouchFocus.shouldAdjust(isTv = true))
    }

    @Test
    fun phoneEnablesSingleTap() {
        assertTrue(TouchFocus.shouldAdjust(isTv = false))
    }
}
