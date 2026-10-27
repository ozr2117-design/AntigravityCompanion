package com.antigravity.companion

import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.webkit.WebView

/**
 * 物理绝杀所有 ActionMode（TYPE_FLOATING / TYPE_PRIMARY）的特制 WebView
 * 无论系统走何种 fallback 分支，在 onActionModeStarted 即刻 finish 绝杀！
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

    override fun onActionModeStarted(mode: ActionMode?) {
        super.onActionModeStarted(mode)
        mode?.let { m ->
            post {
                try {
                    m.finish()
                } catch (e: Exception) {}
            }
        }
    }
}
