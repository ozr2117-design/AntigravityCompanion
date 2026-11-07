package com.antigravity.companion

import android.view.HapticFeedbackConstants

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
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ActionMode
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
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
    private var currentSelectedText: String = ""
    private var autoReconnectAttempts = 0
    private val reconnectHandler = Handler(Looper.getMainLooper())



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
        syncThemeWithSystem()
        checkPermissions()
        initViews()
        initWebView()

        Toast.makeText(this@MainActivity, "Antigravity 伴侣 v1.3.40 已就绪", Toast.LENGTH_SHORT).show()

        // 拦截物理返回键：若复制浮条可见则优先隐藏并清除选区，否则在网页内后退
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.btnFloatingCopy.visibility == View.VISIBLE || currentSelectedText.isNotEmpty()) {
                    currentSelectedText = ""
                    binding.btnFloatingCopy.visibility = View.GONE
                    binding.webView.evaluateJavascript(
                        "if (typeof window.hideSelectionCapsule === 'function') window.hideSelectionCapsule(); window.getSelection()?.removeAllRanges();",
                        null
                    )
                    return
                }
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
        syncThemeWithSystem()
    }

    private fun isNightMode(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun syncThemeWithSystem() {
        val isNight = isNightMode()
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(
                    binding.webView.settings,
                    if (isNight) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(binding.webView.settings, isNight)
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                WebSettingsCompat.setForceDarkStrategy(
                    binding.webView.settings,
                    WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        injectThemeScript(binding.webView, isNight)
    }

    private fun injectThemeScript(view: WebView?, isNight: Boolean) {
        val js = """
            (function() {
                var isNight = $isNight;
                var root = document.documentElement;
                var body = document.body;
                if (!root || !body) return;
                
                if (isNight) {
                    root.style.colorScheme = 'dark';
                    body.classList.remove('theme-light');
                    body.classList.add('theme-standalone', 'dark', 'dark-theme', 'theme-dark');
                    var vars = {
                        '--background': '#070709',
                        '--foreground': '#FFFFFF',
                        '--primary': '#2F71E3',
                        '--primary-foreground': '#FFFFFF',
                        '--shell': '#111114',
                        '--sidebar': '#111114',
                        '--sidebar-secondary': 'rgb(255 255 255 / 0.09)',
                        '--sidebar-muted': 'rgb(255 255 255 / 0.048)',
                        '--secondary': 'rgb(255 255 255 / 0.075)',
                        '--secondary-foreground': '#BCBDC6',
                        '--muted': 'rgb(255 255 255 / 0.042)',
                        '--muted-foreground': '#87888F',
                        '--placeholder': '#636470',
                        '--content': '#070709',
                        '--card': '#131317',
                        '--card-border': 'rgb(255 255 255 / 0.08)',
                        '--border': 'rgb(255 255 255 / 0.06)',
                        '--glass': '#131317',
                        '--glass-border': 'rgb(255 255 255 / 0.085)',
                        '--glass-highlight': 'rgb(255 255 255 / 0.085)',
                        '--onyx-frame': '#0D0D11',
                        '--onyx-well': '#111115',
                        '--onyx-raised': '#15151A',
                        '--onyx-selected': '#1F1F24',
                        '--onyx-key': '#24252A'
                    };
                    for (var k in vars) {
                        root.style.setProperty(k, vars[k]);
                    }
                    body.style.color = 'var(--foreground)';
                    body.style.backgroundColor = 'var(--background)';
                } else {
                    root.style.colorScheme = 'light';
                    body.classList.remove('dark', 'dark-theme', 'theme-dark');
                    body.classList.add('theme-standalone', 'theme-light');
                    var vars = {
                        '--background': '#FFFFFF',
                        '--foreground': '#0A0A0C',
                        '--primary': '#2F75C3',
                        '--primary-foreground': '#FFFFFF',
                        '--shell': '#FAFAFA',
                        '--sidebar': '#FAFAFA',
                        '--sidebar-secondary': 'rgb(10 10 12 / 0.075)',
                        '--sidebar-muted': 'rgb(10 10 12 / 0.042)',
                        '--secondary': 'rgb(10 10 12 / 0.06)',
                        '--secondary-foreground': '#3F3F46',
                        '--muted': 'rgb(10 10 12 / 0.038)',
                        '--muted-foreground': '#71717A',
                        '--placeholder': '#8E8E95',
                        '--content': '#FFFFFF',
                        '--card': '#FFFFFF',
                        '--card-border': 'rgb(10 10 12 / 0.07)',
                        '--border': 'rgb(10 10 12 / 0.06)',
                        '--glass': '#FFFFFF',
                        '--glass-border': 'rgb(10 10 12 / 0.075)',
                        '--glass-highlight': 'rgb(10 10 12 / 0.075)',
                        '--onyx-frame': '#F9F9F9',
                        '--onyx-well': 'rgb(10 10 12 / 0.055)',
                        '--onyx-raised': '#FFFFFF',
                        '--onyx-selected': '#FFFFFF',
                        '--onyx-key': '#FFFFFF'
                    };
                    for (var k in vars) {
                        root.style.setProperty(k, vars[k]);
                    }
                    body.style.color = 'var(--foreground)';
                    body.style.backgroundColor = 'var(--background)';
                }
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun updateSystemBars() {
        val isNightMode = isNightMode()
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
        binding.root.clipChildren = false
        binding.root.clipToPadding = false
        binding.btnFloatingCopy.apply {
            elevation = 24f * resources.displayMetrics.density
            translationZ = 24f * resources.displayMetrics.density
            bringToFront()
        }

        // 底部悬浮“复制已选”胶囊按钮
        binding.btnFloatingCopy.setOnClickListener {
            if (currentSelectedText.isNotBlank()) {
                copyStringToClipboard(currentSelectedText, "已选文本")
                vibratePhone(40)
                currentSelectedText = ""
                binding.btnFloatingCopy.visibility = View.GONE
                binding.webView.evaluateJavascript(
                    "if (typeof window.hideSelectionCapsule === 'function') window.hideSelectionCapsule(); window.getSelection()?.removeAllRanges();",
                    null
                )
            }
        }

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

            // 禁用破坏移动端触控映射的宽视口与缩放控件（网页自带标准移动端 viewport，保持 1:1 物理对齐）
            useWideViewPort = false
            loadWithOverviewMode = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            minimumFontSize = 1
            minimumLogicalFontSize = 1

            userAgentString = settings.userAgentString + " AntigravityCompanion/1.3"
        }

        syncThemeWithSystem()

        // 保持 0（默认 1:1 视口比例），消除超高分屏下的坐标缩放偏移
        webView.setInitialScale(0)

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

            // 彻底恢复原生长按选词与水滴拖拽机制，决不人为拦截触控事件
            isLongClickable = true
            setOnLongClickListener(null)
            setOnTouchListener(null)
        }

        // 注入原生 JS 接口桥梁
        webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.visibility = View.VISIBLE
                injectKeepAliveAndNotificationScript(view)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                injectKeepAliveAndNotificationScript(view)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                injectKeepAliveAndNotificationScript(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
                autoReconnectAttempts = 0
                reconnectHandler.removeCallbacksAndMessages(null)

                // 页面正常加载完成，确保设置按钮完全隐藏，决不遮挡发送按钮！
                showEmptyState(false)

                if (!hasShownConnectToast) {
                    hasShownConnectToast = true
                    Toast.makeText(this@MainActivity, "已连接！Antigravity 伴侣 v1.3.40", Toast.LENGTH_SHORT).show()
                }

                syncThemeWithSystem()

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
                    if (storage.isConfigured()) {
                        if (autoReconnectAttempts < 5) {
                            autoReconnectAttempts++
                            val delayMs = (2000L * autoReconnectAttempts).coerceAtMost(8000L)
                            Toast.makeText(
                                this@MainActivity,
                                "网络波动连接断开，${delayMs / 1000}秒后自动重试 (${autoReconnectAttempts}/5)...",
                                Toast.LENGTH_SHORT
                            ).show()
                            reconnectHandler.removeCallbacksAndMessages(null)
                            reconnectHandler.postDelayed({
                                if (storage.isConfigured() && !isFinishing) {
                                    binding.progressBar.visibility = View.VISIBLE
                                    binding.webView.reload()
                                }
                            }, delayMs)
                        } else {
                            // 超过 5 次重试仍不可达，切回配置界面方便重新扫码或修正 IP
                            showEmptyState(true)
                            Toast.makeText(this@MainActivity, "连接电脑失败，请检查网络或重新配置地址", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        showEmptyState(true)
                    }
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
                    injectKeepAliveAndNotificationScript(view)
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                injectKeepAliveAndNotificationScript(view)
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
                // 1. 设置网页语言为中文，唤醒 ICU 中文分词字典
                try {
                    document.documentElement.lang = 'zh-CN';
                    if (document.body) document.body.lang = 'zh-CN';
                } catch(e) {}

                // 2. 注入自然选词与复制样式（仅隐藏 Quote 引用气泡，绝不破坏 role=menu 与 Portal）
                function getSelectionCSS() {
                    return 'html, body { ' +
                        '-webkit-text-size-adjust: 100% !important; ' +
                        'text-size-adjust: 100% !important; ' +
                        '} ' +
                        '/* 屏蔽 Google 桌面端 Quote 气泡，避免遮挡移动端手柄 */ ' +
                        '.selection-popup, .selection-quote-popup, [data-testid*="selection-quote"], [data-testid="selection-quote-button"] { ' +
                        'display: none !important; ' +
                        'visibility: hidden !important; ' +
                        'pointer-events: none !important; ' +
                        '} ' +
                        '/* 恢复全文及输入框自然选词与光标拖拽能力 */ ' +
                        '.select-text, [class*="select-text"], div[role="article"], .prose, article, p, li { ' +
                        '-webkit-user-select: text !important; ' +
                        'user-select: text !important; ' +
                        '-webkit-touch-callout: default !important; ' +
                        'word-break: break-word !important; ' +
                        '} ' +
                        'input, textarea, [contenteditable="true"] { ' +
                        '-webkit-user-select: text !important; ' +
                        'user-select: text !important; ' +
                        '} ' +
                        '::selection { background: rgba(30, 142, 62, 0.28) !important; color: inherit !important; } ' +
                        '::-webkit-selection { background: rgba(30, 142, 62, 0.28) !important; color: inherit !important; } ' +
                        'button, svg, [role="button"], .__ag_msg_copy_btn { ' +
                        '-webkit-user-select: none !important; ' +
                        'user-select: none !important; ' +
                        '} ' +
                        'table td, table th { ' +
                        'white-space: normal !important; ' +
                        'line-height: 1.6 !important; ' +
                        '} ' +
                        '.__ag_msg_copy_btn { ' +
                        'position: absolute; right: 8px; top: 6px; z-index: 40; ' +
                        'font-size: 11px; color: #5f6368; background: rgba(245, 245, 247, 0.92); ' +
                        'border: 1px solid rgba(0, 0, 0, 0.12); border-radius: 6px; padding: 2px 7px; ' +
                        'cursor: pointer; display: inline-flex; align-items: center; gap: 3px; ' +
                        'backdrop-filter: blur(4px); box-shadow: 0 1px 2px rgba(0,0,0,0.06); ' +
                        'opacity: 0.85; transition: opacity 0.2s, background 0.2s; ' +
                        'user-select: none !important; -webkit-user-select: none !important; ' +
                        '} ' +
                        '.__ag_msg_copy_btn:active { background: #e8eaed; }';
                }

                function injectStyles(targetDoc) {
                    if (!targetDoc) return;
                    try {
                        var styleId = '__ag_precision_selection_style';
                        var styleEl = targetDoc.getElementById(styleId);
                        if (!styleEl) {
                            styleEl = targetDoc.createElement('style');
                            styleEl.id = styleId;
                            styleEl.type = 'text/css';
                            var nEl = targetDoc.querySelector('script[nonce], style[nonce]');
                            var nonce = nEl ? (nEl.nonce || nEl.getAttribute('nonce') || '') : '';
                            if (nonce) {
                                styleEl.nonce = nonce;
                                styleEl.setAttribute('nonce', nonce);
                            }
                            (targetDoc.head || targetDoc.documentElement).appendChild(styleEl);
                            styleEl.textContent = getSelectionCSS();
                        }
                        if (targetDoc.adoptedStyleSheets && window.CSSStyleSheet && !targetDoc.__ag_adopted_sheet) {
                            try {
                                targetDoc.__ag_adopted_sheet = new CSSStyleSheet();
                                targetDoc.__ag_adopted_sheet.replaceSync(getSelectionCSS());
                                targetDoc.adoptedStyleSheets = [...targetDoc.adoptedStyleSheets, targetDoc.__ag_adopted_sheet];
                            } catch(e) {}
                        }
                    } catch(e) {}
                }

                // 3. 选区状态监听：向原生桥梁同步当前选区（驱动底部悬浮复制按钮）
                var selTimer = null;
                function handleSelectionUpdate() {
                    try {
                        var sel = window.getSelection();
                        if (sel && !sel.isCollapsed && sel.rangeCount > 0) {
                            var txt = sel.toString().trim();
                            if (txt.length > 0) {
                                if (window.AndroidBridge && window.AndroidBridge.onSelectionChanged) {
                                    window.AndroidBridge.onSelectionChanged(txt);
                                }
                                return;
                            }
                        }
                        if (window.AndroidBridge && window.AndroidBridge.onSelectionCleared) {
                            window.AndroidBridge.onSelectionCleared();
                        }
                    } catch(e) {}
                }

                if (!window.__ag_selection_reporter_bound) {
                    window.__ag_selection_reporter_bound = true;
                    document.addEventListener('selectionchange', function() {
                        clearTimeout(selTimer);
                        selTimer = setTimeout(handleSelectionUpdate, 60);
                    }, true);

                    document.addEventListener('click', function(e) {
                        setTimeout(function() {
                            var sel = window.getSelection();
                            if (!sel || sel.isCollapsed || !sel.toString().trim()) {
                                if (window.AndroidBridge && window.AndroidBridge.onSelectionCleared) {
                                    window.AndroidBridge.onSelectionCleared();
                                }
                            }
                        }, 100);
                    }, true);
                }

                // 4. 消息“复制全文”按钮一键复制
                function injectMessageCopyButtons(targetDoc) {
                    if (!targetDoc) return;
                    try {
                        var messageRows = targetDoc.querySelectorAll('.scroll-mt-4, [class*="scroll-mt"], [data-testid*="step"], div[role="article"]');
                        for (var i = 0; i < messageRows.length; i++) {
                            var row = messageRows[i];
                            if (row.__ag_copy_btn_injected) continue;

                            var content = row.querySelector('.select-text, [class*="select-text"], div.whitespace-pre-wrap');
                            if (!content) continue;

                            row.__ag_copy_btn_injected = true;
                            if (getComputedStyle(row).position === 'static') {
                                row.style.position = 'relative';
                            }

                            var btn = targetDoc.createElement('button');
                            btn.className = '__ag_msg_copy_btn';
                            btn.setAttribute('type', 'button');
                            btn.innerHTML = '📋 复制全文';

                            (function(targetRow, copyBtn) {
                                copyBtn.addEventListener('click', function(ev) {
                                    ev.preventDefault();
                                    ev.stopPropagation();
                                    var clone = targetRow.cloneNode(true);
                                    var btns = clone.querySelectorAll('button, [role="button"], svg, .__ag_msg_copy_btn');
                                    btns.forEach(function(b) { if (b && b.parentElement) b.parentElement.removeChild(b); });
                                    var txt = (clone.innerText || clone.textContent || '').trim();
                                    if (txt) {
                                        if (window.AndroidBridge && window.AndroidBridge.copyToClipboard) {
                                            window.AndroidBridge.copyToClipboard(txt);
                                        } else if (navigator.clipboard) {
                                            navigator.clipboard.writeText(txt);
                                        }
                                        copyBtn.innerHTML = '✅ 已复制';
                                        copyBtn.style.color = '#1e8e3e';
                                        setTimeout(function() {
                                            copyBtn.innerHTML = '📋 复制全文';
                                            copyBtn.style.color = '#5f6368';
                                        }, 1800);
                                    }
                                });
                            })(row, btn);

                            row.appendChild(btn);
                        }
                    } catch(e) {}
                }

                // 5. SPA 路由全局监听
                if (!window.__ag_history_intercepted && window.history) {
                    window.__ag_history_intercepted = true;
                    var origPushState = history.pushState;
                    var origReplaceState = history.replaceState;
                    function onSpaRouteChange() {
                        setTimeout(function() {
                            injectStyles(document);
                            injectMessageCopyButtons(document);
                        }, 100);
                    }
                    history.pushState = function() {
                        var ret = origPushState.apply(this, arguments);
                        onSpaRouteChange();
                        return ret;
                    };
                    history.replaceState = function() {
                        var ret = origReplaceState.apply(this, arguments);
                        onSpaRouteChange();
                        return ret;
                    };
                    window.addEventListener('popstate', onSpaRouteChange);
                    window.addEventListener('hashchange', onSpaRouteChange);
                }

                injectStyles(document);
                injectMessageCopyButtons(document);

                // 6. 精准断线重连看门狗 (彻底杜绝聊天文本与代码误判)
                function findDisconnectBanner() {
                    var selectors = [
                        '[role="dialog"]',
                        '.monaco-dialog-modal',
                        '.monaco-dialog-box',
                        '.notification-toast',
                        '.banner-widget',
                        '.part.banner'
                    ];
                    for (var s = 0; s < selectors.length; s++) {
                        var elements = document.querySelectorAll(selectors[s]);
                        for (var e = 0; e < elements.length; e++) {
                            var el = elements[e];
                            var text = el.textContent || "";
                            if (text.indexOf("Lost connection to the remote instance") !== -1 ||
                                text.indexOf("Lost connection") !== -1 ||
                                text.indexOf("Connection lost") !== -1) {
                                return el;
                            }
                        }
                    }

                    var fixedOverlays = document.querySelectorAll('body > div, body > aside');
                    for (var j = 0; j < fixedOverlays.length; j++) {
                        var overlay = fixedOverlays[j];
                        var style = window.getComputedStyle(overlay);
                        if (style.position === 'fixed' || style.position === 'absolute') {
                            var overlayText = overlay.textContent || "";
                            if ((overlayText.indexOf("Lost connection") !== -1 || overlayText.indexOf("refresh the page") !== -1) &&
                                overlay.querySelector('pre, code, .rendered-markdown, article, p') === null) {
                                return overlay;
                            }
                        }
                    }
                    return null;
                }

                window.__lastAutoReloadTime = 0;
                window.__disconnectStartTime = 0;
                window.__autoReloadTimer = null;

                window.__checkAndHandleDisconnect = function() {
                    var banner = findDisconnectBanner();
                    if (banner) {
                        if (!window.__disconnectStartTime) {
                            window.__disconnectStartTime = Date.now();
                        }
                        if (!banner.__ag_click_bound) {
                            banner.__ag_click_bound = true;
                            banner.style.cursor = 'pointer';
                            var tip = document.createElement('div');
                            tip.style.cssText = 'font-size: 11px; color: #1a73e8; margin-top: 4px; font-weight: bold; text-align: center; padding: 4px; border-radius: 4px; background: rgba(26,115,232,0.1);';
                            tip.textContent = '⚡ 断线中：点击立即重连 (或等待自动恢复)';
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
                        if (now - window.__disconnectStartTime > 12000) {
                            if (!window.__lastAutoReloadTime || (now - window.__lastAutoReloadTime > 15000)) {
                                if (!window.__autoReloadTimer) {
                                    window.__autoReloadTimer = setTimeout(function() {
                                        window.__autoReloadTimer = null;
                                        window.__lastAutoReloadTime = Date.now();
                                        window.__disconnectStartTime = 0;
                                        if (window.AndroidBridge && window.AndroidBridge.reloadPage) {
                                            window.AndroidBridge.reloadPage();
                                        } else {
                                            window.location.reload();
                                        }
                                    }, 1000);
                                }
                            }
                        }
                    } else {
                        window.__disconnectStartTime = 0;
                    }
                };

                setInterval(function() {
                    window.__checkAndHandleDisconnect();
                }, 1000);

                document.addEventListener('visibilitychange', function() {
                    if (document.visibilityState === 'visible') {
                        window.dispatchEvent(new Event('online'));
                        setTimeout(function() {
                            window.__checkAndHandleDisconnect();
                            injectStyles(document);
                            injectMessageCopyButtons(document);
                        }, 500);
                    }
                });
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }
    private fun loadTargetPage() {
        val targetUrl = storage.serverUrl
        if (targetUrl.isNotBlank()) {
            val isNight = isNightMode()
            val hostThemeValue = if (isNight) "dark" else "light"
            val finalUrl = try {
                if (!targetUrl.contains("hostTheme=")) {
                    val separator = if (targetUrl.contains("?")) "&" else "?"
                    "$targetUrl${separator}hostTheme=$hostThemeValue&defaultThemeMode=system"
                } else {
                    targetUrl
                }
            } catch (e: Exception) {
                targetUrl
            }
            binding.webView.loadUrl(finalUrl)
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
        syncThemeWithSystem()
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
        // 关键保活原则：决不可调用 binding.webView.onPause()！
        // Chromium 的 webView.onPause() 会强行挂起 WebSocket 心跳包与 JS 定时器，
        // 导致手机一旦息屏或切换后台，电脑端即判定连接超时掐断连接，切回前台时必然断线重连。
        // 保持 WebView 处于活跃状态，配合 KeepAliveService 的 Partial WakeLock + WifiLock，
        // 真正实现息屏 1 小时以上长连接稳定不掉线。
    }

    override fun onDestroy() {
        super.onDestroy()
        reconnectHandler.removeCallbacksAndMessages(null)
    }

    private var lastSheetOpenTime = 0L

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
                sheet.requestLayout()

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
        fun onSelectionChanged(text: String) {
            runOnUiThread {
                val clean = text.trim()
                if (clean.isNotEmpty()) {
                    currentSelectedText = clean
                    val count = clean.length
                    binding.btnFloatingCopy.apply {
                        this.text = "📋 复制已选 ($count 字)"
                        bringToFront()
                        if (visibility != View.VISIBLE) {
                            alpha = 0f
                            visibility = View.VISIBLE
                            animate().alpha(1f).setDuration(120).start()
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            vibratePhone(25)
                        }
                    }
                }
            }
        }

        @JavascriptInterface
        fun onSelectionCleared() {
            runOnUiThread {
                currentSelectedText = ""
                if (binding.btnFloatingCopy.visibility == View.VISIBLE) {
                    binding.btnFloatingCopy.animate().alpha(0f).setDuration(120).withEndAction {
                        binding.btnFloatingCopy.visibility = View.GONE
                    }.start()
                }
            }
        }
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
