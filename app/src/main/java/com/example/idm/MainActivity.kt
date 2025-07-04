package com.example.idm

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.write
import android.util.Base64 as AndroidBase64
import kotlin.io.path.exists
import android.webkit.MimeTypeMap
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.MenuItem
import android.widget.PopupMenu
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.media.MediaScannerConnection
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.IOException


class MainActivity : AppCompatActivity() {
    private val REQUEST_CAMERA = 1
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private val CAMERA_PERMISSION_REQUEST_CODE = 1002
    private val WEBVIEW_URL = "http://192.168.17.249:8080"
    private val ORIGIN = "idm.llc"
    private val ORIGIN_ALT = "idatamanage.com"

    public lateinit var webView: WebView
    private lateinit var webViewBackPressedCallback: OnBackPressedCallback
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    private var keepSplashOnScreen = AtomicBoolean(true)
    public val forceRefreshBarCanAppear = AtomicBoolean(false)
    public val refreshBarCanAppear = AtomicBoolean(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        splashScreen.setKeepOnScreenCondition { keepSplashOnScreen.get() }

        init()
    }





    private fun init() {
        initInsets()
        initWebView()
        initSwipeRefresh()
        initNavigation()
    }





    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        webView = findViewById(R.id.webview)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)

        // set bg color to show while webview is loading; on some devices bg color set in xml is not respected, so we also add it here
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.milk))

        webView.loadUrl(WEBVIEW_URL)
        webView.settings.javaScriptEnabled = true

        webView.addJavascriptInterface(WebAppInterface(this, webView, this), "AndroidInterface")


        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView.settings.apply {
            domStorageEnabled = true
            allowContentAccess = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            saveFormData = true
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            //            allowFileAccess = true
            //            setGeolocationEnabled(true)
        }

        webView.apply {
            webChromeClient = MyWebChromeClient()
            webViewClient = MyWebViewClient()
        }

        // enable cookies
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        //        checkLocationPermission()
        //        checkCameraPermission()
    }





    private fun initSwipeRefresh() {
        swipeRefreshLayout.setOnRefreshListener {
            webView.reload()
        }

        swipeRefreshLayout.setColorSchemeResources(R.color.dark_green)

        swipeRefreshLayout.setOnChildScrollUpCallback(object :
            SwipeRefreshLayout.OnChildScrollUpCallback {
            override fun canChildScrollUp(parent: SwipeRefreshLayout, child: View?): Boolean {
                val canScrollVertically = child?.canScrollVertically(-1) ?: false
                if (child is WebView) {
                    val refreshBarCanAppear = refreshBarCanAppear.get()
                    val forceRefreshBarCanAppear = forceRefreshBarCanAppear.get()
                    Log.d("forceRefreshBarCanAppear", "$forceRefreshBarCanAppear")

                    if (forceRefreshBarCanAppear) return false

                    if (!refreshBarCanAppear) return true
                }

                return canScrollVertically
            }
        })

        val density = resources.displayMetrics.density
        val progressViewEndOffset = (56 * density).toInt()

        swipeRefreshLayout.setProgressViewEndTarget(true, progressViewEndOffset)


    }




    private fun initInsets() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) {
                false // this predicate is for determining if the content behind is dark
            },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) {
                false
            }
        )

        window.navigationBarColor = getColor(R.color.milk)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }





    private fun initNavigation() {
        webViewBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false // disable this callback
                    onBackPressedDispatcher.onBackPressed() // trigger default back behavior
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, webViewBackPressedCallback)

        // phantom copy of the link appears and follows finger when long pressed on the link, disable that
        webView.setOnLongClickListener { view ->
            val webView = view as WebView
            val hitTestResult = webView.hitTestResult
            hitTestResult.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        }
    }





    override fun onPause() {
        super.onPause()
        webView.onPause()
    }





    override fun onResume() {
        super.onResume()
        if (::webViewBackPressedCallback.isInitialized && !webViewBackPressedCallback.isEnabled) {
            webViewBackPressedCallback.isEnabled = true
        }
        webView.onResume()
    }





    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
    }





    inner class MyWebViewClient : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            if (swipeRefreshLayout.isRefreshing) {
                swipeRefreshLayout.isRefreshing = false // stop the refreshing animation
            }
        }

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            if(url == null) return true

            if (url.contains(ORIGIN) || url.contains(ORIGIN_ALT) || url.startsWith(WEBVIEW_URL)) {
                return false
            }

            else {
                val uri = url.toUri()
                if (uri.host?.contains("maps.google.") == true || uri.host?.contains("google.com/maps") == true) {
                    // try to open in Google Maps app first
                    val mapIntent = Intent(Intent.ACTION_VIEW, uri)
                    view?.context?.startActivity(mapIntent)
                    return true
                }

                // for other external URLs, try to open them in an external browser
                try {
                    val externalIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    view?.context?.startActivity(externalIntent)
                } catch (e: Exception) {
                    return true
                }
                return true
            }
        }
    }




    inner class MyWebChromeClient : WebChromeClient() {
        private var mFilePathCallback: ValueCallback<Array<Uri>>? = null

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)

            if (newProgress >= 84) {
                if (keepSplashOnScreen.get()) {
                    keepSplashOnScreen.set(false)
                }
            }
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            mFilePathCallback = filePathCallback
            val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            startActivityForResult(takePictureIntent, REQUEST_CAMERA)

            return true
        }

    }
}