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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
    private var twoFingerStartSpan = 0f
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
            storage.lastPageUrl = ""
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
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT

            // 消除字体膨胀失真，与原生 Chrome 渲染一致
            layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
            textZoom = 100

            // 视口与缩放：支持双指缩放，消除 980px 强制缩小的比例漂移
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false

            minimumFontSize = 1
            minimumLogicalFontSize = 1

            userAgentString = settings.userAgentString + " AntigravityCompanion/1.3"
        }

        // 强制 100% 基础物理比例，消除触控消歧算法的坐标漂移
        webView.setInitialScale(100)

        // 启用网页内原生平滑滚动与惯性滑动
        webView.apply {
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS

            // 保持焦点支持，绝不拦截 View 层长按，让原生 Chromium 引擎与水滴光标正常唤出
            isFocusable = true
            isFocusableInTouchMode = true
            isHapticFeedbackEnabled = true
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

                // 1. 基础穿透与防干扰样式：彻底移除导致选区穿透的全局 user-select: text，禁用原生误吸附
                function getSelectionCSS() {
                    return 'html, body { ' +
                        '-webkit-text-size-adjust: 100% !important; ' +
                        'text-size-adjust: 100% !important; ' +
                        '} ' +
                        'body, div, p, span, td, th, pre, code, li, blockquote, h1, h2, h3, h4, h5, h6 { ' +
                        '-webkit-touch-callout: none !important; ' +
                        '} ' +
                        '.monaco-editor .view-overlays { pointer-events: none !important; } ' +
                        'table td, table th { ' +
                        'white-space: normal !important; ' +
                        'line-height: 1.5 !important; ' +
                        '} ' +
                        'input, textarea, [contenteditable="true"] { ' +
                        '-webkit-user-select: text !important; ' +
                        'user-select: text !important; ' +
                        '}';
                }

                // 2. 自定义高精度逐字触摸选择引擎（微信读书/Kindle 级实战架构，100% 单字单符平滑拖拽，严格气泡隔离）
                function initCustomPrecisionSelection(targetDoc) {
                    if (!targetDoc || targetDoc.__ag_custom_sel_injected) return;
                    targetDoc.__ag_custom_sel_injected = true;

                    var isSelecting = false;
                    var currentRange = null;
                    var activeContainer = null;
                    var activeHandle = null;
                    var longPressTimer = null;
                    var touchStartX = 0;
                    var touchStartY = 0;

                    // 创建高精度覆盖层
                    var overlay = targetDoc.createElement('div');
                    overlay.id = '__ag_selection_overlay';
                    overlay.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;pointer-events:none;z-index:999999;';
                    (targetDoc.body || targetDoc.documentElement).appendChild(overlay);

                    var highlightBox = targetDoc.createElement('div');
                    highlightBox.style.cssText = 'position:absolute;top:0;left:0;width:100%;height:100%;pointer-events:none;';
                    overlay.appendChild(highlightBox);

                    function createHandle(type) {
                        var h = targetDoc.createElement('div');
                        h.dataset.type = type;
                        h.style.cssText = 'position:fixed;width:44px;height:44px;margin-left:-22px;display:none;' +
                            'pointer-events:auto;z-index:1000000;touch-action:none;user-select:none;';

                        var visual = targetDoc.createElement('div');
                        visual.style.cssText = 'position:absolute;width:18px;height:18px;background:#1a73e8;' +
                            'box-shadow:0 2px 6px rgba(0,0,0,0.4);pointer-events:none;';

                        if (type === 'start') {
                            visual.style.cssText += 'border-radius:18px 0px 18px 18px;top:0;right:0;';
                        } else {
                            visual.style.cssText += 'border-radius:0px 18px 18px 18px;top:0;left:0;';
                        }
                        h.appendChild(visual);

                        h.addEventListener('touchstart', function(e) {
                            e.preventDefault();
                            e.stopPropagation();
                            activeHandle = type;
                            if (window.AndroidBridge && window.AndroidBridge.vibrate) {
                                window.AndroidBridge.vibrate(15);
                            }
                        }, { passive: false });

                        return h;
                    }

                    var startHandle = createHandle('start');
                    var endHandle = createHandle('end');
                    overlay.appendChild(startHandle);
                    overlay.appendChild(endHandle);

                    // 浮动操作栏 [ 复制 ] [ 全选此段 ]
                    var menuBar = targetDoc.createElement('div');
                    menuBar.id = '__ag_selection_menu';
                    menuBar.style.cssText = 'position:fixed;display:none;background:#202124;color:#ffffff;border-radius:24px;' +
                        'padding:6px 14px;font-size:13px;font-weight:600;box-shadow:0 4px 16px rgba(0,0,0,0.35);' +
                        'pointer-events:auto;z-index:1000000;cursor:pointer;user-select:none;-webkit-user-select:none;' +
                        'align-items:center;gap:12px;transform:translateX(-50%);';

                    var copyBtn = targetDoc.createElement('span');
                    copyBtn.textContent = '复制';
                    copyBtn.style.cssText = 'padding:2px 6px;cursor:pointer;';
                    copyBtn.addEventListener('click', function(e) {
                        e.preventDefault();
                        e.stopPropagation();
                        if (currentRange) {
                            var text = currentRange.toString();
                            if (window.AndroidBridge && window.AndroidBridge.copyToClipboard) {
                                window.AndroidBridge.copyToClipboard(text);
                            }
                            if (window.AndroidBridge && window.AndroidBridge.vibrate) {
                                window.AndroidBridge.vibrate(25);
                            }
                        }
                        clearSelection();
                    });

                    var selectAllBtn = targetDoc.createElement('span');
                    selectAllBtn.textContent = '全选此段';
                    selectAllBtn.style.cssText = 'padding:2px 6px;cursor:pointer;opacity:0.85;border-left:1px solid #444;padding-left:10px;';
                    selectAllBtn.addEventListener('click', function(e) {
                        e.preventDefault();
                        e.stopPropagation();
                        if (activeContainer) {
                            var r = targetDoc.createRange();
                            r.selectNodeContents(activeContainer);
                            currentRange = r;
                            renderSelection();
                            if (window.AndroidBridge && window.AndroidBridge.vibrate) {
                                window.AndroidBridge.vibrate(20);
                            }
                        }
                    });

                    menuBar.appendChild(copyBtn);
                    menuBar.appendChild(selectAllBtn);
                    overlay.appendChild(menuBar);

                    // 气泡级严格边界隔离，杜绝穿透到下一个消息气泡或输入框
                    function findBoundingContainer(el) {
                        var curr = el;
                        while (curr && curr !== targetDoc.body && curr !== targetDoc.documentElement) {
                            var tag = curr.tagName.toLowerCase();
                            if (tag === 'td' || tag === 'th') return curr;
                            if (curr.classList && (
                                curr.classList.contains('interactive-item-container') ||
                                curr.classList.contains('chat-rendered-markdown') ||
                                curr.classList.contains('rendered-markdown') ||
                                curr.classList.contains('chat-response-content') ||
                                curr.classList.contains('message-content')
                            )) {
                                return curr;
                            }
                            if (curr.classList && (
                                curr.classList.contains('interactive-session') ||
                                curr.classList.contains('chat-input-widget') ||
                                curr.classList.contains('monaco-list')
                            )) {
                                break;
                            }
                            curr = curr.parentElement;
                        }
                        return el.closest('.rendered-markdown, .interactive-item-container, article, p, td, th, pre, code, div') || el;
                    }

                    function clearSelection() {
                        isSelecting = false;
                        currentRange = null;
                        activeContainer = null;
                        activeHandle = null;
                        highlightBox.innerHTML = '';
                        startHandle.style.display = 'none';
                        endHandle.style.display = 'none';
                        menuBar.style.display = 'none';
                    }

                    function renderSelection() {
                        if (!currentRange || currentRange.collapsed) {
                            clearSelection();
                            return;
                        }

                        // 清除浏览器原生选区干扰
                        var sel = targetDoc.getSelection ? targetDoc.getSelection() : window.getSelection();
                        if (sel) {
                            try { sel.removeAllRanges(); } catch (e) {}
                        }

                        highlightBox.innerHTML = '';
                        var rects = currentRange.getClientRects();
                        if (!rects || rects.length === 0) {
                            clearSelection();
                            return;
                        }

                        for (var i = 0; i < rects.length; i++) {
                            var r = rects[i];
                            if (r.width <= 0 || r.height <= 0) continue;
                            var box = targetDoc.createElement('div');
                            box.style.cssText = 'position:fixed;left:' + r.left + 'px;top:' + r.top + 'px;' +
                                'width:' + r.width + 'px;height:' + r.height + 'px;' +
                                'background:rgba(26,115,232,0.28);border-radius:2px;pointer-events:none;';
                            highlightBox.appendChild(box);
                        }

                        var firstRect = rects[0];
                        var lastRect = rects[rects.length - 1];

                        startHandle.style.display = 'block';
                        startHandle.style.left = firstRect.left + 'px';
                        startHandle.style.top = firstRect.bottom + 'px';

                        endHandle.style.display = 'block';
                        endHandle.style.left = lastRect.right + 'px';
                        endHandle.style.top = lastRect.bottom + 'px';

                        menuBar.style.display = 'flex';
                        var menuTop = firstRect.top - 46;
                        if (menuTop < 10) {
                            menuTop = lastRect.bottom + 18;
                        }
                        var menuLeft = (firstRect.left + lastRect.right) / 2;
                        menuLeft = Math.max(70, Math.min(window.innerWidth - 70, menuLeft));
                        menuBar.style.top = menuTop + 'px';
                        menuBar.style.left = menuLeft + 'px';

                        isSelecting = true;
                    }

                    // 1) 触摸监听
                    targetDoc.addEventListener('touchstart', function(e) {
                        if (e.touches.length !== 1) {
                            if (longPressTimer) {
                                clearTimeout(longPressTimer);
                                longPressTimer = null;
                            }
                            return;
                        }

                        var touch = e.touches[0];
                        touchStartX = touch.clientX;
                        touchStartY = touch.clientY;

                        if (activeHandle || e.target.closest('#__ag_selection_overlay') || e.target.closest('#__ag_selection_menu')) {
                            return;
                        }

                        if (isSelecting) {
                            clearSelection();
                        }

                        var target = e.target;
                        if (!target) return;

                        if (target.closest('button, [role="button"], input, textarea, select, a[href], [role="tab"], [role="menuitem"]')) {
                            return;
                        }

                        longPressTimer = setTimeout(function() {
                            longPressTimer = null;
                            var caret = null;
                            if (targetDoc.caretRangeFromPoint) {
                                caret = targetDoc.caretRangeFromPoint(touchStartX, touchStartY);
                            }
                            if (!caret || !caret.startContainer) return;

                            var node = caret.startContainer;
                            var offset = caret.startOffset;

                            if (node.nodeType === Node.ELEMENT_NODE) {
                                var walker = targetDoc.createTreeWalker(node, NodeFilter.SHOW_TEXT, null, false);
                                var firstText = walker.nextNode();
                                if (firstText) {
                                    node = firstText;
                                    offset = 0;
                                } else {
                                    return;
                                }
                            }

                            if (node.nodeType !== Node.TEXT_NODE) return;

                            var text = node.textContent || '';
                            if (text.length === 0) return;

                            activeContainer = findBoundingContainer(node.parentElement || node);

                            // 初始选定当前单字或临近 1-3 个字符，坚决不选一大段
                            var start = Math.max(0, offset);
                            var end = Math.min(text.length, offset + 1);

                            while (start > 0 && (offset - start < 3) && !/[\s,.;:!?()\[\]{}"'，。！？（）\n\r、]/.test(text[start - 1])) {
                                start--;
                            }
                            while (end < text.length && (end - offset < 3) && !/[\s,.;:!?()\[\]{}"'，。！？（）\n\r、]/.test(text[end])) {
                                end++;
                            }

                            var range = targetDoc.createRange();
                            range.setStart(node, start);
                            range.setEnd(node, end);
                            currentRange = range;

                            renderSelection();

                            if (window.AndroidBridge && window.AndroidBridge.vibrate) {
                                window.AndroidBridge.vibrate(35);
                            }
                        }, 340);
                    }, { passive: true });

                    // 2) 滑动与平滑拖动
                    targetDoc.addEventListener('touchmove', function(e) {
                        if (longPressTimer && e.touches.length === 1) {
                            var dx = Math.abs(e.touches[0].clientX - touchStartX);
                            var dy = Math.abs(e.touches[0].clientY - touchStartY);
                            if (dx > 7 || dy > 7) {
                                clearTimeout(longPressTimer);
                                longPressTimer = null;
                            }
                        }

                        if (isSelecting && activeHandle && e.touches.length === 1) {
                            e.preventDefault();
                            e.stopPropagation();

                            var touch = e.touches[0];
                            var aimX = touch.clientX;
                            var aimY = touch.clientY - 16;

                            var caret = null;
                            if (targetDoc.caretRangeFromPoint) {
                                caret = targetDoc.caretRangeFromPoint(aimX, aimY);
                            }
                            if (!caret || !caret.startContainer) return;

                            var targetNode = caret.startContainer;
                            var targetOffset = caret.startOffset;

                            if (activeContainer && !activeContainer.contains(targetNode)) {
                                return;
                            }

                            try {
                                var testRange = currentRange.cloneRange();
                                if (activeHandle === 'start') {
                                    testRange.setStart(targetNode, targetOffset);
                                    if (testRange.compareBoundaryPoints(Range.START_TO_END, currentRange) <= 0) {
                                        currentRange.setStart(targetNode, targetOffset);
                                    } else {
                                        var oldEndNode = currentRange.endContainer;
                                        var oldEndOffset = currentRange.endOffset;
                                        currentRange.setStart(oldEndNode, oldEndOffset);
                                        currentRange.setEnd(targetNode, targetOffset);
                                        activeHandle = 'end';
                                    }
                                } else if (activeHandle === 'end') {
                                    testRange.setEnd(targetNode, targetOffset);
                                    if (testRange.compareBoundaryPoints(Range.END_TO_START, currentRange) >= 0) {
                                        currentRange.setEnd(targetNode, targetOffset);
                                    } else {
                                        var oldStartNode = currentRange.startContainer;
                                        var oldStartOffset = currentRange.startOffset;
                                        currentRange.setEnd(oldStartNode, oldStartOffset);
                                        currentRange.setStart(targetNode, targetOffset);
                                        activeHandle = 'start';
                                    }
                                }
                                renderSelection();
                            } catch (err) {}
                        }
                    }, { passive: false });

                    targetDoc.addEventListener('touchend', function() {
                        if (longPressTimer) {
                            clearTimeout(longPressTimer);
                            longPressTimer = null;
                        }
                        if (activeHandle) {
                            activeHandle = null;
                            if (window.AndroidBridge && window.AndroidBridge.vibrate) {
                                window.AndroidBridge.vibrate(10);
                            }
                        }
                    }, { passive: true });

                    window.addEventListener('scroll', function() {
                        if (isSelecting && currentRange) {
                            renderSelection();
                        }
                    }, { passive: true });

                    targetDoc.addEventListener('contextmenu', function(e) {
                        if (isSelecting) {
                            e.preventDefault();
                        }
                    });
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
                        if (viewport) {
                            viewport.content = 'width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes';
                        }
                    } catch (e) {}

                    // 启动高精度逐字触摸选择引擎
                    try {
                        initCustomPrecisionSelection(targetDoc);
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
                                }
                            } catch (err) {}
                        }
                    } catch (e) {}
                }

                // 首次注入顶层文档
                injectStylesAndFixes(document);

                // 动态 DOM 变更观察器
                try {
                    var docObserver = new MutationObserver(function() {
                        injectStylesAndFixes(document);
                    });
                    if (document.body) {
                        docObserver.observe(document.body, { childList: true, subtree: true });
                    }
                } catch (e) {}

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
                    initSmartTextExtractor(document);
                }, 1500);

                var observer = new MutationObserver(function() {
                    window.__checkAndHandleDisconnect();
                    injectStylesAndFixes(document);
                    initSmartTextExtractor(document);
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
                            initSmartTextExtractor(document);
                        }, 300);
                    }
                });
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun loadTargetPage() {
        // 始终加载主页地址（图2主页面），不再停留在历史对话详情页
        val targetUrl = storage.serverUrl
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

    // 两指向下滑动唤出配置弹窗（避开系统三指截屏冲突，同时不干扰双指捏合缩放）
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) {
            if (ev.pointerCount == 2) {
                val dx = ev.getX(0) - ev.getX(1)
                val dy = ev.getY(0) - ev.getY(1)
                val currentSpan = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

                when (ev.actionMasked) {
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        twoFingerStartY = (ev.getY(0) + ev.getY(1)) / 2f
                        twoFingerStartSpan = currentSpan
                        isTwoFingerSwipeActive = true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isTwoFingerSwipeActive) {
                            // 如果双指间距发生明显变化（超过 40px），判定为捏合缩放手势，取消下滑判定
                            if (Math.abs(currentSpan - twoFingerStartSpan) > 40f) {
                                isTwoFingerSwipeActive = false
                            } else if (twoFingerStartY > 0f) {
                                val currentY = (ev.getY(0) + ev.getY(1)) / 2f
                                val deltaY = currentY - twoFingerStartY
                                // 双指向下滑动超过 200 像素，呼出设置对话框
                                if (deltaY > 200f) {
                                    isTwoFingerSwipeActive = false
                                    twoFingerStartY = 0f
                                    showSetupDialog()
                                }
                            }
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
                    val cleanText = text.replace(Regex("[\\u200B\\u200C\\u200D\\uFEFF]"), "")
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Copied Text", cleanText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@MainActivity, "已复制", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun vibrate(duration: Long) {
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vibratorManager?.defaultVibrator ?: @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(duration)
                }
            } catch (e: Exception) {}
        }

        @JavascriptInterface
        fun hideKeyboard() {
            runOnUiThread {
                try {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.hideSoftInputFromWindow(binding.webView.windowToken, 0)
                } catch (e: Exception) {}
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
