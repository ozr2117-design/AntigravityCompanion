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

                // 1. 穿透样式：解锁所有内联文本、代码块，清除 Monaco 遮罩层拦截，并支持编辑态欺骗
                function getSelectionCSS() {
                    return 'html, body { ' +
                        '-webkit-text-size-adjust: 100% !important; ' +
                        'text-size-adjust: 100% !important; ' +
                        '-webkit-user-select: text !important; ' +
                        'user-select: text !important; ' +
                        '} ' +
                        '*, *::before, *::after { ' +
                        '-webkit-user-select: text !important; ' +
                        'user-select: text !important; ' +
                        '-webkit-touch-callout: default !important; ' +
                        '} ' +
                        'button, button *, [role="button"], [role="button"] *, a[role="button"], input, select, textarea, label[for], svg, img, video, canvas, [contenteditable="false"] { ' +
                        '-webkit-user-select: none !important; ' +
                        'user-select: none !important; ' +
                        '} ' +
                        '.monaco-editor .view-overlays { pointer-events: none !important; } ' +
                        'table td, table th { ' +
                        'white-space: normal !important; ' +
                        '-webkit-user-select: text !important; ' +
                        'user-select: text !important; ' +
                        'line-height: 1.5 !important; ' +
                        '} ' +
                        'pre, code, span, p, div { ' +
                        '-webkit-user-select: text !important; ' +
                        'user-select: text !important; ' +
                        '} ' +
                        '.__ag_decept_editable { ' +
                        '-webkit-user-modify: read-write-plaintext-only !important; ' +
                        '-webkit-user-select: text !important; ' +
                        'user-select: text !important; ' +
                        'outline: none !important; ' +
                        'caret-color: #1a73e8 !important; ' +
                        '} ' +
                        '.__ag_decept_editable * { ' +
                        '-webkit-user-modify: inherit !important; ' +
                        '-webkit-user-select: text !important; ' +
                        'user-select: text !important; ' +
                        '} ' +
                        '::selection { background: rgba(26, 115, 232, 0.35) !important; }';
                }

                // 2. 内核编辑态欺骗逐字精度引擎：突破 Chromium 移动端 kWord 分词与气泡拦截，实现逐字平滑点选
                function initKernelEditableDeception(targetDoc) {
                    if (!targetDoc || targetDoc.__ag_deception_injected) return;
                    targetDoc.__ag_deception_injected = true;

                    var activeDeceptEl = null;
                    var touchStartX = 0;
                    var touchStartY = 0;
                    var cleanupTimer = null;

                    function mountEditableDeception(el) {
                        if (!el || el === targetDoc.body || el === targetDoc.documentElement) return;
                        if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'BUTTON' || el.tagName === 'A') return;
                        if (el.isContentEditable && !el.classList.contains('__ag_decept_editable')) return;

                        if (activeDeceptEl && activeDeceptEl !== el) {
                            unmountEditableDeception(activeDeceptEl);
                        }

                        if (!el.classList.contains('__ag_decept_editable')) {
                            el.__ag_orig_contenteditable = el.getAttribute('contenteditable');
                            el.__ag_orig_inputmode = el.getAttribute('inputmode');
                            el.__ag_orig_spellcheck = el.getAttribute('spellcheck');

                            el.setAttribute('contenteditable', 'plaintext-only');
                            el.setAttribute('inputmode', 'none');
                            el.setAttribute('spellcheck', 'false');
                            el.classList.add('__ag_decept_editable');
                            activeDeceptEl = el;

                            if (window.AndroidBridge && window.AndroidBridge.hideKeyboard) {
                                window.AndroidBridge.hideKeyboard();
                            }
                        }
                    }

                    function unmountEditableDeception(el) {
                        if (!el) return;
                        try {
                            if (el.classList && el.classList.contains('__ag_decept_editable')) {
                                if (el.__ag_orig_contenteditable !== null && el.__ag_orig_contenteditable !== undefined) {
                                    el.setAttribute('contenteditable', el.__ag_orig_contenteditable);
                                } else {
                                    el.removeAttribute('contenteditable');
                                }

                                if (el.__ag_orig_inputmode !== null && el.__ag_orig_inputmode !== undefined) {
                                    el.setAttribute('inputmode', el.__ag_orig_inputmode);
                                } else {
                                    el.removeAttribute('inputmode');
                                }

                                if (el.__ag_orig_spellcheck !== null && el.__ag_orig_spellcheck !== undefined) {
                                    el.setAttribute('spellcheck', el.__ag_orig_spellcheck);
                                } else {
                                    el.removeAttribute('spellcheck');
                                }

                                el.classList.remove('__ag_decept_editable');
                                delete el.__ag_orig_contenteditable;
                                delete el.__ag_orig_inputmode;
                                delete el.__ag_orig_spellcheck;
                            }
                        } catch (e) {}
                        if (activeDeceptEl === el) {
                            activeDeceptEl = null;
                        }
                    }

                    // 1) 触摸开始：单指触摸文本区域时，瞬间激活编辑态欺骗，迫使 Chromium 切换到 kCharacter 逐字粒度
                    targetDoc.addEventListener('touchstart', function(e) {
                        if (e.touches && e.touches.length === 1) {
                            if (cleanupTimer) {
                                clearTimeout(cleanupTimer);
                                cleanupTimer = null;
                            }
                            var touch = e.touches[0];
                            touchStartX = touch.clientX;
                            touchStartY = touch.clientY;

                            var target = e.target;
                            if (!target) return;

                            // 过滤交互控件与编辑器核心区
                            if (target.closest('button, [role="button"], input, textarea, select, a[href], .monaco-editor, .view-lines, [role="tab"], [role="menuitem"]')) {
                                return;
                            }

                            // 寻找最近的文本容器（p, td, th, code, pre, span, div, li 等）
                            var candidate = target.closest('p, td, th, pre, code, blockquote, li, span, label, h1, h2, h3, h4, h5, h6') || (target !== targetDoc.body ? target : null);
                            if (candidate) {
                                mountEditableDeception(candidate);
                            }
                        }
                    }, { passive: true });

                    // 2) 滑动检测：如果手指移动超过 8px（说明用户在正常滚动网页，而非长按选取），立即还原
                    targetDoc.addEventListener('touchmove', function(e) {
                        if (activeDeceptEl && e.touches && e.touches.length === 1) {
                            var dx = Math.abs(e.touches[0].clientX - touchStartX);
                            var dy = Math.abs(e.touches[0].clientY - touchStartY);
                            if (dx > 8 || dy > 8) {
                                var sel = targetDoc.getSelection ? targetDoc.getSelection() : window.getSelection();
                                if (!sel || sel.isCollapsed) {
                                    unmountEditableDeception(activeDeceptEl);
                                }
                            }
                        }
                    }, { passive: true });

                    // 3) 触摸结束：若未形成选区，350ms 后静默恢复原始 DOM 属性
                    targetDoc.addEventListener('touchend', function() {
                        if (activeDeceptEl) {
                            if (cleanupTimer) clearTimeout(cleanupTimer);
                            cleanupTimer = setTimeout(function() {
                                var sel = targetDoc.getSelection ? targetDoc.getSelection() : window.getSelection();
                                if (!sel || sel.isCollapsed) {
                                    unmountEditableDeception(activeDeceptEl);
                                }
                            }, 350);
                        }
                    }, { passive: true });

                    // 4) 选区变化监听：当用户点击别处取消选择时，自动还原
                    targetDoc.addEventListener('selectionchange', function() {
                        var sel = targetDoc.getSelection ? targetDoc.getSelection() : window.getSelection();
                        if (!sel || sel.isCollapsed) {
                            if (activeDeceptEl) {
                                if (cleanupTimer) clearTimeout(cleanupTimer);
                                cleanupTimer = setTimeout(function() {
                                    var currentSel = targetDoc.getSelection ? targetDoc.getSelection() : window.getSelection();
                                    if (!currentSel || currentSel.isCollapsed) {
                                        unmountEditableDeception(activeDeceptEl);
                                    }
                                }, 200);
                            }
                        } else {
                            if (cleanupTimer) {
                                clearTimeout(cleanupTimer);
                                cleanupTimer = null;
                            }
                            if (window.AndroidBridge && window.AndroidBridge.hideKeyboard) {
                                window.AndroidBridge.hideKeyboard();
                            }
                        }
                    });

                    // 5) 严防死守键盘与文字篡改：拦截 beforeinput 与按键输入，确保 100% 只读安全
                    targetDoc.addEventListener('beforeinput', function(e) {
                        if (activeDeceptEl && (e.target === activeDeceptEl || activeDeceptEl.contains(e.target))) {
                            e.preventDefault();
                            e.stopPropagation();
                        }
                    }, true);

                    targetDoc.addEventListener('keydown', function(e) {
                        if (activeDeceptEl && (e.target === activeDeceptEl || activeDeceptEl.contains(e.target))) {
                            if ((e.ctrlKey || e.metaKey) && (e.key === 'c' || e.key === 'C')) {
                                return;
                            }
                            e.preventDefault();
                            e.stopPropagation();
                        }
                    }, true);

                    targetDoc.addEventListener('focusin', function(e) {
                        if (e.target && ((e.target.classList && e.target.classList.contains('__ag_decept_editable')) || e.target.getAttribute('inputmode') === 'none')) {
                            if (window.AndroidBridge && window.AndroidBridge.hideKeyboard) {
                                window.AndroidBridge.hideKeyboard();
                            }
                        }
                    }, true);

                    // 6) 复制文本清洗
                    targetDoc.addEventListener('copy', function(e) {
                        var sel = targetDoc.getSelection ? targetDoc.getSelection() : window.getSelection();
                        if (!sel || sel.isCollapsed) return;
                        var rawText = sel.toString();
                        var cleanText = rawText.replace(/[\u200B\u200C\u200D\uFEFF]/g, '');
                        if (e.clipboardData) {
                            e.clipboardData.setData('text/plain', cleanText);
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

                    // 启动内核编辑态欺骗逐字精度引擎
                    try {
                        initKernelEditableDeception(targetDoc);
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
