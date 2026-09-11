package com.antigravity.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
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

    // 三指向上滑动唤出设置
    private var threeFingerStartY = 0f
    private var isThreeFingerSwipeActive = false
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
            userAgentString = settings.userAgentString + " AntigravityCompanion/1.2"
        }

        // 启用网页内原生平滑滚动与惯性滑动
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS

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
                    Toast.makeText(this@MainActivity, "已连接！如需重新配置或刷新，三指向上滑动即可", Toast.LENGTH_SHORT).show()
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
                // 防止重复注入
                if (window.__antigravity_companion_injected) return;
                window.__antigravity_companion_injected = true;

                // 监听切回前台事件，触发页面网络静默唤醒
                document.addEventListener('visibilitychange', function() {
                    if (document.visibilityState === 'visible') {
                        // 触发页面可能存在的重连事件
                        window.dispatchEvent(new Event('online'));
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
        // 切回前台时，主动通知 WebView 唤醒
        binding.webView.evaluateJavascript(
            "if (document.hidden !== undefined) { document.dispatchEvent(new Event('visibilitychange')); }",
            null
        )
    }

    // 三指向上滑动唤出配置弹窗
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) {
            if (ev.pointerCount == 3) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        threeFingerStartY = (ev.getY(0) + ev.getY(1) + ev.getY(2)) / 3f
                        isThreeFingerSwipeActive = true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isThreeFingerSwipeActive && threeFingerStartY > 0f) {
                            val currentY = (ev.getY(0) + ev.getY(1) + ev.getY(2)) / 3f
                            val deltaY = threeFingerStartY - currentY
                            // 向上滑动距离超过 180 像素，触发配置弹窗
                            if (deltaY > 180f) {
                                isThreeFingerSwipeActive = false
                                threeFingerStartY = 0f
                                showSetupDialog()
                            }
                        }
                    }
                }
            } else {
                isThreeFingerSwipeActive = false
                threeFingerStartY = 0f
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
    }
}
