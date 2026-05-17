package com.cuso4.manager

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: CuSO4Bridge
    private var filePickerCallback: ValueCallback<Array<Uri>>? = null
    private var rootWarmupStarted = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(webView)

        bridge = CuSO4Bridge(this)
        warmupRootOnce()
        requestNotificationPermission()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                databaseEnabled = true
                setGeolocationEnabled(false)
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            }

            addJavascriptInterface(bridge, "CuSO4Bridge")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? = null
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    filePickerCallback = filePathCallback
                    zipPicker.launch(arrayOf(
                        "application/zip",
                        "application/octet-stream",
                        "application/x-zip-compressed"
                    ))
                    return true
                }
            }

            loadUrl("file:///android_asset/home/index.html")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun warmupRootOnce() {
        if (rootWarmupStarted) return
        rootWarmupStarted = true
        Thread {
            RootShell.hasRoot()
            RootShell.magiskVersion()
        }.start()
    }

    fun evaluateJs(script: String) {
        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }

    fun openFilePicker() {
        zipPicker.launch(arrayOf(
            "application/zip",
            "application/octet-stream",
            "application/x-zip-compressed"
        ))
    }

    private val zipPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            filePickerCallback?.onReceiveValue(arrayOf(uri))
            filePickerCallback = null
            Thread {
                val path = copyUriToTemp(uri)
                if (path != null) {
                    bridge.installModuleFromZip(path)
                } else {
                    evaluateJs(
                        "if(window.onModuleInstallResult)" +
                        " window.onModuleInstallResult({ok:false,message:'无法读取文件'});"
                    )
                }
            }.start()
        } else {
            filePickerCallback?.onReceiveValue(null)
            filePickerCallback = null
        }
    }

    private fun copyUriToTemp(uri: Uri): String? {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            val tempFile = java.io.File(cacheDir, "install.zip")
            tempFile.outputStream().use { out ->
                input.use { src -> src.copyTo(out) }
            }
            tempFile.setReadable(true, false)
            tempFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bridge.shutdown()
    }
}
