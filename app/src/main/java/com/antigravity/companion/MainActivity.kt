package com.antigravity.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: StorageManager
    private lateinit var notificationHelper: NotificationHelper

    // 传感器与手势：摇一摇 / 双指长按 唤出配置
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var lastShakeTime: Long = 0L
    private var hasShownConnectToast: Boolean = false
    private var twoFingerDownTime: Long = 0L

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

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

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
                    Toast.makeText(this@MainActivity, "已连接！如需重新配置或刷新，摇一摇手机即可", Toast.LENGTH_SHORT).show()
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
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        // 切回前台时，主动通知 WebView 唤醒
        binding.webView.evaluateJavascript(
            "if (document.hidden !== undefined) { document.dispatchEvent(new Event('visibilitychange')); }",
            null
        )
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    // 摇一摇手机唤出配置
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val gForce = sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH
            if (gForce > 2.6) {
                val now = System.currentTimeMillis()
                if (now - lastShakeTime > 2000) {
                    lastShakeTime = now
                    showSetupDialog()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // 双指长按屏幕 1.2 秒唤出设置
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (ev.pointerCount == 2) {
                        twoFingerDownTime = System.currentTimeMillis()
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (ev.pointerCount == 2 && twoFingerDownTime > 0) {
                        if (System.currentTimeMillis() - twoFingerDownTime > 1200) {
                            twoFingerDownTime = 0
                            showSetupDialog()
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    twoFingerDownTime = 0
                }
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
