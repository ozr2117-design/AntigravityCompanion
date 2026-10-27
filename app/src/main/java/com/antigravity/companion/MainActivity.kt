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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.antigravity.companion.databinding.ActivityMainBinding
import com.antigravity.companion.databinding.DialogSetupBinding
import com.antigravity.companion.databinding.DialogTextSelectBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: StorageManager
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var gestureDetector: GestureDetector

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
        initGestureDetector()
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

            // 保持焦点支持
            isFocusable = true
            isFocusableInTouchMode = true
            isHapticFeedbackEnabled = true

            WebView.setWebContentsDebuggingEnabled(true)

            // 由原生 GestureDetector 全权接管长按，彻底杜绝 View 层的事件冲突与 ACTION_CANCEL
            isLongClickable = false
            setOnLongClickListener(null)

            setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                false // 保持 false，保证网页正常平滑滚动与点击绝不受阻
            }
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
                // 记录最近一次触控屏幕坐标（视口 CSS 像素）
                window.__lastTouchX = 0;
                window.__lastTouchY = 0;

                // 全局高精度文本探测器（支持 iframe 穿透、多点放射探测与 Tailwind 语义匹配）
                window.__findBestTextAtPoint = function(x, y) {
                    var localX = (x !== undefined && x !== null && x > 0) ? x : (window.__lastTouchX || 0);
                    var localY = (y !== undefined && y !== null && y > 0) ? y : (window.__lastTouchY || 0);

                    function clearSel(doc) {
                        try {
                            var sel = (doc.defaultView || window).getSelection();
                            if (sel) sel.removeAllRanges();
                        } catch(e) {}
                    }

                    function findContainer(el) {
                        if (!el) return null;
                        if (el.closest('input, textarea, select, [contenteditable="true"], .chat-input-widget, button, [role="button"]')) {
                            return null;
                        }

                        // 优先锁定 Antigravity Web 的消息外壳与 Markdown 排版容器
                        var msg = el.closest(
                            '.select-text, [class*="select-text"], ' +
                            '.scroll-mt-4, [class*="scroll-mt"], ' +
                            'div[class*="code-block"], ' +
                            'div.flex.items-start, div.flex.items-end, ' +
                            'div[class*="bg-card"], div.whitespace-pre-wrap, ' +
                            'article, section, pre, blockquote, table, p, li'
                        );
                        if (msg) return msg;

                        // 逐级向上溯源包含有效文本的最近非全屏块容器
                        var cur = el;
                        while (cur && cur !== document.body && cur !== document.documentElement) {
                            var text = (cur.innerText || cur.textContent || '').trim();
                            if (text.length > 0 && text.length < 50000) {
                                var parent = cur.parentElement;
                                if (parent && parent !== document.body && (parent.innerText || '').trim() === text) {
                                    cur = parent;
                                    continue;
                                }
                                return cur;
                            }
                            cur = cur.parentElement;
                        }
                        return el;
                    }

                    function probeDoc(doc, probeX, probeY) {
                        if (!doc) return "";
                        clearSel(doc);

                        // 放射性多点探测（半径 40px 内 13 个探测点，彻底杜绝内边距/行间距落空）
                        var probes = [
                            [0, 0], [0, -15], [0, 15], [-15, 0], [15, 0],
                            [-25, -25], [25, 25], [-25, 25], [25, -25],
                            [0, -40], [0, 40], [-40, 0], [40, 0]
                        ];
                        for (var p = 0; p < probes.length; p++) {
                            var px = probeX + probes[p][0];
                            var py = probeY + probes[p][1];
                            var c = doc.elementFromPoint(px, py);
                            if (c) {
                                if (c.tagName === 'IFRAME') {
                                    try {
                                        var iframeDoc = c.contentDocument || (c.contentWindow && c.contentWindow.document);
                                        if (iframeDoc) {
                                            var r = c.getBoundingClientRect();
                                            var sub = probeDoc(iframeDoc, px - r.left, py - r.top);
                                            if (sub) return sub;
                                        }
                                    } catch (e) {}
                                }
                                var container = findContainer(c);
                                if (container) {
                                    var t = (container.innerText || container.textContent || '').trim();
                                    if (t.length > 0) return t;
                                }
                            }
                        }
                        return "";
                    }

                    var result = probeDoc(document, localX, localY);
                    if (!result) {
                        // 全局兜底：如果放射探测未命中具体文本，锁定视口内距离触摸 Y 坐标最近的消息气泡
                        try {
                            var allBubbles = document.querySelectorAll('.select-text, [class*="select-text"], [class*="scroll-mt"], div.whitespace-pre-wrap');
                            var best = null;
                            var bestDist = Infinity;
                            for (var i = 0; i < allBubbles.length; i++) {
                                var rect = allBubbles[i].getBoundingClientRect();
                                var dist = Math.abs((rect.top + rect.height / 2) - localY);
                                if (dist < bestDist) {
                                    bestDist = dist;
                                    best = allBubbles[i];
                                }
                            }
                            if (best) {
                                result = (best.innerText || best.textContent || '').trim();
                            }
                        } catch(e) {}
                    }
                    clearSel(document);
                    return result || "";
                };

                // 保持兼容旧方法别名
                window.__extractTextAtPoint = window.__findBestTextAtPoint;

                // 2. 原生纯文本提取器：长按消息气泡唤出 Android 原生 BottomSheet 逐字自由选词
                function initSmartTextExtractor(targetDoc) {
                    if (!targetDoc || targetDoc.__ag_extractor_injected) return;
                    targetDoc.__ag_extractor_injected = true;

                    function callBridge(text) {
                        if (!text || text.length === 0) return;
                        var bridge = window.AndroidBridge || (window.top && window.top.AndroidBridge);
                        if (bridge) {
                            if (bridge.vibrate) bridge.vibrate(22);
                            if (bridge.openTextExtractionSheet) bridge.openTextExtractionSheet(text);
                        }
                    }

                    // 核心防御：拦截 Chromium 移动端长按上下文菜单事件，就地扼杀 Chrome 原生选区抢跑
                    targetDoc.addEventListener('contextmenu', function(e) {
                        var el = e.target;
                        if (!el) return;
                        if (el.closest('input, textarea, select, [contenteditable="true"]')) {
                            return;
                        }
                        e.preventDefault();
                        e.stopPropagation();

                        try {
                            var sel = (targetDoc.defaultView || window).getSelection();
                            if (sel) sel.removeAllRanges();
                        } catch(err) {}

                        var text = window.__findBestTextAtPoint(e.clientX, e.clientY);
                        callBridge(text);
                        return false;
                    }, true);

                    var pressTimer = null;
                    var startX = 0;
                    var startY = 0;
                    var isMoved = false;

                    targetDoc.addEventListener('touchstart', function(e) {
                        if (!e.touches || e.touches.length !== 1) {
                            if (pressTimer) { clearTimeout(pressTimer); pressTimer = null; }
                            return;
                        }
                        var touch = e.touches[0];
                        startX = touch.clientX;
                        startY = touch.clientY;
                        window.__lastTouchX = startX;
                        window.__lastTouchY = startY;
                        isMoved = false;

                        if (pressTimer) clearTimeout(pressTimer);
                        pressTimer = setTimeout(function() {
                            if (isMoved) return;
                            var el = targetDoc.elementFromPoint(startX, startY);
                            if (!el) return;

                            // 避开输入框与交互按钮
                            if (el.closest('input, textarea, select, [contenteditable="true"], .chat-input-widget, button, [role="button"]')) {
                                return;
                            }

                            var text = window.__findBestTextAtPoint(startX, startY);
                            if (text && text.length > 0) {
                                try {
                                    var sel = (targetDoc.defaultView || window).getSelection();
                                    if (sel) sel.removeAllRanges();
                                } catch(err) {}
                                callBridge(text);
                            }
                        }, 360);
                    }, { passive: true });

                    targetDoc.addEventListener('touchmove', function(e) {
                        if (!e.touches || e.touches.length !== 1) {
                            if (pressTimer) { clearTimeout(pressTimer); pressTimer = null; }
                            return;
                        }
                        var touch = e.touches[0];
                        window.__lastTouchX = touch.clientX;
                        window.__lastTouchY = touch.clientY;
                        var dx = touch.clientX - startX;
                        var dy = touch.clientY - startY;
                        if (Math.hypot(dx, dy) > 16) {
                            isMoved = true;
                            if (pressTimer) {
                                clearTimeout(pressTimer);
                                pressTimer = null;
                            }
                        }
                    }, { passive: true });

                    targetDoc.addEventListener('touchend', function() {
                        if (pressTimer) {
                            clearTimeout(pressTimer);
                            pressTimer = null;
                        }
                    }, { passive: true });

                    targetDoc.addEventListener('touchcancel', function() {
                        if (pressTimer) {
                            clearTimeout(pressTimer);
                            pressTimer = null;
                        }
                    }, { passive: true });
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
                        initSmartTextExtractor(targetDoc);
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

    private var lastSheetOpenTime = 0L

    private fun initGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                // 1. 原生立即震动反馈（触感响应）
                vibratePhone(22)

                // 2. 真实屏幕密度换算（物理像素 -> 视口 CSS 像素）
                val density = resources.displayMetrics.density.coerceAtLeast(1f)
                val cssX = (e.x / density).toInt()
                val cssY = (e.y / density).toInt()

                // 3. 提取命中区域文本并弹出 BottomSheet（绝不 dispatch ACTION_CANCEL，防触控管道截断）
                binding.webView.evaluateJavascript(
                    """
                    (function() {
                        if (typeof window.__findBestTextAtPoint === 'function') {
                            return window.__findBestTextAtPoint($cssX, $cssY);
                        }
                        return "";
                    })();
                    """.trimIndent()
                ) { rawResult ->
                    val text = try {
                        if (rawResult != null && rawResult != "null" && rawResult != "\"\"") {
                            org.json.JSONTokener(rawResult).nextValue().toString()
                        } else ""
                    } catch (ex: Exception) {
                        rawResult?.trim('"')?.replace("\\n", "\n") ?: ""
                    }
                    if (text.isNotBlank()) {
                        showTextExtractionSheet(text)
                    } else {
                        Toast.makeText(this@MainActivity, "长按消息内容即可选词复制", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun vibratePhone(duration: Long) {
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

    private fun showTextExtractionSheet(rawText: String) {
        val now = System.currentTimeMillis()
        if (now - lastSheetOpenTime < 600L) {
            return // 600ms 防抖，杜绝双通道同时触发
        }
        val cleanText = rawText.replace(Regex("[\\u200B\\u200C\\u200D\\uFEFF]"), "").trim()
        if (cleanText.isEmpty()) return
        lastSheetOpenTime = now

        val dialog = BottomSheetDialog(this)
        val sheetBinding = DialogTextSelectBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        // 配置 BottomSheet 尺寸与展开状态
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true

                val maxHeight = (resources.displayMetrics.heightPixels * 0.85).toInt()
                sheet.layoutParams.height = maxHeight

                (sheet as? ViewGroup)?.apply {
                    clipChildren = false
                    clipToPadding = false
                }
            }

            dialog.window?.let { win ->
                (win.decorView as? ViewGroup)?.apply {
                    clipChildren = false
                    clipToPadding = false
                }
            }
        }

        // 字符数统计
        sheetBinding.tvCharCount.text = "${cleanText.length} 字"

        // 设置文本与原生高精度选区
        sheetBinding.tvSelectableContent.text = cleanText
        sheetBinding.tvSelectableContent.setTextIsSelectable(true)

        // 优化触摸与选区拖拽：滑动微调水滴光标时，阻止父级拦截
        sheetBinding.tvSelectableContent.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        // 复制已选文字按钮
        sheetBinding.btnCopySelected.setOnClickListener {
            val tv = sheetBinding.tvSelectableContent
            val start = tv.selectionStart
            val end = tv.selectionEnd
            val textToCopy = if (start in 0 until end) {
                tv.text.subSequence(start, end).toString()
            } else {
                cleanText
            }
            copyStringToClipboard(textToCopy, if (start in 0 until end) "已选文字" else "全部文本")
            dialog.dismiss()
        }

        // 复制全文按钮
        sheetBinding.btnCopyAll.setOnClickListener {
            copyStringToClipboard(cleanText, "全部文本")
            dialog.dismiss()
        }

        // 关闭按钮
        sheetBinding.btnSheetClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun copyStringToClipboard(text: String, label: String = "文本") {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Copied Text", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "已复制 $label (${text.length}字)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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
        fun openTextExtractionSheet(text: String) {
            runOnUiThread {
                showTextExtractionSheet(text)
            }
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
