package com.antigravity.companion

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView

/**
 * 原生轻量 WebView 容器，完整支持系统文本选择与 ActionMode（复制、粘贴、全选）
 */
class NoActionModeWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr)
