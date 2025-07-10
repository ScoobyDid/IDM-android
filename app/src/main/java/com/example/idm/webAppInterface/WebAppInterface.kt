package com.example.idm.webAppInterface

import android.app.Activity
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.example.idm.MainActivity
import io.sentry.Sentry
import io.sentry.protocol.User


class WebAppInterface(
    activityContext: Activity,
    webView: WebView,
    private val mainActivity: MainActivity
) {
    private val locationHandler = LocationHandler(activityContext, webView, mainActivity)
    private val fileDownloadHandler = FileDownloadHandler(activityContext, webView)





    @JavascriptInterface
    fun refreshBarCanAppear(value: Boolean) {
        mainActivity.refreshBarCanAppear.set(value)
    }





    @JavascriptInterface
    fun forceRefreshBarCanAppear(value: Boolean) {
        mainActivity.forceRefreshBarCanAppear.set(value)
    }





    @JavascriptInterface
    fun setUserEmail(userEmail: String) {
        val user = User().apply {
            email = userEmail
        }
        Sentry.setUser(user)
    }





    @JavascriptInterface
    fun getCurrentLocation(jsCallbackId: String, optionsJsonString: String) {
        locationHandler.getCurrentLocation(jsCallbackId, optionsJsonString)
    }





    // this method needs to be called from MainActivity's onRequestPermissionsResult
    fun handlePermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode == LocationHandler.LOCATION_PERMISSION_REQUEST_CODE) {
            locationHandler.handlePermissionsResult(requestCode, grantResults)
        }
    }





    @JavascriptInterface
    fun startWatchPosition(watchId: String, optionsJsonString: String) {
        locationHandler.startWatchPosition(watchId, optionsJsonString)
    }





    @JavascriptInterface
    fun stopWatchPosition(watchId: String) {
        locationHandler.stopWatchPosition(watchId)
    }





    @JavascriptInterface
    fun stopAllWatchPositions() {
        locationHandler.stopAllWatchPositions()
    }





    @JavascriptInterface
    fun downloadFile(url: String, fileName: String?, mimeType: String?) {
        fileDownloadHandler.downloadFile(url, fileName, mimeType)
    }





    @JavascriptInterface
    // intended to use for files that are not publicly accessible by URLs (e.g. blob urls)
    fun downloadBase64File(base64Data: String, fileName: String, mimeType: String) {
        fileDownloadHandler.downloadBase64File(base64Data, fileName, mimeType)
    }

}
