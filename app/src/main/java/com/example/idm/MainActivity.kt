package com.example.idm

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.util.concurrent.atomic.AtomicBoolean
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.example.idm.webAppInterface.WebAppInterface
import com.example.idm.webChromeClient.MyWebChromeClient

class MainActivity : AppCompatActivity() {
    private val WEBVIEW_URL = "https://jti.idatamanage.com"
    private val ORIGIN = "idm.llc"
    private val ORIGIN_ALT = "idatamanage.com"

    public lateinit var webView: WebView
    private lateinit var webAppInterface: WebAppInterface
    private lateinit var webViewBackPressedCallback: OnBackPressedCallback
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var myWebChromeClient: MyWebChromeClient
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>

    private var currentFilePathCallback: ValueCallback<Array<Uri>>? = null
    private var currentCameraPhotoUri: Uri? = null
    public var keepSplashOnScreen = AtomicBoolean(true)
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

        webAppInterface = WebAppInterface(this, webView, this)
        webView.addJavascriptInterface(webAppInterface, "AndroidInterface")


        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val granted = permissions.all { it.value } // check if all requested permissions are granted
            myWebChromeClient.onLocationPermissionsResult(granted)
        }

        myWebChromeClient = MyWebChromeClient(
            activityContext = this,
            activity = this,
            permissionLauncher = requestPermissionLauncher,
            fileChooserLauncher = fileChooserLauncher,
            onCameraPhotoUriGenerated = { uri -> currentCameraPhotoUri = uri },
            onFilePathCallbackAssigned = { callback ->
                // if a previous callback exists and a new one is being started,
                // notify the old one with null (user didn't choose anything for it).
                if (currentFilePathCallback != null && callback != null) {
                    currentFilePathCallback?.onReceiveValue(null)
                }
                currentFilePathCallback = callback
            }
        )


//        myWebChromeClient = MyWebChromeClient(this,  requestPermissionLauncher, fileChooserLauncher)

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
            allowFileAccess = true
            setGeolocationEnabled(true)
        }

        webView.apply {
            webChromeClient = myWebChromeClient
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






    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (::webAppInterface.isInitialized) {
            webAppInterface.handlePermissionsResult(requestCode, grantResults)
        }
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





    private val fileChooserLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (currentFilePathCallback == null) {
                currentCameraPhotoUri = null
                return@registerForActivityResult
            }

            var results: Array<Uri>? = null
            if (result.resultCode == Activity.RESULT_OK) {
                val dataString = result.data?.dataString
                val clipData = result.data?.clipData

                if (clipData != null) {
                    results = Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                } else if (dataString != null) {
                    results = arrayOf(dataString.toUri())
                } else if (currentCameraPhotoUri != null) {
                    results = arrayOf(currentCameraPhotoUri!!)
                }
            }
            currentFilePathCallback?.onReceiveValue(results)
            currentFilePathCallback = null
            currentCameraPhotoUri = null // reset after use
        }
}