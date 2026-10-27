package com.antigravity.companion

import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.webkit.WebView

/**
 * 物理绝杀所有 ActionMode（TYPE_FLOATING / TYPE_PRIMARY）的特制 WebView
 * 配合 MainActivity.onActionModeStarted { mode?.finish() } 达成双重物理终结
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
}
