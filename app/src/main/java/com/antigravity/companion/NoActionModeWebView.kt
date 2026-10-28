package com.antigravity.companion

import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.webkit.WebView
import android.widget.PopupMenu

/**
 * 物理绝杀所有 ActionMode（TYPE_FLOATING / TYPE_PRIMARY）的特制 WebView
 * 返回轻量 DummyActionMode，消费系统事件，绝不弹出任何原生 Action Bar 或浮动条
 */
class NoActionModeWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    private val dummyActionMode = object : ActionMode() {
        override fun setTitle(title: CharSequence?) {}
        override fun setTitle(resId: Int) {}
        override fun setSubtitle(subtitle: CharSequence?) {}
        override fun setSubtitle(resId: Int) {}
        override fun setCustomView(view: View?) {}
        override fun invalidate() {}
        override fun finish() {}
        override fun getMenu(): Menu = PopupMenu(context, this@NoActionModeWebView).menu
        override fun getTitle(): CharSequence? = null
        override fun getSubtitle(): CharSequence? = null
        override fun getCustomView(): View? = null
        override fun getMenuInflater(): MenuInflater = MenuInflater(context)
    }

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? {
        return dummyActionMode
    }

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
        return dummyActionMode
    }
}
