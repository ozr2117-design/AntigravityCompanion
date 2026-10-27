package com.antigravity.companion

import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebView

/**
 * 彻底禁用 Android 系统级 ActionMode（TYPE_FLOATING / TYPE_PRIMARY）的特制 WebView
 * 彻底斩断骑在左水滴手柄上的系统白色 Copy 药丸浮层！
 */
class NoActionModeWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    init {
        val noActionCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
            override fun onDestroyActionMode(mode: ActionMode?) {}
        }
        customSelectionActionModeCallback = noActionCallback
    }

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? {
        return null
    }

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
        return null
    }
}
