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

            // 保持焦点支持，屏蔽系统粗粒度长按，全面交由高精度逐字选择器
            isFocusable = true
            isFocusableInTouchMode = true
            isHapticFeedbackEnabled = true
            setOnLongClickListener { true }
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

                // 1. 穿透样式：解锁所有内联文本、代码块，清除 Monaco 遮罩层拦截
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
                        '::selection { background: rgba(26, 115, 232, 0.35) !important; }';
                }

                // 2. 原地高精度逐字字符选择器（绕过 Chromium kWord 强制吸附，支持平滑逐字拖拽）
                function initCharacterSelector(doc) {
                    if (!doc || doc.__ag_char_selector_init) return;
                    doc.__ag_char_selector_init = true;

                    var startRange = null;
                    var endRange = null;
                    var isSelecting = false;
                    var isDraggingHandle = null;
                    var longPressTimer = null;
                    var touchStartX = 0;
                    var touchStartY = 0;
                    var activeTouchId = null;

                    var overlayContainer = doc.createElement('div');
                    overlayContainer.id = '__ag_selection_overlay';
                    overlayContainer.style.cssText = 'position: absolute; left: 0; top: 0; width: 100%; height: 0; pointer-events: none; z-index: 2147483640;';
                    (doc.body || doc.documentElement).appendChild(overlayContainer);

                    var startHandle = doc.createElement('div');
                    startHandle.id = '__ag_handle_start';
                    startHandle.style.cssText = 'position: absolute; width: 22px; height: 22px; background: #1a73e8; border-radius: 0 50% 50% 50%; transform: rotate(45deg); cursor: pointer; pointer-events: auto; z-index: 2147483645; display: none; box-shadow: 0 2px 6px rgba(0,0,0,0.4); touch-action: none; padding: 10px; margin: -10px; background-clip: content-box;';
                    (doc.body || doc.documentElement).appendChild(startHandle);

                    var endHandle = doc.createElement('div');
                    endHandle.id = '__ag_handle_end';
                    endHandle.style.cssText = 'position: absolute; width: 22px; height: 22px; background: #1a73e8; border-radius: 50% 50% 0 50%; transform: rotate(45deg); cursor: pointer; pointer-events: auto; z-index: 2147483645; display: none; box-shadow: 0 2px 6px rgba(0,0,0,0.4); touch-action: none; padding: 10px; margin: -10px; background-clip: content-box;';
                    (doc.body || doc.documentElement).appendChild(endHandle);

                    var toolbar = doc.createElement('div');
                    toolbar.id = '__ag_selection_toolbar';
                    toolbar.style.cssText = 'position: absolute; z-index: 2147483646; background: #282a2e; color: #ffffff; border: 1px solid #3c4043; border-radius: 8px; box-shadow: 0 4px 16px rgba(0,0,0,0.45); padding: 4px 6px; display: none; pointer-events: auto; white-space: nowrap; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; font-size: 14px; font-weight: 500; -webkit-user-select: none; user-select: none;';
                    toolbar.innerHTML = '<button id="__ag_btn_copy" style="background:transparent;border:none;color:#fff;padding:6px 14px;font-size:14px;cursor:pointer;font-weight:500;">复制</button><span style="color:#5f6368;margin:0 2px;">|</span><button id="__ag_btn_all" style="background:transparent;border:none;color:#8ab4f8;padding:6px 14px;font-size:14px;cursor:pointer;font-weight:500;">全选</button>';
                    (doc.body || doc.documentElement).appendChild(toolbar);

                    toolbar.querySelector('#__ag_btn_copy').addEventListener('click', function(e) {
                        e.stopPropagation();
                        e.preventDefault();
                        copyCurrentSelection();
                    });
                    toolbar.querySelector('#__ag_btn_copy').addEventListener('touchend', function(e) {
                        e.stopPropagation();
                        e.preventDefault();
                        copyCurrentSelection();
                    });

                    toolbar.querySelector('#__ag_btn_all').addEventListener('click', function(e) {
                        e.stopPropagation();
                        e.preventDefault();
                        selectAllContainer();
                    });
                    toolbar.querySelector('#__ag_btn_all').addEventListener('touchend', function(e) {
                        e.stopPropagation();
                        e.preventDefault();
                        selectAllContainer();
                    });

                    function getCaretAtPoint(x, y) {
                        if (doc.caretRangeFromPoint) {
                            return doc.caretRangeFromPoint(x, y);
                        } else if (doc.caretPositionFromPoint) {
                            var pos = doc.caretPositionFromPoint(x, y);
                            if (pos) {
                                var r = doc.createRange();
                                r.setStart(pos.offsetNode, pos.offset);
                                r.setEnd(pos.offsetNode, pos.offset);
                                return r;
                            }
                        }
                        return null;
                    }

                    function getCharRangeAtPoint(x, y) {
                        var caret = getCaretAtPoint(x, y);
                        if (!caret) return null;
                        var node = caret.startContainer;
                        if (node.nodeType !== Node.TEXT_NODE) {
                            if (node.childNodes && node.childNodes.length > 0) {
                                var idx = Math.min(caret.startOffset, node.childNodes.length - 1);
                                var child = node.childNodes[idx];
                                if (child && child.nodeType === Node.TEXT_NODE) {
                                    node = child;
                                } else if (child) {
                                    var walker = doc.createTreeWalker(child, NodeFilter.SHOW_TEXT, null, false);
                                    var t = walker.nextNode();
                                    if (t) node = t;
                                }
                            }
                        }
                        if (!node || node.nodeType !== Node.TEXT_NODE) return null;

                        var text = node.textContent || '';
                        if (text.length === 0) return null;
                        var offset = Math.min(Math.max(0, caret.startOffset), Math.max(0, text.length - 1));

                        var r = doc.createRange();
                        r.setStart(node, offset);
                        r.setEnd(node, Math.min(offset + 1, text.length));
                        return r;
                    }

                    function renderSelection() {
                        if (!startRange || !endRange) {
                            overlayContainer.innerHTML = '';
                            startHandle.style.display = 'none';
                            endHandle.style.display = 'none';
                            toolbar.style.display = 'none';
                            return;
                        }

                        var fullRange = doc.createRange();
                        var comp = startRange.compareBoundaryPoints(Range.START_TO_START, endRange);
                        var rStart = (comp <= 0) ? startRange : endRange;
                        var rEnd = (comp <= 0) ? endRange : startRange;

                        fullRange.setStart(rStart.startContainer, rStart.startOffset);
                        fullRange.setEnd(rEnd.endContainer, rEnd.endOffset);

                        var rects = fullRange.getClientRects();
                        var html = '';
                        var scrollX = window.scrollX || doc.documentElement.scrollLeft || 0;
                        var scrollY = window.scrollY || doc.documentElement.scrollTop || 0;

                        for (var i = 0; i < rects.length; i++) {
                            var rect = rects[i];
                            if (rect.width > 0 && rect.height > 0) {
                                html += '<div style="position:absolute;left:' + (rect.left + scrollX) + 'px;top:' + (rect.top + scrollY) + 'px;width:' + rect.width + 'px;height:' + rect.height + 'px;background:rgba(26,115,232,0.3);pointer-events:none;border-radius:2px;"></div>';
                            }
                        }
                        overlayContainer.innerHTML = html;

                        var startBounding = rStart.getBoundingClientRect();
                        var endBounding = rEnd.getBoundingClientRect();

                        startHandle.style.display = 'block';
                        startHandle.style.left = (startBounding.left + scrollX - 11) + 'px';
                        startHandle.style.top = (startBounding.bottom + scrollY + 1) + 'px';

                        endHandle.style.display = 'block';
                        endHandle.style.left = (endBounding.right + scrollX - 11) + 'px';
                        endHandle.style.top = (endBounding.bottom + scrollY + 1) + 'px';

                        if (!isSelecting && !isDraggingHandle) {
                            toolbar.style.display = 'block';
                            var tbWidth = toolbar.offsetWidth || 140;
                            var tbHeight = toolbar.offsetHeight || 38;
                            var tbLeft = startBounding.left + scrollX + (startBounding.width / 2) - (tbWidth / 2);
                            var tbTop = startBounding.top + scrollY - tbHeight - 12;

                            if (tbLeft < 10) tbLeft = 10;
                            if (tbTop < scrollY + 10) tbTop = endBounding.bottom + scrollY + 28;
                            toolbar.style.left = tbLeft + 'px';
                            toolbar.style.top = tbTop + 'px';
                        } else {
                            toolbar.style.display = 'none';
                        }
                    }

                    function clearSelection() {
                        startRange = null;
                        endRange = null;
                        isSelecting = false;
                        isDraggingHandle = null;
                        renderSelection();
                    }

                    function getSelectedText() {
                        if (!startRange || !endRange) return '';
                        var fullRange = doc.createRange();
                        var comp = startRange.compareBoundaryPoints(Range.START_TO_START, endRange);
                        var rStart = (comp <= 0) ? startRange : endRange;
                        var rEnd = (comp <= 0) ? endRange : startRange;
                        fullRange.setStart(rStart.startContainer, rStart.startOffset);
                        fullRange.setEnd(rEnd.endContainer, rEnd.endOffset);
                        return fullRange.toString();
                    }

                    function copyCurrentSelection() {
                        var text = getSelectedText();
                        if (text && text.length > 0) {
                            if (window.AndroidBridge && window.AndroidBridge.copyToClipboard) {
                                window.AndroidBridge.copyToClipboard(text);
                            }
                        }
                        clearSelection();
                    }

                    function selectAllContainer() {
                        if (!startRange) return;
                        var container = startRange.startContainer;
                        while (container && container.nodeType !== Node.ELEMENT_NODE) {
                            container = container.parentNode;
                        }
                        var block = container.closest('td, th, pre, code, p, [class*="message"], [class*="content"]') || container;
                        if (block) {
                            var walker = doc.createTreeWalker(block, NodeFilter.SHOW_TEXT, null, false);
                            var firstText = walker.nextNode();
                            var lastText = firstText;
                            var cur;
                            while (cur = walker.nextNode()) {
                                if (cur.textContent && cur.textContent.trim().length > 0) {
                                    lastText = cur;
                                }
                            }
                            if (firstText && lastText) {
                                startRange = doc.createRange();
                                startRange.setStart(firstText, 0);
                                startRange.setEnd(firstText, Math.min(1, firstText.textContent.length));

                                endRange = doc.createRange();
                                endRange.setStart(lastText, Math.max(0, lastText.textContent.length - 1));
                                endRange.setEnd(lastText, lastText.textContent.length);
                                renderSelection();
                            }
                        }
                    }

                    function bindHandleEvents(handle, type) {
                        handle.addEventListener('touchstart', function(e) {
                            e.stopPropagation();
                            e.preventDefault();
                            isDraggingHandle = type;
                            toolbar.style.display = 'none';
                            if (window.AndroidBridge && window.AndroidBridge.vibrate) {
                                window.AndroidBridge.vibrate(20);
                            }
                        }, { passive: false });

                        handle.addEventListener('touchmove', function(e) {
                            if (!isDraggingHandle) return;
                            e.stopPropagation();
                            e.preventDefault();
                            var touch = e.touches[0];
                            var caret = getCharRangeAtPoint(touch.clientX, touch.clientY - 18);
                            if (caret) {
                                if (isDraggingHandle === 'start') {
                                    startRange = caret;
                                } else {
                                    endRange = caret;
                                }
                                renderSelection();
                            }
                        }, { passive: false });

                        handle.addEventListener('touchend', function(e) {
                            e.stopPropagation();
                            e.preventDefault();
                            isDraggingHandle = null;
                            renderSelection();
                        }, { passive: false });
                    }

                    bindHandleEvents(startHandle, 'start');
                    bindHandleEvents(endHandle, 'end');

                    doc.addEventListener('touchstart', function(e) {
                        if (e.touches.length !== 1) {
                            if (longPressTimer) clearTimeout(longPressTimer);
                            return;
                        }

                        var target = e.target;
                        if (target === startHandle || target === endHandle || toolbar.contains(target)) {
                            return;
                        }

                        if (startRange && !isDraggingHandle) {
                            clearSelection();
                        }

                        if (target.closest('button, [role="button"], a[role="button"], input, textarea, select')) {
                            return;
                        }

                        var touch = e.touches[0];
                        touchStartX = touch.clientX;
                        touchStartY = touch.clientY;
                        activeTouchId = touch.identifier;

                        if (longPressTimer) clearTimeout(longPressTimer);
                        longPressTimer = setTimeout(function() {
                            var charRange = getCharRangeAtPoint(touchStartX, touchStartY);
                            if (charRange) {
                                startRange = charRange.cloneRange();
                                endRange = charRange.cloneRange();
                                isSelecting = true;
                                if (window.AndroidBridge && window.AndroidBridge.vibrate) {
                                    window.AndroidBridge.vibrate(35);
                                }
                                renderSelection();
                            }
                        }, 320);
                    }, { passive: true });

                    doc.addEventListener('touchmove', function(e) {
                        if (isSelecting) {
                            var touch = null;
                            for (var i = 0; i < e.touches.length; i++) {
                                if (e.touches[i].identifier === activeTouchId) {
                                    touch = e.touches[i];
                                    break;
                                }
                            }
                            if (!touch) touch = e.touches[0];

                            var caret = getCharRangeAtPoint(touch.clientX, touch.clientY);
                            if (caret) {
                                endRange = caret;
                                renderSelection();
                            }
                            e.preventDefault();
                            return;
                        }

                        if (longPressTimer) {
                            var touch = e.touches[0];
                            var dx = touch.clientX - touchStartX;
                            var dy = touch.clientY - touchStartY;
                            if (Math.hypot(dx, dy) > 8) {
                                clearTimeout(longPressTimer);
                                longPressTimer = null;
                            }
                        }
                    }, { passive: false });

                    doc.addEventListener('touchend', function(e) {
                        if (longPressTimer) {
                            clearTimeout(longPressTimer);
                            longPressTimer = null;
                        }
                        if (isSelecting) {
                            isSelecting = false;
                            renderSelection();
                        }
                    }, { passive: true });

                    doc.addEventListener('touchcancel', function(e) {
                        if (longPressTimer) {
                            clearTimeout(longPressTimer);
                            longPressTimer = null;
                        }
                        if (isSelecting) {
                            isSelecting = false;
                            renderSelection();
                        }
                    }, { passive: true });

                    window.addEventListener('scroll', function() {
                        if (startRange && !isSelecting && !isDraggingHandle) {
                            renderSelection();
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

                    // 初始化逐字高精度选择器
                    try {
                        initCharacterSelector(targetDoc);
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
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Copied Text", text)
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
        fun reloadPage() {
            runOnUiThread {
                binding.progressBar.visibility = View.VISIBLE
                binding.webView.reload()
                Toast.makeText(this@MainActivity, "正在重新连接 Antigravity...", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
