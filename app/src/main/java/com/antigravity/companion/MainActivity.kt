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
                injectEarlyHook(view)
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

    private fun injectEarlyHook(view: WebView?) {
        // 在网页加载早期立即：
        // 1. 短路 (hover: none)，掐灭 React 20ms re-render 强杀手势
        // 2. 原型链拦截 appendChild/insertBefore，物理阻断 Quote 气泡插入 DOM 树
        val hookJs = """
            (function() {
                try {
                    if (!window.__ag_hover_shortcircuited && window.matchMedia) {
                        window.__ag_hover_shortcircuited = true;
                        var origMatchMedia = window.matchMedia;
                        window.matchMedia = function(q) {
                            if (typeof q === 'string' && (q.indexOf('hover: none') !== -1 || q.indexOf('hover:none') !== -1)) {
                                return {
                                    matches: false,
                                    media: q,
                                    onchange: null,
                                    addListener: function() {},
                                    removeListener: function() {},
                                    addEventListener: function() {},
                                    removeEventListener: function() {},
                                    dispatchEvent: function() { return false; }
                                };
                            }
                            return origMatchMedia.call(window, q);
                        };
                    }

                    // 物理绝杀：拦截 Node.prototype.appendChild 和 insertBefore，彻底杜绝 Quote 气泡进入 DOM
                    if (!window.__ag_dom_hooked && window.Node) {
                        window.__ag_dom_hooked = true;
                        function isQuoteNode(node) {
                            if (!node || node.nodeType !== 1) return false;
                            var cls = node.className || '';
                            if (typeof cls === 'string' && (cls.indexOf('selection-popup') !== -1 || cls.indexOf('selection-quote') !== -1)) return true;
                            var tid = node.getAttribute ? (node.getAttribute('data-testid') || '') : '';
                            if (tid.indexOf('quote') !== -1) return true;
                            if (node.querySelector && node.querySelector('[data-testid*="quote"], .selection-quote-popup')) return true;
                            return false;
                        }

                        var origAppend = Node.prototype.appendChild;
                        Node.prototype.appendChild = function(child) {
                            if (isQuoteNode(child)) return child;
                            return origAppend.apply(this, arguments);
                        };

                        var origInsert = Node.prototype.insertBefore;
                        Node.prototype.insertBefore = function(newNode, refNode) {
                            if (isQuoteNode(newNode)) return newNode;
                            return origInsert.apply(this, arguments);
                        };
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        view?.evaluateJavascript(hookJs, null)
    }

    private fun injectKeepAliveAndNotificationScript(view: WebView?) {
        val js = """
            (function() {
                // 1. 彻底短路 (hover: none)
                try {
                    if (!window.__ag_hover_shortcircuited && window.matchMedia) {
                        window.__ag_hover_shortcircuited = true;
                        var origMatchMedia = window.matchMedia;
                        window.matchMedia = function(q) {
                            if (typeof q === 'string' && (q.indexOf('hover: none') !== -1 || q.indexOf('hover:none') !== -1)) {
                                return {
                                    matches: false,
                                    media: q,
                                    onchange: null,
                                    addListener: function() {},
                                    removeListener: function() {},
                                    addEventListener: function() {},
                                    removeEventListener: function() {},
                                    dispatchEvent: function() { return false; }
                                };
                            }
                            return origMatchMedia.call(window, q);
                        };
                    }
                } catch(e) {}

                // 2. 设置网页语言为中文，唤醒 ICU 中文分词字典
                try {
                    document.documentElement.lang = 'zh-CN';
                    if (document.body) document.body.lang = 'zh-CN';
                } catch(e) {}

                // 3. 根治首字选择与彻底消除 Quote 气泡的高级样式
                function getSelectionCSS() {
                    return 'html, body { ' +
                        '-webkit-text-size-adjust: 100% !important; ' +
                        'text-size-adjust: 100% !important; ' +
                        '} ' +
                        '/* 彻底消灭 Quote 气泡的所有可能容器 */ ' +
                        '.selection-popup, .selection-quote-popup, [data-testid*="selection-quote"], [data-testid*="selection-popup"], button[data-testid*="quote"], div:has(> button[data-testid*="selection-quote-button"]) { ' +
                        'display: none !important; ' +
                        'visibility: hidden !important; ' +
                        'pointer-events: none !important; ' +
                        'width: 0 !important; ' +
                        'height: 0 !important; ' +
                        'opacity: 0 !important; ' +
                        'position: fixed !important; ' +
                        'top: -9999px !important; ' +
                        'left: -9999px !important; ' +
                        'transform: scale(0) !important; ' +
                        'z-index: -99999 !important; ' +
                        '} ' +
                        '/* 根治首字选择痛点：列表标记符号彻底禁止参与长按命中，防止首字误抓到圆点导致全选 */ ' +
                        'li::marker, ul > li::before, ol > li::before { ' +
                        'user-select: none !important; ' +
                        '-webkit-user-select: none !important; ' +
                        'pointer-events: none !important; ' +
                        '} ' +
                        '/* 根治首字选择痛点：为段落和列表项建立内部边界保护，长按首字绝不向外逃逸触发整段全选 */ ' +
                        '.select-text, [class*="select-text"], div[role="article"] { ' +
                        '-webkit-user-select: text !important; ' +
                        'user-select: text !important; ' +
                        '-webkit-touch-callout: default !important; ' +
                        'touch-action: pan-y !important; ' +
                        'line-height: 1.75 !important; ' +
                        'letter-spacing: 0.025em !important; ' +
                        '} ' +
                        '.select-text p, .select-text li, div[role="article"] p, div[role="article"] li { ' +
                        'contain: inline-size !important; ' +
                        '} ' +
                        '/* 高清晰选区高亮颜色 */ ' +
                        '::selection { background: rgba(30, 142, 62, 0.28) !important; color: inherit !important; } ' +
                        '::-webkit-selection { background: rgba(30, 142, 62, 0.28) !important; color: inherit !important; } ' +
                        '/* 避免非文本元素（按钮、头像、图标）误入选区引发全选 */ ' +
                        'button, svg, [role="button"], .__ag_msg_copy_btn { ' +
                        '-webkit-user-select: none !important; ' +
                        'user-select: none !important; ' +
                        '} ' +
                        '.monaco-editor .view-overlays { pointer-events: none !important; } ' +
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

                // 4. 首字长按智能纠偏引擎：记录触控坐标，若首字长按误触整段全选，1ms 内拉回单字并呼出水滴！
                if (!window.__ag_first_char_engine_bound) {
                    window.__ag_first_char_engine_bound = true;
                    window.__ag_touch_x = 0;
                    window.__ag_touch_y = 0;
                    window.__ag_is_touching = false;

                    document.addEventListener('touchstart', function(e) {
                        if (e.touches && e.touches.length === 1) {
                            window.__ag_touch_x = e.touches[0].clientX;
                            window.__ag_touch_y = e.touches[0].clientY;
                            window.__ag_is_touching = true;
                        }
                    }, { passive: true, capture: true });

                    document.addEventListener('touchend', function() {
                        setTimeout(function() { window.__ag_is_touching = false; }, 400);
                    }, { passive: true, capture: true });

                    document.addEventListener('selectionchange', function() {
                        // 1. 顺手清除任何可能漏掉的 Quote 气泡
                        removeQuotePopups(document);

                        // 2. 首字纠偏守护
                        var sel = window.getSelection();
                        if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return;
                        
                        // 如果在手指长按触控期间，选区被异常扩张成超过 25 字的大段（典型首字 Block 溢出症状）：
                        var textLen = sel.toString().trim().length;
                        if (window.__ag_is_touching && textLen > 25) {
                            var range = sel.getRangeAt(0);
                            // 如果选区正好从开头开始
                            if (range.startOffset === 0 && document.caretRangeFromPoint) {
                                var caret = document.caretRangeFromPoint(window.__ag_touch_x, window.__ag_touch_y);
                                if (caret && caret.startContainer) {
                                    sel.removeAllRanges();
                                    var newR = document.createRange();
                                    newR.setStart(caret.startContainer, caret.startOffset);
                                    var maxLen = caret.startContainer.length || 1;
                                    newR.setEnd(caret.startContainer, Math.min(caret.startOffset + 1, maxLen));
                                    sel.addRange(newR);
                                }
                            }
                        }
                    }, true);
                }

                // 5. 消息气泡注入“📋 复制全文”极简按钮
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

                function removeQuotePopups(targetDoc) {
                    if (!targetDoc) return;
                    try {
                        var popups = targetDoc.querySelectorAll('.selection-popup, .selection-quote-popup, [data-testid*="selection-quote"], button[data-testid*="selection-quote-button"]');
                        for (var i = 0; i < popups.length; i++) {
                            var el = popups[i];
                            var p = el;
                            while (p && p !== targetDoc.body && p.parentElement && (p.classList.contains('selection-popup') || p.offsetHeight < 80)) {
                                el = p;
                                p = p.parentElement;
                            }
                            if (el && el.parentNode) {
                                el.parentNode.removeChild(el);
                            }
                        }
                    } catch(e) {}
                }

                function injectStylesAndFixes(targetDoc) {
                    if (!targetDoc) return;
                    try {
                        targetDoc.documentElement.lang = 'zh-CN';
                        if (targetDoc.body) targetDoc.body.lang = 'zh-CN';

                        var styleId = '__ag_precision_selection_style';
                        var styleEl = targetDoc.getElementById(styleId);
                        if (!styleEl) {
                            styleEl = targetDoc.createElement('style');
                            styleEl.id = styleId;
                            styleEl.type = 'text/css';
                            (targetDoc.head || targetDoc.documentElement).appendChild(styleEl);
                        }
                        styleEl.innerHTML = getSelectionCSS();

                        removeQuotePopups(targetDoc);
                        injectMessageCopyButtons(targetDoc);

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

                injectStylesAndFixes(document);

                // 6. 断线重连看门狗
                function findDisconnectBanner() {
                    var keywords = ["Lost connection to the remote instance", "Lost connection", "refresh the page"];
                    var walker = document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT, null, false);
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
                            var tip = document.createElement('div');
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
                    }
                };

                setInterval(function() {
                    window.__checkAndHandleDisconnect();
                    injectStylesAndFixes(document);
                }, 1500);

                var observer = new MutationObserver(function() {
                    window.__checkAndHandleDisconnect();
                    injectStylesAndFixes(document);
                });
                if (document.body) {
                    observer.observe(document.body, { childList: true, subtree: true });
                }

                document.addEventListener('visibilitychange', function() {
                    if (document.visibilityState === 'visible') {
                        window.dispatchEvent(new Event('online'));
                        setTimeout(function() {
                            window.__checkAndHandleDisconnect();
                            injectStylesAndFixes(document);
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
