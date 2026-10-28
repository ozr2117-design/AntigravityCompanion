package com.antigravity.companion

import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.view.View
import android.webkit.WebView

/**
 * 物理绝杀所有 ActionMode（TYPE_FLOATING / TYPE_PRIMARY）的特制 WebView
 * 针对 Android 14/15/16/17 (HyperOS 4)：
 * 同时拦截 startActionMode 与 startActionModeForChild，
 * 彻底掐断 Chromium 内部子视图（AwContentsView / SelectPopup）向系统冒泡请求 ActionMode 的全部通道。
 */
class NoActionModeWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? {
        return null
    }

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
        return null
    }

    override fun startActionModeForChild(originalView: View?, callback: ActionMode.Callback?): ActionMode? {
        return null
    }

    override fun startActionModeForChild(originalView: View?, callback: ActionMode.Callback?, type: Int): ActionMode? {
        return null
    }
}
