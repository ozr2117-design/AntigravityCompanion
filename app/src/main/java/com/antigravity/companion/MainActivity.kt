package com.antigravity.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.antigravity.companion.databinding.ActivityMainBinding
import com.antigravity.companion.databinding.DialogSetupBinding
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: StorageManager
    private lateinit var notificationHelper: NotificationHelper

    // 文件与媒体上传回调
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    // 网页中点击 "+" 选择图片/文件 Launcher
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = mutableListOf<Uri>()
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            data?.data?.let { uris.add(it) }
            data?.clipData?.let { clipData ->
                for (i in 0 until clipData.itemCount) {
                    uris.add(clipData.getItemAt(i).uri)
                }
            }
        }
        fileUploadCallback?.onReceiveValue(if (uris.isNotEmpty()) uris.distinct().toTypedArray() else null)
        fileUploadCallback = null
    }

    // 两指向下滑动唤出设置（避开系统三指截屏冲突）
    private var twoFingerStartY = 0f
    private var isTwoFingerSwipeActive = false
    private var hasShownConnectToast: Boolean = false

    // 二维码扫描 Launcher
    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val scannedUrl = result.contents.trim()
            saveAndLoadUrl(scannedUrl)
        }
    }

    // Android 13+ 通知权限请求
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                // 权限已获取
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = StorageManager(this)
        notificationHelper = NotificationHelper(this)

        updateSystemBars()
        checkPermissions()
        initViews()
        initWebView()

        // 拦截物理返回键：优先在网页内后退
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    // 最小化到后台，不退出进程
                    moveTaskToBack(true)
                }
            }
        })

        // 检查配置
        if (storage.isConfigured()) {
            loadTargetPage()
            KeepAliveService.start(this)
        } else {
            showEmptyState(true)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateSystemBars()
    }

    private fun updateSystemBars() {
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val statusBarBg = ContextCompat.getColor(this, R.color.status_bar_color)
        val navBarBg = ContextCompat.getColor(this, R.color.surface)

        window.statusBarColor = statusBarBg
        window.navigationBarColor = navBarBg

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        // 浅色模式（白天模式）：文字图标变深黑(true)；深色模式（夜间模式）：文字图标变白色(false)
        insetsController.isAppearanceLightStatusBars = !isNightMode
        insetsController.isAppearanceLightNavigationBars = !isNightMode
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun initViews() {
        // 初始页面的扫码和手动配置按钮
        binding.btnScanSetup.setOnClickListener {
            startScan()
        }
        binding.btnManualSetup.setOnClickListener {
            showSetupDialog()
        }

        // 浮动设置按钮
        binding.fabSettings.setOnClickListener {
            showSetupDialog()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        val webView = binding.webView
        val settings = webView.settings

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            userAgentString = settings.userAgentString + " AntigravityCompanion/1.2"
        }

        // 启用网页内原生平滑滚动与惯性滑动
        webView.apply {
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS

            // 确保启用原生文本长按选择与触感反馈，获取焦点支持
            isFocusable = true
            isFocusableInTouchMode = true
            isLongClickable = true
            isHapticFeedbackEnabled = true
            isClickable = true
            isEnabled = true
        }

        // 注入原生 JS 接口桥梁
        webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE

                // 页面正常加载完成，确保设置按钮完全隐藏，决不遮挡发送按钮！
                showEmptyState(false)

                if (!hasShownConnectToast) {
                    hasShownConnectToast = true
                    Toast.makeText(this@MainActivity, "已连接！如需重新配置或刷新，两指向下滑动即可", Toast.LENGTH_SHORT).show()
                }

                // 记录最后访问的 URL，方便断线或重进时直达该页面
                if (!url.isNullOrBlank() && !url.contains("about:blank")) {
                    storage.lastPageUrl = url
                }

                // 页面加载完成后注入监听脚本：支持切回前台自动触发唤醒
                injectKeepAliveAndNotificationScript(view)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    binding.progressBar.visibility = View.GONE
                    // 网络不可达或电脑未开启时，切回配置界面，方便用户重新扫码或修改 IP
                    showEmptyState(true)
                    Toast.makeText(this@MainActivity, "连接电脑失败，请检查网络或重新配置地址", Toast.LENGTH_SHORT).show()
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                // 允许内网自签名证书
                handler?.proceed()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                if (newProgress >= 100) {
                    binding.progressBar.visibility = View.GONE
                }
            }

            // 处理网页中点击 "+" 选择图片/附件/媒体文件
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = try {
                    fileChooserParams?.createIntent()?.apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                    } ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                } catch (e: Exception) {
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                }

                if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                try {
                    filePickerLauncher.launch(intent)
                } catch (e: Exception) {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    Toast.makeText(this@MainActivity, "无法打开系统文件选择器", Toast.LENGTH_SHORT).show()
                    return false
                }
                return true
            }
        }
    }

    private fun injectKeepAliveAndNotificationScript(view: WebView?) {
        val js = """
            (function() {
                if (window.__antigravity_companion_injected) return;
                window.__antigravity_companion_injected = true;

                // 1. 穿透样式：解锁所有内联文本、代码块，清除 Monaco 遮罩层拦截
                function getSelectionCSS() {
                    return 'html, body { -webkit-user-select: auto !important; user-select: auto !important; } ' +
                        'p, span, em, strong, b, i, u, small, mark, abbr, cite, q, h1, h2, h3, h4, h5, h6, li, dt, dd, th, td, blockquote, code, pre, kbd, samp, var, pre code, pre span, .view-line, .monaco-tokenized-source, [class*="chat"], [class*="message"], [class*="content"], [class*="markdown"], [class*="prose"], [class*="bubble"], [class*="text"] { ' +
                        '-webkit-user-select: text !important; user-select: text !important; -webkit-touch-callout: default !important; } ' +
                        'button, [role="button"], a[role="button"], input, select, textarea, label[for], svg, img, video, canvas, [contenteditable="false"] { ' +
                        '-webkit-user-select: none !important; user-select: none !important; } ' +
                        '.monaco-editor .view-overlays { pointer-events: none !important; } ' +
                        '[class*="message"], [class*="content"], [class*="chat"], [class*="bubble"], [class*="markdown"], [class*="prose"], .monaco-scrollable-element { ' +
                        'touch-action: pan-y !important; } ' +
                        '::selection { background: rgba(26, 115, 232, 0.35) !important; }';
                }

                function injectStylesAndFixes(targetDoc) {
                    if (!targetDoc) return;
                    try {
                        var styleId = '__ag_precision_selection_style';
                        if (!targetDoc.getElementById(styleId)) {
                            var style = targetDoc.createElement('style');
                            style.id = styleId;
                            style.type = 'text/css';
                            style.innerHTML = getSelectionCSS();
                            (targetDoc.head || targetDoc.documentElement).appendChild(style);
                        }
                        if (targetDoc.body) {
                            if (targetDoc.body.onselectstart) targetDoc.body.onselectstart = null;
                            if (targetDoc.body.oncontextmenu) targetDoc.body.oncontextmenu = null;
                        }
                        var viewport = targetDoc.querySelector('meta[name="viewport"]');
                        if (!viewport && targetDoc.head) {
                            viewport = targetDoc.createElement('meta');
                            viewport.name = 'viewport';
                            targetDoc.head.appendChild(viewport);
                        }
                        if (viewport && (!viewport.content || !viewport.content.includes('width=device-width'))) {
                            viewport.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
                        }
                    } catch (e) {}

                    // 递归注入并穿透所有相同源 iframe
                    try {
                        var iframes = targetDoc.querySelectorAll('iframe');
                        for (var i = 0; i < iframes.length; i++) {
                            try {
                                var iframeDoc = iframes[i].contentDocument || (iframes[i].contentWindow && iframes[i].contentWindow.document);
                                if (iframeDoc && !iframeDoc.__ag_injected) {
                                    iframeDoc.__ag_injected = true;
                                    injectStylesAndFixes(iframeDoc);
                                    initPrecisionSelector(iframeDoc);
                                }
                            } catch (err) {}
                        }
                    } catch (e) {}
                }

                // 2. 自定义高精度水滴游标、逐字微调手柄与放大镜复制条
                function initPrecisionSelector(targetDoc) {
                    if (!targetDoc || targetDoc.__ag_selector_ready) return;
                    targetDoc.__ag_selector_ready = true;

                    var activeRange = null;
                    var overlay = null;
                    var handleStart = null;
                    var handleEnd = null;
                    var toolbar = null;
                    var magnifier = null;
                    var longPressTimer = null;
                    var touchStartX = 0;
                    var touchStartY = 0;
                    var isSelecting = false;

                    function createOverlay() {
                        if (overlay && overlay.parentNode) return;
                        overlay = targetDoc.createElement('div');
                        overlay.id = '__ag_precision_overlay';
                        overlay.style.cssText = 'position:fixed;top:0;left:0;width:100vw;height:100vh;z-index:2147483647;pointer-events:none;display:none;';

                        overlay.innerHTML = 
                            '<div id="__ag_h_start" style="position:absolute;width:44px;height:44px;margin-left:-22px;margin-top:0px;cursor:pointer;pointer-events:auto;touch-action:none;display:flex;align-items:center;justify-content:center;z-index:2147483647;">' +
                                '<div style="width:16px;height:16px;border-radius:50% 0 50% 50%;background:#1a73e8;border:2px solid #fff;box-shadow:0 2px 6px rgba(0,0,0,0.4);transform:rotate(-45deg);"></div>' +
                            '</div>' +
                            '<div id="__ag_h_end" style="position:absolute;width:44px;height:44px;margin-left:-22px;margin-top:0px;cursor:pointer;pointer-events:auto;touch-action:none;display:flex;align-items:center;justify-content:center;z-index:2147483647;">' +
                                '<div style="width:16px;height:16px;border-radius:50% 50% 50% 0;background:#1a73e8;border:2px solid #fff;box-shadow:0 2px 6px rgba(0,0,0,0.4);transform:rotate(-45deg);"></div>' +
                            '</div>' +
                            '<div id="__ag_toolbar" style="position:absolute;background:#202124;color:#fff;padding:4px 6px;border-radius:8px;box-shadow:0 4px 16px rgba(0,0,0,0.45);pointer-events:auto;z-index:2147483647;display:flex;align-items:center;gap:2px;font-size:13px;font-family:sans-serif;white-space:nowrap;user-select:none;">' +
                                '<button id="__ag_btn_copy" style="background:transparent;border:none;color:#fff;font-size:13px;font-weight:bold;padding:6px 10px;border-radius:4px;cursor:pointer;">📋 复制</button>' +
                                '<button id="__ag_btn_all" style="background:transparent;border:none;color:#ccc;font-size:13px;padding:6px 8px;border-radius:4px;cursor:pointer;">全选</button>' +
                                '<button id="__ag_btn_close" style="background:transparent;border:none;color:#999;font-size:13px;padding:6px 8px;border-radius:4px;cursor:pointer;">✕</button>' +
                            '</div>' +
                            '<div id="__ag_mag" style="position:absolute;background:#202124;color:#fff;border:1px solid #5f6368;border-radius:8px;padding:6px 12px;font-size:15px;font-family:sans-serif;box-shadow:0 4px 16px rgba(0,0,0,0.4);pointer-events:none;white-space:nowrap;display:none;z-index:2147483647;transform:translate(-50%,-100%);margin-top:-16px;"></div>';

                        (targetDoc.body || targetDoc.documentElement).appendChild(overlay);

                        handleStart = overlay.querySelector('#__ag_h_start');
                        handleEnd = overlay.querySelector('#__ag_h_end');
                        toolbar = overlay.querySelector('#__ag_toolbar');
                        magnifier = overlay.querySelector('#__ag_mag');

                        overlay.querySelector('#__ag_btn_copy').addEventListener('click', function(e) {
                            e.preventDefault();
                            e.stopPropagation();
                            copySelectedText();
                        });

                        overlay.querySelector('#__ag_btn_all').addEventListener('click', function(e) {
                            e.preventDefault();
                            e.stopPropagation();
                            selectAllAround();
                        });

                        overlay.querySelector('#__ag_btn_close').addEventListener('click', function(e) {
                            e.preventDefault();
                            e.stopPropagation();
                            dismissOverlay();
                        });

                        bindHandleDrag(handleStart, 'start');
                        bindHandleDrag(handleEnd, 'end');
                    }

                    function getCaretAtPoint(x, y) {
                        if (targetDoc.caretRangeFromPoint) {
                            var r = targetDoc.caretRangeFromPoint(x, y);
                            if (r) return { node: r.startContainer, offset: r.startOffset };
                        } else if (targetDoc.caretPositionFromPoint) {
                            var p = targetDoc.caretPositionFromPoint(x, y);
                            if (p) return { node: p.offsetNode, offset: p.offset };
                        }
                        return null;
                    }

                    function getInitialWordRange(node, offset) {
                        if (!node) return null;
                        if (node.nodeType !== Node.TEXT_NODE) {
                            if (node.firstChild && node.firstChild.nodeType === Node.TEXT_NODE) {
                                node = node.firstChild;
                                offset = 0;
                            } else {
                                return null;
                            }
                        }
                        var text = node.textContent || '';
                        if (text.length === 0) return null;

                        var start = Math.max(0, Math.min(offset, text.length - 1));
                        var end = start;

                        var isCJK = function(c) { return /[\u4e00-\u9fa5\u3040-\u30ff]/.test(c); };
                        var isWord = function(c) { return /[\w-]/.test(c); };

                        var ch = text.charAt(start);
                        if (isCJK(ch)) {
                            end = Math.min(text.length, start + 1);
                        } else if (isWord(ch)) {
                            while (start > 0 && isWord(text.charAt(start - 1))) start--;
                            while (end < text.length && isWord(text.charAt(end))) end++;
                        } else {
                            end = Math.min(text.length, start + 1);
                        }

                        var range = targetDoc.createRange();
                        range.setStart(node, start);
                        range.setEnd(node, Math.max(end, start + 1));
                        return range;
                    }

                    function updateOverlayUI() {
                        if (!activeRange || !overlay) return;
                        var rects = activeRange.getClientRects();
                        if (!rects || rects.length === 0) {
                            var single = activeRange.getBoundingClientRect();
                            if (!single || (single.width === 0 && single.height === 0)) return;
                            rects = [single];
                        }

                        var first = rects[0];
                        var last = rects[rects.length - 1];

                        handleStart.style.left = first.left + 'px';
                        handleStart.style.top = first.bottom + 'px';

                        handleEnd.style.left = last.right + 'px';
                        handleEnd.style.top = last.bottom + 'px';

                        var bound = activeRange.getBoundingClientRect();
                        var top = bound.top - 46;
                        if (top < 10) top = bound.bottom + 10;
                        var left = bound.left + (bound.width / 2);
                        var maxW = window.innerWidth || targetDoc.documentElement.clientWidth || 360;
                        if (left < 75) left = 75;
                        if (left > maxW - 75) left = maxW - 75;

                        toolbar.style.left = left + 'px';
                        toolbar.style.top = top + 'px';
                        toolbar.style.transform = 'translateX(-50%)';

                        overlay.style.display = 'block';
                    }

                    function syncToNativeSelection() {
                        if (!activeRange) return;
                        try {
                            var sel = targetDoc.getSelection();
                            sel.removeAllRanges();
                            sel.addRange(activeRange);
                        } catch (e) {}
                    }

                    function copySelectedText() {
                        if (!activeRange) return;
                        var text = activeRange.toString();
                        if (text && text.length > 0) {
                            if (window.AndroidBridge && window.AndroidBridge.copyToClipboard) {
                                window.AndroidBridge.copyToClipboard(text);
                            } else if (navigator.clipboard && navigator.clipboard.writeText) {
                                navigator.clipboard.writeText(text);
                            }
                        }
                        dismissOverlay();
                    }

                    function selectAllAround() {
                        if (!activeRange) return;
                        var container = activeRange.commonAncestorContainer;
                        while (container && container !== targetDoc.body && container.offsetHeight < 400) {
                            var tag = container.tagName;
                            if (tag === 'P' || tag === 'PRE' || tag === 'CODE' || (container.className && typeof container.className === 'string' && container.className.indexOf('message') !== -1)) {
                                break;
                            }
                            container = container.parentElement;
                        }
                        if (!container) container = targetDoc.body;
                        activeRange.selectNodeContents(container);
                        syncToNativeSelection();
                        updateOverlayUI();
                    }

                    function dismissOverlay() {
                        if (overlay) overlay.style.display = 'none';
                        if (magnifier) magnifier.style.display = 'none';
                        isSelecting = false;
                        activeRange = null;
                    }

                    function showMagnifierBubble(x, y, node, offset) {
                        if (!magnifier || !node || node.nodeType !== Node.TEXT_NODE) return;
                        var txt = node.textContent || '';
                        var start = Math.max(0, offset - 6);
                        var end = Math.min(txt.length, offset + 6);
                        var before = txt.substring(start, offset);
                        var targetChar = txt.charAt(offset) || ' ';
                        var after = txt.substring(offset + 1, end);

                        magnifier.innerHTML = 
                            '<span style="color:#aaa;">' + escapeText(before) + '</span>' +
                            '<span style="background:#1a73e8;color:#fff;padding:1px 4px;border-radius:3px;font-weight:bold;margin:0 1px;">' + escapeText(targetChar) + '</span>' +
                            '<span style="color:#aaa;">' + escapeText(after) + '</span>';
                        magnifier.style.left = x + 'px';
                        magnifier.style.top = (y - 30) + 'px';
                        magnifier.style.display = 'block';
                    }

                    function escapeText(s) {
                        return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
                    }

                    function bindHandleDrag(handleEl, type) {
                        var dragging = false;

                        handleEl.addEventListener('touchstart', function(e) {
                            dragging = true;
                            e.preventDefault();
                            e.stopPropagation();
                        }, { passive: false });

                        handleEl.addEventListener('touchmove', function(e) {
                            if (!dragging || !activeRange) return;
                            e.preventDefault();
                            e.stopPropagation();

                            var t = e.touches[0];
                            var caret = getCaretAtPoint(t.clientX, t.clientY);
                            if (caret && caret.node) {
                                try {
                                    if (type === 'start') {
                                        activeRange.setStart(caret.node, caret.offset);
                                    } else {
                                        activeRange.setEnd(caret.node, caret.offset);
                                    }
                                    syncToNativeSelection();
                                    updateOverlayUI();
                                    showMagnifierBubble(t.clientX, t.clientY, caret.node, caret.offset);
                                } catch (err) {}
                            }
                        }, { passive: false });

                        var endDrag = function(e) {
                            if (dragging) {
                                dragging = false;
                                if (magnifier) magnifier.style.display = 'none';
                                e.preventDefault();
                                e.stopPropagation();
                            }
                        };
                        handleEl.addEventListener('touchend', endDrag);
                        handleEl.addEventListener('touchcancel', endDrag);
                    }

                    // 监听长按触发
                    targetDoc.addEventListener('touchstart', function(e) {
                        if (e.touches.length !== 1) {
                            clearTimeout(longPressTimer);
                            return;
                        }
                        var t = e.touches[0];
                        touchStartX = t.clientX;
                        touchStartY = t.clientY;

                        // 如果点击的不是手柄或工具栏，且已经开启了选择，点击外部取消
                        if (isSelecting && overlay && overlay.style.display !== 'none') {
                            var el = targetDoc.elementFromPoint(t.clientX, t.clientY);
                            if (el && !el.closest('#__ag_precision_overlay')) {
                                dismissOverlay();
                            }
                        }

                        clearTimeout(longPressTimer);
                        longPressTimer = setTimeout(function() {
                            var caret = getCaretAtPoint(touchStartX, touchStartY);
                            if (caret && caret.node) {
                                var range = getInitialWordRange(caret.node, caret.offset);
                                if (range) {
                                    createOverlay();
                                    activeRange = range;
                                    isSelecting = true;
                                    syncToNativeSelection();
                                    updateOverlayUI();
                                }
                            }
                        }, 380);
                    }, { passive: true });

                    targetDoc.addEventListener('touchmove', function(e) {
                        if (longPressTimer) {
                            var t = e.touches[0];
                            if (Math.abs(t.clientX - touchStartX) > 8 || Math.abs(t.clientY - touchStartY) > 8) {
                                clearTimeout(longPressTimer);
                                longPressTimer = null;
                            }
                        }
                    }, { passive: true });

                    targetDoc.addEventListener('touchend', function() {
                        clearTimeout(longPressTimer);
                    }, { passive: true });

                    targetDoc.addEventListener('touchcancel', function() {
                        clearTimeout(longPressTimer);
                    }, { passive: true });

                    window.addEventListener('scroll', function() {
                        if (isSelecting && activeRange) {
                            updateOverlayUI();
                        }
                    }, { passive: true });
                }

                // 首次注入顶层文档与当前子框架
                injectStylesAndFixes(document);
                initPrecisionSelector(document);

                // 3. 断线重连看门狗：监测 "Lost connection to the remote instance" 提示
                function findDisconnectBanner() {
                    var keywords = [
                        "Lost connection to the remote instance",
                        "Lost connection",
                        "refresh the page"
                    ];
                    var walker = document.createTreeWalker(
                        document.body || document.documentElement,
                        NodeFilter.SHOW_TEXT,
                        null,
                        false
                    );
                    var node;
                    while (node = walker.nextNode()) {
                        var val = node.nodeValue;
                        if (!val) continue;
                        for (var i = 0; i < keywords.length; i++) {
                            if (val.indexOf(keywords[i]) !== -1) {
                                var el = node.parentElement;
                                while (el && el !== document.body && el.offsetHeight < 220) {
                                    if (el.tagName === 'DIV' || el.tagName === 'SECTION' || el.getAttribute('role') === 'alert') {
                                        return el;
                                    }
                                    el = el.parentElement;
                                }
                                return node.parentElement;
                            }
                        }
                    }
                    return null;
                }

                window.__lastAutoReloadTime = 0;
                window.__autoReloadTimer = null;

                window.__checkAndHandleDisconnect = function() {
                    var banner = findDisconnectBanner();
                    if (banner) {
                        if (!banner.__ag_click_bound) {
                            banner.__ag_click_bound = true;
                            banner.style.cursor = 'pointer';
                            banner.style.transition = 'all 0.2s ease';

                            var tip = document.createElement('div');
                            tip.id = '__ag_reconnect_tip';
                            tip.style.cssText = 'font-size: 11px; color: #1a73e8; margin-top: 4px; font-weight: bold; text-align: center;';
                            tip.textContent = '⚡ 点击立即重连 (或稍后自动恢复)';
                            banner.appendChild(tip);

                            banner.addEventListener('click', function(e) {
                                e.preventDefault();
                                e.stopPropagation();
                                if (window.AndroidBridge && window.AndroidBridge.reloadPage) {
                                    window.AndroidBridge.reloadPage();
                                } else {
                                    window.location.reload();
                                }
                            });
                        }

                        var now = Date.now();
                        if (!window.__lastAutoReloadTime || (now - window.__lastAutoReloadTime > 8000)) {
                            if (!window.__autoReloadTimer) {
                                window.__autoReloadTimer = setTimeout(function() {
                                    window.__autoReloadTimer = null;
                                    window.__lastAutoReloadTime = Date.now();
                                    if (window.AndroidBridge && window.AndroidBridge.reloadPage) {
                                        window.AndroidBridge.reloadPage();
                                    } else {
                                        window.location.reload();
                                    }
                                }, 2200);
                            }
                        }
                    } else {
                        if (window.__autoReloadTimer) {
                            clearTimeout(window.__autoReloadTimer);
                            window.__autoReloadTimer = null;
                        }
                    }
                };

                setInterval(function() {
                    window.__checkAndHandleDisconnect();
                    injectStylesAndFixes(document);
                    initPrecisionSelector(document);
                }, 1500);

                var observer = new MutationObserver(function() {
                    window.__checkAndHandleDisconnect();
                    injectStylesAndFixes(document);
                    initPrecisionSelector(document);
                });
                if (document.body) {
                    observer.observe(document.body, { childList: true, subtree: true, characterData: true });
                }

                document.addEventListener('visibilitychange', function() {
                    if (document.visibilityState === 'visible') {
                        window.dispatchEvent(new Event('online'));
                        setTimeout(function() {
                            window.__checkAndHandleDisconnect();
                            injectStylesAndFixes(document);
                            initPrecisionSelector(document);
                        }, 300);
                    }
                });
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun loadTargetPage() {
        val targetUrl = if (storage.lastPageUrl.isNotBlank()) {
            storage.lastPageUrl
        } else {
            storage.serverUrl
        }

        if (targetUrl.isNotBlank()) {
            binding.webView.loadUrl(targetUrl)
        }
    }

    private fun startScan() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("将电脑屏幕上的 Antigravity 二维码对准取景框")
            setCameraId(0)
            setBeepEnabled(true)
            setOrientationLocked(true)
            setCaptureActivity(com.journeyapps.barcodescanner.CaptureActivity::class.java)
        }
        barcodeLauncher.launch(options)
    }

    private fun showSetupDialog() {
        val dialogBinding = DialogSetupBinding.inflate(layoutInflater)
        dialogBinding.etServerUrl.setText(storage.serverUrl)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnDialogScan.setOnClickListener {
            dialog.dismiss()
            startScan()
        }

        dialogBinding.btnDialogSave.setOnClickListener {
            val inputUrl = dialogBinding.etServerUrl.text?.toString()?.trim() ?: ""
            if (inputUrl.isNotBlank()) {
                dialog.dismiss()
                saveAndLoadUrl(inputUrl)
            } else {
                Toast.makeText(this, "请输入有效的地址", Toast.LENGTH_SHORT).show()
            }
        }

        dialogBinding.btnDialogReload.setOnClickListener {
            dialog.dismiss()
            binding.webView.reload()
            Toast.makeText(this, "正在刷新当前页面...", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun saveAndLoadUrl(rawUrl: String) {
        var formattedUrl = rawUrl
        if (!formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
            formattedUrl = "http://$formattedUrl"
        }

        storage.serverUrl = formattedUrl
        storage.lastPageUrl = formattedUrl
        showEmptyState(false)
        loadTargetPage()
        KeepAliveService.start(this)
        Toast.makeText(this, "已保存连接配置", Toast.LENGTH_SHORT).show()
    }

    private fun showEmptyState(show: Boolean) {
        binding.emptyStateLayout.visibility = if (show) View.VISIBLE else View.GONE
        binding.webView.visibility = if (show) View.GONE else View.VISIBLE
        // 关键改动：已连接状态下绝对隐藏浮动按钮，不挡任何网页输入与发送按钮
        binding.fabSettings.visibility = if (show) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        binding.webView.setNetworkAvailable(true)
        updateSystemBars()
        // 切回前台时，主动通知 WebView 唤醒并执行重连/刷新检查
        binding.webView.evaluateJavascript(
            """
            (function() {
                if (document.hidden !== undefined) {
                    document.dispatchEvent(new Event('visibilitychange'));
                }
                window.dispatchEvent(new Event('online'));
                if (typeof window.__checkAndHandleDisconnect === 'function') {
                    window.__checkAndHandleDisconnect();
                }
            })();
            """.trimIndent(),
            null
        )
    }

    override fun onPause() {
        super.onPause()
        binding.webView.onPause()
    }

    // 两指向下滑动唤出配置弹窗（避开系统三指截屏）
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) {
            if (ev.pointerCount == 2) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        twoFingerStartY = (ev.getY(0) + ev.getY(1)) / 2f
                        isTwoFingerSwipeActive = true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isTwoFingerSwipeActive && twoFingerStartY > 0f) {
                            val currentY = (ev.getY(0) + ev.getY(1)) / 2f
                            val deltaY = currentY - twoFingerStartY
                            // 向下滑动距离超过 180 像素，触发配置弹窗
                            if (deltaY > 180f) {
                                isTwoFingerSwipeActive = false
                                twoFingerStartY = 0f
                                showSetupDialog()
                            }
                        } else if (!isTwoFingerSwipeActive) {
                            twoFingerStartY = (ev.getY(0) + ev.getY(1)) / 2f
                            isTwoFingerSwipeActive = true
                        }
                    }
                }
            } else {
                isTwoFingerSwipeActive = false
                twoFingerStartY = 0f
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // 原生与 JS 互通桥梁
    inner class WebAppInterface {
        @JavascriptInterface
        fun postNotification(title: String, message: String) {
            notificationHelper.showMessageNotification(title, message)
        }

        @JavascriptInterface
        fun saveLastConversation(url: String) {
            storage.lastPageUrl = url
        }

        @JavascriptInterface
        fun copyToClipboard(text: String) {
            runOnUiThread {
                try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Copied Text", text)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@MainActivity, "已复制 ${text.length} 个字", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun reloadPage() {
            runOnUiThread {
                binding.progressBar.visibility = View.VISIBLE
                binding.webView.reload()
                Toast.makeText(this@MainActivity, "正在重新连接 Antigravity...", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
