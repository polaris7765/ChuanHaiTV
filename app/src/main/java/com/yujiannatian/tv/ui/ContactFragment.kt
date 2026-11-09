package com.yujiannatian.tv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.yujiannatian.tv.R

/** 「联系作者」：作者信息、赞赏二维码、版权与侵权联系邮箱（信息从设置页迁移而来） */
class ContactFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_contact, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // 进入页面时把焦点交给左上角「返回」，避免焦点落到不可聚焦的说明文字上
        view.post { activity?.findViewById<View>(R.id.btn_back)?.requestFocus() }
    }
}
