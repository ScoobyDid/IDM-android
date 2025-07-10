package com.example.idm

import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import org.json.JSONException
import org.json.JSONObject

class WebAppInterface(private val activityContext: Activity, private val webView: WebView, private val mainActivity: MainActivity) {
    val FILE_DOWNLOAD_CHANNEL_ID = "base64_file_downloads"
    val FILE_DOWNLOAD_CHANNEL_NAME = "File Downloads"

    private var fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(activityContext)

    // for single getCurrentLocation requests
    private var pendingPermissionJsCallbackId: String? = null
    private var pendingPermissionOptionsJson: String? = null

    // for watchPosition
    private val activeWatchers = mutableMapOf<String, LocationCallback>()
    private var pendingWatchPermissionWatchId: String? = null
    private var pendingWatchPermissionOptionsJson: String? = null

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val DEFAULT_HIGH_ACCURACY_THRESHOLD_METERS = 100f // 100 meters
        private const val DEFAULT_WATCH_INTERVAL_MS: Long = 10000 // Default interval for watchPosition if not specified
        private const val DEFAULT_WATCH_FASTEST_INTERVAL_MS: Long = 5000 // Default fastest interval
    }

    private data class LocationOptions(
        val enableHighAccuracy: Boolean,
        val maximumAge: Long,
        val interval: Long?,
        val fastestInterval: Long?
    )



    @JavascriptInterface
    fun refreshBarCanAppear(value: Boolean) {
        mainActivity.refreshBarCanAppear.set(value)
    }





    @JavascriptInterface
    fun forceRefreshBarCanAppear(value: Boolean) {
        mainActivity.forceRefreshBarCanAppear.set(value)
    }





    @JavascriptInterface
    fun getCurrentLocation(jsCallbackId: String, optionsJsonString: String) {
        Log.d("WebAppInterface", "getCurrentLocation called from JS with callback ID: $jsCallbackId and options: $optionsJsonString")
        val options = parseOptions(optionsJsonString)

        activityContext.runOnUiThread {
            if (hasLocationPermission()) {
                Log.d("WebAppInterface", "Location permission already granted for $jsCallbackId.")
                processLocationRequest(jsCallbackId, options)
            } else {
                Log.d("WebAppInterface", "Location permission NOT granted for $jsCallbackId. Requesting permission.")
                this.pendingPermissionJsCallbackId = jsCallbackId
                this.pendingPermissionOptionsJson = optionsJsonString // Store options too
                requestLocationPermission()
            }
        }
    }


    // --- Permission Handling ---
    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            activityContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            mainActivity,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE // Use the same request code
        )
    }

    // This method needs to be called from MainActivity's onRequestPermissionsResult
    fun handlePermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (pendingWatchPermissionWatchId != null) {
                val watchId = this.pendingWatchPermissionWatchId!!
                val optionsJson = this.pendingWatchPermissionOptionsJson!!
                this.pendingWatchPermissionWatchId = null
                this.pendingWatchPermissionOptionsJson = null
                val options = parseOptions(optionsJson, true)

                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d("WebAppInterface", "Location permission GRANTED by user for watch $watchId.")
                    startNativeWatch(watchId, options)
                } else {
                    Log.e("WebAppInterface", "Location permission DENIED by user for watch $watchId.")
                    sendPositionErrorToJs(watchId, "PERMISSION_DENIED", "User denied location permission for watch.")
                }
            }

            else if (pendingPermissionJsCallbackId != null) {
                val jsCallbackId = this.pendingPermissionJsCallbackId!!
                val optionsJson = this.pendingPermissionOptionsJson!!
                this.pendingPermissionJsCallbackId = null
                this.pendingPermissionOptionsJson = null
                val options = parseOptions(optionsJson, false)

                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d("WebAppInterface", "Location permission GRANTED for $jsCallbackId.")
                    processLocationRequest(jsCallbackId, options)
                } else {
                    Log.e("WebAppInterface", "Location permission DENIED for $jsCallbackId.")
                    sendErrorToJs(jsCallbackId, "PERMISSION_DENIED", "User denied location permission.")
                }
            } else {
                Log.w("WebAppInterface", "Permission result received but no pending request found.")
            }
        }
    }



    @SuppressLint("MissingPermission") // Permission is checked before calling this path
    private fun processLocationRequest(jsCallbackId: String, options: LocationOptions) {
        if (!isLocationEnabled()) {
            sendErrorToJs(jsCallbackId, "LOCATION_SERVICES_DISABLED", "Location services are disabled on the device.")
            return
        }

        if (options.maximumAge > 0) {
            try {
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { location: Location? ->
                        if (location != null) {
                            val locationAge = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
                            val locationAgeMillis = locationAge / 1_000_000

                            Log.d("WebAppInterface", "Last known location for $jsCallbackId: $location, Age: $locationAgeMillis ms, MaxAge: ${options.maximumAge}")

                            var useCached = locationAgeMillis <= options.maximumAge

                            if (useCached && options.enableHighAccuracy) {
                                // If high accuracy is requested, cached location must also be accurate enough
                                if (location.accuracy > DEFAULT_HIGH_ACCURACY_THRESHOLD_METERS) {
                                    Log.d("WebAppInterface", "Cached location for $jsCallbackId is recent, but not accurate enough for enableHighAccuracy=true (Accuracy: ${location.accuracy}m). Requesting fresh.")
                                    useCached = false
                                } else {
                                    Log.d("WebAppInterface", "Cached location for $jsCallbackId is recent AND accurate enough for enableHighAccuracy=true.")
                                }
                            } else if (useCached) {
                                Log.d("WebAppInterface", "Cached location for $jsCallbackId is recent and enableHighAccuracy=false. Using cached.")
                            }


                            if (useCached) {
                                Log.d("WebAppInterface", "Using cached location for $jsCallbackId.")
                                sendLocationToJs(jsCallbackId, location)
                                return@addOnSuccessListener // Successfully used cached location
                            }
                        } else {
                            Log.d("WebAppInterface", "Last known location is null for $jsCallbackId.")
                        }
                        // If cached location not used (too old, not accurate enough, or null), request a fresh one.
                        Log.d("WebAppInterface", "Proceeding to request fresh location for $jsCallbackId.")
                        requestFreshLocation(jsCallbackId, options)
                    }
                    .addOnFailureListener { e ->
                        Log.e("WebAppInterface", "Error getting lastLocation for $jsCallbackId. Requesting fresh location as fallback.", e)
                        requestFreshLocation(jsCallbackId, options) // Fallback to fresh location
                    }
                return // Exit processLocationRequest as lastLocation handling is asynchronous
            } catch (se: SecurityException) {
                // This catch block is for the rare case that .lastLocation itself throws a SecurityException
                // (e.g. if permissions were revoked between the check and the call, though unlikely with runOnUiThread)
                Log.e("WebAppInterface", "SecurityException on lastLocation access for $jsCallbackId. Requesting fresh location as fallback.", se)
                // Fallback to fresh location
                requestFreshLocation(jsCallbackId, options)
                return
            }
        }

        // 2. If maximumAge is 0 or not set, or if lastLocation check decided to fetch fresh:
        Log.d("WebAppInterface", "maximumAge is 0 or lastLocation check failed/skipped. Requesting fresh location for $jsCallbackId.")
        requestFreshLocation(jsCallbackId, options)
    }



    @SuppressLint("MissingPermission") // Permissions are checked before calling
    private fun requestFreshLocation(jsCallbackId: String, options: LocationOptions) {
        if (!isLocationEnabled()) {
            // This check is important here as well, as user might disable it between permission grant and this call
            sendErrorToJs(jsCallbackId, "LOCATION_SERVICES_DISABLED", "Location services are disabled on the device (for current location).")
            return
        }

        val locationRequestPriority = if (options.enableHighAccuracy) {
            LocationRequest.PRIORITY_HIGH_ACCURACY
        } else {
            LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            numUpdates = 1 // We only need one update for getCurrentPosition
        }

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    Log.d("WebAppInterface", "Fetched current location: $location")
                    sendLocationToJs(jsCallbackId, location)
                } ?: run {
                    Log.e("WebAppInterface", "Current location result is null in callback.")
                    sendErrorToJs(jsCallbackId, "ERROR_CURRENT_LOCATION_NULL", "Failed to get current location (result null).")
                }
                fusedLocationClient.removeLocationUpdates(this)
            }

            override fun onLocationAvailability(locationAvailability: LocationAvailability) {
                if (!locationAvailability.isLocationAvailable) {
                    Log.e("WebAppInterface", "Location not available.")
                    sendErrorToJs(jsCallbackId, "LOCATION_NOT_AVAILABLE", "Location is currently not available.")
                    fusedLocationClient.removeLocationUpdates(this) // Stop trying if not available
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            Log.e("WebAppInterface", "Exception during requestLocationUpdates", e)
            sendErrorToJs(jsCallbackId, "ERROR_REQUESTING_UPDATES", "Failed to request location updates: ${e.message}")
        }
    }







    @JavascriptInterface
    fun startWatchPosition(watchId: String, optionsJsonString: String) {
        Log.d("WebAppInterface", "startWatchPosition called from JS for watch ID: $watchId with options: $optionsJsonString")
        val options = parseOptions(optionsJsonString, true) // Parse as watch request

        activityContext.runOnUiThread {
            if (hasLocationPermission()) {
                Log.d("WebAppInterface", "Location permission already granted for watch $watchId.")
                startNativeWatch(watchId, options)
            } else {
                Log.d("WebAppInterface", "Location permission NOT granted for watch $watchId. Requesting permission.")
                this.pendingWatchPermissionWatchId = watchId
                this.pendingWatchPermissionOptionsJson = optionsJsonString
                requestLocationPermission()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNativeWatch(watchId: String, options: LocationOptions) {
        if (!isLocationEnabled()) {
            sendPositionErrorToJs(watchId, "LOCATION_SERVICES_DISABLED", "Location services are disabled for watch.")
            return
        }

        // if a watcher with this ID already exists, stop it first
        activeWatchers[watchId]?.let { existingCallback ->
            fusedLocationClient.removeLocationUpdates(existingCallback)
            Log.d("WebAppInterface", "Stopped existing watcher for ID: $watchId before starting new one.")
        }

        val locationRequestPriority = if (options.enableHighAccuracy) {
            LocationRequest.PRIORITY_HIGH_ACCURACY
        } else {
            LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val locationRequest = LocationRequest.create().apply {
            priority = locationRequestPriority
            options.interval?.let { interval = it }
            options.fastestInterval?.let { fastestInterval = it }
            // numUpdates is NOT set, for continuous updates
        }
        Log.d("WebAppInterface", "Starting NATIVE watch for $watchId with priority: $locationRequestPriority, interval: ${options.interval}")


        val watchLocationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    Log.d("WebAppInterface", "Watch update for $watchId: $location")
                    sendPositionUpdateToJs(watchId, location)
                }
            }

            override fun onLocationAvailability(locationAvailability: LocationAvailability) {
                if (!locationAvailability.isLocationAvailable) {
                    Log.e("WebAppInterface", "Location not available for watch $watchId.")
                    sendPositionErrorToJs(watchId, "WATCH_LOCATION_NOT_AVAILABLE", "Location became unavailable for watch $watchId.")
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                watchLocationCallback,
                Looper.getMainLooper()
            )
            activeWatchers[watchId] = watchLocationCallback
            Log.d("WebAppInterface", "Successfully started and stored native watcher for ID: $watchId")
        } catch (e: Exception) {
            Log.e("WebAppInterface", "Exception during startNativeWatch for $watchId", e)
            sendPositionErrorToJs(watchId, "ERROR_STARTING_WATCH", "Failed to start watch: ${e.message}")
        }
    }

    @JavascriptInterface
    fun stopWatchPosition(watchId: String) {
        activityContext.runOnUiThread {
            activeWatchers.remove(watchId)?.let { callback ->
                fusedLocationClient.removeLocationUpdates(callback)
                Log.d("WebAppInterface", "Stopped and removed native watcher for ID: $watchId")
            } ?: run {
                Log.w("WebAppInterface", "No active watcher found to stop for ID: $watchId")
            }
        }
    }





    @JavascriptInterface
    fun stopAllWatchPositions() {
        activeWatchers.forEach { (watchId, callback) ->
            fusedLocationClient.removeLocationUpdates(callback)
            Log.d("WebAppInterface", "Stopped and removed native watcher for ID: $watchId")
        }
        activeWatchers.clear()
    }







    private fun isLocationEnabled(): Boolean {
        val locationManager = activityContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }


    private fun sendLocationToJs(jsCallbackId: String, location: Location) {
        val positionObj = JSONObject()
        val coordsObj = JSONObject()
        coordsObj.put("latitude", location.latitude)
        coordsObj.put("longitude", location.longitude)
        // by default accuracy has 68% confidence level, which is not enough if we want to show accuracy circle to users
        // so we convert it to 99% confidence level by multiplying by 2.58 (lower accuracy but higher confidence level)
        // for comparison, html5 geolocation api provides 95% confidence level (multiplied by 1.96)
        coordsObj.put("accuracy", location.accuracy * 2.58f)
        coordsObj.put("speed", if (location.hasSpeed()) location.speed else null)
        positionObj.put("coords", coordsObj)
        positionObj.put("timestamp", location.time)

        val script = "javascript:window.androidLocationBridge.resolveLocation('$jsCallbackId', ${positionObj.toString()});"
        Log.d("WebAppInterface", "Sending location to JS for $jsCallbackId: $script")
        webView.post { webView.evaluateJavascript(script, null) }
    }

    private fun sendErrorToJs(jsCallbackId: String, errorCode: String, message: String) {
        val errorObj = JSONObject()
        errorObj.put("code", errorCode)
        errorObj.put("message", message)

        val script = "javascript:window.androidLocationBridge.rejectLocation('$jsCallbackId', ${errorObj.toString()});"
        Log.d("WebAppInterface", "Sending error to JS for $jsCallbackId: $script")
        webView.post { webView.evaluateJavascript(script, null) }
    }

    private fun sendPositionUpdateToJs(watchId: String, location: Location) {
        val positionObj = JSONObject() // Same structure as getCurrentPosition
        val coordsObj = JSONObject()
        coordsObj.put("latitude", location.latitude)
        coordsObj.put("longitude", location.longitude)
        coordsObj.put("accuracy", location.accuracy * 2.58f) // Your multiplier
        coordsObj.put("speed", if (location.hasSpeed()) location.speed else null)
        positionObj.put("coords", coordsObj)
        positionObj.put("timestamp", location.time) // Original timestamp

        // Call the new bridge function
        val script = "javascript:window.androidLocationBridge.notifyPositionUpdate('$watchId', ${positionObj.toString()});"
        Log.d("WebAppInterface", "Sending watch update to JS for $watchId: $script")
        webView.post { webView.evaluateJavascript(script, null) }
    }

    private fun sendPositionErrorToJs(watchId: String, errorCode: String, message: String) {
        val errorObj = JSONObject()
        errorObj.put("code", errorCode)
        errorObj.put("message", message)
        // Call the new bridge function
        val script = "javascript:window.androidLocationBridge.notifyPositionError('$watchId', ${errorObj.toString()});"
        Log.d("WebAppInterface", "Sending watch error to JS for $watchId: $script")
        webView.post { webView.evaluateJavascript(script, null) }
    }






    private fun parseOptions(optionsJsonString: String?, isWatchRequest: Boolean = false): LocationOptions {
        var enableHighAccuracy = true
        var maximumAge: Long = 0
        var interval: Long? = if (isWatchRequest) DEFAULT_WATCH_INTERVAL_MS else null
        var fastestInterval: Long? = if (isWatchRequest) DEFAULT_WATCH_FASTEST_INTERVAL_MS else null


        if (optionsJsonString != null) {
            try {
                val json = JSONObject(optionsJsonString)
                enableHighAccuracy = json.optBoolean("enableHighAccuracy", true)
                maximumAge = json.optLong("maximumAge", 0) // Used by getCurrentLocation only

                if (isWatchRequest) {
                    interval = json.optLong("interval", DEFAULT_WATCH_INTERVAL_MS)
                    fastestInterval = json.optLong("fastestInterval", interval / 2) // Common practice
                }
            } catch (e: JSONException) {
                Log.e("WebAppInterface", "Failed to parse location options JSON: $optionsJsonString", e)
            }
        }
        return LocationOptions(enableHighAccuracy, maximumAge, interval, fastestInterval)
    }







    @JavascriptInterface
    fun downloadFile(url: String, fileName: String?, mimeType: String?) {
        activityContext.runOnUiThread {
            val downloadManager =
                activityContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

            if (downloadManager == null) {
                webView.showJsToast("DownloadManager not available.", listOf("error"), 10_000)
                return@runOnUiThread
            }

            try {
                val requestUri = url.toUri()
                val effectiveFileName = if (!fileName.isNullOrEmpty()) {
                    fileName
                } else {
                    // guess filename if not provided.
                    URLUtil.guessFileName(url, null, null)
                }

                val request = DownloadManager.Request(requestUri).apply {
                    setTitle(effectiveFileName)
                    setDescription("Downloading file...")
                    allowScanningByMediaScanner()
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

                    // save to the public 'Downloads' directory
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        effectiveFileName
                    )

                    // set mime type if known, otherwise DownloadManager tries to infer
                    if (!mimeType.isNullOrEmpty()) {
                        setMimeType(mimeType)
                    }

                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(true)
                }

                webView.showJsToast("Download started", null, 2000)
                downloadManager.enqueue(request)
            }
            catch (e: IllegalArgumentException) {
                webView.showJsToast("Invalid URL for download: $url", listOf("error"), 10_000)
            }
            catch (e: Exception) {
                webView.showJsToast("Could not start download.", listOf("error"), 10_000)
            }
        }
    }






    @JavascriptInterface
    // intended to use for files that are not publicly accessible by URLs (e.g. blob urls)
    fun downloadBase64File(base64Data: String, fileName: String, mimeType: String) {
        activityContext.runOnUiThread {
            var fileUriForNotification: Uri? = null

            try {
                val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                var successfullySaved = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // for API 29+
                    val resolver = activityContext.contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    var insertedUri: Uri? = null
                    try {
                        insertedUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        if (insertedUri == null) throw IOException("Failed to create MediaStore record.")

                        resolver.openOutputStream(insertedUri).use { os ->
                            os?.write(decodedBytes) ?: throw IOException("Failed to get output stream.")
                        }
                        contentValues.clear()
                        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(insertedUri, contentValues, null, null)
                        fileUriForNotification = insertedUri
                        successfullySaved = true

                        // explicit broadcast to encourage faster gallery update for images
                        val scanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                        scanIntent.data = fileUriForNotification
                        activityContext.sendBroadcast(scanIntent)

                    } catch (e: Exception) {
                        insertedUri?.let { resolver.delete(it, null, null) }
                        throw e
                    }
                }
                else {
                    // for API < 29
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) {
                        downloadsDir.mkdirs()
                    }
                    val file = File(downloadsDir, fileName)
                    FileOutputStream(file).use { outputStream ->
                        outputStream.write(decodedBytes)
                    }

                    try {
                        fileUriForNotification = FileProvider.getUriForFile(
                            activityContext,
                            "${activityContext.packageName}.provider",
                            file
                        )
                    } catch (e: IllegalArgumentException) {
                        webView.showJsToast("FileProvider error: ${e.message}", listOf("error"), 10_000)
                        fileUriForNotification = Uri.fromFile(file)
                    }
                    successfullySaved = true

                    // explicit broadcast to encourage faster gallery update for images
                    MediaScannerConnection.scanFile(
                        activityContext,
                        arrayOf(file.absolutePath),
                        arrayOf(mimeType)
                    ) { path, uri -> }
                }

                if (successfullySaved && fileUriForNotification != null) {
                    Toast.makeText(activityContext, "$fileName downloaded.", Toast.LENGTH_SHORT).show()
                    showDownloadCompleteNotification(
                        activityContext,
                        fileUriForNotification,
                        fileName,
                        mimeType,
                        (System.currentTimeMillis() % 10000).toInt()
                    )
                } else {
                    throw IOException("File saving failed or URI was not obtained.")
                }

            } catch (e: IllegalArgumentException) {
                webView.showJsToast("Error processing file data.", listOf("error"), 10_000)
//                Log.e("WebAppInterface", "Error decoding Base64 data: ${e.message}", e)
            } catch (e: Exception) {
                webView.showJsToast("Error processing file data.", listOf("error"), 10_000)
//                Log.e("WebAppInterface", "Error saving or notifying for Base64 file '$fileName': ${e.message}", e)
            }
        }
    }





    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                FILE_DOWNLOAD_CHANNEL_ID,
                FILE_DOWNLOAD_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH // Or IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for files downloaded from the app"
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }





    private fun showDownloadCompleteNotification(
        context: Context,
        fileUri: Uri,
        fileName: String,
        mimeType: String,
        notificationId: Int
    ) {
        createNotificationChannel(context)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val openFileIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, mimeType)
            // grant read permission to the app that handles the ACTION_VIEW intent
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        // check if any app can handle the intent
        if (openFileIntent.resolveActivity(context.packageManager) == null) {
            webView.showJsToast("No app available to open this file type.", listOf("error"), 10_000)
//            Log.w("Notification", "No activity found to handle $mimeType for URI $fileUri")

            // still show a notification but without a content intent or with a different message
            val builder = NotificationCompat.Builder(context, FILE_DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("$fileName Downloaded")
                .setContentText("No app to open this file type.")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
            notificationManager.notify(notificationId, builder.build())
            return
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openFileIntent,
            pendingIntentFlags
        )

        val builder = NotificationCompat.Builder(context, FILE_DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("$fileName Downloaded")
            .setContentText("Tap to open $fileName")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            notificationManager.notify(notificationId, builder.build())
        } catch (se: SecurityException) {
//            Log.e("WebAppInterface", "SecurityException on notify. POST_NOTIFICATIONS permission missing? ${se.message}")
            webView.showJsToast("Notification permission needed to show download status.", listOf("error"), 10_000)
        }
    }
}